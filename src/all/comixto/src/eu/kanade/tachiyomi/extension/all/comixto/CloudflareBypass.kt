package eu.kanade.tachiyomi.extension.all.comixto

import android.webkit.CookieManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Request-hardening helpers for the Comix API client.
 *
 * v45: Cloudflare raised comix.to to managed challenges (`cf-mitigated:
 * challenge`) on the API — the chapter-list calls started answering 403
 * "Just a moment…" while the site itself kept loading fine in a browser.
 * Header hardening of plain OkHttp traffic can NEVER pass a managed
 * challenge (only a JS-executing browser can), so the hard block/retry
 * loop this class used to carry is GONE — managed challenges are now
 * solved by the repo's `CloudflareSolverInterceptor` (window-attached
 * WebView, Turnstile tap, self-heal verify), installed ahead of these
 * interceptors in [Comix.client].
 *
 * What remains here is the belt-and-braces layer that made the old Comix
 * CF handling good:
 *
 * 1. **Cookie sync** — explicitly attaches WebView cookies (`cf_clearance`,
 *    `__cf_bm`, session cookies) to requests for the protected hosts. The
 *    solver wipes and re-mints clearances through the same CookieManager,
 *    so a retried request that re-enters this interceptor picks up the
 *    FRESH clearance automatically.
 * 2. **Browser fingerprint headers** — `sec-fetch-*`, `sec-ch-ua*` client
 *    hints (derived from the request's own user agent, so the versions
 *    always match the WebView that solved the challenge), `accept-language`
 *    and a proper `accept`. Keeps the request shape browser-like between
 *    solves; the solver's own fingerprint pass only fills MISSING headers,
 *    so the two never fight.
 *
 * The user agent is intentionally NOT overridden: `cf_clearance` is bound to
 * the user agent of the WebView that solved the challenge, and the app's
 * default agent always matches it.
 */
class CloudflareBypass(
    /** Hosts that receive synced WebView cookies (site + API hosts). */
    private val cookieHosts: Set<String>,
) {
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        addInterceptor(::cookieSyncInterceptor)
        addInterceptor(::fingerprintInterceptor)
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

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    private companion object {
        val CHROME_VERSION_REGEX = Regex("""Chrome[/ ](\d+)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "svg")
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
