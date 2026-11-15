package eu.kanade.tachiyomi.extension.all.manhuarmtl

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import keiyoushi.utils.applicationContext
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object OcrHarvest {
    /** DOM-scraped box in FRACTION space (relative to the page's visible image frame). */
    @Serializable
    private class DomBox(val x: Float, val y: Float, val w: Float, val h: Float, val t: String)

    @Serializable
    private class DomPage(val src: String = "", val boxes: List<DomBox> = emptyList())

    /** domScrapeJs census: totals, loaded, mappable frames, canvas/svg carriers. */
    @Serializable
    private class DomScan(
        val p: List<DomPage> = emptyList(),
        val lt: Int = 0,
        val ld: Int = 0,
        val lf: Int = 0,
        val cv: Int = 0,
        val sv: Int = 0,
    )

    @Serializable
    private class WindowScan(
        val c: List<String> = emptyList(),
        val p: List<String> = emptyList(),
        val pairs: List<List<String>> = emptyList(),
    )

    /**
     * Everything one harvest session observed. The source prefers
     * [payloadCandidates] and [credentialArrays] (gate-space coordinates)
     * over [pages] (DOM-rendered geometry in fraction space; wire/replay
     * boxes ride inside `pages` and win per filename there).
     */
    internal class HarvestResult(
        val pages: List<OcrPage>,
        val payloadCandidates: List<String>,
        val credentialArrays: List<String>,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8.seconds)
        .readTimeout(12.seconds)
        .build()

    /** One harvest at a time. Reader preloads can open several chapters at once. */
    private val inFlight = AtomicBoolean(false)

    private val preemptRequested = AtomicBoolean(false)

    /** OCR payload captured off the wire (tee/replay/storage). Gate space. */
    private val wirePages = ConcurrentLinkedQueue<OcrPage>()

    /** Runtime window-scan hits: OCR-payload-shaped objects, JSON-stringified. */
    private val runtimePayloads = ConcurrentLinkedQueue<String>()

    /** Runtime window-scan hits: credential-shaped arrays, JSON-stringified. */
    private val runtimeCreds = ConcurrentLinkedQueue<String>()

    /** Runtime context-scan hits: {cid, ref} body pairs for the gate POST replay. */
    private val replayPairs = ConcurrentLinkedQueue<Pair<String, String>>()

    /** Gate POST requests observed on the wire (headers only. Bodies are hidden). */
    private class WirePost(val url: String, val headers: Map<String, String>, val summary: String)

    private val wirePosts = ConcurrentLinkedQueue<WirePost>()

    private var replayAttempts = 0

    private val replayInFlight = AtomicBoolean(false)

    private val replayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ManhuaRMTL-OcrReplay").apply { isDaemon = true }
    }

    /** Latest DOM census snapshot, appended to the harvest-result line. */
    @Volatile
    private var censusSnapshot: String = ""

    /** Logs the "replay held. No pair" note once per visit, not per tick. */
    @Volatile
    private var replayHoldLogged = false

    @Volatile
    private var sweepPass = 1

    @Volatile
    private var currentChapterUrl: String = ""

    /**
     * DOM-scraped boxes, deduplicated across sweeps: filename → (key → box).
     * Same-text boxes that overlap replace the older entry (LATEST layout
     * state wins). Sweeps during image lazy-loading used to leave stale
     * ghost boxes behind, which burned as doubled, misaligned text.
     */
    private val domAccum = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, DomBox>>()

    private val domLock = Any()
    private var domSeq = 0L

    private const val SOFT_BUDGET_MS = 40_000L
    private const val HARD_TIMEOUT_S = 55L
    private const val POLL_MS = 600L
    private const val MAX_DOM_BOXES = 1500
    private const val MAX_TEE_BODY = 2_000_000
    private const val MAX_WINDOW_SCAN_TICKS = 25

    /** Diagnostics build tag. One line per visit, so logs are attributable. */
    private const val DIAG_TAG = "v59"

    private const val STALL_SETTLE_TICKS = 8
    private const val STALL_SETTLE_MIN_MS = 20_000L

    fun isBusy(): Boolean = inFlight.get()

    fun harvest(chapterUrl: String, userAgent: String, preemptCurrent: Boolean = false): HarvestResult? {
        if (preemptCurrent && inFlight.get()) {
            preemptRequested.set(true)
            OcrDiagnostics.record("harvest: foreground waiting — background visit asked to wrap up")
            for (i in 1..12) {
                if (!inFlight.get()) break
                Thread.sleep(1_000L)
            }
            if (inFlight.get()) {
                OcrDiagnostics.record("harvest: skipped (slot still busy after preempt)")
                return null
            }
        }
        if (!inFlight.compareAndSet(false, true)) {
            OcrDiagnostics.record("harvest: skipped (one already running)")
            return null
        }
        preemptRequested.set(false)
        replayAttempts = 0
        censusSnapshot = ""
        replayHoldLogged = false
        sweepPass = 1
        currentChapterUrl = chapterUrl
        OcrDiagnostics.record("harvest: visit start (diag $DIAG_TAG)")
        wirePages.clear()
        domAccum.clear()
        runtimePayloads.clear()
        runtimeCreds.clear()
        replayPairs.clear()
        wirePosts.clear()
        return try {
            runBlocking { harvestSuspend(chapterUrl, userAgent) }
        } catch (t: Throwable) {
            OcrDiagnostics.record("harvest: failed (${t.classSimpleName()})")
            null
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun harvestSuspend(chapterUrl: String, ua: String): HarvestResult? {
        val chapterHost = chapterUrl.toHttpUrlOrNull()?.host.orEmpty()
        var sawChallenge = false
        var challengeDumped = false
        var domQuietTicks = 0
        var noNewBoxesTicks = 0
        var noNewImagesTicks = 0
        var lastDomCount = 0
        var lastLoaded = -1
        var censusHintLogged = false
        var lastLt = 0
        var lastLd = 0
        var lastCensusLt = -1
        var lastCensusLd = -1
        var pass = 1
        var scrolledOut = false
        var lastScrollY = -1
        var sameScrollTicks = 0
        var storageScanned = false
        var reconDone = false
        var windowScanTicks = 0
        var windowScanDone = false
        val startAt = System.currentTimeMillis()

        return runWebView(timeout = HARD_TIMEOUT_S.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Identity coherence with the app's network client: cf_clearance
            // is bound to the UA, and the WebView shares the cookie store.
            userAgent = ua

            interceptRequest { request ->
                teeGateRequest(request, chapterHost)
            }

            poll(POLL_MS.milliseconds) {
                val elapsed = System.currentTimeMillis() - startAt

                if (preemptRequested.get()) {
                    OcrDiagnostics.record("harvest: preempted — returning partials to the background caller")
                    resolve(buildResult())
                    return@poll
                }

                // ---- channel 5: recon (once, early) ----
                if (!reconDone) {
                    reconDone = true
                    evaluateJs(reconJs()) { raw ->
                        val decoded = decodeEval(raw).orEmpty()
                        val names = decodeList(decoded)
                        val hits = names.filter { OCR_HINTS.any { h -> it.contains(h, ignoreCase = true) } }
                        if (hits.isNotEmpty()) {
                            OcrDiagnostics.record("recon gate-ish URLs: ${hits.take(3).joinToString(" | ")}")
                        }
                        if (names.any { it.contains("challenge", true) }) sawChallenge = true
                        val sizes = decodeSizes(decoded)
                        if (sizes.isNotEmpty()) {
                            val totalKb = sizes.sumOf { it.second } / 1024
                            OcrDiagnostics.record(
                                "recon: ${sizes.size} gate response(s), ~${totalKb}KB total — DOM shows what the site renders, the wire shows what it fetched",
                            )
                        }
                    }
                    evaluateJs(challengeTitleJs()) { raw ->
                        val title = decodeEval(raw).orEmpty()
                        if (title.contains("just a moment", true) || title.contains("attention required", true)) {
                            sawChallenge = true
                            OcrDiagnostics.record("harvest: challenge page in WebView (title)")
                        }
                    }
                }

                // ---- challenge forensics (once) ----
                if (sawChallenge && !challengeDumped) {
                    challengeDumped = true
                    evaluateJs(cfIframeDumpJs()) { raw ->
                        OcrDiagnostics.record("harvest: challenge dump — ${decodeEval(raw).orEmpty().take(300)}")
                    }
                }

                // ---- channels 1+replay: runtime context scan (read-only, until hits or cap) ----
                if (!windowScanDone) {
                    windowScanTicks++
                    val havePayloads = runtimePayloads.isNotEmpty()
                    val haveCreds = runtimeCreds.isNotEmpty() || replayPairs.isNotEmpty()
                    if ((havePayloads && haveCreds) || windowScanTicks > MAX_WINDOW_SCAN_TICKS || elapsed > 30_000) {
                        windowScanDone = true
                    } else {
                        evaluateJs(contextScanJs()) { raw ->
                            val scan = decodeWindowScan(decodeEval(raw))
                            runtimeCreds.addAll(scan.c)
                            runtimePayloads.addAll(scan.p)
                            for (pair in scan.pairs) {
                                if (pair.size >= 2) replayPairs.add(pair[0] to pair[1])
                            }
                        }
                    }
                }

                tryReplayPost()

                // ---- channel 4: storage scan (once, after scripts had time) ----
                if (!storageScanned && elapsed > 6_000) {
                    storageScanned = true
                    evaluateJs(storageScanJs()) { raw ->
                        for (value in decodeList(decodeEval(raw))) {
                            parseOcrPayload(value)?.let { pgs ->
                                if (pgs.isNotEmpty()) {
                                    wirePages.addAll(pgs)
                                    OcrDiagnostics.record("storage: ${pgs.size} pages from cached JSON")
                                }
                            }
                        }
                    }
                }

                // ---- drive the page like a reader (two passes) ----
                if (!scrolledOut) {
                    evaluateJs(scrollJs()) { raw ->
                        val y = decodeEval(raw)?.trim() ?: "-1"
                        val windowY = y.substringBefore('|').toIntOrNull() ?: -1
                        if (windowY == lastScrollY) sameScrollTicks++ else sameScrollTicks = 0
                        lastScrollY = windowY
                        if (sameScrollTicks >= 2) {
                            scrolledOut = true
                            if (pass == 1) {
                                pass = 2
                                scrolledOut = false
                                sameScrollTicks = 0
                                lastScrollY = -1
                                domQuietTicks = 0
                                sweepPass = 2
                                evaluateJs(scrollTopJs()) { }
                                OcrDiagnostics.record("dom: second pass — full re-sweep of the loaded page")
                            }
                        }
                    }
                }

                // ---- channel 2: DOM overlay sweep ----
                evaluateJs(domScrapeJs()) { raw ->
                    val scan = decodeDomScan(decodeEval(raw))
                    val changed = addDomBoxes(scan.p)
                    if (changed == 0) domQuietTicks++ else domQuietTicks = 0
                    if (changed > 0) noNewBoxesTicks = 0
                    if (scan.ld != lastLoaded) {
                        noNewImagesTicks = 0
                        noNewBoxesTicks = 0
                    } else {
                        noNewImagesTicks++
                    }
                    lastLoaded = scan.ld
                    lastLt = scan.lt
                    lastLd = scan.ld
                    if (scan.lt > 0) {
                        censusSnapshot =
                            "${scan.ld}/${scan.lt} loaded, ${scan.lf} frames, canvas=${scan.cv}, svg=${scan.sv}"
                    }
                    if (!censusHintLogged && scan.lt > 0 && scan.ld < scan.lt) {
                        censusHintLogged = true
                        OcrDiagnostics.record(
                            "dom: ${scan.ld}/${scan.lt} page images loaded — boxes over unloaded images are invisible",
                        )
                    }
                    if (scan.lt > 0 && (scan.lt > lastCensusLt + 9 || scan.ld > lastCensusLd + 9)) {
                        lastCensusLt = scan.lt
                        lastCensusLd = scan.ld
                        OcrDiagnostics.record(
                            "dom: census ${scan.ld}/${scan.lt} loaded, ${scan.lf} frames, canvas=${scan.cv}, svg=${scan.sv}",
                        )
                    }
                    val total = synchronized(domLock) { domAccum.values.sumOf { it.size } }
                    if (total > lastDomCount + 40) {
                        lastDomCount = total
                        OcrDiagnostics.record("dom: $total boxes on ${domAccum.size} pages so far")
                    }
                }

                // ---- resolution ----
                val haveWire = wirePages.isNotEmpty()
                val haveRuntime = runtimePayloads.isNotEmpty() || runtimeCreds.isNotEmpty()
                val haveDom = synchronized(domLock) { domAccum.isNotEmpty() }
                val censusComplete = lastLt == 0 || lastLd >= lastLt
                val settled = scrolledOut && domQuietTicks >= 2 && censusComplete
                val stalled = elapsed >= STALL_SETTLE_MIN_MS &&
                    noNewBoxesTicks >= STALL_SETTLE_TICKS &&
                    noNewImagesTicks >= STALL_SETTLE_TICKS
                val wireFast = haveWire && elapsed >= 5_000 && domQuietTicks >= 2
                when {
                    wireFast -> resolve(buildResult())
                    settled && (haveWire || haveRuntime || haveDom) -> resolve(buildResult())
                    stalled && (haveWire || haveDom) -> {
                        OcrDiagnostics.record(
                            "harvest: page stalled (census $lastLd/$lastLt) — returning partials",
                        )
                        resolve(buildResult())
                    }
                    elapsed > SOFT_BUDGET_MS -> {
                        if (haveWire || haveRuntime || haveDom) {
                            OcrDiagnostics.record("harvest: soft budget with data — returning partials")
                            resolve(buildResult())
                        } else {
                            val why = when {
                                sawChallenge -> "challenge page"
                                else -> "no overlay rendered, nothing teed, storage empty"
                            }
                            OcrDiagnostics.record("harvest: nothing observed after ${elapsed / 1000}s — $why")
                            resolve(null)
                        }
                    }
                }
            }

            loadUrl(chapterUrl)
        }
    }

    /**
     * One at a time, at most two attempts per visit: take the observed gate
     * POST (headers captured app-side. The platform never exposes the body)
     * and the scanned {cid, ref} pair, and fire the SAME request the site's
     * own runtime just fired. A 200 with a parseable payload is the site's
     * full OCR data for the chapter. Often more than its DOM renders.
     */
    private fun tryReplayPost() {
        if (replayAttempts >= 2) return
        if (!replayInFlight.compareAndSet(false, true)) return
        val post = wirePosts.poll()
        if (post == null) {
            replayInFlight.set(false)
            return
        }
        var pair = replayPairs.poll()
        if (pair == null) {
            pair = pairFromCredentialArrays()
            if (pair != null) {
                OcrDiagnostics.record("wire2: {cid, ref} derived from a runtime credential array")
            }
        }
        if (pair == null) {
            // Keep the observation queued for a later tick. The context scan
            // may not have found the pair yet.
            wirePosts.add(post)
            replayInFlight.set(false)
            if (!replayHoldLogged && runtimeCreds.isNotEmpty()) {
                replayHoldLogged = true
                OcrDiagnostics.record(
                    "wire2: replay held — ${runtimeCreds.size} credential set(s) scanned, no usable {cid, ref} pair yet",
                )
            }
            return
        }
        replayAttempts++
        replayExecutor.execute {
            try {
                runReplayPost(post, pair)
            } finally {
                replayInFlight.set(false)
            }
        }
    }

    /** The replay POST itself. Runs on [replayExecutor], never the main thread. */
    private fun runReplayPost(post: WirePost, pair: Pair<String, String>) {
        try {
            val body = """{"cid":"${pair.first}","ref":"${pair.second}"}"""
            val builder = Request.Builder()
                .url(post.url)
                .post(body.toRequestBody("application/json".toMediaType()))
            for ((k, v) in post.headers) {
                if (k.equals("host", true) ||
                    k.equals("cookie", true) ||
                    k.equals("content-length", true) ||
                    k.equals("content-type", true)
                ) {
                    continue
                }
                runCatching { builder.header(k, v) }
            }
            CookieManager.getInstance().getCookie(post.url)?.let {
                builder.header("Cookie", it)
            }
            val postHttpUrl = post.url.toHttpUrlOrNull()
            if (postHttpUrl != null && post.headers.keys.none { it.equals("Origin", true) }) {
                builder.header("Origin", "${postHttpUrl.scheme}://${postHttpUrl.host}")
            }
            if (post.headers.keys.none { it.equals("Referer", true) } && currentChapterUrl.isNotEmpty()) {
                builder.header("Referer", currentChapterUrl)
            }
            if (post.headers.keys.none { it.equals("X-Requested-With", true) }) {
                builder.header("X-Requested-With", "XMLHttpRequest")
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body.bytes()
                if (!resp.isSuccessful) {
                    OcrDiagnostics.record("wire2: replay HTTP ${resp.code} (${post.summary})")
                    return
                }
                val parsed = parseOcrPayload(String(bytes, Charsets.UTF_8))
                if (parsed.isNullOrEmpty()) {
                    OcrDiagnostics.record("wire2: replay HTTP ${resp.code}, unparsed ${bytes.size}B")
                    return
                }
                wirePages.addAll(
                    parsed.map { page ->
                        page.copy(
                            image = page.image?.trim()?.substringAfterLast('/')?.substringBefore('?'),
                        )
                    },
                )
                OcrDiagnostics.record("wire2: replay POST → ${parsed.size} pages (gate coordinates)")
            }
        } catch (t: Throwable) {
            OcrDiagnostics.record("wire2: replay threw ${t.classSimpleName()}")
        }
    }

    private fun pairFromCredentialArrays(): Pair<String, String>? {
        val hex = Regex("^[0-9a-fA-F]{16,64}$")
        for (raw in runtimeCreds) {
            val els = runCatching {
                (json.parseToJsonElement(raw) as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
            }.getOrNull() ?: continue
            val cid = els.firstOrNull {
                it.length in 4..512 && !it.startsWith("http") && !it.all(Char::isDigit)
            } ?: continue
            val hexes = els.filter { hex.matches(it) }.sortedByDescending { it.length }
            val ref = hexes.firstOrNull { it.length == 32 }
                ?: hexes.getOrNull(1)
                ?: hexes.firstOrNull()
                ?: continue
            return cid to ref
        }
        return null
    }

    // ==================== channel 1: runtime context scan ====================

    private fun contextScanJs(): String = """
        (function(){
          try {
            var out = { c: [], p: [], pairs: [] };
            var visited = 0;
            function hex32(s) { return typeof s === 'string' && /^[0-9a-fA-F]{16,64}$/.test(s); }
            function b64ish(s) {
              if (typeof s !== 'string' || s.length < 8 || s.length > 512) return false;
              if (/^https?:/i.test(s)) return false;
              return /^[A-Za-z0-9+/=_-]+$/.test(s);
            }
            function isCredShape(arr) {
              if (!arr || arr.length < 3 || arr.length > 12) return false;
              var strs = 0, hex = 0, b64 = 0, url = false;
              for (var i = 0; i < arr.length; i++) {
                var v = arr[i];
                if (typeof v === 'string') {
                  strs++;
                  if (hex32(v)) hex++;
                  else if (b64ish(v)) b64++;
                  if (v.length > 8 && v.indexOf('http') === 0 && v.toLowerCase().indexOf('ocr') >= 0) url = true;
                } else if (typeof v === 'number') {
                  // timestamp slot
                } else if (v !== null && typeof v !== 'undefined') {
                  return false;
                }
              }
              return strs >= 2 && hex >= 1 && b64 >= 1 && url;
            }
            function payloadArr(v) {
              try {
                var arr = null;
                if (Array.isArray(v)) arr = v;
                else if (Array.isArray(v.data)) arr = v.data;
                else if (Array.isArray(v.pages)) arr = v.pages;
                if (!arr || !arr.length || arr.length > 2000) return null;
                var o = arr[0];
                if (!o || typeof o !== 'object' || Array.isArray(o)) return null;
                var hasTexts = Array.isArray(o.texts) || Array.isArray(o.dialogues) ||
                               Array.isArray(o.boxes) || Array.isArray(o.lines);
                var imgv = o.image || o.img || o.file || o.filename || o.url || o.src;
                var hasImg = typeof imgv === 'string' && imgv.length > 0;
                return (hasTexts && hasImg) ? arr : null;
              } catch (e) { return null; }
            }
            function addPair(cid, ref) {
              try {
                if (typeof cid !== 'string' || typeof ref !== 'string') return;
                if (cid.length < 4 || cid.length > 512 || ref.length < 8 || ref.length > 256) return;
                for (var i = 0; i < out.pairs.length; i++) {
                  if (out.pairs[i][0] === cid && out.pairs[i][1] === ref) return;
                }
                if (out.pairs.length < 6) out.pairs.push([cid, ref]);
              } catch (e) {}
            }
            function pairFromObj(o) {
              try {
                if (!o || typeof o !== 'object') return;
                var cid = o.cid || o.CID || null;
                var ref = o.ref || o.REF || o.reference || null;
                if (typeof cid === 'string' && typeof ref === 'string' &&
                    (b64ish(cid) || hex32(cid)) && hex32(ref)) addPair(cid, ref);
              } catch (e) {}
            }
            function scan(obj, depth) {
              if (visited > 600) return;
              var keys;
              try { keys = Object.keys(obj); } catch (e) { return; }
              for (var i = 0; i < keys.length && visited <= 600; i++) {
                var v;
                try { v = obj[keys[i]]; } catch (e) { continue; }
                if (!v) continue;
                if (typeof v === 'object') {
                  visited++;
                  try {
                    pairFromObj(v);
                    if (Array.isArray(v)) {
                      if (isCredShape(v)) {
                        if (out.c.length < 5) out.c.push(JSON.stringify(v));
                      } else {
                        var pa = payloadArr(v);
                        if (pa) {
                          var s = JSON.stringify(pa);
                          if (s.length < 900000 && out.p.length < 5) out.p.push(s);
                        }
                      }
                    } else if (v.nodeType === undefined && v !== window && depth < 3) {
                      scan(v, depth + 1);
                    }
                  } catch (e) {}
                }
              }
            }
            try { scan(window, 0); } catch (e) {}
            try {
              var html = (document.documentElement && document.documentElement.outerHTML) || '';
              html = html.slice(0, 1500000);
              var arrRe = /\[\s*"(?:[^"\\]|\\.)*"(?:\s*,\s*(?:"(?:[^"\\]|\\.)*"|\d+))+\s*\]/g;
              var m, found = 0;
              while ((m = arrRe.exec(html)) && found < 8 && out.c.length < 5) {
                found++;
                try {
                  var a = JSON.parse(m[0]);
                  if (Array.isArray(a) && isCredShape(a) && out.c.length < 5) out.c.push(JSON.stringify(a));
                } catch (e) {}
              }
              var objRe = /\{\s*["']?cid["']?\s*:\s*["']([^"']{4,300})["']\s*,\s*["']?ref["']?\s*:\s*["']([^"']{8,200})["']\s*\}/gi;
              while ((m = objRe.exec(html)) && out.pairs.length < 6) addPair(m[1], m[2]);
            } catch (e) {}
            return JSON.stringify(out);
          } catch (e) { return '{"c":[],"p":[],"pairs":[]}'; }
        })();
    """.trimIndent()

    // ==================== channel 3: GET tee + POST capture ====================

    private fun teeGateRequest(request: WebResourceRequest, chapterHost: String): WebResourceResponse? {
        val url = request.url ?: return null
        val urlString = url.toString()
        val method = request.method ?: "GET"
        val host = url.host.orEmpty()

        val sameSite = host == chapterHost ||
            (chapterHost.isNotEmpty() && host.endsWith(".$chapterHost")) ||
            host.substringAfterLast('.', "").let { tld ->
                chapterHost.substringAfterLast('.', "") == tld && tld.isNotEmpty()
            }
        val ocrish = OCR_HINTS.any { urlString.contains(it, ignoreCase = true) }

        if (!ocrish || !sameSite) return null
        if (isMediaUrl(urlString)) return null

        if (!method.equals("GET", ignoreCase = true)) {
            OcrDiagnostics.record("recon: site $method → ${urlString.takeAfterSlash(120)}")
            if (wirePosts.size < 3) {
                val headers = request.requestHeaders ?: emptyMap()
                val token = headers.keys.firstOrNull { it.equals("X-Gate-Token", true) }
                val nonce = headers.keys.firstOrNull { it.equals("X-Gate-Nonce", true) }
                val ts = headers.keys.firstOrNull { it.equals("X-Gate-Timestamp", true) }
                val ctype = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "?"
                val summary = "token=${token != null}, nonce=${nonce != null}, ts=${ts != null}, ct=$ctype"
                OcrDiagnostics.record("wire: $method gate call captured ($summary)")
                wirePosts.add(WirePost(urlString, headers, summary))
            }
            return null
        }

        return runCatching {
            val headersBuilder = Request.Builder().url(urlString)
            for ((k, v) in request.requestHeaders ?: emptyMap()) {
                if (k.equals("cookie", true) || k.equals("host", true) || k.equals("user-agent", true)) continue
                headersBuilder.header(k, v)
            }
            CookieManager.getInstance().getCookie(urlString)?.let {
                headersBuilder.header("Cookie", it)
            }
            val response = httpClient.newCall(headersBuilder.build()).execute()
            response.use { resp ->
                val contentType = resp.header("Content-Type") ?: "application/json"
                if (!resp.isSuccessful) {
                    OcrDiagnostics.record("tee: ${resp.code} from ${urlString.takeAfterSlash(120)}")
                    return null
                }
                val bytes = resp.body.bytes()
                if (bytes.size > MAX_TEE_BODY) return null
                val body = String(bytes, Charsets.UTF_8)
                val parsed = parseOcrPayload(body)
                if (parsed == null) {
                    OcrDiagnostics.record(
                        "tee: unparsed ${bytes.size}B ${contentType.take(24)} ${urlString.takeAfterSlash(120)}",
                    )
                    return null
                }
                if (parsed.isNotEmpty()) {
                    wirePages.addAll(parsed)
                    OcrDiagnostics.record("tee: ${parsed.size} pages from ${urlString.takeAfterSlash(120)}")
                }
                val mime = contentType.substringBefore(';').trim().ifEmpty { "application/json" }
                val encoding = contentType.substringAfter("charset=", "").trim().ifEmpty { "utf-8" }
                WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
            }
        }.getOrNull()
    }

    // ==================== DOM accumulation (latest-wins) ====================

    /**
     * Adds swept boxes. Same-text boxes overlapping ≥ 40% of the smaller
     * one are the SAME overlay re-measured at a different moment (image
     * lazy-load swaps, scrollbar-induced reflow, entrance animations). The
     * newest measurement replaces the old one instead of piling up beside
     * it. Boxes identical to what's stored (within 0.4%) count as no-change
     * so the settle logic still converges.
     *
     * Returns how many boxes were added or replaced.
     */
    private fun addDomBoxes(pages: List<DomPage>): Int {
        var changed = 0
        synchronized(domLock) {
            for (page in pages) {
                val filename = page.src.substringBefore('?').substringAfterLast('/')
                if (filename.isEmpty() || page.boxes.isEmpty()) continue
                val slot = domAccum.getOrPut(filename) { LinkedHashMap() }
                for (b in page.boxes) {
                    val text = b.t.trim()
                    if (text.isEmpty() || b.w <= 0f || b.h <= 0f) continue
                    var replacedKey: String? = null
                    var identical = false
                    for ((k, existing) in slot) {
                        if (existing.t != text) continue
                        val settles = sweepPass >= 2
                        if (settles || fractionOverlap(existing, b) >= 0.4f) {
                            replacedKey = k
                            identical = existing.closeEnough(b)
                            break
                        }
                    }
                    when {
                        identical -> {}
                        replacedKey != null -> {
                            slot.remove(replacedKey)
                            slot["b${domSeq++}"] = b
                            changed++
                        }
                        else -> {
                            slot["b${domSeq++}"] = b
                            changed++
                        }
                    }
                    if (slot.size >= 400) break
                }
            }
        }
        return changed
    }

    /** Intersection area / smaller box area, in fraction space. */
    private fun fractionOverlap(a: DomBox, b: DomBox): Float {
        val ix = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
        val iy = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
        if (ix <= 0f || iy <= 0f) return 0f
        val minArea = minOf(a.w * a.h, b.w * b.h)
        return if (minArea <= 0f) 0f else (ix * iy) / minArea
    }

    private fun DomBox.closeEnough(o: DomBox): Boolean = t == o.t &&
        kotlin.math.abs(x - o.x) < 0.004f &&
        kotlin.math.abs(y - o.y) < 0.004f &&
        kotlin.math.abs(w - o.w) < 0.004f &&
        kotlin.math.abs(h - o.h) < 0.004f

    // ==================== result merge ====================

    /**
     * Wire payloads (tee/replay/storage) are authoritative per filename: they
     * carry the gate's own pixel coordinates. DOM-scraped fraction boxes fill
     * in every image filename the wire didn't cover.
     */
    private fun buildResult(): HarvestResult {
        val merged = LinkedHashMap<String, MutableList<OcrText>>()
        fun add(filename: String?, boxes: List<OcrText>) {
            val key = filename?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val slot = merged.getOrPut(key) { mutableListOf() }
            for (b in boxes) {
                if (slot.none { it.text == b.text && it.cleanBox == b.cleanBox && it.boxList == b.boxList }) {
                    slot += b
                }
            }
        }

        for (page in wirePages) add(page.image, page.texts.orEmpty())
        synchronized(domLock) {
            for ((filename, boxes) in domAccum) {
                if (filename in merged) continue
                add(filename, boxes.values.map { it.toOcrText() })
            }
        }

        val result = merged
            .map { (filename, texts) -> OcrPage(image = filename, texts = texts.filter { !it.text.isNullOrBlank() }) }
            .filter { !it.texts.isNullOrEmpty() }
        OcrDiagnostics.record(
            "harvest result: ${result.size} pages, ${result.sumOf { it.texts?.size ?: 0 }} boxes" +
                (if (runtimePayloads.isNotEmpty()) ", ${runtimePayloads.size} site-memory payload(s)" else "") +
                (if (runtimeCreds.isNotEmpty()) ", ${runtimeCreds.size} runtime credential set(s)" else "") +
                (if (censusSnapshot.isNotEmpty()) " [$censusSnapshot]" else ""),
        )
        return HarvestResult(
            pages = result,
            payloadCandidates = runtimePayloads.toList(),
            credentialArrays = runtimeCreds.toList(),
        )
    }

    private fun DomBox.toOcrText(): OcrText = OcrText(
        text = t.trim(),
        boxList = JsonArray(
            listOf(
                JsonPrimitive(x),
                JsonPrimitive(y),
                JsonPrimitive(w),
                JsonPrimitive(h),
            ),
        ),
        normalized = true,
    )

    // ==================== page-side read scripts ====================
    // All of these only read. Nothing on the page is replaced or wrapped, so
    // native-function integrity checks still pass. Results are JSON strings.

    private fun scrollJs(): String = """
        (function(){
          try {
            var step = Math.max(300, Math.round(window.innerHeight * 0.9));
            var before = window.scrollY;
            window.scrollBy(0, step);
            if (Math.abs(window.scrollY - before) > 10) return String(window.scrollY);
            var nodes = document.querySelectorAll('div,main,section');
            for (var i = 0; i < nodes.length && i < 150; i++) {
              var el = nodes[i];
              if (el.scrollHeight > el.clientHeight + 100 && el.clientHeight > 200) {
                var b = el.scrollTop;
                el.scrollTop = b + step;
                if (el.scrollTop - b > 10) return String(window.scrollY) + '|' + String(el.scrollTop);
              }
            }
            return String(window.scrollY);
          } catch (e) { return '-1'; }
        })();
    """.trimIndent()

    private fun scrollTopJs(): String = """
        (function(){
          try {
            window.scrollTo(0, 0);
            var nodes = document.querySelectorAll('div,main,section');
            for (var i = 0; i < nodes.length && i < 150; i++) {
              var el = nodes[i];
              if (el.scrollHeight > el.clientHeight + 100 && el.clientHeight > 200) el.scrollTop = 0;
            }
            return '1';
          } catch (e) { return '0'; }
        })();
    """.trimIndent()

    private fun challengeTitleJs(): String = "(function(){try{return document.title||''}catch(e){return ''}})()"

    private fun cfIframeDumpJs(): String = """
        (function(){
          try{
            var frs=document.querySelectorAll('iframe');
            var parts=[];
            for(var i=0;i<frs.length&&i<4;i++){
              var b=frs[i].getBoundingClientRect();
              var src=(frs[i].src||'').slice(0,48);
              parts.push(src+' '+Math.round(b.width)+'x'+Math.round(b.height));
            }
            var t=(document.title||'').slice(0,40);
            var q=function(s){try{return document.querySelector(s)?1:0}catch(e){return 0}};
            var stage=q('#challenge-stage')+q('#challenge-form')+q('[class*="cf-turnstile"]')+q('[id*="challenge"]');
            var txt=(document.body&&document.body.innerText||'').replace(/\s+/g,' ').slice(0,60);
            return 'iframes='+frs.length+'['+parts.join(' | ')+'] title="'+t+'" stage='+stage+' txt="'+txt+'"';
          }catch(e){ return 'dump-failed'; }
        })();
    """.trimIndent()

    private fun reconJs(): String = """
        (function(){
          try {
            var out = [];
            var sized = [];
            var es = performance.getEntriesByType('resource') || [];
            for (var i = 0; i < es.length; i++) {
              out.push(es[i].name);
              var n = es[i].name || '';
              if (n.toLowerCase().indexOf('ocr') >= 0 || n.toLowerCase().indexOf('gate') >= 0) {
                sized.push([n.slice(0, 120), es[i].encodedBodySize || es[i].transferSize || 0]);
              }
            }
            out.push(String(location.href));
            return JSON.stringify({ names: out, sized: sized });
          } catch (e) { return '{"names":[],"sized":[]}'; }
        })();
    """.trimIndent()

    private fun storageScanJs(): String = """
        (function(){
          try {
            var vals = [];
            function scan(store) {
              if (!store) return;
              var n = Math.min(store.length || 0, 80);
              for (var i = 0; i < n; i++) {
                var k = store.key(i);
                if (!k) continue;
                var v = '';
                try { v = store.getItem(k) || ''; } catch (e) { continue; }
                if (v.length > 10 && v.length < 900000) {
                  var c = v.replace(/^\s+/, '').charAt(0);
                  if (c === '[' || c === '{') vals.push(v);
                }
              }
            }
            try { scan(window.localStorage); } catch (e) {}
            try { scan(window.sessionStorage); } catch (e) {}
            return JSON.stringify(vals);
          } catch (e) { return '[]'; }
        })();
    """.trimIndent()

    private fun domScrapeJs(): String = """
        (function(){
          try {
            var SKIP = /nav|menu|button|btn|header|footer|comment|sidebar|banner|logo|watermark|share|social|ad[-_]|[-_]ad|ads|slider|breadcrumb|pagin|toast|notice|tooltip|modal|popup|script|style|noscript|loading|spinner/i;
            var out = [];
            var imgsTotal = 0, imgsLoaded = 0, count = 0, framesTotal = 0, cvFrames = 0, svFrames = 0;
            function clampF(v) { return Math.max(-0.1, Math.min(1.1, v)); }
            function frameOf(im) {
              var r = im.getBoundingClientRect();
              var nw = im.naturalWidth || 0, nh = im.naturalHeight || 0;
              if (r.width < 60 || r.height < 120 || !nw || !nh) return null;
              var src = im.currentSrc || im.src || im.getAttribute('data-src') || '';
              if (src.indexOf('http') !== 0) return null;
              var arEl = r.width / r.height, arNat = nw / nh;
              var vx = r.left, vy = r.top, vw = r.width, vh = r.height;
              if (arEl > 0 && arNat > 0 && Math.abs(arEl - arNat) / arNat > 0.02) {
                if (arEl > arNat) {
                  vh = r.width / arNat;
                  vy = r.top + (r.height - vh) / 2;
                } else {
                  vw = r.height * arNat;
                  vx = r.left + (r.width - vw) / 2;
                }
              }
              return { l: vx, t: vy, r: vx + vw, b: vy + vh, w: vw, h: vh, src: src };
            }
            function sweep(doc) {
              if (!doc || !doc.body) return;
              var imgs = [];
              try { imgs = Array.prototype.slice.call(doc.images || []); } catch (e) { imgs = []; }
              var shadows = [];
              try {
                var hosts = doc.body.querySelectorAll('*');
                for (var h = 0; h < hosts.length; h++) {
                  var sr = null;
                  try { sr = hosts[h].shadowRoot; } catch (e) { sr = null; }
                  if (sr) shadows.push(sr);
                }
              } catch (e) {}
              for (var s = 0; s < shadows.length; s++) {
                try {
                  var simgs = shadows[s].querySelectorAll('img');
                  for (var si = 0; si < simgs.length; si++) imgs.push(simgs[si]);
                } catch (e) {}
              }
              imgsTotal += imgs.length;
              var pages = [];
              for (var i = 0; i < imgs.length; i++) {
                try {
                  var im = imgs[i];
                  if (im.complete && im.naturalWidth) imgsLoaded++;
                  var f = frameOf(im);
                  if (f) pages.push(f);
                } catch (e) {}
              }
              if (!pages.length) return;
              framesTotal += pages.length;
              // Counts frames carrying a canvas/svg overlay element.
              function overlayCount(tag) {
                var n = 0;
                try {
                  var els = doc.body.getElementsByTagName(tag);
                  for (var i = 0; i < els.length; i++) {
                    var er = els[i].getBoundingClientRect();
                    if (er.width < 8 || er.height < 8) continue;
                    var cx = er.left + er.width / 2, cy = er.top + er.height / 2;
                    for (var k = 0; k < pages.length; k++) {
                      var P = pages[k];
                      if (cx >= P.l && cx <= P.r && cy >= P.t && cy <= P.b) { n++; break; }
                    }
                  }
                } catch (e) {}
                return n;
              }
              cvFrames += overlayCount('canvas');
              svFrames += overlayCount('svg');
              var byPage = {};
              function consider(el) {
                if (count > $MAX_DOM_BOXES) return;
                if (!el || el.nodeType !== 1) return;
                var txt = '';
                try {
                  if (el.childElementCount === 0) {
                    txt = el.textContent || '';
                  } else if (el.innerText && el.innerText.length < 400 && el.innerText === el.textContent) {
                    txt = el.innerText;
                  }
                } catch (e) { txt = ''; }
                txt = String(txt).replace(/\s+/g, ' ').trim();
                if (txt.length < 2 || txt.length > 600) return;
                var cls = (typeof el.className === 'string') ? el.className : '';
                var id = el.id || '';
                if (SKIP.test(cls) || SKIP.test(id)) return;
                var er;
                try { er = el.getBoundingClientRect(); } catch (e) { return; }
                if (er.width < 4 || er.height < 4) return;
                try {
                  var st = getComputedStyle(el);
                  if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity || '1') === 0) return;
                } catch (e) {}
                var cx = er.left + er.width / 2, cy = er.top + er.height / 2;
                for (var k = 0; k < pages.length; k++) {
                  var P = pages[k];
                  if (cx >= P.l && cx <= P.r && cy >= P.t && cy <= P.b) {
                    var key = P.src;
                    if (!byPage[key]) byPage[key] = [];
                    if (byPage[key].length < 400) {
                      byPage[key].push({
                        x: clampF((er.left - P.l) / P.w),
                        y: clampF((er.top - P.t) / P.h),
                        w: clampF(er.width / P.w),
                        h: clampF(er.height / P.h),
                        t: txt
                      });
                      count++;
                    }
                    break;
                  }
                }
              }
              try {
                var all = doc.body.getElementsByTagName('*');
                for (var j = 0; j < all.length; j++) consider(all[j]);
              } catch (e) {}
              for (var s2 = 0; s2 < shadows.length; s2++) {
                try {
                  var sel = shadows[s2].querySelectorAll('*');
                  for (var s3 = 0; s3 < sel.length; s3++) consider(sel[s3]);
                } catch (e) {}
              }
              for (var key2 in byPage) out.push({ src: key2, boxes: byPage[key2] });
            }
            try { sweep(document); } catch (e) {}
            try {
              for (var fi = 0; fi < window.frames.length && fi < 10; fi++) {
                try { sweep(window.frames[fi].document); } catch (e) {}
              }
            } catch (e) {}
            return JSON.stringify({ p: out, lt: imgsTotal, ld: imgsLoaded, lf: framesTotal, cv: cvFrames, sv: svFrames });
          } catch (e) { return '{"p":[],"lt":0,"ld":0,"lf":0,"cv":0,"sv":0}'; }
        })();
    """.trimIndent()

    // ==================== small helpers ====================

    /**
     * evaluateJavascript hands back the expression result JSON-encoded (a
     * string comes back quoted+escaped). Decode that one layer; fall back to
     * the raw value when it isn't JSON-quoted.
     */
    private fun decodeEval(raw: String?): String? = runCatching {
        json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.content
    }.getOrNull() ?: raw

    private fun decodeList(decoded: String?): List<String> = runCatching {
        val arr = json.parseToJsonElement(decoded.orEmpty()) as? JsonArray ?: return emptyList()
        arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }.getOrDefault(emptyList())

    private fun decodeSizes(decoded: String?): List<Pair<String, Long>> = runCatching {
        val obj = json.parseToJsonElement(decoded.orEmpty())
        if (obj !is kotlinx.serialization.json.JsonObject) return emptyList()
        val sized = obj["sized"] as? JsonArray ?: return emptyList()
        sized.mapNotNull { el ->
            val arr = el as? JsonArray ?: return@mapNotNull null
            val name = (arr.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
            val size = (arr.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
            name to size
        }
    }.getOrDefault(emptyList())

    private fun decodeDomScan(decoded: String?): DomScan = runCatching {
        json.decodeFromString<DomScan>(decoded.orEmpty())
    }.getOrDefault(DomScan())

    private fun decodeWindowScan(decoded: String?): WindowScan = runCatching {
        json.decodeFromString<WindowScan>(decoded.orEmpty())
    }.getOrDefault(WindowScan())

    private fun isMediaUrl(url: String): Boolean = Regex(
        """\.(webp|jpe?g|png|gif|avif|svg|mp4|webm|woff2?|ttf)(\?|#|$)""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(url)

    private fun String.takeAfterSlash(max: Int): String = substringAfter("://").let { if (it.length > max) it.take(max) + "…" else it }

    private fun Throwable.classSimpleName(): String = javaClass.simpleName.ifEmpty { "error" }

    /** URL keyword family that marks a request as OCR-gate-shaped. */
    private val OCR_HINTS = listOf("ocr", "dialog", "translat", "/gate", "bubble")
}

/**
 * Rolling OCR pipeline log, the OCR counterpart of the Cloudflare
 * solver diagnostics. The gate handshake and the WebView harvest both run
 * without any visible surface; when the user reports "raw pages", this log
 * is the difference between a data-driven next round and guesswork.
 */
internal object OcrDiagnostics {
    private const val MAX_LINES = 60
    private const val PREFS = "manhuarmtl_ocr_diag"
    private const val KEY = "log"
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Volatile
    private var loaded = false

    @Volatile
    private var lines: String = ""

    fun record(event: String) {
        ensureLoaded()
        val stamped = "[${fmt.format(java.util.Date())}] $event"
        synchronized(this) {
            lines = (lines + "\n" + stamped)
                .split('\n')
                .takeLast(MAX_LINES)
                .joinToString("\n")
            persist()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            lines = runCatching {
                applicationContext
                    .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getString(KEY, "")
                    .orEmpty()
            }.getOrDefault("")
            loaded = true
        }
    }

    private fun persist() {
        runCatching {
            applicationContext
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, lines)
                .apply()
        }
    }

    fun snapshot(): String = synchronized(this) { lines }
}
