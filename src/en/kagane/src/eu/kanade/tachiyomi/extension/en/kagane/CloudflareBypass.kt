package eu.kanade.tachiyomi.extension.en.kagane

import android.webkit.CookieManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Modern Cloudflare bypass system for API-based extensions.
 *
 * Hardens every request to look like a real browser XHR, which is what Cloudflare's
 * bot scoring actually checks beyond the `cf_clearance` cookie:
 *
 * 1. **Browser fingerprint headers** — `sec-fetch-*`, `sec-ch-ua*` client hints
 *    (derived from the request's own user agent, so the versions always match the
 *    WebView that solved the challenge), `accept-language` and a proper `accept`.
 * 2. **Cookie sync** — explicitly attaches WebView cookies (`cf_clearance`,
 *    `__cf_bm`, session cookies) to requests for the protected hosts. Belt and
 *    braces on top of the app's cookie jar; guarantees clearance cookies flow even
 *    after a WebView solve mid-session.
 * 3. **Root priming (reverse-engineered fix)** — the app's Cloudflare WebView
 *    solver loads the REQUEST URL to clear a challenge, but for API endpoints
 *    (`/api/v2/...`) that URL answers JSON/405 instead of the challenge page,
 *    so the solve aborts and no clearance is ever minted. On a detected
 *    challenge we therefore GET the SITE ROOT (a real HTML page) through the
 *    priming client first — triggering the WebView solve where it can
 *    actually succeed — then retry the original request with the fresh
 *    `cf_clearance`.
 * 4. **Smart retry** — detects Cloudflare block responses (403/429/503 with
 *    `cf-mitigated` / `server: cloudflare` headers) and retries with backoff,
 *    honouring `Retry-After`. Survives short rate-limit windows without failing
 *    the whole refresh.
 *
 * The user agent is intentionally NOT overridden: `cf_clearance` is bound to the
 * user agent of the WebView that solved the challenge, and the app's default agent
 * always matches it (it is stamped onto every request by the app before this
 * interceptor chain runs). A custom UA here would break the app's own
 * challenge-solving flow.
 *
 * Requests that are still blocked after the retries throw an [IOException] with an
 * actionable message, which Mihon surfaces to the user.
 */
class CloudflareBypass(
    /** Hosts that receive synced WebView cookies (site + API hosts). */
    private val cookieHosts: Set<String>,
    /** URL fetched once to mint a fresh clearance (site root HTML). */
    private val primeUrl: String? = null,
    /** Client used for priming — must NOT have priming itself installed. */
    private val primeClient: OkHttpClient? = null,
) {
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        addInterceptor(::cookieSyncInterceptor)
        addInterceptor(::fingerprintInterceptor)
        addInterceptor(::retryInterceptor)
    }

    // ------------------------------------------------------------------------
    // 1. Cookie sync — attach WebView cookies (cf_clearance, __cf_bm, ...)
    // ------------------------------------------------------------------------

    private fun cookieSyncInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (host !in cookieHosts || request.header("Cookie") != null) {
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

        val path = request.url.encodedPath
        if (request.header("Accept") == null) {
            val accept = if (path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS) {
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            } else {
                "application/json, text/plain, */*"
            }
            builder.header("Accept", accept)
        }

        builder.header("accept-language", "en-US,en;q=0.9")
        builder.header("sec-fetch-dest", "empty")
        builder.header("sec-fetch-mode", "cors")

        val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host
        builder.header(
            "sec-fetch-site",
            when {
                refererHost == request.url.host -> "same-origin"
                refererHost != null && refererHost == request.url.host
                    .substringBefore('.') -> "same-site"
                else -> "cross-site"
            },
        )

        return chain.proceed(builder.build())
    }

    // ------------------------------------------------------------------------
    // 3. Root priming — mint clearance where the WebView solve can succeed
    // ------------------------------------------------------------------------

    @Volatile
    private var lastPrimeAt: Long = 0L

    private fun primeClearance() {
        val url = primeUrl ?: return
        val client = primeClient ?: return
        val now = System.currentTimeMillis()
        if (now - lastPrimeAt < PRIME_INTERVAL_MS) return
        lastPrimeAt = now

        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                resp.body.close()
            }
        } catch (_: Exception) {
            // Best-effort: the host app may lack a WebView solver or the
            // challenge may be interactive; the final error says what to do.
        }
    }

    // ------------------------------------------------------------------------
    // 4. Smart retry on Cloudflare blocks (rate limits, transient challenges)
    // ------------------------------------------------------------------------

    private fun retryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        var attempt = 0
        while (isCloudflareBlock(response) && attempt < MAX_RETRIES) {
            attempt++
            val isChallenge = isChallengeResponse(response)
            val retryAfterMs = response.header("Retry-After")
                ?.trim()?.toLongOrNull()?.times(1000)
                ?: (attempt * 1200L)
            response.close()

            // Challenge pages resolve on the WebView's own clock — waiting a
            // long backoff on top of the solve just adds latency. Rate limits
            // (429) keep the real Retry-After / stepped backoff.
            val backoffMs = if (isChallenge) CHALLENGE_BACKOFF_MS else retryAfterMs.coerceAtMost(5_000L)

            // Mint a fresh clearance against the site root in parallel with
            // the backoff (API URLs cannot be solved inside the WebView
            // directly), then wait for the solve before retrying.
            var primeThread: Thread? = null
            if (isChallenge || attempt == 1) {
                primeThread = Thread({ primeClearance() }, "CF-Prime").also { it.start() }
            }

            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            primeThread?.join(PRIME_JOIN_TIMEOUT_MS)

            response = chain.proceed(request)
        }

        if (isCloudflareBlock(response)) {
            val code = response.code
            response.close()
            throw IOException(
                "Cloudflare is blocking requests to ${request.url.host} (HTTP $code). " +
                    "Open kagane.to in WebView (Browse → Sources → Kagane → ⋮ → " +
                    "Open in WebView) to solve the challenge, then try again.",
            )
        }

        return response
    }

    private fun isCloudflareBlock(response: Response): Boolean {
        if (response.code !in BLOCK_CODES) return false

        val mitigated = response.header("cf-mitigated")
        if (mitigated?.contains("challenge", ignoreCase = true) == true) return true

        val server = response.header("server") ?: return false
        return server.contains("cloudflare", ignoreCase = true)
    }

    private fun isChallengeResponse(response: Response): Boolean = response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    private companion object {
        const val MAX_RETRIES = 3
        const val PRIME_INTERVAL_MS = 10_000L
        const val PRIME_JOIN_TIMEOUT_MS = 30_000L
        const val CHALLENGE_BACKOFF_MS = 300L
        val BLOCK_CODES = intArrayOf(403, 429, 503)
        val CHROME_VERSION_REGEX = Regex("""Chrome[/ ](\d+)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "svg")
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
