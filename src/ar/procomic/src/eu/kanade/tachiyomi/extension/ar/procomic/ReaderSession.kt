package eu.kanade.tachiyomi.extension.ar.procomic

import android.webkit.WebResourceResponse
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The reader session (the manhuarmtl-style WebView harvest).
 *
 * WHY THIS EXISTS
 *
 * The site-gated CDN era leaves the WebView-free chain exactly the pages the
 * flight embeds publicly (publicImageCount, live-observed: 3) — everything
 * beyond rides a per-call Cloudflare Turnstile credential plus a session-
 * bound signer, neither of which a cookie-less client can mint. The reader
 * session stops replicating that ladder and lets the SITE do what it already
 * does in a browser: it loads the real chapter page in a window-attached
 * offscreen WebView (the same core helper the manhuarmtl harvest and the
 * Comix bridge run on — not the crash-prone main-looper surface the v117–v120
 * saga removed), lets the page's own reader boot, mint its Turnstile tokens,
 * exchange its deferred batches and sign its images, and simply collects the
 * mounted `<img>` sources off the rendered DOM while an auto-scroll walks
 * the chapter like a reading user.
 *
 * The collected URLs are the page's own `<img>` srcs — the exact addresses
 * the site's reader displays: signed-filename app-host files (verified live:
 * the app.<apex> class serves any plain client, 200 for a bare curl) or
 * /api/cdn-image wrappers with their token in the query. Order comes from
 * the /pNN/ path segment every reader image carries, so mount order and
 * lazy loading cannot scramble the list.
 *
 * Safety profile (the crash class is structurally out):
 *  - runWebView attaches the WebView to a real window offscreen, installs a
 *    renderer-death handler and bounds the whole session with a timeout;
 *  - blockImages keeps the page's AVIF bytes out of the session (only the
 *    metadata fetches run — the loopback proxy fetches the harvested URLs);
 *  - a host allow-list (the site's apexes + the Turnstile challenge frame)
 *    and a single-flight guard mirror the manhuarmtl harvest;
 *  - every failure mode resolves with whatever was collected — the caller
 *    keeps its WebView-free list — and never throws into the host.
 */
internal object ProComicReaderSession {

    private const val POLL_MS = 700L

    /** Quiet ticks (no new page number) before the session resolves. */
    private const val QUIET_TICKS = 6

    /** Hard tick cap — the session never outlives ~80 s of polling. */
    private const val MAX_TICKS = 115

    private const val DIAG_TAG = "reader-session"

    /** One-flight guard: a chapter open never stacks two sessions. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isBusy(): Boolean = inFlight.get()

    /**
     * Loads [readerUrl] in the offscreen WebView and returns every reader
     * image the page mounted, ordered by its /pNN/ page number. Never
     * throws. Blocking; must not run on the main thread.
     */
    fun harvest(readerUrl: String, userAgent: String): ReaderSessionHarvest {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ProComicReaderSession.harvest must not be called on the main thread"
        }
        if (!inFlight.compareAndSet(false, true)) {
            return ReaderSessionHarvest(emptyList(), listOf("$DIAG_TAG: skipped (a session is already running)"))
        }
        return try {
            runBlocking { harvestSuspend(readerUrl, userAgent) }
        } catch (t: Throwable) {
            ReaderSessionHarvest(emptyList(), listOf("$DIAG_TAG: failed (${t.javaClass.simpleName})"))
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun harvestSuspend(readerUrl: String, forcedUa: String): ReaderSessionHarvest {
        val notes = mutableListOf<String>()
        // pageNum -> urls, first-seen order preserved inside each page
        val seen = LinkedHashMap<Int, MutableList<String>>()
        val seenUrls = HashSet<String>()
        var challengeSeen = false

        notes += "$DIAG_TAG: start"

        val result = runWebView<LinkedHashMap<Int, MutableList<String>>>(timeout = 95.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The page's bytes are not wanted — only its rendered img srcs.
            blockImages = true
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

            var quietTicks = 0
            var lastMaxPage = -1
            var ticks = 0

            fun absorb(urls: List<String>): Int {
                var newest = 0
                for (url in urls) {
                    if (!seenUrls.add(url)) continue
                    val page = pageNumber(url)
                    seen.getOrPut(page) { mutableListOf() }.add(url)
                    newest = maxOf(newest, if (page == Int.MAX_VALUE) 0 else page)
                }
                return newest
            }

            onPageStarted { _ -> evaluateJs(collectAndScrollScript()) }
            onPageFinished { _ -> evaluateJs(collectAndScrollScript()) }

            poll(POLL_MS.milliseconds) {
                ticks++
                evaluateJs(collectAndScrollScript()) { raw ->
                    val urls = decodeStringArray(decodeEval(raw))
                    val newest = absorb(urls)
                    if (newest > lastMaxPage) {
                        lastMaxPage = newest
                        quietTicks = 0
                    } else {
                        quietTicks++
                    }
                    if (ticks >= MAX_TICKS || (quietTicks >= QUIET_TICKS && lastMaxPage > 1)) {
                        resolve(seen)
                    }
                }
                if (ticks % 5 == 2) {
                    evaluateJs("document.title || ''") { raw ->
                        val title = decodeEval(raw).orEmpty()
                        if (title.contains("just a moment", true) || title.contains("attention required", true)) {
                            challengeSeen = true
                        }
                    }
                }
            }

            loadUrl(readerUrl)
        }

        val pages = result.entries
            .sortedBy { it.key }
            .flatMap { (_, urls) -> urls }

        if (challengeSeen) notes += "$DIAG_TAG: the page answered a Cloudflare challenge — the harvest is whatever cleared"
        notes += "$DIAG_TAG: ${pages.size} image urls across ${result.size} page numbers (max p${result.keys.maxOrNull() ?: 0})"
        return ReaderSessionHarvest(pages, notes)
    }

    // ------------------------------------------------------------------
    // Page-number ordering
    // ------------------------------------------------------------------

    /** Reader images carry their page in the path: /chapters/<c>/<id>/p12/…. */
    internal fun pageNumber(url: String): Int = PAGE_NUMBER_REGEX.find(url)
        ?.groupValues?.get(1)?.toIntOrNull()
        ?: Int.MAX_VALUE // unknown page numbers sort last, first-seen order kept

    /**
     * Merges the WebView-free list with a session harvest. The harvest is
     * authoritative for the page numbers it carries (fresher apex, complete
     * ordering); WebView-free urls fill any page number the session missed.
     * Pure so the harness pins it.
     */
    internal fun mergeWithSession(
        webViewFree: List<String>,
        harvested: List<String>,
    ): List<String> {
        if (harvested.isEmpty()) return webViewFree
        val merged = LinkedHashMap<Int, String>() // pageNum -> first-seen url
        harvested.forEach { url ->
            val key = pageNumber(url).let { if (it == Int.MAX_VALUE) 1_000_000 + merged.size else it }
            merged.putIfAbsent(key, url)
        }
        webViewFree.forEach { url ->
            val key = pageNumber(url).let { if (it == Int.MAX_VALUE) 1_000_000 + merged.size else it }
            merged.putIfAbsent(key, url)
        }
        return merged.entries.sortedBy { it.key }.map { it.value }
    }

    // ------------------------------------------------------------------
    // In-page scripts
    // ------------------------------------------------------------------

    /**
     * Collects every mounted reader image and walks the chapter one step
     * deeper (document flow plus any tall inner scroll containers). Returns
     * the collected urls as a JSON string so the eval callback can decode
     * them — the OcrHarvest channel shape.
     */
    private fun collectAndScrollScript(): String = """
        (function () {
            var urls = [];
            var imgs = document.images;
            for (var i = 0; i < imgs.length; i++) {
                var s = imgs[i].currentSrc || imgs[i].src || '';
                if (s.indexOf('http') === 0 && s.indexOf('/chapters/') >= 0) urls.push(s);
            }
            try {
                var sc = document.scrollingElement || document.body;
                window.scrollTo(0, sc.scrollHeight);
                var groups = document.querySelectorAll('div,section,main');
                for (var j = 0; j < groups.length; j++) {
                    var el = groups[j];
                    if (el.scrollHeight > el.clientHeight + 200 && el.clientHeight > 300) {
                        el.scrollTop = el.scrollHeight;
                    }
                }
            } catch (e) {}
            return JSON.stringify(urls);
        })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // Eval-result decoding (evaluateJs delivers JSON-encoded script values)
    // ------------------------------------------------------------------

    private val json = Json

    private fun decodeEval(raw: String?): String? = runCatching {
        json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.content
    }.getOrNull() ?: raw

    private fun decodeStringArray(decoded: String?): List<String> = runCatching {
        val body = decoded.orEmpty().trim()
        if (body.isEmpty() || !body.startsWith("[")) return emptyList()
        val list = org.json.JSONArray(body)
        List(list.length()) { list.optString(it) }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}

/** Reader images carry their page in the path: /chapters/<c>/<id>/p12/…. */
private val PAGE_NUMBER_REGEX = Regex("""/p(\d+)/""")

/** A reader-session outcome: ordered page urls plus legible diag notes. */
internal class ReaderSessionHarvest(
    val pageUrls: List<String>,
    val notes: List<String>,
)
