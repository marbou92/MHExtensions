package eu.kanade.tachiyomi.extension.ar.procomic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.TimeUnit
import kotlin.Pair
import kotlin.concurrent.thread

/**
 * TurnstileSolver — the bounded offscreen widget mint (reconstructed for
 * v1.6.56, behavior-faithful to the shipped v1.6.55 build).
 *
 * v1.6.56 (v143) PLACEMENT: the solver is ONLY reachable through the
 * opt-in browser road ("Browser page walk" switch, default OFF). The
 * default open path never constructs this class — the field device has
 * died inside every WebView-bearing profile this extension ever shipped,
 * so no browser may open unless the user explicitly asks for one.
 */
internal object TurnstileSolver {

    private const val TAG = "ProComicTurnstile"

    /** Per-rung widget solve timeouts (seconds). */
    private val RUNG_TIMEOUTS = listOf(24L, 14L)

    /** The live-fetched sitekey arrives blank on some apexes — the baked
     * login sitekey is the last-resort render key. */
    private const val FALLBACK_SITEKEY = "0x4AAAAAACk1d7qWJi_of2qU"

    /** The mint origin (the field-proven pairing). */
    private const val MINT_BASE_URL = "https://procomic.pro/"

    /** Minimum spacing between widget mints (the site's own pacing). */
    private const val SOLVE_SPACING_MS = 3000L

    /** Ladder settle wait after the latch releases. */
    private const val THREAD_JOIN_MS = 2500L

    /** The in-page script-timeout (ms) baked into the widget HTML. */
    private const val SCRIPT_TIMEOUT_MS = 38000L

    private val lastSolveElapsed = java.util.concurrent.atomic.AtomicLong(0L)

    /** Solve one widget token: (token, widgetId) or null. [mode] is the
     * site's gate mode ("visible" → always/normal first; hidden → the
     * flexible rungs first). [ua] is the browser UA stamped on the widget
     * WebView. */
    fun solve(context: Context, siteKey: String?, mode: String?, ua: String): Pair<String, String>? {
        val spacing = SystemClock.elapsedRealtime() - lastSolveElapsed.get()
        if (spacing in 0 until SOLVE_SPACING_MS) {
            try {
                Thread.sleep(SOLVE_SPACING_MS - spacing)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        lastSolveElapsed.set(SystemClock.elapsedRealtime())
        val key = siteKey?.takeIf { it.isNotBlank() } ?: FALLBACK_SITEKEY
        val rungs: List<Pair<String, String>> = if (mode == "visible") {
            listOf("always" to "normal", "interaction-only" to "flexible")
        } else {
            listOf("interaction-only" to "flexible", "always" to "normal")
        }
        rungs.forEachIndexed { index, (appearance, size) ->
            val timeout = RUNG_TIMEOUTS.getOrElse(index) { 14L }
            runCatching {
                solveOnce(context, key, appearance, size, timeout, MINT_BASE_URL, ua)
            }.getOrNull()?.let { return it }
            Log.i(TAG, "ladder rung $index ($appearance/$size) yielded no token")
        }
        return null
    }

    /** One bounded widget render: a HandlerThread-owned WebView loads the
     * widget page, the bridge hands the token out through a latch. */
    private fun solveOnce(
        context: Context,
        siteKey: String,
        appearance: String,
        size: String,
        timeoutSeconds: Long,
        baseUrl: String,
        ua: String,
    ): Pair<String, String>? {
        val result = SolveResult()
        val webViewBox = arrayOfNulls<WebView>(1)
        val handlerThread = HandlerThread("ProComicTurnstile")
        handlerThread.setUncaughtExceptionHandler { thread, t ->
            Log.w(TAG, "solver thread ${thread.name} died safely: ${t.javaClass.simpleName}")
        }
        return try {
            handlerThread.start()
            val handler = Handler(handlerThread.looper)
            val latch = java.util.concurrent.CountDownLatch(1)
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            val done = Runnable {
                if (settled.compareAndSet(false, true)) latch.countDown()
            }
            handler.post {
                var webView: WebView? = null
                try {
                    webView = buildWebView(context, result, done, ua)
                    webViewBox[0] = webView
                    try {
                        webView.measure(
                            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY),
                        )
                        webView.layout(0, 0, 320, 140)
                    } catch (_: Throwable) {
                    }
                    webView.loadDataWithBaseURL(
                        baseUrl,
                        widgetHtml(siteKey, appearance, size),
                        "text/html",
                        "utf-8",
                        null,
                    )
                    handler.postDelayed(
                        {
                            if (result.error == null) result.error = "timeout"
                            done.run()
                        },
                        TimeUnit.SECONDS.toMillis(timeoutSeconds),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "solver setup failed: ${t.javaClass.simpleName}")
                    result.error = "setup-${t.javaClass.simpleName}"
                    done.run()
                    try {
                        webView?.destroy()
                    } catch (_: Throwable) {
                    }
                }
            }
            latch.await(TimeUnit.SECONDS.toMillis(timeoutSeconds) + 5000, TimeUnit.MILLISECONDS)
            val token = result.token
            if (!token.isNullOrBlank()) {
                Log.i(TAG, "token minted (${token.length} chars)")
                cleanup(handlerThread, webViewBox)
                token to (result.widgetId ?: "0")
            } else {
                Log.i(TAG, "no token (error=${result.error ?: "timeout"})")
                cleanup(handlerThread, webViewBox)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "solve failed: ${t.javaClass.simpleName}")
            cleanup(handlerThread, webViewBox)
            null
        }
    }

    private fun cleanup(handlerThread: HandlerThread, webViewBox: Array<WebView?>) {
        try {
            val looper: Looper? = handlerThread.looper
            if (looper != null && looper.thread.isAlive) {
                Handler(looper).post {
                    try {
                        webViewBox[0]?.stopLoading()
                        webViewBox[0]?.destroy()
                    } catch (_: Throwable) {
                    }
                    looper.quitSafely()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            handlerThread.join(THREAD_JOIN_MS)
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context, result: SolveResult, done: Runnable, ua: String): WebView {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.blockNetworkImage = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.settings.userAgentString = ua
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                try {
                    view?.evaluateJavascript("typeof __pcRender==='function'&&__pcRender()", null)
                } catch (_: Throwable) {
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "renderer gone — solver yields")
                try {
                    view?.stopLoading()
                } catch (_: Throwable) {
                }
                try {
                    view?.destroy()
                } catch (_: Throwable) {
                }
                result.error = "renderer-gone"
                done.run()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(result, done), "ProChanBridge")
        return webView
    }

    private class SolveResult {
        @Volatile var token: String? = null

        @Volatile var widgetId: String? = null

        @Volatile var error: String? = null
    }

    private class Bridge(private val result: SolveResult, private val done: Runnable) {
        @JavascriptInterface
        fun onError(error: String?) {
            result.error = error ?: "error"
        }

        @JavascriptInterface
        fun onSolved(widgetId: String?, token: String?) {
            if (!token.isNullOrBlank()) {
                result.token = token
                result.widgetId = widgetId ?: "0"
            }
            done.run()
        }
    }

    /** The widget page — the site's own render call on an explicit #ts
     * target, with the solver's retry ladder and error callbacks. */
    private fun widgetHtml(siteKey: String, appearance: String, size: String): String {
        val script = """window.__pcRendered=false;
function __pcRender(){
  if(window.__pcRendered)return;
  if(!window.turnstile){setTimeout(__pcRender,150);return}
  window.__pcRendered=true;
  try{
    window.__wid=turnstile.render('#ts',{
      sitekey:'$siteKey',
      appearance:'$appearance',
      size:'$size',
      retry:'auto',
      'retry-interval':2000,
      callback:function(t){try{window.__BRIDGE__.onSolved(String(window.__wid||'0'),t);}catch(e){}},
      'error-callback':function(e){try{window.__BRIDGE__.onError(String(e||'error'));}catch(e2){}},
      'expired-callback':function(){try{window.__BRIDGE__.onError('expired');}catch(e2){}}
    });
  }catch(e){try{window.__BRIDGE__.onError('render-exception');}catch(e2){}}
}
setTimeout(function(){try{window.__BRIDGE__.onError('script-timeout');}catch(e){}},$SCRIPT_TIMEOUT_MS);
__pcRender();"""
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;padding:0;background:#ffffff}</style></head><body><div id=\"ts\"></div><script>" +
            script.replace("__BRIDGE__", "ProChanBridge") +
            "</script>" +
            "<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit\" async></script></body></html>"
    }
}
