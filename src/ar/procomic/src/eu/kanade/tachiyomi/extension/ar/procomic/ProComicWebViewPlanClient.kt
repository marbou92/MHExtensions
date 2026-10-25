package eu.kanade.tachiyomi.extension.ar.procomic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ProComic requests that must look like the site's own browser, executed
 * inside a real offscreen Android WebView.
 *
 * WHY THIS EXISTS (2026-10-25): every OkHttp tier of the plan POST — the
 * bare inner client AND the session client with the full browser XHR header
 * set from the reader bundle — still answers 502 on some devices from page
 * ~4 onward, while the site's own reader works in the same device's normal
 * browser. The one difference left is not headers or cookies, it is the
 * CLIENT ITSELF: a real browser engine (its TLS fingerprint, its client
 * hints, a cookie jar that genuinely cleared the Cloudflare challenge).
 *
 * v1.6.15 RE-ROLE: the reader bundle's own protocol decrypts browser_session
 * map tokens LOCALLY with a session key from `GET
 * /chapter-map-session-key/{id}?legacy=1` (one call per chapter, cached) and
 * only POSTs proxy plans for protectionV2 chapters. The edge answers the
 * same GET to plain-curl clients but 403s `use_proxy_plan` to OkHttp's TLS
 * fingerprint — so the WebView's primary job now is THAT session-key GET
 * (one per chapter), not a per-page plan POST. `get(url, origin)` serves it;
 * the plan POST (`post`, the v93 method) stays as the legacy fallback tier.
 *
 * Isolation contract with the JVM harness: this object only loads when
 * `android.webkit.WebView` is a real class (gated in ProtectedPageServer
 * through a Class.forName probe), so the harness never reaches any of it.
 * The application context is resolved through ActivityThread (no Injekt,
 * no constructor plumbing); on the JVM the resolution is unreachable.
 */
@SuppressLint("SetJavaScriptEnabled")
internal object ProComicWebViewPlanClient {

    private class WebViewResult(val status: Int, val body: String)

    private val lock = Any()

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var webView: WebView? = null
    private var warmedOrigin: String? = null

    /**
     * The session client whose cookie jar receives whatever the WebView
     * earned (Cloudflare clearance in particular) — set by the
     * ProtectedPageServer when one exists. Merged back so every later
     * non-WebView request carries the same clearance.
     */
    @Volatile
    internal var cookieSink: OkHttpClient? = null

    internal class PostResult(val status: Int, val body: String)

    /**
     * Runs `POST [url]` with [bodyJson] inside the WebView against [origin].
     * Returns (status, body): status <= 0 on non-HTTP failures. One automatic
     * retry after a Cloudflare challenge response: the interstitial's own JS
     * clears inside the WebView, so the second attempt rides the fresh
     * clearance. @JvmStatic + Pair: ProtectedPageServer reaches this through
     * reflection (the harness build excludes this file — it touches
     * android.webkit), so the surface must stay simple.
     */
    @JvmStatic
    fun post(url: String, bodyJson: String, origin: String): Pair<Int, String> {
        val first = attemptPost(url, bodyJson, origin, timeoutMs = 15_000L)
        if (first.status in 200..299) {
            syncCookies(origin)
            return Pair(first.status, first.body)
        }
        if (first.status == 403) {
            // Likely the challenge page; give its JS a moment, re-warm, retry.
            Thread.sleep(2_500L)
            synchronized(lock) { warmedOrigin = null }
            val second = attemptPost(url, bodyJson, origin, timeoutMs = 12_000L)
            if (second.status in 200..299) {
                syncCookies(origin)
                return Pair(second.status, second.body)
            }
            return Pair(second.status, second.body)
        }
        return Pair(first.status, first.body)
    }

    /**
     * Runs `GET [url]` inside the WebView against [origin] — the session-key
     * fetch the way the site's own browser performs it (real TLS stack, real
     * cookie jar, credentials included). Returns (status, body); status <= 0
     * on non-HTTP failures. One automatic retry after a Cloudflare challenge
     * response, exactly like [post]. The JVM harness never loads this object
     * (Class.forName gate), so the surface can stay @JvmStatic-simple.
     */
    @JvmStatic
    fun get(url: String, origin: String): Pair<Int, String> {
        val first = attemptGet(url, origin, timeoutMs = 15_000L)
        if (first.status in 200..299) {
            syncCookies(origin)
            return Pair(first.status, first.body)
        }
        if (first.status == 403) {
            Thread.sleep(2_500L)
            synchronized(lock) { warmedOrigin = null }
            val second = attemptGet(url, origin, timeoutMs = 12_000L)
            if (second.status in 200..299) {
                syncCookies(origin)
                return Pair(second.status, second.body)
            }
            return Pair(second.status, second.body)
        }
        return Pair(first.status, first.body)
    }

    private fun attemptPost(url: String, bodyJson: String, origin: String, timeoutMs: Long): WebViewResult = attempt(url, origin, timeoutMs) { result, latch -> runFetchPost(url, bodyJson, origin, timeoutMs, result, latch) }

    private fun attemptGet(url: String, origin: String, timeoutMs: Long): WebViewResult = attempt(url, origin, timeoutMs) { result, latch -> runFetchGet(url, origin, timeoutMs, result, latch) }

    /** Scaffolding shared by both fetch shapes: off-handler invoke, latch,
     * bounded wait, error mapping. The lambda runs on the WebView handler
     * thread; the bridge fills [result] and counts [latch] down (and the
     * finally guarantees the latch lands even on a throw). */
    private inline fun attempt(
        url: String,
        origin: String,
        timeoutMs: Long,
        crossinline run: (result: AtomicReference<WebViewResult?>, latch: CountDownLatch) -> Unit,
    ): WebViewResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WebViewResult?>(null)
        val h = synchronized(lock) { ensureHandler() } ?: return WebViewResult(-1, "no webview handler")
        h.post {
            try {
                run(result, latch)
            } catch (t: Throwable) {
                result.compareAndSet(null, WebViewResult(-1, t.message ?: t.javaClass.simpleName))
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs + 10_000L, TimeUnit.MILLISECONDS)) {
            result.compareAndSet(null, WebViewResult(-2, "webview timeout"))
        }
        return result.get() ?: WebViewResult(-3, "webview no result")
    }

    /** Runs on the WebView handler thread; blocks until the bridge answers. */
    private fun runFetchPost(
        url: String,
        bodyJson: String,
        origin: String,
        timeoutMs: Long,
        result: AtomicReference<WebViewResult?>,
        latch: CountDownLatch,
    ) {
        val wv = ensureWebView(origin)
        val bridge = PlanBridge(result, latch)
        wv.addJavascriptInterface(bridge, "__pcPlan")

        val urlB64 = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bodyB64 = Base64.encodeToString(bodyJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Mirror of the site reader's own plan fetch (page-chapter bundle):
        // same-origin POST, credentials included, JSON content-type, no cache.
        // The URL and body travel base64-encoded so no quoting can break the
        // script; the body is decoded with TextDecoder for UTF-8 safety.
        val js = """
            (function(){
              function done(s, b){ window.__pcPlan.onResult(String(s), b == null ? "" : String(b)); }
              try {
                var bytes = Uint8Array.from(atob("$bodyB64"), function(c){ return c.charCodeAt(0); });
                var bodyText = new TextDecoder().decode(bytes);
                fetch(atob("$urlB64"), {
                  method: "POST",
                  credentials: "include",
                  cache: "no-store",
                  headers: { "content-type": "application/json", "accept": "application/json" },
                  body: bodyText
                })
                  .then(function(r){ return r.text().then(function(t){ done(r.status, t); }); })
                  .catch(function(e){ done(0, String(e)); });
                return "started";
              } catch (e) { done(-1, String(e)); }
            })()
        """.trimIndent()
        wv.evaluateJavascript(js, null)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** The GET shape the session-key endpoint expects: same-origin fetch with
     * credentials included and an accept header, mirroring the reader's own
     * B() function (which runs `fetch(url, {credentials:"include",
     * cache:"no-store", headers:{accept:"application/json"}}`). */
    private fun runFetchGet(
        url: String,
        origin: String,
        timeoutMs: Long,
        result: AtomicReference<WebViewResult?>,
        latch: CountDownLatch,
    ) {
        val wv = ensureWebView(origin)
        val bridge = PlanBridge(result, latch)
        wv.addJavascriptInterface(bridge, "__pcPlan")

        val urlB64 = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = """
            (function(){
              function done(s, b){ window.__pcPlan.onResult(String(s), b == null ? "" : String(b)); }
              try {
                fetch(atob("$urlB64"), {
                  method: "GET",
                  credentials: "include",
                  cache: "no-store",
                  headers: { "accept": "application/json" }
                })
                  .then(function(r){ return r.text().then(function(t){ done(r.status, t); }); })
                  .catch(function(e){ done(0, String(e)); });
                return "started";
              } catch (e) { done(-1, String(e)); }
            })()
        """.trimIndent()
        wv.evaluateJavascript(js, null)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** JS bridge: carries the fetch answer back over the thread boundary. */
    private class PlanBridge(
        val result: AtomicReference<WebViewResult?>,
        val latch: CountDownLatch,
    ) {
        @JavascriptInterface
        fun onResult(status: String, body: String) {
            result.compareAndSet(null, WebViewResult(status.toIntOrNull() ?: -1, body))
            latch.countDown()
        }
    }

    private fun ensureHandler(): Handler? {
        if (handler == null) {
            val thread = HandlerThread("ProComicPlanWebView").apply {
                isDaemon = true
                start()
            }
            handlerThread = thread
            handler = Handler(thread.looper)
        }
        return handler
    }

    /** Creates the WebView once and warms the origin once per clearance
     * lifetime: the first navigation lets Cloudflare's challenge run in a
     * real engine, exactly like a normal visit. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(origin: String): WebView {
        webView?.let { return it }
        val context = resolveAppContext()
            ?: throw IllegalStateException("ProComic: no application context for the WebView tier")
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = ProtectedPageServer.BROWSER_UA
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                synchronized(lock) { warmedOrigin = origin }
            }
        }
        webView = wv
        warmUp(wv, origin)
        return wv
    }

    private fun warmUp(wv: WebView, origin: String) {
        val done = CountDownLatch(1)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                done.countDown()
            }
        }
        wv.loadUrl("$origin/")
        done.await(20_000L, TimeUnit.MILLISECONDS)
        // Settle: the challenge (or the SPA boot) runs right after finish.
        Thread.sleep(2_000L)
        synchronized(lock) { warmedOrigin = origin }
        syncCookies(origin)
    }

    /** Copies the WebView's cookies for [origin] into the session client's
     * jar, so the lower OkHttp tiers inherit the clearance the WebView
     * earned (and the site stops seeing two different "browsers"). */
    private fun syncCookies(origin: String) {
        val sink = cookieSink ?: return
        runCatching {
            val raw = CookieManager.getInstance().getCookie(origin) ?: return
            val httpUrl = origin.toHttpUrl()
            val cookies = raw.split(';').mapNotNull { part ->
                val pair = part.trim().split('=', limit = 2)
                if (pair.size != 2 || pair[0].isBlank()) return@mapNotNull null
                runCatching {
                    Cookie.Builder()
                        .name(pair[0])
                        .value(pair[1])
                        .domain(httpUrl.host)
                        .build()
                }.getOrNull()
            }
            cookies.forEach { cookie -> sink.cookieJar.saveFromResponse(httpUrl, listOf(cookie)) }
        }
    }

    /** The application context, resolved reflectively so this object stays
     * loadable (though unreachable) on the JVM harness. */
    private fun resolveAppContext(): Context? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
        thread.getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}
