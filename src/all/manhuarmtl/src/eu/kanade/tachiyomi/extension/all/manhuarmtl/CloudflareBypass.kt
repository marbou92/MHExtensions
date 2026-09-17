package eu.kanade.tachiyomi.extension.all.manhuarmtl

import android.webkit.CookieManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * Cloudflare handling for manhuarmtl.com (the site enables managed challenges
 * intermittently, which is why loads randomly fail).
 *
 * 1. **Browser fingerprint headers** — `sec-fetch-*` + `sec-ch-ua*` client
 *    hints derived from the request UA, so requests replayed with the
 *    WebView's `cf_clearance` cookie look like they come from the same
 *    browser that solved the challenge.
 * 2. **Cookie sync** — explicitly attaches WebView cookies (`cf_clearance`,
 *    `__cf_bm`, ...) to requests for the protected hosts.
 * 3. **Smart retry** — transparent retries with backoff for transient
 *    Cloudflare blocks (429/503, `Retry-After`).
 *
 * The user agent is intentionally NOT overridden: `cf_clearance` is bound to
 * the user agent of the WebView that solved the challenge, and the app's
 * default agent always matches it.
 */
class CloudflareBypass(
    /** Hosts that receive synced WebView cookies (site + CDN hosts). */
    private val cookieHosts: Set<String>,
) {
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        addInterceptor(::cookieSyncInterceptor)
        addInterceptor(::fingerprintInterceptor)
        addInterceptor(::retryInterceptor)
    }

    // ------------------------------------------------------------------------
    // 1. Cookie sync
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
    // 2. Browser fingerprint
    // ------------------------------------------------------------------------

    private fun fingerprintInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        val userAgent = request.header("User-Agent") ?: FALLBACK_UA
        val chromeMajor = CHROME_VERSION_REGEX.find(userAgent)?.groupValues?.get(1)

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
        val isImage = path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
        val isXhr = request.header("X-Requested-With") == "XMLHttpRequest" ||
            request.header("Accept")?.contains("application/json") == true

        if (request.header("Accept") == null) {
            val accept = when {
                isImage -> "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
                isXhr -> "application/json, text/plain, */*"
                else -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            }
            builder.header("Accept", accept)
        }

        builder.header("accept-language", "en-US,en;q=0.9")

        when {
            isImage -> {
                builder.header("sec-fetch-dest", "image")
                builder.header("sec-fetch-mode", "no-cors")
            }
            isXhr -> {
                builder.header("sec-fetch-dest", "empty")
                builder.header("sec-fetch-mode", "cors")
            }
            else -> {
                builder.header("sec-fetch-dest", "document")
                builder.header("sec-fetch-mode", "navigate")
                builder.header("sec-fetch-user", "?1")
                builder.header("upgrade-insecure-requests", "1")
            }
        }

        val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host
        builder.header(
            "sec-fetch-site",
            when {
                refererHost == null -> "none"
                refererHost == request.url.host -> "same-origin"
                else -> "same-site"
            },
        )

        return chain.proceed(builder.build())
    }

    // ------------------------------------------------------------------------
    // 3. Smart retry
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

        if (isCloudflareBlock(response)) {
            val code = response.code
            response.close()
            throw IOException(
                "Cloudflare is blocking requests to ${request.url.host} (HTTP $code). " +
                    "Open manhuarmtl.com in WebView (Browse → Sources → ManhuaRMTL → ⋮ → " +
                    "Open in WebView) to solve the challenge, then reload.",
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
