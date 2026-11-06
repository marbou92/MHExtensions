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

/**
 * The reader session (the manhuarmtl-style WebView harvest) — v127.
 *
 * WHY THIS EXISTS, AND WHY v1.6.37 COULD NOT WORK
 *
 * Live recon (v124): the READER no longer lives on the catalog apex at all.
 * procomic.net's own read button is `readIsExternal:true` pointing at
 * **procomic.pro**, and every procomic.net chapter route 308s to the series
 * page — so v1.6.37's session (which loaded `$baseUrl/ar/chapter/…`, the
 * .net apex) landed on a series page, harvested nothing, tripped its own
 * 3-strikes breaker and left every reader on the WebView-free list — whose
 * chapter flights are gone too (no `images`/`appImages`/`deferredMedia` in
 * any flight on either apex, document or RSC form, live-verified). The
 * WebView-free chain cannot be patched back to life: the pages stream into
 * a real browser only.
 *
 * So the session IS the chain now — exactly the manhuarmtl architecture:
 * open the REAL reader page (procomic.pro), let the site's own reader boot,
 * and walk it like a reading user. The reader mounts one row per page
 * (`div[data-reader-image-row="N"]`); open pages are a plain `<img>`;
 * protected pages are absolutely-positioned piece `<img>`s assembled
 * visually by the site. The harvest therefore collects, per row: either
 * the single source URL, or the piece sources WITH their rectangles — and
 * the extension stitches the pieces itself (loopback composed pages), the
 * same descramble the v101-era ladder did, only with the site's own
 * freshly-signed URLs.
 *
 * The session walks the apex list (the .net route still 308s — detected by
 * the landed path, never a blind first hit), waits through a Cloudflare
 * challenge (title probe) instead of resolving empty, blocks every
 * non-site host (ads die — they wedge real renderers, observed), and
 * leaves the image bytes flowing (piece rows need their pixels; the old
 * blockImages=true would have starved every protected page).
 *
 * Safety profile (unchanged class): runWebView attaches the WebView to a
 * real window offscreen with a renderer-death handler and bounds the whole
 * session; single-flight; every failure resolves with whatever was
 * collected — the caller keeps its WebView-free list — and never throws
 * into the host. Cookies flow through the shared android CookieManager
 * (the client and the WebView already share it — clearance the page earns
 * is the client's clearance).
 */
internal object ProComicReaderSession {

    private const val POLL_MS = 750L

    /** Quiet ticks (no growth in mounted rows) before the session resolves. */
    private const val QUIET_TICKS = 5

    /** Hard tick cap — the session never outlives ~110 s of polling. */
    private const val MAX_TICKS = 150

    /** Ticks of a Cloudflare challenge before the session calls it unwinnable. */
    private const val CHALLENGE_TICK_CAP = 60

    /** Ticks the session tolerates a parked /login redirect (the site's
     * unlock flow bounces through it; earned cookies usually pass it
     * silently) before the session reports the wall and leaves. */
    private const val LOGIN_WALL_TICKS = 18

    /** v127 — WALK-AWARE SETTLE. Two bounds keep the session from
     * resolving while the site's own deferred walk is still working:
     *
     * [WALK_GRACE_TICKS] — once the scroll reaches the absolute end with
     * rows mounted, the site's reader needs a beat to NOTICE and fire its
     * own /chapter-deferred-media walk (the batch loader the reader
     * drives on scroll). The session never settles before this grace
     * lapses — the v1.6.39 field report was exactly a settle that won the
     * race against the site's walk on a short (3 public rows) reader.
     *
     * [WALK_ACTIVE_MS] — after every observed /chapter-deferred-media
     * request, the site is actively minting/exchanging for the next
     * batch; its rows follow within seconds when the mint succeeds. The
     * session stays patient for this long after the LAST walk request
     * (a retry loop therefore keeps the session alive — bounded by the
     * hard tick cap), and settles only once the walk has been quiet for
     * this long. A chapter whose walk never fires (public-only) settles
     * on the grace alone. */
    private const val WALK_GRACE_TICKS = 10
    private const val WALK_ACTIVE_MS = 10_000L

    private const val DIAG_TAG = "reader-session"

    /** v133: bounded captured-row registry per session (a chapter's
     * protected pages; the same scale as MAX_DEFERRED_PAGES). */
    private const val MAX_CAPTURED_ROWS = 128

    /** v133: ticks a pending capture keeps being pulled before the row
     * gives up (30 s at POLL_MS — the in-page capture is fast, but a
     * signed-proxy retry ladder can lag a beat). */
    private const val PIX_PULL_ATTEMPTS = 40

    /** v133: the capture canvas's longest edge — pieces are bounded tiles;
     * this keeps every data url well under the bridge's comfortable size. */
    private const val CAPTURE_MAX_EDGE = 1600

    /** One-flight guard: a chapter open never stacks two sessions. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isBusy(): Boolean = inFlight.get()

    /**
     * Walks [readerApexes] in the offscreen WebView until one lands on a
     * real chapter route, then harvests the reader's rows. Never throws.
     * Blocking; must not run on the main thread.
     */
    fun harvest(readerApexes: List<String>, chapterPath: String, userAgent: String): ReaderSessionHarvest {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ProComicReaderSession.harvest must not be called on the main thread"
        }
        if (!inFlight.compareAndSet(false, true)) {
            return ReaderSessionHarvest(emptyList(), listOf("$DIAG_TAG: skipped (a session is already running)"))
        }
        return try {
            runBlocking { harvestSuspend(readerApexes, chapterPath, userAgent) }
        } catch (t: Throwable) {
            ReaderSessionHarvest(emptyList(), listOf("$DIAG_TAG: failed (${t.javaClass.simpleName})"))
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun harvestSuspend(readerApexes: List<String>, chapterPath: String, forcedUa: String): ReaderSessionHarvest {
        val notes = mutableListOf<String>()
        val rows = LinkedHashMap<Int, HarvestRow>() // row index -> row (newest wins)
        resetCaptureState()
        var challengeTicks = 0
        // v127: the site's own deferred walk, observed on the wire. The
        // interceptRequest below stamps every /chapter-deferred-media
        // request; the settle logic reads the stamp so it never resolves
        // while the site is mid-walk.
        val lastWalkAt = java.util.concurrent.atomic.AtomicLong(0L)
        var walkNoteLogged = false

        notes += "$DIAG_TAG: start (${readerApexes.size} apexes)"

        runWebView<Unit>(timeout = 100.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // v124: the piece pixels MUST flow — the protected rows are
            // stitched from them. Only off-site traffic (ads, trackers)
            // is denied, by the allow-list below.
            blockImages = false
            // Identity coherence: clearance cookies are UA-bound, and the
            // loopback proxy fetches the harvested URLs with this same UA.
            userAgent = forcedUa

            val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
            interceptRequest { request ->
                val requestUrl = request.url?.toString()?.toHttpUrlOrNull()
                    ?: return@interceptRequest emptyResponse
                // v127: the site's own batch walk rides this path — every
                // observed call stamps the walk clock the settle logic waits on.
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

                // v126: the site monetized the reader — a chapter route now
                // mounts an unlock gate (an "open the chapter" button) and
                // may bounce to /login. Dismiss the consent banner, press
                // the site's OWN unlock button once, and wait out a login
                // bounce (the session shares the host cookie store — a
                // device signed into the site passes it silently). A parked
                // login wall after the grace window ends the session
                // legibly; the breaker keeps the next opens fast.
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
                    // /login bounce is NOT an apex problem — the unlock
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

                    // v133: pull ONE finished pixel capture per tick (bounded
                    // bridge payloads — a captured row is ~1 MB of JPEG data
                    // urls). The in-page capture lands asynchronously; absent
                    // keys retry on later ticks until the attempts cap.
                    nextPendingPixKey(rows)?.let { key ->
                        pullCapture(key) { script, callback -> evaluateJs(script) { raw -> callback(raw) } }
                    }

                    val growthKey = "${state["rows"]}:${state["loaded"]}"
                    if (growthKey != lastGrowthKey) {
                        lastGrowthKey = growthKey
                        quietTicks = 0
                    } else {
                        quietTicks++
                    }
                    // v127 — WALK-AWARE SETTLE. Quiet is not enough alone:
                    // the walk must have REACHED THE BOTTOM (the reading
                    // motion is what makes the site mount every row), the
                    // site's own deferred walk must have had its grace to
                    // fire, and — once it fires — it must have gone quiet
                    // again (each walk request buys the session fresh
                    // patience: its batches mount as rows seconds later).
                    val atEnd = state["atEnd"] == "1"
                    if (atEnd && rows.isNotEmpty()) endTicks++ else endTicks = 0
                    val walkQuiet = System.currentTimeMillis() - lastWalkAt.get() >= WALK_ACTIVE_MS
                    if (ticks >= MAX_TICKS ||
                        (
                            quietTicks >= QUIET_TICKS && rows.isNotEmpty() && atEnd &&
                                endTicks >= WALK_GRACE_TICKS && walkQuiet
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
            notes += "$DIAG_TAG: ${capturedRows.size} protected rows captured as rendered pixels " +
                "(the WebView decoded the site's AVIF pieces — no on-device AVIF decode needed)"
        }
        val failedCount = failedPix.size
        if (failedCount > 0) {
            notes += "$DIAG_TAG: $failedCount protected rows could not be captured in-page (they fall back to the loopback compose on AVIF-capable devices, or stay unlisted)"
        }
        return ReaderSessionHarvest(ordered, notes, capturedRows.toMap())
    }

    // ------------------------------------------------------------------
    // v133 — the rendered harvest (captured piece pixels)
    // ------------------------------------------------------------------

    //
    // v133 — THE RENDERED HARVEST. FIELD VERDICT on v132: "it came back to
    // only 3 pages". The decode wall dropped every protected page on the
    // Android 10 device, which silently un-fixed the page count. The wall
    // itself was live-re-verified this round: the image edge serves AVIF
    // unconditionally (no Accept negotiation — every Accept dialect, including
    // jpeg-first, answers the same ftypavif bytes), no transcoder can fetch
    // the referer-gated img{1-4} pieces (weserv cannot send a referer), the
    // site's own Next.js /_next/image optimizer refuses *.procomic.im hosts
    // (it transcodes app.procomic.net/.pro to WebP — verified — but the
    // whitelist never covers the piece edge), and the edge sends no CORS
    // headers, so an in-page canvas of cross-origin pieces taints.
    //
    // But the session's WebView has been RENDERING these pages all along —
    // Chromium's own software AV1/AVIF decoder works on every Android
    // version. So the pieces are no longer shipped as urls for the loopback
    // to decode; the harvest CAPTURES THEM AS PIXELS, in the page:
    //
    // 1. each piece is re-fetched SAME-ORIGIN through the site's own signed
    // proxy (POST /api/cdn-image/sign from the cleared reader session,
    // then /api/cdn-image?url=… — the exact healing path the site's own
    // reader runs for lapsed pieces), so the canvas never taints;
    // 2. each same-origin piece is drawn onto a bounded canvas and encoded
    // with toDataURL('image/jpeg') — the WebView's Chromium decodes the
    // AVIF, not the app;
    // 3. the data urls park in window.__mihonPix; the session pulls ONE row
    // per tick (bounded bridge payloads), clears the slot, and hands the
    // tiles to the loopback's new /.captured route, which stitches them
    // with plain BitmapFactory draws — JPEG in, JPEG out, no AVIF
    // anywhere on the Java side.
    //
    // A device whose WebView is too old to decode AVIF simply never reports
    // the piece imgs as loaded — the capture never fires for it and the
    // row falls back to the v132 behavior (unlisted, named in the diag),
    // so the failure mode stays legible and crash-free.
    //

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
                    // still capturing in-page — a later tick tries again
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
            val arr = Json.parseToJsonElement(rawRows)
            (arr as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
                runCatching {
                    val row = Json.decodeFromJsonElement(HarvestRow.serializer(), element)
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

    /**
     * One reader row as the site rendered it: a single mounted source
     * ([u]), or the site's own piece layout ([pieces] with their
     * rectangles relative to the row box [w]×[h]).
     *
     * v133: [pix] names the row's in-page pixel-capture slot
     * (window.__mihonPix[key]) when the row is a piece assembly — the
     * session pulls the capture (JPEG data urls) on a later tick.
     */
    @Serializable
    internal data class HarvestRow(
        val index: Int,
        val u: String? = null,
        val w: Int = 0,
        val h: Int = 0,
        val pieces: List<Piece> = emptyList(),
        val pix: String? = null,
    ) {
        val isPieces: Boolean get() = pieces.isNotEmpty()
    }

    @Serializable
    internal data class Piece(val u: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** A reader-session outcome: ordered rows, the rows captured as
     * rendered pixels (v133), and legible diag notes. */
    internal class ReaderSessionHarvest(
        val rows: List<HarvestRow>,
        val notes: List<String>,
        val captured: Map<Int, CapturedRow> = emptyMap(),
    )

    // ------------------------------------------------------------------
    // In-page scripts
    // ------------------------------------------------------------------

    /**
     * v126: the site's own unlock flow. A chapter route mounts a consent
     * banner (رفض/موافق) and an "open the chapter" button (the monetized
     * gate the reader sits behind); pressing THE SITE'S OWN button is the
     * lawful way in — the same tap a reading user performs. Returns one
     * of: "login" (parked on /login), "clicked:<label>", "consent", or
     * "none". Never navigates to the bypass/short-link host — only the
     * on-page button.
     */
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

    /**
     * Collects the reader's mounted rows and steps the scroll one beat
     * deeper. Per row (`div[data-reader-image-row="N"]`): a single loaded
     * image is reported as its absolute src; a piece assembly is reported
     * as every loaded piece's src plus its rectangle relative to the row
     * (the extension re-assembles the image itself — same descramble the
     * site performs, no canvas taint games).
     *
     * v133: piece rows ALSO capture their pixels in the page (the rendered
     * harvest — see the class docs). When every img of a piece row has
     * loaded, an async capture re-fetches each piece SAME-ORIGIN through
     * the site's own signed proxy (/api/cdn-image — the reader's healing
     * path, so the canvas never taints), draws it on a bounded canvas and
     * parks the JPEG data urls in window.__mihonPix['r<idx>']; the session
     * pulls the slot on a later tick. The row's payload carries the slot
     * key as `pix` so the Kotlin side knows what to pull. A capture that
     * cannot complete (sign refused, old WebView, tainted canvas) parks
     * the 'failed' tombstone instead — the row then falls back to the
     * loopback compose (Android 12+) or stays unlisted, named in the diag.
     *
     * v126: the scroll walks to the ABSOLUTE END of the reader (last row
     * scrolled into view every beat) and reports atEnd so the session
     * only settles after the site has had its chance to mount everything.
     */
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
                if (loaded.length === 1) {
                    out.push({ index: idx, u: abs(loaded[0].currentSrc || loaded[0].src) });
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
                        // v133: fire the pixel capture once, when EVERY img of
                        // the row has loaded (a partial capture would park a
                        // page with a hole in it forever).
                        var key = 'r' + idx;
                        if (loaded.length === imgs.length && store[key] === undefined && !busy[key]) {
                            busy[key] = 1;
                            window.__mihonCapture(key, row, loaded, rects);
                        }
                        out.push({ index: idx, w: Math.round(rr.width), h: Math.round(rr.height), pieces: pieces, pix: key });
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
            try {
                var sc = document.scrollingElement || document.body;
                var step = Math.max(600, Math.round(window.innerHeight * 1.2));
                var lastRow = nodeList.length > 0 ? nodeList[nodeList.length - 1] : null;
                if (lastRow) {
                    try { lastRow.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e3) { lastRow.scrollIntoView(true); }
                }
                window.scrollBy(0, step);
                var groups = document.querySelectorAll('div,section,main');
                for (var g = 0; g < groups.length; g++) {
                    var el = groups[g];
                    if (el.scrollHeight > el.clientHeight + 200 && el.clientHeight > 300) {
                        el.scrollTop = el.scrollTop + step;
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
