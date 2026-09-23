package eu.kanade.tachiyomi.extension.all.manhuarmtl

import android.webkit.CookieManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The Comix Cloudflare method, ported 1:1 from our working comixto source
 * (the build the user reports as "soo good" at CF).
 *
 * Hardens every request to look like a real browser XHR, which is what
 * Cloudflare's bot scoring actually checks beyond the `cf_clearance` cookie:
 *
 * 1. **Cookie sync** — explicitly attaches WebView cookies (`cf_clearance`,
 *    `__cf_bm`, session cookies) to requests for the protected hosts, on top
 *    of the app's cookie jar. Guarantees clearance cookies flow even after a
 *    WebView solve mid-session.
 * 2. **Browser fingerprint** — `sec-ch-ua*` client hints derived from the
 *    request's own user agent (so versions always match the WebView that
 *    solved the challenge), `sec-fetch-*`, `accept-language` and a proper
 *    `accept`. Headers the request already carries are left untouched, so
 *    the image requests keep their `Sec-Fetch-Dest: image` etc.
 * 3. **Smart retry** — detects Cloudflare block responses (403/429/503 with
 *    `cf-mitigated` / `server: cloudflare`) and retries with backoff,
 *    honouring `Retry-After`. Survives short rate-limit windows and transient
 *    challenges without failing the refresh or popping a WebView.
 *
 * The user agent is intentionally NOT overridden: `cf_clearance` is bound to
 * the user agent of the WebView that solved the challenge, and the app's
 * default agent always matches it.
 *
 * When requests are STILL challenged after the retries, the challenge
 * response is returned as-is so the app-level CloudflareInterceptor (which
 * runs outside the source interceptors) can solve it in the shared WebView
 * and re-issue the request — the same solve the user already knows.
 */
class CloudflareBypass(
    /** Hosts that receive synced WebView cookies (site + API hosts). */
    private val cookieHosts: Set<String>,
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

        if (!isProtectedHost(host) || request.header("Cookie") != null) {
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

/** The registrable domain itself or any of its subdomains (api., cache., ...). */
    private fun isProtectedHost(host: String): Boolean = cookieHosts.any { root -> host == root || host.endsWith(".$root") }

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

        if (request.header("accept-language") == null) {
            builder.header("accept-language", "en-US,en;q=0.9")
        }

        // Only fill in sec-fetch-* when the request doesn't already carry
        // them (image requests do — keep their real values).
        if (request.header("sec-fetch-dest") == null) builder.header("sec-fetch-dest", "empty")
        if (request.header("sec-fetch-mode") == null) builder.header("sec-fetch-mode", "cors")

        if (request.header("sec-fetch-site") == null) {
            val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host
            builder.header(
                "sec-fetch-site",
                when {
                    refererHost == request.url.host -> "same-origin"
                    refererHost != null && request.url.host.endsWith(".${refererHost.substringBefore('.')}") -> "same-site"
                    else -> "cross-site"
                },
            )
        }

        return chain.proceed(builder.build())
    }

    // ------------------------------------------------------------------------
    // 3. Smart retry on Cloudflare blocks (rate limits, transient challenges)
    // ------------------------------------------------------------------------

    private fun retryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        var attempt = 0
        while (isCloudflareBlock(response) && attempt < MAX_RETRIES) {
            attempt++
            val retryAfterMs = response.header("Retry-After")
                ?.trim()?.toLongOrNull()?.times(1000)
                ?: (attempt * 1200L)

            response.close()
            try {
                Thread.sleep(retryAfterMs.coerceAtMost(5_000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            response = chain.proceed(request)
        }

        // Still challenged after the retries: hand the response back so the
        // app-level CloudflareInterceptor can solve it in the shared WebView.
        return response
    }

    private fun isCloudflareBlock(response: Response): Boolean {
        if (response.code !in BLOCK_CODES) return false

        val mitigated = response.header("cf-mitigated")
        if (mitigated?.contains("challenge", ignoreCase = true) == true) return true

        val server = response.header("server") ?: return false
        return server.contains("cloudflare", ignoreCase = true)
    }

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    private companion object {
        const val MAX_RETRIES = 2
        val BLOCK_CODES = intArrayOf(403, 429, 503)
        val CHROME_VERSION_REGEX = Regex("""Chrome[/ ](\d+)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "svg")
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
