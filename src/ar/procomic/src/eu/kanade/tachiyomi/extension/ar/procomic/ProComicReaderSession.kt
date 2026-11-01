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
 * The reader session (the manhuarmtl-style WebView harvest) — v124.
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

    /** Hard tick cap — the session never outlives ~90 s of polling. */
    private const val MAX_TICKS = 120

    /** Ticks of a Cloudflare challenge before the session calls it unwinnable. */
    private const val CHALLENGE_TICK_CAP = 60

    private const val DIAG_TAG = "reader-session"

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
        var challengeTicks = 0

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

                evaluateJs(harvestScript()) { raw ->
                    val state = decodeRowState(decodeEval(raw))
                    // Apex walk: a route that redirected off /chapter/ (the
                    // .net deployment 308s to the series page) moves to the
                    // next apex instead of harvesting the wrong page.
                    val href = state["href"].orEmpty()
                    val onChapter = href.contains("/chapter/")
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

                    val growthKey = "${state["rows"]}:${state["loaded"]}"
                    if (growthKey != lastGrowthKey) {
                        lastGrowthKey = growthKey
                        quietTicks = 0
                    } else {
                        quietTicks++
                    }
                    if (ticks >= MAX_TICKS || (quietTicks >= QUIET_TICKS && rows.isNotEmpty())) {
                        resolve(Unit)
                    }
                }
            }

            loadUrl(readerApexes.first().trimEnd('/') + chapterPath)
        }

        val ordered = rows.entries.sortedBy { it.key }.map { it.value }
        notes += "$DIAG_TAG: ${ordered.size} reader rows harvested"
        return ReaderSessionHarvest(ordered, notes)
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
     */
    @Serializable
    internal data class HarvestRow(
        val index: Int,
        val u: String? = null,
        val w: Int = 0,
        val h: Int = 0,
        val pieces: List<Piece> = emptyList(),
    ) {
        val isPieces: Boolean get() = pieces.isNotEmpty()
    }

    @Serializable
    internal data class Piece(val u: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** A reader-session outcome: ordered rows plus legible diag notes. */
    internal class ReaderSessionHarvest(
        val rows: List<HarvestRow>,
        val notes: List<String>,
    )

    // ------------------------------------------------------------------
    // In-page scripts
    // ------------------------------------------------------------------

    /**
     * Collects the reader's mounted rows and steps the scroll one beat
     * deeper. Per row (`div[data-reader-image-row="N"]`): a single loaded
     * image is reported as its absolute src; a piece assembly is reported
     * as every loaded piece's src plus its rectangle relative to the row
     * (the extension re-assembles the image itself — same descramble the
     * site performs, no canvas taint games). Falls back to any mounted
     * site-hosted image when the row grid is absent (older readers).
     */
    private fun harvestScript(): String = """
        (function () {
            function abs(u) {
                if (!u || u.indexOf('http') === 0 || u.indexOf('data:') === 0) return u;
                try { return new URL(u, location.origin).href; } catch (e) { return u; }
            }
            var out = [];
            var nodeList = document.querySelectorAll('[data-reader-image-row]');
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
                        for (var k = 0; k < loaded.length; k++) {
                            var p = loaded[k];
                            var pr = p.getBoundingClientRect();
                            pieces.push({
                                u: abs(p.currentSrc || p.src),
                                x: Math.round(pr.left - rr.left),
                                y: Math.round(pr.top - rr.top),
                                w: Math.round(pr.width),
                                h: Math.round(pr.height)
                            });
                        }
                        out.push({ index: idx, w: Math.round(rr.width), h: Math.round(rr.height), pieces: pieces });
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
                window.scrollBy(0, step);
                var groups = document.querySelectorAll('div,section,main');
                for (var g = 0; g < groups.length; g++) {
                    var el = groups[g];
                    if (el.scrollHeight > el.clientHeight + 200 && el.clientHeight > 300) {
                        el.scrollTop = el.scrollTop + step;
                    }
                }
            } catch (e) {}
            return JSON.stringify({
                href: location.href,
                rows: nodeList.length,
                loaded: loadedRows,
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
