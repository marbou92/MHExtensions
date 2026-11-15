package eu.kanade.tachiyomi.extension.ar.procomic

import android.webkit.WebResourceResponse
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object ProComicReaderSession {

    private const val POLL_MS = 600L

    /** Quiet ticks (no growth in mounted rows) before the session resolves. */
    private const val QUIET_TICKS = 5

    private const val MAX_TICKS = 480

    /** Ticks of a Cloudflare challenge before the session calls it unwinnable. */
    private const val CHALLENGE_TICK_CAP = 60

    /** Ticks the session tolerates a parked /login redirect (the site's
     * unlock flow bounces through it; earned cookies usually pass it
     * silently) before the session reports the wall and leaves. */
    private const val LOGIN_WALL_TICKS = 18

    private const val WALK_GRACE_TICKS = 10
    private const val WALK_ACTIVE_MS = 10_000L

    private const val DIAG_TAG = "reader-session"

    private const val MAX_CAPTURED_ROWS = 224

    private const val PIX_PULL_ATTEMPTS = 120

    private const val MAX_PIX_PULLS_PER_TICK = 4

    private const val NO_PROGRESS_TICK_CAP = 40

    private const val CAPTURE_MAX_EDGE = 1600

    /** One-flight guard: a chapter open never stacks two sessions. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isBusy(): Boolean = inFlight.get()

    /**
     * Walks [readerApexes] in the offscreen WebView until one lands on a
     * real chapter route, then harvests the reader's rows. Never throws.
     * Blocking; must not run on the main thread.
     */
    fun harvest(
        readerApexes: List<String>,
        chapterPath: String,
        userAgent: String,
        timeoutSeconds: Long = 240L,
    ): ReaderSessionHarvest {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ProComicReaderSession.harvest must not be called on the main thread"
        }
        if (!inFlight.compareAndSet(false, true)) {
            return ReaderSessionHarvest(emptyList(), listOf("$DIAG_TAG: skipped (a session is already running)"))
        }
        return try {
            runBlocking { harvestSuspend(readerApexes, chapterPath, userAgent, timeoutSeconds) }
        } catch (t: Throwable) {
            ReaderSessionHarvest(emptyList(), listOf("$DIAG_TAG: failed (${t.javaClass.simpleName})"))
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun harvestSuspend(
        readerApexes: List<String>,
        chapterPath: String,
        forcedUa: String,
        timeoutSeconds: Long,
    ): ReaderSessionHarvest {
        val notes = mutableListOf<String>()
        val rows = LinkedHashMap<Int, HarvestRow>() // row index -> row (newest wins)
        resetCaptureState()
        var challengeTicks = 0
        val lastWalkAt = java.util.concurrent.atomic.AtomicLong(0L)
        var walkNoteLogged = false

        notes += "$DIAG_TAG: start (${readerApexes.size} apexes)"

        runWebView<Unit>(timeout = timeoutSeconds.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockImages = false
            // Identity coherence: clearance cookies are UA-bound, and the
            // loopback proxy fetches the harvested URLs with this same UA.
            userAgent = forcedUa

            val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
            interceptRequest { request ->
                val requestUrl = request.url?.toString()?.toHttpUrlOrNull()
                    ?: return@interceptRequest emptyResponse
                if (requestUrl.encodedPath.contains("/chapter-deferred-media")) {
                    lastWalkAt.set(System.currentTimeMillis())
                    if (!walkNoteLogged) {
                        walkNoteLogged = true
                        notes += "$DIAG_TAG: the site's own deferred walk fired — staying for its batches"
                    }
                }
                val allowed = requestUrl.isHttps && requestUrl.host.let { host ->
                    host == "procomic.net" ||
                        host == "www.procomic.net" ||
                        host == "procomic.pro" ||
                        host == "www.procomic.pro" ||
                        host == "prochan.net" ||
                        host == "www.prochan.net" ||
                        host == "procomic.im" ||
                        host == "www.procomic.im" ||
                        host.endsWith(".procomic.net") ||
                        host.endsWith(".procomic.pro") ||
                        host.endsWith(".prochan.net") ||
                        host.endsWith(".procomic.im") ||
                        host == "challenges.cloudflare.com" ||
                        host.endsWith(".cloudflare.com")
                }
                if (allowed) null else emptyResponse
            }

            onPageStarted { _ -> evaluateJs(harvestScript()) }
            onPageFinished { _ -> evaluateJs(harvestScript()) }

            var quietTicks = 0
            var lastGrowthKey = ""
            var ticks = 0
            var noProgressTicks = 0
            var lastProgressKey = ""

            var apexIndex = 0
            var loginTicks = 0
            var unlockClicked = false
            var endTicks = 0

            poll(POLL_MS.milliseconds) {
                ticks++
                // Challenge watch: a managed challenge holds the reader for
                // a while; waiting through it beats resolving empty.
                evaluateJs("document.title || ''") { raw ->
                    val title = decodeEval(raw).orEmpty()
                    if (title.contains("just a moment", true) || title.contains("attention required", true)) {
                        challengeTicks++
                    } else {
                        challengeTicks = 0
                    }
                }
                if (challengeTicks > CHALLENGE_TICK_CAP) {
                    notes += "$DIAG_TAG: the challenge never cleared ($CHALLENGE_TICK_CAP ticks)"
                    resolve(Unit)
                    return@poll
                }

                evaluateJs(unlockScript()) { raw ->
                    val action = decodeEval(raw).orEmpty().trim('"')
                    when {
                        action.startsWith("login") -> {
                            loginTicks++
                            if (loginTicks == 1) notes += "$DIAG_TAG: unlock bounced to /login — waiting on the shared cookies"
                            if (loginTicks >= LOGIN_WALL_TICKS) {
                                notes += "$DIAG_TAG: the reader is behind a login the WebView cookies cannot pass"
                                resolve(Unit)
                            }
                        }
                        action.startsWith("clicked") -> {
                            if (!unlockClicked) notes += "$DIAG_TAG: pressed the site's own unlock button"
                            unlockClicked = true
                            loginTicks = 0
                        }
                        else -> {
                            loginTicks = 0
                        }
                    }
                }

                evaluateJs(harvestScript()) { raw ->
                    val state = decodeRowState(decodeEval(raw))
                    // Apex walk: a route that redirected off /chapter/ (the
                    // .net deployment 308s to the series page) moves to the
                    // next apex instead of harvesting the wrong page. A
                    // /login bounce is NOT an apex problem. The unlock
                    // branch above owns it.
                    val href = state["href"].orEmpty()
                    val onChapter = href.contains("/chapter/") || href.contains("/login")
                    if (!onChapter && apexIndex < readerApexes.lastIndex) {
                        apexIndex++
                        ticks = 0
                        quietTicks = 0
                        val next = readerApexes[apexIndex].trimEnd('/') + chapterPath
                        notes += "$DIAG_TAG: $href is not a reader route — trying $next"
                        loadUrl(next)
                        return@evaluateJs
                    }

                    absorbRows(state, rows)

                    repeat(MAX_PIX_PULLS_PER_TICK) {
                        nextPendingPixKey(rows)?.let { key ->
                            pullCapture(key) { script, callback -> evaluateJs(script) { raw -> callback(raw) } }
                        }
                    }


                    val growthKey = "${state["rows"]}:${state["loaded"]}"
                    if (growthKey != lastGrowthKey) {
                        lastGrowthKey = growthKey
                        quietTicks = 0
                    } else {
                        quietTicks++
                    }
                    val atEnd = state["atEnd"] == "1"
                    if (atEnd && rows.isNotEmpty()) endTicks++ else endTicks = 0
                    val walkQuiet = System.currentTimeMillis() - lastWalkAt.get() >= WALK_ACTIVE_MS
                    val progressKey = "${rows.size}:${capturedRows.size}:${failedPix.size}"
                    if (progressKey != lastProgressKey) {
                        lastProgressKey = progressKey
                        noProgressTicks = 0
                    } else {
                        noProgressTicks++
                    }
                    val capturesPending = nextPendingPixKey(rows) != null
                    val stalled = noProgressTicks >= NO_PROGRESS_TICK_CAP
                    if (ticks >= MAX_TICKS ||
                        (
                            quietTicks >= QUIET_TICKS && rows.isNotEmpty() && atEnd &&
                                endTicks >= WALK_GRACE_TICKS && walkQuiet &&
                                (!capturesPending || stalled)
                            )
                    ) {
                        resolve(Unit)
                    }
                }
            }

            loadUrl(readerApexes.first().trimEnd('/') + chapterPath)
        }

        val ordered = rows.entries.sortedBy { it.key }.map { it.value }
        notes += "$DIAG_TAG: ${ordered.size} reader rows harvested"
        if (capturedRows.isNotEmpty()) {
            notes += "$DIAG_TAG: ${capturedRows.size} protected rows captured in-page " +
                "(the page's own canvas — the renderer decoded the AVIF, not the app)"
        }
        val failedCount = failedPix.size
        if (failedCount > 0) {
            notes += "$DIAG_TAG: $failedCount protected rows could not be captured in-page"
        }
        return ReaderSessionHarvest(ordered, notes, capturedRows.toMap())
    }



    /** One captured layer: a JPEG data url plus its rect relative to the row. */
    @Serializable
    internal data class CapturedTile(val d: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** A fully captured row: canvas size + the captured tile layers. */
    @Serializable
    internal data class CapturedRow(val w: Int, val h: Int, val tiles: List<CapturedTile>)

    /** Rows captured as pixels during THIS session (row index -> capture). */
    private val capturedRows = LinkedHashMap<Int, CapturedRow>()

    /** Rows whose in-page capture answered the 'failed' tombstone. */
    private val failedPix = HashSet<String>()


    /** Pull attempts per pending key (a capture that never lands stops trying). */
    private val pixAttempts = HashMap<String, Int>()

    private fun resetCaptureState() {
        capturedRows.clear()
        failedPix.clear()
        pixAttempts.clear()
    }

    /** The next row key whose capture is ready to pull, or null. */
    private fun nextPendingPixKey(rows: LinkedHashMap<Int, HarvestRow>): String? {
        if (capturedRows.size >= MAX_CAPTURED_ROWS) return null
        for (row in rows.values) {
            val key = row.pix ?: continue
            if (capturedRows.containsKey(row.index)) continue
            if (key in failedPix) continue
            val attempts = pixAttempts[key] ?: 0
            if (attempts >= PIX_PULL_ATTEMPTS) {
                failedPix.add(key)
                continue
            }
            return key
        }
        return null
    }

    /** Pulls one capture out of the page store (one evaluate per tick). */
    private fun pullCapture(key: String, evaluateJs: (String, (String?) -> Unit) -> Unit) {
        pixAttempts[key] = (pixAttempts[key] ?: 0) + 1
        val safeKey = key.filter { it.isLetterOrDigit() || it == '_' }.take(48)
        if (safeKey.isEmpty()) {
            failedPix.add(key)
            return
        }
        val pullScript = """
            (function () {
                var store = window.__mihonPix || {};
                var v = store['$safeKey'];
                return JSON.stringify(v === undefined ? null : v);
            })();
        """.trimIndent()
        evaluateJs(pullScript) { raw ->
            val decoded = decodeEval(raw)?.trim()?.trim('"')
            when {
                decoded == null || decoded == "null" || decoded.isEmpty() -> {
                    // still capturing in-page. A later tick tries again
                }
                decoded == "failed" -> failedPix.add(key)
                decoded.startsWith("{") -> runCatching {
                    val captured = Json.decodeFromString(CapturedRow.serializer(), decoded)
                    val rowIndex = key.removePrefix("r").toIntOrNull()
                    if (captured.tiles.isNotEmpty() && rowIndex != null &&
                        !capturedRows.containsKey(rowIndex)
                    ) {
                        capturedRows[rowIndex] = captured
                        // free the page-side slot immediately (bounded heap)
                        evaluateJs(
                            "(function(){ if (window.__mihonPix) { window.__mihonPix['$safeKey'] = null; } return 'ok'; })();",
                        ) { _ -> }
                    }
                }.onFailure {
                    failedPix.add(key)
                }
                else -> failedPix.add(key)
            }
        }
    }

    /** Merges one harvest tick's rows into the accumulated map. */
    private fun absorbRows(state: Map<String, String>, rows: LinkedHashMap<Int, HarvestRow>) {
        val rawRows = state["rowsJson"] ?: return
        runCatching {
            val arr = json.parseToJsonElement(rawRows)
            (arr as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
                runCatching {
                    val row = json.decodeFromJsonElement(HarvestRow.serializer(), element)
                    if (row.index >= 0 && (row.u != null || row.pieces.isNotEmpty())) {
                        rows[row.index] = row
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Harvest rows
    // ------------------------------------------------------------------

    @Serializable
    internal data class HarvestRow(
        val index: Int,
        val u: String? = null,
        val w: Int = 0,
        val h: Int = 0,
        val pieces: List<Piece> = emptyList(),
        val pix: String? = null,
        val vx: Int = 0,
        val vy: Int = 0,
        val total: Int = 0,
    ) {
        val isPieces: Boolean get() = pieces.isNotEmpty()
    }

    @Serializable
    internal data class Piece(val u: String, val x: Int, val y: Int, val w: Int, val h: Int)

    internal class ReaderSessionHarvest(
        val rows: List<HarvestRow>,
        val notes: List<String>,
        val captured: Map<Int, CapturedRow> = emptyMap(),
    )

    // ------------------------------------------------------------------
    // In-page scripts
    // ------------------------------------------------------------------

    private fun unlockScript(): String = """
        (function () {
            if (location.pathname.indexOf('/login') !== -1) return 'login';
            var texts = ['رفض', 'Decline', 'Refuse'];
            var buttons = document.querySelectorAll('button, [role=button], a');
            for (var i = 0; i < buttons.length; i++) {
                var t = (buttons[i].textContent || '').trim();
                if (texts.indexOf(t) !== -1) { try { buttons[i].click(); return 'consent'; } catch (e) {} }
            }
            for (var j = 0; j < buttons.length; j++) {
                var b = buttons[j];
                var label = (b.textContent || '').trim();
                if (label.length === 0 || label.length > 60) continue;
                if (label.indexOf('الفصل') !== -1 && (b.tagName === 'BUTTON' || b.getAttribute('role') === 'button')) {
                    try { b.click(); return 'clicked:' + label.slice(0, 30); } catch (e2) {}
                }
            }
            return 'none';
        })();
    """.trimIndent()

    private fun harvestScript(): String = """
        (function () {
            function abs(u) {
                if (!u || u.indexOf('http') === 0 || u.indexOf('data:') === 0) return u;
                try { return new URL(u, location.origin).href; } catch (e) { return u; }
            }
            var out = [];
            var nodeList = document.querySelectorAll('[data-reader-image-row]');
            var store = (window.__mihonPix = window.__mihonPix || {});
            var busy = (window.__mihonBusy = window.__mihonBusy || {});
            if (!window.__mihonCapReady) {
                window.__mihonCapReady = true;
                var CAP_MAX_EDGE = ${CAPTURE_MAX_EDGE};
                function findPass() {
                    try {
                        for (var i = 0; i < sessionStorage.length; i++) {
                            var v = sessionStorage.getItem(sessionStorage.key(i));
                            if (typeof v === 'string' && v.indexOf('p1.') === 0) return v;
                        }
                    } catch (e) {}
                    try {
                        for (var j = 0; j < localStorage.length; j++) {
                            var v2 = localStorage.getItem(localStorage.key(j));
                            if (typeof v2 === 'string' && v2.indexOf('p1.') === 0) return v2;
                        }
                    } catch (e2) {}
                    return null;
                }
                function cleanedUrl(u) {
                    var c = String(u || '').split('#')[0];
                    var q = c.indexOf('?');
                    return q >= 0 ? c.slice(0, q) : c;
                }
                async function sameOriginSrc(u) {
                    if (!u || u.indexOf('data:') === 0) return u;
                    try {
                        var p = new URL(u, location.origin);
                        if (p.origin === location.origin) return p.href;
                    } catch (e) {}
                    var cleaned = cleanedUrl(u);
                    var headers = { 'content-type': 'application/json', 'accept': 'application/json' };
                    var pass = findPass();
                    if (pass) headers['x-chapter-pass'] = pass;
                    try {
                        var r = await fetch('/api/cdn-image/sign', {
                            method: 'POST', credentials: 'include', cache: 'no-store',
                            headers: headers, body: JSON.stringify({ url: cleaned })
                        });
                        if (r.ok) {
                            var answer = await r.json();
                            if (answer && answer.token && typeof answer.expires === 'number') {
                                var qs = new URLSearchParams();
                                qs.set('url', cleaned);
                                qs.set('token', answer.token);
                                qs.set('expires', String(answer.expires));
                                if (typeof answer.es === 'string' && answer.es) qs.set('es', answer.es);
                                return '/api/cdn-image?' + qs.toString();
                            }
                        }
                    } catch (e3) {}
                    return '/api/cdn-image?url=' + encodeURIComponent(u);
                }
                function loadImg(src) {
                    return new Promise(function (res) {
                        var im = new Image();
                        var done = false;
                        var t = setTimeout(function () { if (!done) { done = true; res(null); } }, 8000);
                        im.onload = function () { if (!done) { done = true; clearTimeout(t); res(im); } };
                        im.onerror = function () { if (!done) { done = true; clearTimeout(t); res(null); } };
                        im.src = src;
                    });
                }
                async function captureRow(key, row, loaded, rects) {
                    var rr = row.getBoundingClientRect();
                    if (!(rr.width >= 8) || !(rr.height >= 8)) { store[key] = 'failed'; return; }
                    var tiles = [];
                    for (var i = 0; i < loaded.length; i++) {
                        try {
                            var src = await sameOriginSrc(loaded[i].currentSrc || loaded[i].src);
                            if (!src) { store[key] = 'failed'; return; }
                            var im2 = await loadImg(src);
                            if (!im2 || !im2.naturalWidth || !im2.naturalHeight) { store[key] = 'failed'; return; }
                            var scale = Math.min(1, CAP_MAX_EDGE / Math.max(im2.naturalWidth, im2.naturalHeight));
                            var cw = Math.max(1, Math.round(im2.naturalWidth * scale));
                            var ch = Math.max(1, Math.round(im2.naturalHeight * scale));
                            var c = document.createElement('canvas');
                            c.width = cw; c.height = ch;
                            var ctx = c.getContext('2d');
                            ctx.drawImage(im2, 0, 0, cw, ch);
                            var d = c.toDataURL('image/jpeg', 0.85);
                            tiles.push({
                                d: d,
                                x: Math.round(rects[i].left - rr.left),
                                y: Math.round(rects[i].top - rr.top),
                                w: Math.round(rects[i].width),
                                h: Math.round(rects[i].height)
                            });
                        } catch (e4) { store[key] = 'failed'; return; }
                    }
                    if (tiles.length < 2) { store[key] = 'failed'; return; }
                    store[key] = { w: Math.round(rr.width), h: Math.round(rr.height), tiles: tiles };
                }
                window.__mihonCapture = function (key, row, loaded, rects) {
                    captureRow(key, row, loaded, rects).catch(function () { store[key] = 'failed'; });
                };
            }
            for (var r = 0; r < nodeList.length; r++) {
                var row = nodeList[r];
                var idx = parseInt(row.getAttribute('data-reader-image-row'), 10);
                if (isNaN(idx) || idx < 0) continue;
                var imgs = row.querySelectorAll('img');
                var loaded = [];
                for (var j = 0; j < imgs.length; j++) {
                    var im = imgs[j];
                    var s = im.currentSrc || im.src || '';
                    if (s && s.indexOf('data:') !== 0 && im.complete && im.naturalWidth > 0) loaded.push(im);
                }
                // The row's total <img> count rides every report.
                // the half-loaded guard uses it. A row with more than one
                // <img> must NEVER ship a single loaded piece as a whole
                // page: the simple branch fires only when the row HAS one
                // img, and a piece row reports as a piece assembly.
                if (imgs.length === 1 && loaded.length === 1) {
                    out.push({ index: idx, u: abs(loaded[0].currentSrc || loaded[0].src), total: 1 });
                } else if (loaded.length > 1) {
                    try {
                        var rr = row.getBoundingClientRect();
                        if (rr.width < 8 || rr.height < 8) continue;
                        var pieces = [];
                        var rects = [];
                        for (var k = 0; k < loaded.length; k++) {
                            var p = loaded[k];
                            var pr = p.getBoundingClientRect();
                            rects.push(pr);
                            pieces.push({
                                u: abs(p.currentSrc || p.src),
                                x: Math.round(pr.left - rr.left),
                                y: Math.round(pr.top - rr.top),
                                w: Math.round(pr.width),
                                h: Math.round(pr.height)
                            });
                        }
                        // Fire the pixel capture once, when every img of
                        // the row has loaded (a partial capture would park a
                        // page with a hole in it forever).
                        var key = 'r' + idx;
                        if (loaded.length === imgs.length && store[key] === undefined && !busy[key]) {
                            busy[key] = 1;
                            window.__mihonCapture(key, row, loaded, rects);
                        }
                        out.push({
                            index: idx,
                            w: Math.round(rr.width),
                            h: Math.round(rr.height),
                            vx: Math.round(rr.left),
                            vy: Math.round(rr.top),
                            total: imgs.length,
                            pieces: pieces,
                            pix: key
                        });
                    } catch (e) {}
                }
            }
            if (out.length === 0) {
                var imgs = document.images;
                for (var m = 0; m < imgs.length; m++) {
                    var s2 = imgs[m].currentSrc || imgs[m].src || '';
                    if (s2.indexOf('http') === 0) out.push({ index: m, u: s2 });
                }
            }
            var loadedRows = 0;
            for (var q = 0; q < nodeList.length; q++) {
                if (nodeList[q].querySelector('img')) loadedRows++;
            }
            var tvyOut = null;
            try {
                // The script honors window.__mihonTarget +
                // window.__mihonSeg. With a target set (the band draw
                // pass. Latched until the walk reached the end), the
                // requested band of that row is PLACED at the viewport
                // top. Window first, then inner scrollable ancestors. 
                // and the placed top is reported (tvy). Without a target
                // the walk continues: the last row into view, one beat
                // deeper every tick, so the site's reader mounts its rows
                // all the way down.
                var target = -1;
                var segTop = 0;
                try { target = parseInt(window.__mihonTarget, 10); } catch (e0) {}
                if (isNaN(target)) target = -1;
                try { segTop = parseInt(window.__mihonSeg, 10); } catch (e1) {}
                if (isNaN(segTop) || segTop < 0) segTop = 0;
                var step = Math.max(600, Math.round(window.innerHeight * 1.2));
                if (target >= 0) {
                    var trow = null;
                    for (var t = 0; t < nodeList.length; t++) {
                        var tIdx = parseInt(nodeList[t].getAttribute('data-reader-image-row'), 10);
                        if (tIdx === target) { trow = nodeList[t]; break; }
                    }
                    if (trow) {
                        var tr = trow.getBoundingClientRect();
                        var cur = tr.top + segTop;
                        if (Math.abs(cur) > 1) {
                            try { window.scrollBy(0, cur); } catch (e5) {}
                            tr = trow.getBoundingClientRect();
                            cur = tr.top + segTop;
                        }
                        if (Math.abs(cur) > 1) {
                            var groupsP = document.querySelectorAll('div,section,main');
                            for (var g = 0; g < groupsP.length; g++) {
                                var el = groupsP[g];
                                if (el.scrollHeight > el.clientHeight + 200 && el.clientHeight > 300 && el.contains(trow)) {
                                    el.scrollTop += cur;
                                    tr = trow.getBoundingClientRect();
                                    cur = tr.top + segTop;
                                    if (Math.abs(cur) <= 1) break;
                                }
                            }
                        }
                        var trr = trow.getBoundingClientRect();
                        tvyOut = Math.round(trr.top + segTop);
                    }
                } else {
                    var lastRow = nodeList.length > 0 ? nodeList[nodeList.length - 1] : null;
                    if (lastRow) {
                        try { lastRow.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e3) { lastRow.scrollIntoView(true); }
                    }
                    window.scrollBy(0, step);
                    var groups = document.querySelectorAll('div,section,main');
                    for (var g2 = 0; g2 < groups.length; g2++) {
                        var el2 = groups[g2];
                        if (el2.scrollHeight > el2.clientHeight + 200 && el2.clientHeight > 300) {
                            el2.scrollTop = el2.scrollTop + step;
                        }
                    }
                }
            } catch (e) {}
            var atEnd = 0;
            try {
                var sc2 = document.scrollingElement || document.documentElement;
                var bottom = (sc2.scrollHeight || document.body.scrollHeight) - window.innerHeight - 40;
                if (window.scrollY >= Math.max(0, bottom)) atEnd = 1;
            } catch (e4) {}
            return JSON.stringify({
                href: location.href,
                rows: nodeList.length,
                loaded: loadedRows,
                atEnd: atEnd,
                vh: window.innerHeight,
                dpr: window.devicePixelRatio || 1,
                tvy: tvyOut,
                rowsJson: JSON.stringify(out)
            });
        })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // Eval-result decoding (evaluateJs delivers JSON-encoded script values)
    // ------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeEval(raw: String?): String? = runCatching {
        json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.content
    }.getOrNull() ?: raw

    /** Decodes the harvest state object; returns an empty map when absent. */
    private fun decodeRowState(decoded: String?): Map<String, String> {
        val body = decoded.orEmpty().trim()
        if (body.isEmpty() || !body.startsWith("{")) return emptyMap()
        return runCatching {
            val obj = Json.parseToJsonElement(body)
            buildMap {
                (obj as? kotlinx.serialization.json.JsonObject)?.forEach { (key, value) ->
                    put(key, value.toString().trim('"'))
                }
            }
        }.getOrDefault(emptyMap())
    }
}
