package keiyoushi.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import keiyoushi.webview.internal.WebViewGlueBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Thrown when the WebView's render process crashes or is killed mid-run. */
class RenderProcessGoneException internal constructor(
    didCrash: Boolean,
) : Exception(
    if (didCrash) "WebView render process crashed" else "WebView render process was killed",
)

/**
 * Thrown by [runWebView] on timeout. Deliberately a plain [Exception] rather than a
 * [kotlinx.coroutines.CancellationException], so callers see a failure instead of a
 * silently swallowed cancellation.
 */
class WebViewTimeoutException internal constructor(
    timeout: Duration,
) : Exception("Timed out waiting for WebView after $timeout")

/**
 * DSL for configuring and driving a single [runWebView] run.
 *
 * Threading: page/navigation hooks and [poll] run on the main thread; [interceptRequest]
 * and [jsBridge] handlers run on WebView background threads. An exception thrown from any
 * callback fails the run via [reject].
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewScope<T> internal constructor(
    private val webView: WebView,
    private val deferred: CompletableDeferred<T>,
) {
    internal val pageStartedHooks = CopyOnWriteArrayList<(String) -> Unit>()
    internal val pageFinishedHooks = CopyOnWriteArrayList<(String) -> Unit>()
    internal val receivedErrorHooks = CopyOnWriteArrayList<(WebResourceRequest, WebResourceError) -> Unit>()
    internal val bridgeNames = CopyOnWriteArrayList<String>()

    @Volatile
    internal var interceptHook: ((WebResourceRequest) -> WebResourceResponse?)? = null

    @Volatile
    internal var destroyed = false

    @Volatile
    internal var renderDead = false

    private val loaded = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Completes [runWebView] with [value]. First completion wins; later calls are no-ops. */
    fun resolve(value: T) {
        deferred.complete(value)
    }

    /** Completes [runWebView] by throwing [error]. No-op if already completed. */
    fun reject(error: Throwable) {
        deferred.completeExceptionally(error)
    }

    var javaScriptEnabled: Boolean by setting({ javaScriptEnabled }, { javaScriptEnabled = it })
    var domStorageEnabled: Boolean by setting({ domStorageEnabled }, { domStorageEnabled = it })
    var blockImages: Boolean by setting({ blockNetworkImage }, { blockNetworkImage = it })
    var useWideViewPort: Boolean by setting({ useWideViewPort }, { useWideViewPort = it })
    var loadWithOverviewMode: Boolean by setting({ loadWithOverviewMode }, { loadWithOverviewMode = it })

    /** Also spoofs the `Sec-CH-UA` client hints to match the user agent. */
    var userAgent: String
        get() = webView.settings.userAgentString
        set(value) {
            webView.settings.userAgentString = value
            WebViewGlueBridge.setClientHintsFromUserAgent(webView.settings, value)
        }

    /** Runs [block] on every navigation start. */
    fun onPageStarted(block: (url: String) -> Unit) {
        pageStartedHooks += { url ->
            try {
                block(url)
            } catch (t: Throwable) {
                reject(t)
            }
        }
    }

    /**
     * Runs [block] on every navigation finish. Note that this can fire multiple times for a
     * single page (redirects, SPA navigations, iframes).
     */
    fun onPageFinished(block: (url: String) -> Unit) {
        pageFinishedHooks += { url ->
            try {
                block(url)
            } catch (t: Throwable) {
                reject(t)
            }
        }
    }

    /** Runs [block] on navigation failures (DNS/SSL/network errors). */
    fun onReceivedError(block: (request: WebResourceRequest, error: WebResourceError) -> Unit) {
        receivedErrorHooks += { request, error ->
            try {
                block(request, error)
            } catch (t: Throwable) {
                reject(t)
            }
        }
    }

    /**
     * Registers [block] to inspect/replace/block every resource request; return null to let a
     * request proceed normally. Runs on a WebView background thread. Can only be registered once.
     */
    fun interceptRequest(block: (WebResourceRequest) -> WebResourceResponse?) {
        check(interceptHook == null) { "interceptRequest already registered" }
        interceptHook = { request ->
            try {
                block(request)
            } catch (t: Throwable) {
                reject(t)
                null
            }
        }
    }

    /**
     * Exposes `window.$name.post(message)` to the page, invoking [handler] with the message.
     * Must be called before [loadUrl]/[loadData]; interfaces added afterward are not visible
     * to the already-loaded page.
     */
    fun jsBridge(name: String, handler: (String) -> Unit) {
        bridgeNames += name
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun post(message: String) {
                    if (destroyed) return
                    try {
                        handler(message)
                    } catch (t: Throwable) {
                        reject(t)
                    }
                }
            },
            name,
        )
    }

    /** Loads [url]. Can only be called once per scope. */
    fun loadUrl(url: String, headers: Map<String, String> = emptyMap()) {
        markLoaded()
        webView.loadUrl(url, headers)
    }

    /** Loads [html] as the page content, with [baseUrl] as its origin. Can only be called once per scope. */
    fun loadData(baseUrl: String, html: String, mimeType: String = "text/html") {
        markLoaded()
        webView.loadDataWithBaseURL(baseUrl, html, mimeType, "UTF-8", null)
    }

    /** Runs [script], optionally passing its JSON-encoded result to [callback]. */
    fun evaluateJs(script: String, callback: ((String) -> Unit)? = null) {
        runOnMain {
            if (destroyed) return@runOnMain
            if (callback == null) {
                webView.evaluateJavascript(script, null)
            } else {
                webView.evaluateJavascript(script) { value ->
                    try {
                        callback(value)
                    } catch (t: Throwable) {
                        reject(t)
                    }
                }
            }
        }
    }

    fun stopLoading() {
        runOnMain {
            if (!destroyed) {
                webView.stopLoading()
            }
        }
    }

    /**
     * Dispatches a synthetic tap at ([x], [y]) in the WebView's own (physical)
     * coordinate space — i.e. what a finger touching that point would produce.
     *
     * This reaches through cross-origin iframes (Cloudflare Turnstile and
     * reCAPTCHA checkboxes) that page JavaScript can never click, because the
     * events enter at the platform input layer instead of the DOM. Fire-and-
     * forget: failures are logged, never fatal to the run.
     */
    fun dispatchTap(x: Float, y: Float) {
        runOnMain {
            if (destroyed) return@runOnMain
            try {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                webView.dispatchTouchEvent(down)
                down.recycle()
                val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
                webView.dispatchTouchEvent(up)
                up.recycle()
            } catch (t: Throwable) {
                Log.w("KeiyoushiWebView", "dispatchTap failed", t)
            }
        }
    }

    /**
     * Repeats [block] on the main thread every [interval] until [resolve]/[reject] is called or
     * the scope is destroyed. The first run happens after one [interval], not immediately.
     */
    fun poll(interval: Duration = 500.milliseconds, block: () -> Unit) {
        val runnable = object : Runnable {
            override fun run() {
                if (destroyed || deferred.isCompleted) return
                try {
                    block()
                } catch (t: Throwable) {
                    reject(t)
                    return
                }
                if (!destroyed && !deferred.isCompleted) {
                    mainHandler.postDelayed(this, interval.inWholeMilliseconds)
                }
            }
        }
        runOnMain { mainHandler.postDelayed(runnable, interval.inWholeMilliseconds) }
    }

    private fun markLoaded() {
        check(loaded.compareAndSet(false, true)) { "load already called on this WebViewScope" }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun <V> setting(
        get: WebSettings.() -> V,
        set: WebSettings.(V) -> Unit,
    ): ReadWriteProperty<Any?, V> = object : ReadWriteProperty<Any?, V> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): V = webView.settings.get()

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: V) {
            webView.settings.set(value)
        }
    }
}

/**
 * Patched as early as possible into every page (see [ScopeWebViewClient]): the
 * DOM-level visibility signals report "visible" even when the WebView could not
 * be attached to a real window (the fallback path of [attachOffscreen]). The
 * engine-level visibility of the widget iframes (Cloudflare Turnstile) cannot be
 * patched from page JS — only a real window attachment fixes those.
 */
private val VISIBILITY_PATCH_JS = """
    (function(){
      try{
        if(window.__mhVisPatched) return; window.__mhVisPatched=true;
        var def=function(o,p,v){try{Object.defineProperty(o,p,{get:function(){return v},configurable:true})}catch(e){}};
        def(document,'visibilityState','visible');
        def(document,'hidden',false);
        def(document,'webkitVisibilityState','visible');
        def(document,'webkitHidden',false);
        try{document.hasFocus=function(){return true}}catch(e){}
        try{document.dispatchEvent(new Event('visibilitychange'))}catch(e){}
      }catch(e){}
    })();
""".trimIndent()

private class ScopeWebViewClient(
    private val scope: WebViewScope<*>,
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (scope.destroyed) return
        view?.evaluateJavascript(VISIBILITY_PATCH_JS, null)
        scope.pageStartedHooks.forEach { it(url.orEmpty()) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (scope.destroyed) return
        scope.pageFinishedHooks.forEach { it(url.orEmpty()) }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        if (scope.destroyed || request == null) return null
        return scope.interceptHook?.invoke(request)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (scope.destroyed) return
        if (request != null && error != null) {
            scope.receivedErrorHooks.forEach { it(request, error) }
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        scope.destroyed = true
        scope.renderDead = true
        scope.reject(RenderProcessGoneException(detail?.didCrash() == true))
        return true
    }
}

private class LoggingWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val priority = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
            else -> Log.INFO
        }
        Log.println(
            priority,
            "KeiyoushiWebView",
            "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
        )
        return true
    }
}

/**
 * A container that isolates the hidden WebView from the host activity: every
 * touch that falls through the real app UI is swallowed here (the site must
 * never receive the user's taps), and no child can steal window focus. The
 * extension's own synthetic taps are unaffected — [WebViewScope.dispatchTap]
 * calls [WebView.dispatchTouchEvent] directly, bypassing parent dispatch.
 */
private class IsolatingContainer(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
    override fun onTouchEvent(ev: MotionEvent?): Boolean = true
    override fun requestChildFocus(child: View?, focused: View?) {}
}

/**
 * Best-effort lookup of the current foreground activity. Extension code only
 * ever gets the Application context, so the activity has to be found through
 * ActivityThread's activity record map (long-standing, greylisted reflection;
 * every step is guarded and the whole probe returns null on failure).
 */
private fun findForegroundActivity(): Activity? = runCatching {
    val threadClass = Class.forName("android.app.ActivityThread")
    val thread = threadClass.getMethod("currentActivityThread").invoke(null) ?: return null
    val activities = threadClass.getDeclaredField("mActivities")
        .apply { isAccessible = true }
        .get(thread) as? Map<*, *> ?: return null

    var fallback: Activity? = null
    for (record in activities.values) {
        val activity = runCatching {
            record?.javaClass?.getDeclaredField("activity")
                ?.apply { isAccessible = true }
                ?.get(record) as? Activity
        }.getOrNull() ?: continue
        if (activity.isFinishing || activity.isDestroyed) continue
        if (activity.window == null) continue
        // The focused activity is the one whose window is really showing.
        if (activity.hasWindowFocus()) return activity
        if (fallback == null) fallback = activity
    }
    fallback
}.getOrNull()

/**
 * Attaches [webView] to a real window — fully laid out and drawn, but parked
 * at index 0 of the activity's decor view (behind the app's own content), so
 * the user never sees it.
 *
 * WHY THIS MATTERS: a WebView that is never attached to a window reports
 * `document.visibilityState = "hidden"`. In that state every visibility-
 * sensitive challenge silently stalls: Cloudflare Turnstile defers its widget
 * until the page becomes visible and never issues a token (the v18-v22
 * "off-screen" builds could never solve anything for this reason), and the
 * managed-challenge loops forever. Once attached — even translated out of
 * sight — Chromium marks the page visible, the widget actually renders, and
 * the non-interactive branch auto-solves exactly like a real browser.
 *
 * Returns an [AutoCloseable] that removes the WebView again, or null when no
 * usable activity was found (the WebView then stays unattached — the old
 * behavior — and only the DOM-level VISIBILITY_PATCH_JS applies).
 */
private fun attachOffscreen(webView: WebView): AutoCloseable? = runCatching {
    val activity = findForegroundActivity() ?: return null
    val decor = activity.window?.decorView as? ViewGroup ?: return null
    val metrics = Resources.getSystem().displayMetrics

    webView.isFocusable = false
    webView.isFocusableInTouchMode = false
    webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

    val container = IsolatingContainer(webView.context).apply {
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    container.addView(
        webView,
        ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels),
    )
    decor.addView(
        container,
        0,
        ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels),
    )

    AutoCloseable { runCatching { decor.removeView(container) } }
}.getOrNull()

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        blockNetworkImage = false
        useWideViewPort = false
        loadWithOverviewMode = false
    }

    runCatching {
        val metrics = Resources.getSystem().displayMetrics
        webView.layoutParams = ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}

/**
 * Runs a WebView with [configure], suspending until it calls `resolve`/`reject` or [timeout]
 * elapses. The WebView is torn down whether this returns, throws, or is canceled.
 */
suspend fun <T> runWebView(
    timeout: Duration = 30.seconds,
    configure: WebViewScope<T>.() -> Unit,
): T = withContext(Dispatchers.Main) {
    val deferred = CompletableDeferred<T>()
    val webView = WebView(applicationContext)
    setupWebView(webView)
    val scope = WebViewScope(webView, deferred)
    webView.webViewClient = ScopeWebViewClient(scope)
    webView.webChromeClient = LoggingWebChromeClient()
    // A never-attached WebView is "hidden" to Chromium — Turnstile and the
    // managed-challenge widget stall forever. Attach it to the foreground
    // activity's window (hidden behind the app UI) whenever possible.
    val detach = attachOffscreen(webView)
    try {
        try {
            scope.configure()
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
        }
        try {
            withTimeout(timeout) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw WebViewTimeoutException(timeout)
        }
    } finally {
        scope.destroyed = true
        webView.stopLoading()
        detach?.close()
        webView.destroy()
    }
}

/**
 * Blocking wrapper around [runWebView] for non-suspend call sites like OkHttp interceptors.
 * OkHttp exposes no per-call cancellation callback, so a side coroutine polls
 * [Call.isCanceled] and cancels the run when it flips. Cancellation surfaces as an
 * [IOException] (matching how OkHttp itself reports a canceled call to interceptors)
 * rather than leaking a raw [CancellationException].
 */
fun <T> runWebViewBlocking(
    call: Call,
    timeout: Duration = 30.seconds,
    configure: WebViewScope<T>.() -> Unit,
): T {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "runWebViewBlocking must not be called on the main thread"
    }
    return try {
        runBlocking {
            val watcher = launch {
                while (isActive) {
                    if (call.isCanceled()) {
                        this@runBlocking.cancel("OkHttp call was canceled")
                        break
                    }
                    delay(250.milliseconds)
                }
            }
            try {
                runWebView(timeout, configure)
            } finally {
                watcher.cancel()
            }
        }
    } catch (e: CancellationException) {
        throw IOException("Canceled", e)
    }
}

/**
 * Reads [key] from `localStorage` after loading [url], or null if unset. Loads an empty
 * page with [url] as its origin, so [url] itself is never fetched over the network.
 */
suspend fun getLocalStorage(url: String, key: String): String? = runWebView(timeout = 10.seconds) {
    onPageFinished {
        evaluateJs("localStorage.getItem(${key.toJsonString()})") { value ->
            resolve(value.parseAs<String?>())
        }
    }
    loadData(url, "")
}
