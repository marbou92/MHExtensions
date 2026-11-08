package keiyoushi.cloudflare

import android.annotation.SuppressLint
import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A compact, self-contained Cloudflare challenge solver.
 *
 * This is the compatibility surface for the MHExtensions fork's
 * `keiyoushi.cloudflare.CloudflareSolverInterceptor` (the upstream
 * keiyoushi extensions-source tree no longer ships one). In ProComic it
 * is reachable ONLY when the user turns OFF "Safe chapter fetch" — the
 * default (safe) path answers challenged responses with a legible
 * IOException and never constructs this class.
 *
 * Behavior: proceed with the request; on a 403/503 challenge answer,
 * run one bounded offscreen WebView pass against the challenge page,
 * harvest the clearance cookies the solve sets, and retry once with
 * them. Every failure stays a legible IOException.
 */
class CloudflareSolverInterceptor(private val hosts: Set<String> = emptySet()) : Interceptor {

    /** The app context (the WebView factory) — lazy keeps the safe path
     * free of any WebView dependency. */
    private val appContext by lazy { applicationContext }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) return response
        val host = request.url.host
        if (hosts.isNotEmpty() && host !in hosts) return response
        response.close()

        val cookies = runCatching { solve(request.url) }.getOrNull()
            ?: throw IOException("Cloudflare challenge on $host — the bounded solver came back empty")

        return chain.proceed(
            request.newBuilder().header("Cookie", cookies).build(),
        )
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503 && response.code != 429) return false
        if (response.header("cf-mitigated") == "challenge") return true
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isNotEmpty() && !contentType.contains("text/html", ignoreCase = true)) return false
        val body = runCatching { response.peekBody(256L * 1024L).string() }.getOrDefault("")
        return CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    /** One bounded WebView pass: load the challenge page, wait for the
     * clearance cookie to appear, return the cookie header string. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun solve(url: HttpUrl): String? {
        val latch = CountDownLatch(1)
        val thread = HandlerThread("KeiCloudflareSolver")
        var solvedCookies: String? = null
        try {
            thread.start()
            val handler = Handler(thread.looper)
            handler.post {
                var webView: WebView? = null
                try {
                    webView = WebView(appContext)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = USER_AGENT
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            val raw = CookieManager.getInstance().getCookie(url.toString())
                            if (raw != null && CLEARANCE_MARKERS.any { raw.contains(it, ignoreCase = true) }) {
                                solvedCookies = raw
                                latch.countDown()
                            }
                        }
                    }
                    webView.loadUrl(url.toString())
                } catch (t: Throwable) {
                    latch.countDown()
                }
            }
            latch.await(45, TimeUnit.SECONDS)
            return solvedCookies?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("; ")
        } finally {
            try {
                thread.looper.quitSafely()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val CHALLENGE_MARKERS = listOf(
            "Just a moment",
            "Attention Required",
            "cf_chl_opt",
            "cf-challenge",
            "challenge-platform",
            "challenge-error-text",
        )

        val CLEARANCE_MARKERS = listOf("cf_clearance", "cf_chl")
    }
}
