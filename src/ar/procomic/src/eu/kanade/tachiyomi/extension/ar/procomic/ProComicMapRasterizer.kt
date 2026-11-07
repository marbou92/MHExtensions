package eu.kanade.tachiyomi.extension.ar.procomic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * v137 — THE OFFSCREEN RASTER TIER: how pages 4+ finally draw on a device
 * that cannot decode AVIF (Android 10/11), without the site's DOM, without
 * a login, and without the tiers that died in the field.
 *
 * The wall this tier ends (every fact live re-verified from the sandbox,
 * 2026-11-07, chapter 57 of series 300):
 *
 *  - the WebView-free chain is alive end to end: the reader-signal
 *    exchange answered 5 reconstruction maps + a fresh pass (HTTP 200),
 *    the plan POST answered a 1600x893 `grid_2x3` map with 6 freshly
 *    signed piece urls + absolute rects (HTTP 200), and the img{1-4}
 *    piece edge answered 200 `image/avif` to a browser UA carrying the
 *    chapter referer (403 without the referer, 403 with
 *    `Sec-Fetch-Mode: cors` — only no-cors image loads pass);
 *  - the pieces really are AVIF under every Accept header — no
 *    content-negotiation escape exists, so a decode-less device must
 *    borrow a decoder;
 *  - the field device is Android 10 (the round-8 recon's own words):
 *    BitmapFactory decodes every piece null, so v1.6.48/v1.6.49's
 *    compose path could never answer those rows and the loopback's
 *    fast legible 410 card became the reader-visible verdict;
 *  - the round-8 recon also stands: the site's ANONYMOUS reader never
 *    mounts the protected rows at all (zero reader rows, paywall text)
 *    — the v1.6.48/v1.6.49 capture walked a page that never contained
 *    what it was sent to capture, which is why two capture rewrites
 *    delivered nothing;
 *  - v134's WebView transcode tier DID reach the right decoder (the
 *    WebView's own Chromium, software AV1, every Android version) but
 *    through a profile the field device died of: per-piece full-page
 *    canvases inside the renderer plus `canvas.toDataURL` JPEG strings
 *    across the JS bridge, several pages at a time.
 *
 * This tier keeps the one part of v134 that works — Chromium decodes the
 * AVIF — and changes EVERYTHING about how the pixels are taken:
 *
 *  1. THE PLAN BECOMES A PAGE: one synthetic HTML document lays each
 *     piece out as an absolutely-positioned `<img>` at its plan rect
 *     (percentages of the plan's dims — density-proof, no zoom math).
 *     The site's reader DOM, its scroll walk, its lazy loader, its
 *     placement races: none of it is involved. The raster is as
 *     deterministic as the plan POST itself.
 *  2. THE BYTES NEVER CROSS THE JS BRIDGE: every piece request is
 *     answered by shouldInterceptRequest, which fetches the AVIF bytes
 *     through the server's own OkHttp ladder (browser UA + the chapter
 *     referer — the exact request verified 200) and streams them into
 *     the renderer. Chromium decodes; no canvas in JS ever exists; no
 *     toDataURL string is ever built. The renderer never touches the
 *     network (every request it can possibly make is answered by the
 *     intercept itself — nothing falls through to the stack).
 *  3. THE DRAW IS A VIEW DRAW: the WebView is measured at a bounded
 *     device-pixel size (width capped, aspect preserved) and
 *     `webView.draw(canvas)` composes the laid-out grid into one
 *     software bitmap — the same compositor that draws any web page on
 *     this device every day. The bitmap is JPEG-encoded and recycled
 *     immediately. Peak memory is the view bitmap plus the output, both
 *     bounded by the 1400x4000 device-pixel view cap (~22 MB worst case,
 *     ~4 MB at the observed 1600x893 page shape) — never a fleet of
 *     full-page canvases.
 *  4. EVERYTHING IS GUARDED LIKE THE SURVIVING TIERS: one warm WebView
 *     on its own HandlerThread serving one page at a time under a lock;
 *     a readiness poll with a hard wall (a piece that never finishes
 *     leaves its rect blank — the page stays readable, exactly the
 *     compose contract); onRenderProcessGone returns true so the host
 *     app survives and the death counts against a 3-strike breaker; a
 *     tripped breaker disables the tier for the process (the loopback
 *     then answers its legible 410 again and the reader-session
 *     fallback re-arms — a hostile WebView stack can never crash-loop
 *     the host app).
 *
 * Failure profile: every failure answers null — the caller names the
 * stage in its diag trail and the reader keeps its legible card. Never
 * throws into the build path.
 */
internal object ProComicMapRasterizer {

    private const val TAG = "ProComicMapRaster"

    /** Renderer deaths tolerated before the tier disables for the
     * process. One is a bad file; three is a hostile stack. */
    private const val MAX_RENDERER_DEATHS = 3

    /** Hard wall for one raster job (load + readiness + draw). The job
     * holds this tier's lock, so a wedged renderer must surface within
     * this budget; the WebView is poisoned and rebuilt after a miss. */
    private const val JOB_WALL_MS = 25_000L

    /** Readiness poll cadence. */
    private const val POLL_MS = 200L

    /** Boot/warm-page wall — the first load is the slowest. */
    private const val BOOT_WALL_MS = 20_000L

    /** Device-pixel bounds of the drawing view. The observed map shape
     * (1600x893) renders under the width cap at full width; a tall page
     * keeps its aspect under the height cap. The output bitmap equals
     * the view. */
    private const val MAX_VIEW_W = 1400
    private const val MAX_VIEW_H = 4000

    /** JPEG quality of the composed page (the compose path's own value). */
    private const val JPEG_QUALITY = 85

    private val disabled = AtomicBoolean(false)
    private val deaths = AtomicInteger(0)

    private val lock = ReentrantLock(true)
    private var handler: Handler? = null
    private var webView: WebView? = null

    /** True when the tier may still run (never disabled, deaths under
     * the breaker). Pure so the harness can pin the gate. */
    fun isAvailable(): Boolean = !disabled.get() && deaths.get() < MAX_RENDERER_DEATHS

    /**
     * Rasterizes one planned page: [plan] (dims, draw-ordered piece urls,
     * slot rects) becomes an `<img>` grid; [fetchPiece] supplies each
     * piece's raw bytes (the server's OkHttp ladder — browser UA + the
     * chapter referer); the drawn view is answered as finished JPEG bytes.
     * Serialized — one renderer serves one page at a time. Null on any
     * failure; never throws into the caller's build path.
     */
    fun raster(
        context: Context,
        plan: ProComicImageServer.MapPagePlanResult,
        referer: String,
        fetchPiece: (String) -> ByteArray?,
    ): ByteArray? {
        if (!isAvailable() || plan.pieces.isEmpty()) return null
        lock.withLock {
            if (!isAvailable()) return null
            if (handler == null && !bootRenderer(context)) {
                Log.i(TAG, "renderer boot failed — tier out")
                return null
            }
            val latch = CountDownLatch(1)
            val answer = AtomicReference<ByteArray?>(null)
            var dispatched = false
            try {
                handler?.post {
                    val view = webView
                    if (view == null) {
                        latch.countDown()
                        return@post
                    }
                    try {
                        answer.set(drawPlan(view, plan, referer, fetchPiece))
                    } catch (t: Throwable) {
                        Log.i(TAG, "raster job failed: ${t.javaClass.simpleName}")
                        poisonRenderer()
                    } finally {
                        latch.countDown()
                    }
                }
                dispatched = true
            } catch (t: Throwable) {
                Log.i(TAG, "dispatch failed: ${t.javaClass.simpleName}")
                return null
            }
            if (!dispatched) return null
            return try {
                if (latch.await(JOB_WALL_MS + BOOT_WALL_MS, TimeUnit.MILLISECONDS)) {
                    answer.get()
                } else {
                    Log.i(TAG, "job wall reached — poisoning the renderer")
                    poisonRenderer()
                    null
                }
            } catch (_: InterruptedException) {
                null
            }
        }
    }

    /** Boots the one warm renderer: a HandlerThread and a software-layer
     * WebView whose every request the intercept answers. Mirrors the
     * surviving tiers' boot contracts (TurnstileSolver, the session). */
    @SuppressLint("SetJavaScriptEnabled")
    private fun bootRenderer(context: Context): Boolean = try {
        val ht = HandlerThread("ProComicMapRaster").apply { start() }
        val ready = CountDownLatch(1)
        var booted = false
        Handler(ht.looper).post {
            try {
                val view = WebView(context)
                val s = view.settings
                s.javaScriptEnabled = true
                s.domStorageEnabled = false
                s.databaseEnabled = false
                s.cacheMode = WebSettings.LOAD_NO_CACHE
                s.loadsImagesAutomatically = true
                s.mediaPlaybackRequiresUserGesture = true
                // No blockNetwork* here: the intercept answers EVERY
                // request this renderer can make (the grid HTML holds
                // only piece imgs; anything else gets the empty
                // response), so no request ever falls through to the
                // network stack — and some builds drop even intercepted
                // images when the block flags are on.
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                view.setBackgroundColor(WHITE)
                view.webViewClient = PieceFetcher(referer = "", fetchPiece = { null })
                view.webChromeClient = WebChromeClient()
                webView = view
                handler = Handler(ht.looper)
                booted = true
            } catch (t: Throwable) {
                Log.i(TAG, "boot threw: ${t.javaClass.simpleName}")
                try {
                    ht.quit()
                } catch (_: Throwable) {
                    // the thread dies with the process anyway
                }
            } finally {
                ready.countDown()
            }
        }
        (ready.await(BOOT_WALL_MS, TimeUnit.MILLISECONDS) && booted).also { got ->
            if (!got) Log.i(TAG, "boot wall reached — tier out")
        }
    } catch (t: Throwable) {
        Log.i(TAG, "boot failed: ${t.javaClass.simpleName}")
        false
    }

    /** Loads the plan grid into [view], waits for the pieces, draws the
     * view into a bounded bitmap, answers JPEG bytes. Runs on the
     * renderer's handler thread. Throws only into the job's own guard —
     * the caller poisons on any escape. */
    private fun drawPlan(
        view: WebView,
        plan: ProComicImageServer.MapPagePlanResult,
        referer: String,
        fetchPiece: (String) -> ByteArray?,
    ): ByteArray? {
        val planW = plan.width.coerceIn(8, MAX_COMPOSED_EDGE_SAFE)
        val planH = plan.height.coerceIn(8, MAX_COMPOSED_EDGE_SAFE * 2)
        // View size: width-capped, aspect preserved, height-capped (the
        // width then shrinks to keep the aspect — a very tall page renders
        // slightly narrower, never cropped).
        var viewW = MAX_VIEW_W
        var viewH = viewW.toLong() * planH / planW
        if (viewH > MAX_VIEW_H) {
            viewH = MAX_VIEW_H.toLong()
            viewW = (viewH * planW / planH).toInt().coerceIn(64, MAX_VIEW_W)
        }
        val w = viewW.toInt().coerceAtLeast(64)
        val h = viewH.toInt().coerceAtLeast(64)

        view.layoutAt(w, h)
        view.webViewClient = PieceFetcher(referer, fetchPiece)
        view.loadDataWithBaseURL(BASE_URL, planHtml(plan, planW, planH), "text/html", "utf-8", null)

        // Readiness: poll the grid until every img decodes (naturalWidth
        // > 0), or the wall — then draw anyway when at least one piece
        // rendered (failed slots stay blank, the page stays readable —
        // the compose contract). Zero pieces rendered: nothing to ship.
        val readiness = waitForPieces(view, plan.pieces.size)
        if (readiness == null || readiness.ok == 0) {
            Log.i(TAG, "no piece rendered (ok=${readiness?.ok ?: -1}/${plan.pieces.size})")
            blank(view)
            return null
        }
        if (readiness.ok < plan.pieces.size) {
            Log.i(TAG, "partial grid: ${readiness.ok}/${plan.pieces.size} pieces rendered — blank slots stay blank")
        }

        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.i(TAG, "bitmap ${w}x$h alloc failed: ${t.javaClass.simpleName}")
            blank(view)
            return null
        }
        val out: ByteArray
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(WHITE)
            view.draw(canvas)
            val buf = ByteArrayOutputStream(w * h / 2)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buf)
            out = buf.toByteArray()
        } finally {
            bitmap.recycle()
        }
        blank(view)
        if (out.isEmpty()) return null
        Log.i(TAG, "raster: ${plan.pieces.size} pieces -> ${w}x$h JPEG (${out.size} bytes)")
        return out
    }

    /** Polls the grid's load state until every piece is decoded or the
     * wall. Null when the bridge itself failed. */
    private fun waitForPieces(view: WebView, count: Int): GridState? {
        val wall = System.currentTimeMillis() + JOB_WALL_MS
        while (System.currentTimeMillis() < wall) {
            val state = pollOnce(view) ?: return null
            if (state.ok >= count) return state
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return state
            }
        }
        return pollOnce(view)
    }

    /** One readiness probe. Null when the renderer/bridge is gone. */
    private fun pollOnce(view: WebView): GridState? {
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String?>(null)
        return try {
            view.evaluateJavascript(READINESS_SCRIPT) { raw ->
                answer.set(raw)
                latch.countDown()
            }
            if (!latch.await(2, TimeUnit.SECONDS)) return null
            val raw = answer.get() ?: return null
            // The bridge answers a JSON string literal:
            // "{\"n\":6,\"ok\":6,\"err\":0}"
            val unquoted = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else {
                raw
            }
            val n = Regex("\"n\":(\\d+)").find(unquoted)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val ok = Regex("\"ok\":(\\d+)").find(unquoted)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            GridState(n, ok)
        } catch (t: Throwable) {
            Log.i(TAG, "poll failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Resets the renderer to a blank page — releases the pieces' decoded
     * copies and the software layer before the next job. */
    private fun blank(view: WebView) {
        try {
            view.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
        } catch (t: Throwable) {
            Log.i(TAG, "blank failed: ${t.javaClass.simpleName}")
        }
    }

    /** Marks the current renderer dead and drops it — the next job boots
     * a fresh one, unless the death breaker has tripped. */
    private fun poisonRenderer() {
        val view = webView
        webView = null
        handler = null
        try {
            view?.destroy()
        } catch (_: Throwable) {
            // already gone
        }
    }

    /** The renderer's client: feeds piece bytes from the app's own OkHttp
     * ladder through the intercept; every other request is answered by
     * the intercept itself so nothing ever falls through to the network
     * stack; renderer death is absorbed (the host app survives — the
     * surviving tiers' contract) and counted against the breaker. */
    private class PieceFetcher(
        private val referer: String,
        private val fetchPiece: (String) -> ByteArray?,
    ) : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            val host = url.host ?: return emptyResponse()
            val isImageHost = host == "procomic.im" || host.endsWith(".procomic.im") ||
                host == "procomic.net" || host.endsWith(".procomic.net") ||
                host == "procomic.pro" || host.endsWith(".procomic.pro")
            if (!isImageHost) return emptyResponse()
            val bytes = try {
                fetchPiece(url.toString())
            } catch (_: Throwable) {
                null
            } ?: return emptyResponse()
            return try {
                WebResourceResponse("image/avif", null, ByteArrayInputStream(bytes))
            } catch (_: Throwable) {
                emptyResponse()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            deaths.incrementAndGet()
            Log.w(TAG, "renderer gone (death ${deaths.get()}/$MAX_RENDERER_DEATHS) — the host app survives")
            poisonRenderer()
            if (deaths.get() >= MAX_RENDERER_DEATHS) {
                disabled.set(true)
                Log.w(TAG, "renderer death breaker tripped — the raster tier is out for this process")
            }
            return true
        }
    }

    private class GridState(val n: Int, val ok: Int)

    /** Measure + layout an unattached view at exact device pixels (the
     * standard offscreen capture recipe — the view never joins a
     * window, so there is no display to fight). */
    private fun WebView.layoutAt(w: Int, h: Int) {
        measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, w, h)
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private val WHITE: Int = android.graphics.Color.WHITE

    private const val BASE_URL = "https://procomic.pro/"

    /** The compose path's own edge cap — the plan's declared dims are
     * coerced to the same bounds before layout. */
    private const val MAX_COMPOSED_EDGE_SAFE = 2600

    /** One probe for the whole grid: count, decoded, failed. */
    private const val READINESS_SCRIPT =
        "(function(){var im=document.images,n=im.length,ok=0,err=0;" +
            "for(var i=0;i<n;i++){if(im[i].complete){if(im[i].naturalWidth>0)ok++;else err++;}}" +
            "return JSON.stringify({n:n,ok:ok,err:err});})()"

    /**
     * The plan as a page: one container at the plan's aspect, each
     * piece absolutely positioned at its rect in PERCENTAGES of the
     * container (density-proof — Chromium resolves them against the
     * containing block whatever the device's density or scale). A
     * failed slot simply never renders: its rect stays the white
     * background, the page stays readable. Internal so the JVM harness
     * can pin the generated HTML.
     */
    internal fun planHtml(plan: ProComicImageServer.MapPagePlanResult, planW: Int, planH: Int): String {
        val sb = StringBuilder(plan.pieces.size * 160 + 256)
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        // html/body MUST carry the explicit 100% box: without it the body
        // height collapses to 0 (its children are all absolutely
        // positioned), every percentage rect resolves to zero and the
        // draw answers a blank page — caught live in the sandbox
        // rehearsal (2026-11-07: the grid decoded 6/6 yet pageH read 0).
        sb.append("<style>html,body{margin:0;padding:0;background:#fff;overflow:hidden;width:100%;height:100%}")
        sb.append("#page{position:relative;width:100%;height:100%;overflow:hidden}")
        sb.append("#page img{position:absolute;display:block}</style></head>")
        sb.append("<body><div id=\"page\">")
        for (i in plan.pieces.indices) {
            val rect = plan.rects.getOrNull(i) ?: continue
            val url = plan.pieces[i]
                .replace("&", "&amp;")
                .replace("\"", "%22")
                .replace("<", "%3C")
            val l = rect.left * 100.0 / planW
            val t = rect.top * 100.0 / planH
            val w = rect.width * 100.0 / planW
            val h = rect.height * 100.0 / planH
            sb.append("<img src=\"").append(url).append("\" alt=\"p").append(i)
                .append("\" style=\"left:").append(l).append("%;top:").append(t)
                .append("%;width:").append(w).append("%;height:").append(h).append("%\">")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }
}
