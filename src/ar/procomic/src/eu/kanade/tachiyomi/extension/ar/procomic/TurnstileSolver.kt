package eu.kanade.tachiyomi.extension.ar.procomic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v113 — the one credential the plain-HTTP chain cannot mint: a real
 * Cloudflare Turnstile token for the site's reader gate.
 *
 * Why this exists: since the site capped the SSR page embed at three
 * pages, everything beyond rides `GET /chapter-deferred-media/{id}` and
 * the endpoint demands `x-turnstile-token` — a token only Cloudflare's
 * own browser JS (challenges.cloudflare.com) can produce for the site's
 * sitekey. Nothing forgeable over plain HTTP replays it (verified live:
 * bogus tokens, edge headers, the android route — all rejected). The
 * site's own reader solves the widget in-page, invisibly for clean
 * clients, which is why the website "just loads" the rest on scroll.
 *
 * How it solves: a throwaway offscreen WebView loads a ~1 KB local page
 * whose ORIGIN is the site's apex (loadDataWithBaseURL), renders the
 * site's Turnstile widget and hands the minted token to this object
 * through a JS bridge. The widget runs `appearance: interaction-only` —
 * exactly what the site's reader passes — so for the clean, residential
 * clients the challenge resolves with no interaction at all.
 *
 * The crash-safety contract (the v104–v110 lesson, non-negotiable):
 *   - This WebView NEVER loads an image. Not one. blockNetworkImage is
 *     set BEFORE the page loads, and the page HTML contains no <img>,
 *     no CSS background, no remote stylesheet — the only network fetch
 *     is Cloudflare's challenge JS. Every v104–v110 crash was AVIF
 *     bytes reaching a decoder; there is no decoder path anywhere near
 *     this page, and nothing from the site's content pipeline touches it.
 *   - A renderer (sandboxed chromium) death is handled:
 *     onRenderProcessGone returns true — the app survives, the solver
 *     returns null.
 *   - Every lifecycle step is wrapped; the whole solve is bounded by a
 *     hard timeout; the WebView is always destroyed; the HandlerThread
 *     is always quit. A solve failure costs nothing: the source keeps
 *     the public pages exactly as v112 shipped them.
 */
internal object TurnstileSolver {

    private const val TAG = "ProComicTurnstile"

    /** The site's reader gate sitekey (page chunk 19573, live 2026-10-28). */
    const val SITE_KEY = "0x4AAAAAACk1d7qWJi_of2qU"

    /** Origin the solver page runs on — Turnstile is domain-bound. */
    const val ORIGIN = "https://procomic.pro/"

    /** Cloudflare's widget script (render=explicit, same as the reader). */
    const val WIDGET_SCRIPT = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"

    /** JS bridge name. */
    const val BRIDGE_NAME = "ProChanBridge"

    /** Hard wall for the whole solve. */
    const val TIMEOUT_SECONDS = 30L

    /** Minimum spacing between solves (be polite to the challenge). */
    private const val SOLVE_SPACING_MS = 3000L

    @Volatile private var lastSolveAt = 0L

    /**
     * Mints one Turnstile token, or null. Never throws. Blocks up to
     * [TIMEOUT_SECONDS]. Returns (token, widgetId) — the widget id rides
     * along because the site's reader sends it as `x-turnstile-widget`.
     */
    fun solve(context: Context): Pair<String, String>? {
        val now = elapsedMillis()
        val sinceLast = now - lastSolveAt
        if (sinceLast in 0 until SOLVE_SPACING_MS) {
            try {
                Thread.sleep(SOLVE_SPACING_MS - sinceLast)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        lastSolveAt = elapsedMillis()

        val result = Cell()
        var thread: HandlerThread? = null
        val webViewHolder = arrayOfNulls<WebView>(1)
        try {
            thread = HandlerThread("ProComicTurnstile").apply { start() }
            val handler = Handler(thread.looper)
            val finished = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val finishOnce = Runnable {
                if (finished.compareAndSet(false, true)) latch.countDown()
            }

            handler.post {
                var webView: WebView? = null
                try {
                    @SuppressLint("AddJavascriptInterface", "SetJavaScriptEnabled")
                    fun build(): WebView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // THE crash-safety line: no image resource is ever
                        // fetched by this WebView. The challenge page needs
                        // none — it is one script and one empty div.
                        settings.blockNetworkImage = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?,
                            ): Boolean {
                                // The widget never navigates; nothing else
                                // is allowed to either.
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Belt and suspenders: the inline loader
                                // already polls for window.turnstile.
                                try {
                                    view?.evaluateJavascript("typeof __pcRender==='function'&&__pcRender()", null)
                                } catch (_: Throwable) {
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                // Chromium died: the APP must not. Consume
                                // the event, tear this WebView down, report
                                // a graceful no-token. (The solver WebView
                                // is never attached to a view hierarchy, so
                                // there is nothing to detach.)
                                Log.w(TAG, "renderer gone — solver yields")
                                try {
                                    view?.stopLoading()
                                    view?.destroy()
                                } catch (_: Throwable) {
                                }
                                result.error = "renderer-gone"
                                finishOnce.run()
                                return true
                            }
                        }
                        webChromeClient = WebChromeClient()
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onSolved(widget: String?, token: String?) {
                                    if (!token.isNullOrBlank()) {
                                        result.token = token
                                        result.widget = widget ?: "0"
                                    }
                                    finishOnce.run()
                                }

                                @JavascriptInterface
                                fun onError(code: String?) {
                                    result.error = code ?: "error"
                                    // Not finishing on every widget error:
                                    // the widget auto-retries (retry:auto).
                                    // A final wall is the timeout below.
                                }
                            },
                            BRIDGE_NAME,
                        )
                    }

                    webView = build()
                    webViewHolder[0] = webView
                    webView?.loadDataWithBaseURL(ORIGIN, pageHtml(), "text/html", "utf-8", null)

                    // The hard wall: whatever happens, the WebView dies.
                    handler.postDelayed(
                        {
                            result.error = result.error ?: "timeout"
                            finishOnce.run()
                        },
                        TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "solver setup failed: ${t.javaClass.simpleName}")
                    result.error = "setup-${t.javaClass.simpleName}"
                    finishOnce.run()
                    try {
                        webView?.destroy()
                    } catch (_: Throwable) {
                    }
                }
            }

            latch.await(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS) + 5_000, TimeUnit.MILLISECONDS)

            val token = result.token
            if (token.isNullOrBlank()) {
                Log.i(TAG, "no token (error=${result.error})")
                return null
            }
            Log.i(TAG, "token minted (${token.length} chars)")
            return token to (result.widget ?: "0")
        } catch (t: Throwable) {
            Log.w(TAG, "solve failed: ${t.javaClass.simpleName}")
            return null
        } finally {
            try {
                thread?.looper?.let { looper ->
                    if (looper.thread.isAlive) {
                        Handler(looper).post {
                            // Whatever the outcome, the WebView dies here —
                            // on the thread that owns it.
                            try {
                                webViewHolder[0]?.stopLoading()
                                webViewHolder[0]?.destroy()
                            } catch (_: Throwable) {
                            }
                            Looper.myLooper()?.quitSafely()
                        }
                    }
                }
                thread?.join(2_500)
            } catch (_: Throwable) {
            }
        }
    }

    private fun elapsedMillis(): Long = android.os.SystemClock.elapsedRealtime()

    /** The volatile hand-off cell between the JavaBridge thread and the
     * calling (OkHttp) thread. */
    private class Cell {
        @Volatile var token: String? = null

        @Volatile var widget: String? = null

        @Volatile var error: String? = null
    }

    /** The solver page: one empty div, the widget loader, nothing else.
     * No <img>, no stylesheet, no font, no tracking — the only remote
     * fetch is Cloudflare's challenge JS. */
    internal fun pageHtml(): String {
        val js = """
            window.__pcRendered=false;
            function __pcRender(){
              if(window.__pcRendered)return;
              if(!window.turnstile){setTimeout(__pcRender,150);return}
              window.__pcRendered=true;
              try{
                window.__wid=turnstile.render('#ts',{
                  sitekey:'__SITEKEY__',
                  appearance:'interaction-only',
                  retry:'auto',
                  'retry-interval':2000,
                  callback:function(t){try{window.__BRIDGE__.onSolved(String(window.__wid||'0'),t);}catch(e){}},
                  'error-callback':function(e){try{window.__BRIDGE__.onError(String(e||'error'));}catch(e2){}},
                  'expired-callback':function(){try{window.__BRIDGE__.onError('expired');}catch(e2){}}
                });
              }catch(e){try{window.__BRIDGE__.onError('render-exception');}catch(e2){}}
            }
            setTimeout(function(){try{window.__BRIDGE__.onError('script-timeout');}catch(e){}},__TIMEOUT_MS__);
            __pcRender();
        """.trimIndent()
            .replace("__SITEKEY__", SITE_KEY)
            .replace("__BRIDGE__", BRIDGE_NAME)
            .replace("__TIMEOUT_MS__", (TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS) - 2_000).toString())
        return """<!doctype html><html><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<style>html,body{margin:0;padding:0;background:#ffffff}</style></head>""" +
            """<body><div id="ts"></div><script>""" + js + """</script>""" +
            """<script src="__WIDGET__" async></script></body></html>"""
                .replace("__WIDGET__", WIDGET_SCRIPT)
    }
}
