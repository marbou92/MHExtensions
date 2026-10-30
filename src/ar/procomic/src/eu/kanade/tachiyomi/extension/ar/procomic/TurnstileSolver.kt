package eu.kanade.tachiyomi.extension.ar.procomic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v114 — the one credential the plain-HTTP chain cannot mint: a real
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
 * v114 field verdict on v113: the mint never produced a token because
 * the SITEKEY had rotated. The reader does not bake the key — it fetches
 * it per session from GET /api/auth/public-settings
 * (`loginTurnstile.siteKey`, live 2026-10-28: 0x4AAAAAACwjiJ5Kt0K0kWx9,
 * while v113's baked 0x4AAAAAACk1d7qWJi_of2qU was already dead). A dead
 * sitekey makes turnstile.render fail immediately (invalid sitekey
 * error, retry:auto cannot recover), so the solver timed out and the
 * source shipped the 3 public pages. The sitekey now arrives as a
 * parameter (fetched fresh per gate run by ProComic), and the widget
 * config follows the server's OWN turnstileMode from the 403 answer:
 * visible -> appearance:always + size:normal, hidden ->
 * interaction-only + size:flexible — exactly what the reader chunk
 * passes for each mode. The WebView is also measure()+layout()-ed with
 * real pixels now: Turnstile checks the widget container's box, and a
 * never-laid-out 0x0 view gives the challenge iframe nothing to render
 * into (a second, independent reason v113's mint could stall).
 *
 * v115 field verdict on v114: still three pages in the field. The
 * exchange side is provably consistent (the minted token never carries
 * a UA the site could see — Cloudflare's siteverify does not expose it
 * — and the deferred JWT is fetched and spent with ONE forced UA), so
 * the failing link is the mint itself. Two changes, both surgical:
 *   1. MODE LADDER — the gate's `turnstileMode` is the site's UX
 *      preference for the widget; the server validates the TOKEN, not
 *      the widget's appearance. v114 minted once with the server's
 *      mode; a `visible` widget sits in an interaction loop forever in
 *      a low-trust WebView. The solver now runs a LADDER: the server's
 *      mode first (20 s), then the opposite mode (14 s) — an
 *      interaction-only/flexible widget frequently auto-passes where
 *      the visible one loops, and the token is identical either way.
 *   2. UA MASKING — the WebView's natural UA carries the `; wv)`
 *      marker, an instant WebView/bot tell for Cloudflare. The solver
 *      now presents the module's BROWSER_UA (the exact string every
 *      other hop of the chain uses), removing the most deterministic
 *      of the challenge's negative signals. (Client-hint headers still
 *      speak with the WebView's real version — nothing more can be
 *      done about that from a WebView.)
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

    /** The reader-chunk sitekey, BAKED-IN FALLBACK ONLY (v114). The live
     * value rotates and arrives per gate run via public-settings; this
     * constant is the last-resort default when that fetch fails. */
    const val DEFAULT_SITE_KEY = "0x4AAAAAACk1d7qWJi_of2qU"

    /** Kept as an alias so older probes keep compiling. */
    const val SITE_KEY = DEFAULT_SITE_KEY

    /** Origin the solver page runs on — Turnstile is domain-bound.
     * v117: parameterizable (the deferred exchange now walks the .net
     * apex first, so the mint runs there too); the .pro constant stays
     * the default for the harness pins and any legacy caller. */
    const val ORIGIN = "https://procomic.pro/"

    /** Cloudflare's widget script (render=explicit, same as the reader). */
    const val WIDGET_SCRIPT = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"

    /** JS bridge name. */
    const val BRIDGE_NAME = "ProChanBridge"

    /** Hard wall for the whole solve (both ladder rungs). */
    const val TIMEOUT_SECONDS = 40L

    /** Per-rung walls: the server's own mode gets the bigger window. */
    internal val RUNG_TIMEOUTS_SECONDS = listOf(24L, 14L)

    /** Minimum spacing between solves (be polite to the challenge). */
    private const val SOLVE_SPACING_MS = 3000L

    /** v115: the ladder of widget configs one solve walks. The gate's
     * `turnstileMode` ("visible"/"hidden") picks the FIRST rung — the
     * server's own preference — and the OPPOSITE mode rides second: the
     * token validates the same regardless of the widget's appearance,
     * and an interaction-only widget often auto-passes where a visible
     * one loops in a low-trust WebView. Pure so the harness can pin it.
     * Returns (appearance, size) pairs, reader-chunk semantics:
     * visible -> always/normal, anything else -> interaction-only/flexible. */
    internal fun ladderConfigs(mode: String?): List<Pair<String, String>> {
        val visible = "always" to "normal"
        val hidden = "interaction-only" to "flexible"
        return if (mode == "visible") listOf(visible, hidden) else listOf(hidden, visible)
    }

    @Volatile private var lastSolveAt = 0L

    /**
     * Mints one Turnstile token, or null. Never throws. Blocks up to
     * [TIMEOUT_SECONDS]. Returns (token, widgetId) — the widget id rides
     * along because the site's reader sends it as `x-turnstile-widget`.
     *
     * v114: [siteKey] is the LIVE key from /api/auth/public-settings
     * (null -> the baked-in fallback), [mode] is the server's own
     * turnstileMode from the gate's 403 answer. v115: [ua] masks the
     * WebView's `; wv)` tell with the chain's forced browser UA.
     */
    fun solve(
        context: Context,
        siteKey: String? = null,
        mode: String? = null,
        ua: String? = null,
        origin: String = ORIGIN,
    ): Pair<String, String>? {
        val key = siteKey?.takeIf { it.isNotBlank() } ?: DEFAULT_SITE_KEY
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

        val rungs = ladderConfigs(mode)
        rungs.forEachIndexed { index, (appearance, size) ->
            val budget = RUNG_TIMEOUTS_SECONDS.getOrElse(index) { 14L }
            val solved = runCatching {
                attemptOnce(context, key, appearance, size, ua, budget, origin)
            }.getOrNull()
            if (solved != null) return solved
            Log.i(TAG, "ladder rung $index ($appearance/$size) yielded no token")
        }
        return null
    }

    /**
     * One ladder rung: one WebView, one widget, one [timeoutSeconds]
     * wall. Identical to the v114 solver apart from the [ua] override
     * and the parameterized budget. Never throws.
     */
    @Suppress("SameParameterValue")
    private fun attemptOnce(
        context: Context,
        key: String,
        appearance: String,
        size: String,
        ua: String?,
        timeoutSeconds: Long,
        origin: String = ORIGIN,
    ): Pair<String, String>? {
        val result = Cell()
        var thread: HandlerThread? = null
        val webViewHolder = arrayOfNulls<WebView>(1)
        try {
            thread = HandlerThread("ProComicTurnstile").apply {
                // v119: the process-death firewall for the mint. The
                // per-step guards (try/catch at every lifecycle step,
                // renderer-gone consumed) cover the known paths — but any
                // throwable a WebView/chromium callback raises OUTSIDE
                // them reaches the default uncaught-exception handler and
                // kills the HOST APP. This thread's death is now a null
                // token, never a dead app.
                setUncaughtExceptionHandler { t, e ->
                    Log.w(TAG, "solver thread ${t.name} died safely: ${e.javaClass.simpleName}")
                }
                start()
            }
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
                        // v115: mask the WebView's `; wv)` UA tell with the
                        // chain's forced browser UA (null -> the natural UA).
                        ua?.let { settings.userAgentString = it }
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
                    // v114: the widget needs a real laid-out surface. The
                    // view is never attached to a window (nothing is ever
                    // user-visible), but Turnstile checks the container's
                    // box before running its challenge — a 0x0 view can
                    // stall the challenge forever. measure+layout with
                    // real pixels gives the challenge iframe an actual
                    // viewport.
                    try {
                        webView?.measure(
                            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY),
                        )
                        webView?.layout(0, 0, 320, 140)
                    } catch (_: Throwable) {
                    }
                    webView?.loadDataWithBaseURL(origin, pageHtml(key, appearance, size), "text/html", "utf-8", null)

                    // The hard wall: whatever happens, the WebView dies.
                    handler.postDelayed(
                        {
                            result.error = result.error ?: "timeout"
                            finishOnce.run()
                        },
                        TimeUnit.SECONDS.toMillis(timeoutSeconds),
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

            latch.await(TimeUnit.SECONDS.toMillis(timeoutSeconds) + 5_000, TimeUnit.MILLISECONDS)

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
     * fetch is Cloudflare's challenge JS. v114: the sitekey, appearance
     * and size arrive as parameters (live sitekey + the server's
     * turnstileMode — the reader's exact per-mode config). */
    internal fun pageHtml(
        siteKey: String = DEFAULT_SITE_KEY,
        appearance: String = "interaction-only",
        size: String = "flexible",
    ): String {
        val js = """
            window.__pcRendered=false;
            function __pcRender(){
              if(window.__pcRendered)return;
              if(!window.turnstile){setTimeout(__pcRender,150);return}
              window.__pcRendered=true;
              try{
                window.__wid=turnstile.render('#ts',{
                  sitekey:'__SITEKEY__',
                  appearance:'__APPEARANCE__',
                  size:'__SIZE__',
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
            .replace("__SITEKEY__", siteKey)
            .replace("__APPEARANCE__", appearance)
            .replace("__SIZE__", size)
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
