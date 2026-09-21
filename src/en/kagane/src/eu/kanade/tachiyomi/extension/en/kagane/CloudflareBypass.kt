package eu.kanade.tachiyomi.extension.en.kagane

import android.webkit.CookieManager
import keiyoushi.utils.runWebViewBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

/**
 * Extension-level Cloudflare handling, modelled on keiyoushi's MangaFire
 * `ChallengeSolverInterceptor` (the user-visible effect there: challenges
 * clear in the background and browsing feels instant).
 *
 * What it does, in order:
 *
 * 1. **Browser fingerprint** — `sec-ch-ua*` client hints derived from the
 *    request's own user agent (so versions always match the WebView that
 *    solves challenges), `sec-fetch-*`, `accept-language` and a proper
 *    `accept`. A Chrome UA without client hints is itself a bot signal;
 *    consistent hints mean Cloudflare scores requests like a browser and
 *    issues fewer challenges in the first place.
 *
 * 2. **Cookie sync** — explicitly attaches WebView cookies (`cf_clearance`,
 *    `__cf_bm`, …) to requests for the protected hosts. Belt and braces on
 *    top of the app cookie jar so a clearance minted anywhere in the app
 *    immediately flows to this source.
 *
 * 3. **Solve & retry** — when a response is a Cloudflare challenge the app's
 *    own interceptor did not handle (it only reacts to `cf-mitigated:
 *    challenge` + `server: cloudflare`), the request URL is loaded in an
 *    off-screen WebView via the library's [runWebViewBlocking] — exactly the
 *    MangaFire pattern — and the request is retried once. Unsolved responses
 *    are handed back untouched so the app-level interceptor still gets its
 *    chance; no sleeping, no extra round-trips on the happy path.
 */
class CloudflareBypass(
    /** Hosts that receive synced WebView cookies (site + API/CDN hosts). */
    private val protectedHosts: Set<String>,
) {

    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        addInterceptor(::cookieSyncInterceptor)
        addInterceptor(::fingerprintInterceptor)
        addInterceptor(::solveInterceptor)
    }

    // ------------------------------------------------------------------------
    // 1. Cookie sync — attach WebView cookies (cf_clearance, __cf_bm, ...)
    // ------------------------------------------------------------------------

    private fun cookieSyncInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (host !in protectedHosts || request.header("Cookie") != null) {
            return chain.proceed(request)
        }

        val cookies = runCatching {
            CookieManager.getInstance().getCookie("https://$host/")
        }.getOrNull()

        return if (cookies.isNullOrEmpty()) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("Cookie", cookies).build())
        }
    }

    // ------------------------------------------------------------------------
    // 2. Browser fingerprint — sec-fetch-* + client hints derived from the UA
    // ------------------------------------------------------------------------

    private fun fingerprintInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        val userAgent = request.header("User-Agent") ?: FALLBACK_UA
        val chromeMajor = CHROME_VERSION_REGEX.find(userAgent)?.groupValues?.get(1)

        // Client hints must match the UA's Chrome version, otherwise the
        // inconsistency itself becomes a bot signal.
        if (chromeMajor != null) {
            builder.header(
                "sec-ch-ua",
                "\"Not.A/Brand\";v=\"99\", \"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\"",
            )
            builder.header(
                "sec-ch-ua-mobile",
                if (userAgent.contains("Mobile", ignoreCase = true)) "?1" else "?0",
            )
            builder.header("sec-ch-ua-platform", "\"Android\"")
        }

        if (request.header("Accept") == null) {
            val path = request.url.encodedPath
            builder.header(
                "Accept",
                when {
                    path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS ->
                        "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

                    request.url.host in protectedHosts && (path.contains("/api/") || path.endsWith(".json")) ->
                        "application/json, text/plain, */*"

                    else -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                },
            )
        }

        builder.header("accept-language", "en-US,en;q=0.9")
        builder.header("sec-fetch-dest", "empty")
        builder.header("sec-fetch-mode", "cors")

        val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host
        builder.header(
            "sec-fetch-site",
            when {
                refererHost == request.url.host -> "same-origin"
                refererHost != null && refererHost == request.url.host.substringBefore('.') -> "same-site"
                else -> "cross-site"
            },
        )

        return chain.proceed(builder.build())
    }

    // ------------------------------------------------------------------------
    // 3. Solve & retry — off-screen WebView solve on challenge responses
    // ------------------------------------------------------------------------

    private fun solveInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!isChallenge(response)) return response

        // Solve in our own WebView (MangaFire pattern). If the solve fails we
        // hand the ORIGINAL response back — the app's Cloudflare interceptor
        // runs after source interceptors and will attempt its own solve.
        val solved = runCatching { solveInWebView(chain.call(), request) }.getOrDefault(false)
        if (!solved || chain.call().isCanceled()) return response

        response.close()
        return chain.proceed(request)
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) return true
        if (response.code !in CHALLENGE_CODES) return false
        if (response.header("server")?.contains("cloudflare", ignoreCase = true) != true) return false

        // Header-less challenge variants: sniff the body once without
        // consuming it (peekBody keeps the response fully readable).
        return runCatching {
            val body = response.peekBody(MAX_PEEK_BYTES).string()
            CHALLENGE_BODY_MARKERS.any(body::contains)
        }.getOrDefault(false)
    }

    private fun solveInWebView(call: okhttp3.Call, request: Request): Boolean {
        val url = request.url.toString()
        val ua = request.header("User-Agent") ?: FALLBACK_UA

        return runWebViewBlocking(call, timeout = 45.seconds) {
            userAgent = ua

            var finished = false
            onPageFinished { _ -> finished = true }

            poll(1.seconds) {
                evaluateJs("document.title") { raw ->
                    val title = raw
                        ?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        .orEmpty()

                    val stillChallenged = title.isEmpty() ||
                        CHALLENGE_TITLE_REGEX.containsMatchIn(title)

                    if (finished && !stillChallenged) resolve(true)
                }
            }

            loadUrl(url)
        }
    }

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    private companion object {
        val CHALLENGE_CODES = setOf(403, 429, 503)
        val CHALLENGE_BODY_MARKERS = listOf(
            "Just a moment",
            "challenge-platform",
            "cf-chl",
            "_cf_chl_opt",
            "Attention Required",
            "Verify you are human",
        )
        val CHALLENGE_TITLE_REGEX = Regex(
            "just a moment|attention required|security verification|verify you are human|checking your browser",
            RegexOption.IGNORE_CASE,
        )
        val CHROME_VERSION_REGEX = Regex("""Chrome[/ ](\d+)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "svg")
        const val MAX_PEEK_BYTES = 64L * 1024L
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
