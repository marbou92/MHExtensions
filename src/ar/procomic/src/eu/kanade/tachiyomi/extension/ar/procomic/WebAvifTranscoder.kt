package eu.kanade.tachiyomi.extension.ar.procomic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v138 — THE WEBVIEW AVIF→JPEG TRANSCODER, RE-ARMED PIECE-SIZED: the
 * decode tier that ends the wall WITHOUT the profiles the field device
 * ever died of. History the design answers:
 *
 *  - the site's reconstruction-map chain is provably alive end to end —
 *    the reader-signal exchange answers maps + a fresh pass (HTTP 200),
 *    the plan POST answers a 1600x893 `grid_2x3` map with 6 freshly
 *    signed piece urls + absolute rects (HTTP 200), and the img{1-4}
 *    piece edge answers 200 `image/avif` to a browser UA carrying ANY
 *    procomic referer (403 bare, 403 with a foreign referer — the edge
 *    checks the referer's HOST, live re-verified 2026-11-07);
 *  - on the affected Android 10 device BitmapFactory decodes every AVIF
 *    null, and weserv — which cannot send a Referer — 404s the pieces,
 *    so the v111 remote-transcode ladder has no answer for them;
 *  - the one decoder that HAS been rendering the site's AVIF all along
 *    is the WebView's own Chromium (software AV1/AVIF, every Android
 *    version) — but the field device died on EVERY prior attempt to use
 *    it in-process: v108–v110 (MediaCodec, then full-size view draws,
 *    then bounded ~9 MB draws), v134 (full-page canvases PER PIECE +
 *    toDataURL strings, several pages at a time), v137 (ONE view draw
 *    of a software-layer WebView — Chromium's rasterizer runs in the
 *    APP process there). Every death was an ALLOCATION profile; none
 *    was the canvas decode itself.
 *
 * v138's profile — the math that was never field-tested:
 *
 *  1. ONE warm, offscreen, network-ISOLATED WebView on its own
 *     HandlerThread (blockNetworkLoads + blockNetworkImage BEFORE any
 *     load — the v104–v110 lesson: this renderer never touches the
 *     network; the only input is a data: url), never destroyed between
 *     pieces, NEVER drawn as a view (no draw(), no software layer —
 *     the v137 vector is structurally absent);
 *  2. per piece: evaluateJavascript draws the data-url AVIF onto a
 *     canvas sized to THAT PIECE (the observed grid_2x3 shape is
 *     800x298 ≈ 0.95 MB; the edge is capped at 1280 whatever the
 *     piece claims — a hostile 4000x6000 decodes at 1280 edge ≈ 5.9 MB,
 *     still 15x under v110's fatal profile) and returns ONE
 *     canvas.toDataURL('image/jpeg') — Chromium decodes inside its
 *     SANDBOXED renderer process; this process only ever sees finished
 *     JPEG bytes (sandbox-walk 2026-11-07: a REAL freshly-signed piece
 *     answered 800x298 / 46 KB in 25 ms);
 *  3. STRICTLY ONE decode at a time process-wide (the tier's own
 *     lock) — no "several pages at a time" (the v134 profile is
 *     structurally absent); data: urls are same-origin-safe — the
 *     canvas never taints, no site cookies or credentials are involved;
 *  4. everything is bounded and guarded: a hard per-piece latch
 *     timeout (a wedged renderer poisons and recreates the WebView),
 *     onRenderProcessGone returns true (the host app survives — the
 *     exact contract TurnstileSolver and the reader session run), and
 *     a 3-deaths breaker disables the tier for the process so a
 *     hostile WebView stack can never crash-loop the host app.
 *
 * Failure profile: every failure answers null — the caller falls
 * through to the next tier (weserv for referer-free hosts, the legible
 * 502 / blank-rect paths for pieces) exactly like v132's contract.
 */
internal object WebAvifTranscoder {

    private const val TAG = "ProComicAvifWeb"

    /** Renderer deaths tolerated before the tier disables for the
     * process. One death is a bad file; three is a hostile stack. */
    private const val MAX_RENDERER_DEATHS = 3

    /** Hard wall for one piece (decode + encode + bridge). The map-page
     * builder runs on a worker pool under its own permits; a wedged
     * piece must never hold a build thread past this. */
    private const val PIECE_TIMEOUT_MS = 15_000L

    /** Warm-page wall — the first load is the slowest (renderer boot). */
    private const val WARM_TIMEOUT_MS = 20_000L

    /** v138: the canvas edge cap for natural-size decodes (maxEdge = 0).
     * The observed grid_2x3 pieces are 800x298 — the cap never touches
     * them; a hostile oversized piece decodes at 1280 edge (≈5.9 MB
     * ARGB worst case) instead of its natural size, and the compose
     * draws it into the rect at ANY size (drawBitmap scales). */
    internal const val NATURAL_DECODE_EDGE_CAP = 1280

    private val disabled = AtomicBoolean(false)
    private val deaths = AtomicInteger(0)

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var webView: WebView? = null
    private var warmed = false

    /** True when the tier may still run (never disabled, deaths under
     * the breaker). Pure so the harness can pin the gate. */
    fun isAvailable(): Boolean = !disabled.get() && deaths.get() < MAX_RENDERER_DEATHS

    /**
     * Decodes one AVIF byte array through the WebView's Chromium and
     * answers finished JPEG bytes (or null on any failure — never
     * throws into the caller's build path). [maxEdge] bounds the
     * longest canvas edge (0 = keep natural size); [quality] is the
     * JPEG encode quality. Serialized on [lock] — one renderer serves
     * every caller, and a page's pieces arrive sequentially anyway
     * (the loopback coalesces per page).
     */
    fun toJpeg(context: Context, bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray? {
        if (!isAvailable() || bytes.isEmpty()) return null
        synchronized(lock) {
            if (!isAvailable()) return null
            val b64 = try {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (t: Throwable) {
                Log.i(TAG, "base64 encode failed: ${t.javaClass.simpleName}")
                return null
            }
            if (handler == null && !bootRenderer(context)) return null
            val latch = CountDownLatch(1)
            val answer = AtomicReference<ByteArray?>(null)
            // v138: a natural-size request (maxEdge = 0) still caps the
            // canvas edge — the allocation profile the field device ever
            // died of never re-appears, whatever the piece claims.
            val script = pieceScript(b64, if (maxEdge > 0) maxEdge else NATURAL_DECODE_EDGE_CAP, quality)
            val dispatched = AtomicBoolean(false)
            try {
                handler?.post {
                    val view = webView
                    if (view == null) {
                        latch.countDown()
                        return@post
                    }
                    try {
                        view.evaluateJavascript(script) { raw ->
                            answer.set(parseAnswer(raw))
                            latch.countDown()
                        }
                        dispatched.set(true)
                    } catch (t: Throwable) {
                        Log.i(TAG, "evaluate failed: ${t.javaClass.simpleName}")
                        latch.countDown()
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "dispatch failed: ${t.javaClass.simpleName}")
                return null
            }
            if (!dispatched.get() && !latch.awaitQuiet(2_000L)) {
                // a dead looper — the post never ran and nothing will
                return null
            }
            if (!latch.awaitQuiet(PIECE_TIMEOUT_MS)) {
                Log.i(TAG, "piece decode timed out — poisoning the renderer")
                poison()
                return null
            }
            return answer.get()
        }
    }

    /** Tears the renderer down synchronously (bounded) and counts the
     * death (the breaker disables the tier past [MAX_RENDERER_DEATHS]).
     * Called only from [toJpeg] while holding [lock] — the fields have
     * no other concurrent writer, and the wedged evaluate occupies the
     * renderer's JS, never this HandlerThread (posts still run). */
    private fun poison() {
        val h = handler
        val t = thread
        if (h == null || t == null) {
            tallyDeath()
            return
        }
        val done = CountDownLatch(1)
        try {
            h.post {
                destroyWebView()
                done.countDown()
            }
        } catch (_: Throwable) {
            done.countDown()
        }
        done.awaitQuiet(3_000L)
        try {
            t.quitSafely()
        } catch (_: Throwable) {}
        thread = null
        handler = null
        webView = null
        warmed = false
        tallyDeath()
    }

    private fun tallyDeath() {
        val total = deaths.incrementAndGet()
        if (total >= MAX_RENDERER_DEATHS) {
            disabled.set(true)
            Log.w(TAG, "$total renderer deaths — the WebView transcode tier is disabled for this process")
        }
    }

    /** Destroys everything (process teardown hygiene). */
    fun shutdown() {
        synchronized(lock) {
            val h = handler
            if (h != null) {
                val done = CountDownLatch(1)
                try {
                    h.post {
                        destroyWebView()
                        done.countDown()
                    }
                } catch (_: Throwable) {
                    done.countDown()
                }
                done.awaitQuiet(3_000L)
            }
            try {
                thread?.quitSafely()
            } catch (_: Throwable) {}
            thread = null
            handler = null
            webView = null
            warmed = false
        }
    }

    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        warmed = false
        try {
            view.stopLoading()
        } catch (_: Throwable) {}
        try {
            view.destroy()
        } catch (_: Throwable) {}
    }

    /** Creates + warms the offscreen WebView on the handler thread.
     * Answers true when the page is loaded and ready for jobs. */
    private fun bootRenderer(context: Context): Boolean {
        if (thread != null && handler != null) return warmed
        val t = HandlerThread("ProComicAvifTranscoder")
        t.start()
        thread = t
        handler = Handler(t.looper)
        val ready = CountDownLatch(1)
        try {
            handler?.post {
                try {
                    destroyWebView()
                    val view = createWebView(context.applicationContext)
                    webView = view
                    // onPageFinished clears the latch (and sets warmed).
                    view.loadDataWithBaseURL(
                        "https://procomic.pro/",
                        WARM_PAGE_HTML,
                        "text/html",
                        "utf-8",
                        null,
                    )
                } catch (t: Throwable) {
                    Log.i(TAG, "webview warm failed: ${t.javaClass.simpleName}")
                    ready.countDown()
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "webview dispatch failed: ${t.javaClass.simpleName}")
            return false
        }
        val booted = ready.awaitQuiet(WARM_TIMEOUT_MS)
        if (!booted || !warmed) {
            // a renderer that never finished its warm page is torn down
            // so the next call can boot a clean one
            poison()
            return false
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(appContext: Context): WebView {
        val view = WebView(appContext)
        // The v104–v110 lesson, enforced BEFORE any load: this renderer
        // never touches the network — the only input is a data: url.
        view.settings.run {
            javaScriptEnabled = true
            blockNetworkImage = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // the warm page is up — jobs may run
                warmed = true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // the app survives — the exact TurnstileSolver/ReaderSession
                // contract. This callback rides the same HandlerThread as
                // every other accessor (no lock — it cannot deadlock); the
                // pending evaluate's latch times out and [poison] tallies.
                Log.w(TAG, "transcoder renderer died — the host app survives")
                try {
                    view.destroy()
                } catch (_: Throwable) {}
                if (webView === view) {
                    webView = null
                    warmed = false
                }
                return true
            }
        }
        view.webChromeClient = android.webkit.WebChromeClient()
        return view
    }

    /** The per-piece script: draw the data-url AVIF, encode JPEG, answer
     * a data url (or the 'data:,' tombstone on any failure).
     * evaluateJavascript resolves the promise and hands the string to
     * the callback. */
    private fun pieceScript(b64: String, maxEdge: Int, quality: Int): String {
        val edge = if (maxEdge > 0) maxEdge else 0
        val q = quality.coerceIn(30, 95)
        val scaleExpr = if (edge > 0) {
            "Math.min(1, $edge / Math.max(nw, nh))"
        } else {
            "1"
        }
        // The base64 rides as a JS string literal; the alphabet is
        // quote-free so no escaping is needed.
        return "(function () {" +
            " return new Promise(function (resolve) {" +
            " var im = new Image();" +
            " var done = false;" +
            " var finish = function (v) { if (!done) { done = true; resolve(v); } };" +
            " var timer = setTimeout(function () { finish('data:,'); }, 10000);" +
            " im.onload = function () {" +
            " if (done) return;" +
            " try {" +
            " var nw = im.naturalWidth || 0, nh = im.naturalHeight || 0;" +
            " if (nw < 1 || nh < 1) { clearTimeout(timer); finish('data:,'); return; }" +
            " var scale = $scaleExpr;" +
            " var w = Math.max(1, Math.round(nw * scale));" +
            " var h = Math.max(1, Math.round(nh * scale));" +
            " var c = document.createElement('canvas');" +
            " c.width = w; c.height = h;" +
            " var ctx = c.getContext('2d');" +
            " ctx.drawImage(im, 0, 0, w, h);" +
            " var d = c.toDataURL('image/jpeg', $q);" +
            " clearTimeout(timer);" +
            " finish(d && d.length > 32 ? d : 'data:,');" +
            " } catch (e) { clearTimeout(timer); finish('data:,'); }" +
            " };" +
            " im.onerror = function () { clearTimeout(timer); finish('data:,'); };" +
            " im.src = 'data:image/avif;base64,$b64';" +
            " });" +
            " })()"
    }

    /** Parses the evaluateJavascript answer: a JSON string literal
     * holding a data url → JPEG bytes (null on any tombstone). */
    private fun parseAnswer(raw: String?): ByteArray? {
        val text = raw
            ?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.takeIf { it.startsWith("data:image") && it.length > 32 }
            ?: return null
        val comma = text.indexOf(',')
        if (comma < 0) return null
        return try {
            Base64.decode(text.substring(comma + 1), Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    private fun CountDownLatch.awaitQuiet(ms: Long): Boolean = try {
        await(ms, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** The warm page: no network, no images, no remote script — the
     * piece jobs arrive as data urls through evaluateJavascript. */
    private const val WARM_PAGE_HTML =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>" +
            "<body></body></html>"
}
