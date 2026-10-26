package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Compact attempt trail for one page request: every plan/piece attempt is
 * recorded with the client that ran it, the generation it targeted and the
 * HTTP code (or error class) it produced. The trail goes into the 502 body
 * (reader-visible), the X-ProComic-Diag header, logcat and the /.diag route,
 * so a device-side failure report pinpoints the failing stage WITHOUT a
 * debugger — the site's plan/piece endpoints behave differently per region
 * and per POP (per-POP decryptor lag was observed live as HTTP 400), and
 * the bare inner client can be treated differently by the WAF than a
 * session-carrying one.
 */
internal class PageDiag {

    private val rows = ArrayList<String>(8)
    private val lock = Any()

    fun add(row: String) {
        synchronized(lock) {
            if (rows.size < 12) rows.add(row)
        }
    }

    fun summary(): String = synchronized(lock) { rows.joinToString(", ") }

    fun isEmpty(): Boolean = synchronized(lock) { rows.isEmpty() }
}

/**
 * Serves ProComic reader images from a loopback HTTP server.
 *
 * Two routes:
 *
 * 1. `/.protected-page/{chapterId}/{index}` — "protected" pages. The reader
 *    SSR exposes the first few pages as plain CDN URLs; the rest arrive as
 *    encrypted reconstruction "maps". The site's own reader decrypts each
 *    map into a plan `{dim, mode, pieces, order, rects}` where every piece
 *    is a short-lived `/i/{token}` image on the CDN, and draws the pieces
 *    onto a canvas at their rects. Since v1.6.15 the server decrypts
 *    LOCALLY, exactly like the reader does: v2 "browser" tokens derive
 *    their AES key from a seed constant (no network), v3
 *    "browser_session" tokens fetch ONE session key per chapter via
 *    `GET /chapter-map-session-key/{chapterId}?legacy=1` (cached until its
 *    expiry; a real WebView fetches the key when the edge 403s OkHttp's
 *    TLS fingerprint), then decrypt with AES-GCM. The proxy-plan POST
 *    (`POST /chapter-map-proxy-plan/{chapterId}`) remains only as the
 *    legacy fallback — the per-page POSTs it needed are what the device
 *    edge answered 502 from page ~4 onward. The flow here is:
 *
 *      getPageList -> deferred-media (map tokens, cached in memory)
 *                  -> loopback `http://127.0.0.1:{port}/.protected-page/{chapterId}/{index}` URLs
 *      server      -> local decrypt (session key, cached)
 *                  -> download pieces -> stitch at rects -> JPEG response
 *
 * 2. `/.image-proxy/{base64url}` — plain CDN images, proxied byte-for-byte.
 *    Host apps fetch image URLs with THEIR OWN client, and the site's WAF
 *    answers 403 to non-browser User-Agents (Kotatsu's app UA among them —
 *    verified live), so direct CDN URLs break in those readers while the
 *    browser works fine. Proxying through the extension's process (browser
 *    UA, site Referer) makes every page image host-app-proof, exactly like
 *    the protected-page route above.
 *
 * Why a loopback server instead of a client interceptor: host apps differ in
 * how they execute image requests. Several Mihon-compatible readers (Kotatsu's
 * Mihon-compat layer among them) run image requests through their own client
 * without the source's application interceptors, which sent the synthetic
 * protected-page URL to the site raw and got HTTP 403 back. A plain HTTP URL
 * that any HTTP client can fetch works everywhere, with identical behavior in
 * every reader and in downloaders.
 *
 * Piece images are AVIF: the platform codec only exists on Android 12+ and
 * some OEM builds lack it even after that, so decodePiece falls back to the
 * bundled software decoder (libavif/dav1d, every ABI) and then ImageDecoder
 * before surfacing an error. The image-proxy route streams bytes without
 * decoding, so it has no API-level requirement.
 */
class ProtectedPageServer(
    private val baseUrl: String,
    private val mapCache: MapCache,
    /**
     * The source's own client (cookie jar, CF solver, browser UA) used as
     * the SESSION FALLBACK: when the bare inner client cannot get a plan or
     * a piece through, the same request is retried through this client so
     * the site sees the browsing session the reader page established
     * (cookies, any Cloudflare clearance the source holds). Verified live:
     * the plan/piece endpoints accept unauthenticated traffic, so the inner
     * client stays primary; the session path only runs after it failed.
     */
    private val sessionClient: OkHttpClient? = null,
    private val flightRebuild: ((readerUrl: String, chapterId: String) -> RebuiltFlight?)? = null,
) {

    init {
        // The WebView plan tier hands every cookie it earns (Cloudflare
        // clearance in particular) back to the session client, so the two
        // never present different "browsers" to the edge. Reflection: the
        // client class touches android.webkit and is excluded from the JVM
        // harness build, where this field simply stays unset.
        runCatching {
            Class.forName("eu.kanade.tachiyomi.extension.ar.procomic.ProComicWebViewPlanClient")
                .getDeclaredField("cookieSink")
                .apply { isAccessible = true }
                .set(null, sessionClient)
        }
    }

    private class Machine(val socket: ServerSocket)

    /** Verification probes (JVM harness): force the two last-resort plan
     * paths for one cached page so they are exercised against live data —
     * the proxy-plan path otherwise always wins. The server itself never
     * calls these. */
    internal fun debugLocalPlanFor(chapterId: String, pageIndex: Int): PlanDto {
        val entry = mapCache.get(chapterId, pageIndex)
            ?: throw IOException("ProComic: no cached map for $chapterId/$pageIndex")
        return localPlan(chapterId, entry)
    }

    internal fun debugRebuildPlanFor(chapterId: String, pageIndex: Int): PlanDto {
        val entry = mapCache.get(chapterId, pageIndex)
            ?: throw IOException("ProComic: no cached map for $chapterId/$pageIndex")
        return rebuildPlan(chapterId, entry)
    }

    private val machine = AtomicReference<Machine?>()
    private val startLock = Any()

    private val startedAtMs = System.currentTimeMillis()

    /** Fair 2-wide semaphore over the BITMAP stage only (decode + draw +
     * JPEG encode). Fairness is the point: a reader preloads the next
     * several pages while the user is still looking at the current one, and
     * a non-fair semaphore would let late requests barge ahead of pages the
     * user is about to reach, starving them past the host's image-fetch
     * timeout. Plan and piece downloads run OUTSIDE this semaphore —
     * serializing those (the pre-v87 shape) put a whole preload window
     * behind one page's full pipeline latency, which is exactly the
     * "first ~4 pages load, every page after that fails" report: with a
     * software AVIF decode per piece the serialized pipeline ran 8-12s per
     * page, so the fifth preloaded page crossed the reader's ~30s fetch
     * timeout while still queued. Two concurrent stitches peak at two
     * canvases (~32 MB with the RGB_565 fallback below), transient, with
     * piece bitmaps recycled inside the loop. */
    private val stitchPermits = Semaphore(MAX_CONCURRENT_STITCHES, true)
    private val proxyPermits = Semaphore(MAX_CONCURRENT_IMAGE_PROXIES)

    /** One inner client for plan + piece traffic, independent of the host
     * app's client (no source interceptors, no rate limiter contention, no
     * recursion). The plan/piece endpoints accept unauthenticated requests —
     * verified live — so cookies and CF solving are not needed here. */
    private val innerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val executor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ProComicPageServer").apply { isDaemon = true }
        }
    }

    /** Loopback URL for the protected page at [pageIndex] of [chapterId]. */
    fun pageUrl(chapterId: String, pageIndex: Int): String = "http://127.0.0.1:${ensureStarted()}/$PROTECTED_PATH_PREFIX/$chapterId/$pageIndex"

    /**
     * Loopback URL that serves [imageUrl] through the extension's own client
     * (browser User-Agent + site Referer), so host apps that would get 403
     * from the CDN with their own headers can still load the image.
     */
    fun imageProxyUrl(imageUrl: String): String {
        val encoded = android.util.Base64.encodeToString(
            imageUrl.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        return "http://127.0.0.1:${ensureStarted()}/$IMAGE_PROXY_PATH_PREFIX/$encoded"
    }

    /** Starts (or restarts) the loopback server and returns its port. */
    private fun ensureStarted(): Int {
        machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
        synchronized(startLock) {
            machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
            machine.set(Machine(socket))
            val acceptor = Thread({ acceptLoop(socket) }, "ProComicPageServer-accept")
            acceptor.isDaemon = true
            acceptor.start()
            return socket.localPort
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                return // socket closed or unrecoverable; a new one starts on demand
            }
            executor.execute { handleConnection(connection) }
        }
    }

    private fun handleConnection(connection: Socket) {
        try {
            connection.use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = socket.getInputStream()
                val request = readRequestLine(input) ?: return
                val (method, rawPath) = request
                val path = rawPath.substringBefore('?')
                val output = socket.getOutputStream()

                when (method) {
                    "GET" -> serve(path, output)
                    else -> writeResponse(output, 405, "Method Not Allowed", "text/plain", "method not allowed".toByteArray())
                }
            }
        } catch (_: Exception) {
            // connection-level failure: the client went away or the socket
            // died mid-response; nothing sensible to reply with
        }
    }

    /** Reads the request line plus headers (draining up to the body boundary). */
    private fun readRequestLine(input: InputStream): Pair<String, String>? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        // drain the rest of the header block (bounded)
        var consumed = 0
        while (consumed < MAX_HEADER_BYTES) {
            val line = reader.readLine() ?: break
            consumed += line.length + 2
            if (line.isEmpty()) break
        }
        return parts[0] to parts[1]
    }

    private fun serve(path: String, output: java.io.OutputStream) {
        when {
            path.startsWith("/$DIAG_PATH_PREFIX") -> serveDiag(path, output)
            path.startsWith("/$PROTECTED_PATH_PREFIX/") -> serveProtectedPage(path, output)
            path.startsWith("/$IMAGE_PROXY_PATH_PREFIX/") -> serveImageProxy(path, output)
            else -> writeResponse(output, 404, "Not Found", "text/plain", "unknown path".toByteArray())
        }
    }

    /** Failure ring buffer feeding /.diag — the last [MAX_FAILURE_RECORDS]
     * page failures across all chapters, newest last. */
    private class FailureRecord(
        val chapterId: String,
        val pageIndex: Int,
        val stage: String,
        val attempts: String,
        val at: Long,
    )

    private val failures = ArrayDeque<FailureRecord>()

    private val failuresLock = Any()

    private fun recordFailure(chapterId: String, pageIndex: Int, stage: String, diag: PageDiag) {
        if (diag.isEmpty()) return
        synchronized(failuresLock) {
            if (failures.size >= MAX_FAILURE_RECORDS) failures.removeFirst()
            failures.addLast(FailureRecord(chapterId, pageIndex, stage, diag.summary(), System.currentTimeMillis()))
        }
    }

    /**
     * `/.diag` — JSON state of the stitch server for remote diagnosis:
     * which chapters carry cached maps (method/generation/token size per
     * page, never the tokens themselves), the plan cache size and the last
     * failures with their full attempt trails. `/.diag/{chapterId}` scopes
     * to one chapter. Plain JSON on purpose: the user can paste it as-is.
     */
    private fun serveDiag(path: String, output: java.io.OutputStream) {
        val scopedId = path.removePrefix("/$DIAG_PATH_PREFIX").trimStart('/')
        val body = try {
            buildDiagJson(scopedId)
        } catch (t: Throwable) {
            "{\"error\":\"diag failed: ${t.javaClass.simpleName}\"}"
        }
        writeResponse(output, 200, "OK", "application/json", body.toByteArray())
    }

    private fun buildDiagJson(scopedChapterId: String): String {
        val sb = StringBuilder(2048)
        sb.append('{')
        sb.append("\"server\":\"procomic-stitch/v94\"")
        sb.append(",\"uptimeMs\":").append(System.currentTimeMillis() - startedAtMs)
        val scoped = scopedChapterId.takeIf { it.isNotEmpty() }
        val chapterIds = scoped?.let { listOf(it) } ?: mapCache.chapterIds()
        sb.append(",\"chapters\":[")
        chapterIds.forEachIndexed { ci, chapterId ->
            if (ci > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":\"").append(chapterId).append('"')
            val entries = mapCache.describe(chapterId).orEmpty()
            sb.append(",\"maps\":").append(entries.size)
            val method = entries.firstOrNull()?.get("method")
            val cdnPath = entries.firstOrNull()?.get("cdnPath")
            if (method != null) sb.append(",\"method\":\"").append(method).append('"')
            if (cdnPath != null) sb.append(",\"cdnPath\":\"").append(cdnPath).append('"')
            if (entries.isNotEmpty()) {
                sb.append(",\"pages\":[")
                entries.forEachIndexed { pi, e ->
                    if (pi > 0) sb.append(',')
                    sb.append("{\"page\":").append(e["page"])
                        .append(",\"tokenBytes\":").append(e["tokenBytes"])
                        .append(",\"hasReaderUrl\":").append(e["hasReaderUrl"])
                        .append('}')
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append(']')
        synchronized(planCacheLock) { sb.append(",\"planCacheEntries\":").append(planCache.size) }
        sb.append(",\"recentFailures\":[")
        val records = synchronized(failuresLock) { failures.toList() }
            .filter { scoped == null || it.chapterId == scoped }
        records.forEachIndexed { fi, f ->
            if (fi > 0) sb.append(',')
            sb.append("{\"at\":").append(f.at)
                .append(",\"chapterId\":\"").append(f.chapterId).append('\"')
                .append(",\"page\":").append(f.pageIndex)
                .append(",\"stage\":\"").append(f.stage).append('\"')
                .append(",\"attempts\":\"").append(f.attempts.replace("\"", "'")).append('\"')
                .append('}')
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }

    private fun serveProtectedPage(path: String, output: java.io.OutputStream) {
        val prefix = "/$PROTECTED_PATH_PREFIX/"
        val segments = path.removePrefix(prefix).split('/')
        val chapterId = segments.getOrNull(0).orEmpty()
        val pageIndex = segments.getOrNull(1)?.toIntOrNull()
        if (chapterId.isEmpty() || pageIndex == null) {
            writeResponse(output, 400, "Bad Request", "text/plain", "malformed page url".toByteArray())
            return
        }

        // Fast path: this page was already assembled for this chapter — a
        // retry after a reader-side image-fetch timeout (routine while the
        // preload queue drains) answers instantly instead of re-running
        // the whole plan/download/stitch pipeline.
        cachedPageResult(chapterId, pageIndex)?.let {
            writeResponse(output, 200, "OK", "image/jpeg", it)
            return
        }

        val entry = mapCache.get(chapterId, pageIndex)
        if (entry == null) {
            writeResponse(
                output,
                410,
                "Gone",
                "text/plain",
                "page plan expired, reopen the chapter".toByteArray(),
            )
            return
        }

        // Pipeline layout (the pre-serialization used to put plan + piece
        // downloads + stitch ALL behind one permit, which serialized a
        // reader's whole preload window behind a single page's latency and
        // pushed every queued page past the host's image-fetch timeout —
        // the device-visible "first ~4 pages load, the rest fail"):
        //   plan    -> network only, concurrent across pages, 90s result cache
        //   pieces  -> network only, concurrent across pages (3-wide pool)
        //   stitch  -> bitmap work only, fair 2-wide semaphore
        // Identical concurrent requests (host preload window + visible page
        // + its own prefetch) share ONE build through buildPageCoalesced.
        try {
            val bytes = buildPageCoalesced(chapterId, pageIndex) {
                buildPageBytes(chapterId, pageIndex, entry)
            }
            writeResponse(output, 200, "OK", "image/jpeg", bytes)
        } catch (failure: PageBuildFailure) {
            Log.w(TAG, "protected page $chapterId/$pageIndex failed at stage=${failure.stage}: ${failure.readerBody}")
            // Deterministic device-side failures (decode/stitch) render a
            // legible JPEG — most hosts surface only the HTTP code, which
            // made every earlier diagnosis trail invisible to the user.
            val image = if (failure.stage == "stitch") errorPageJpeg(failure) else null
            if (image != null) {
                writeResponse(output, 200, "OK", "image/jpeg", image)
            } else {
                writeResponse(output, 502, "Bad Gateway", "text/plain", failure.readerBody.toByteArray(), stage = failure.stage, diag = failure.diag)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "protected page $chapterId/$pageIndex dispatch failed: ${t.javaClass.simpleName}")
            writeResponse(output, 502, "Bad Gateway", "text/plain", "page preparation failed (${t.javaClass.simpleName})".toByteArray())
        }
    }

    /** The full plan -> pieces -> stitch pipeline for one page, throwing
     * [PageBuildFailure] with the reader-visible body. Shared by the
     * request path and the background preloader; runs ONCE per (chapter,
     * page) even under concurrent host fetches. */
    private fun buildPageBytes(chapterId: String, pageIndex: Int, entry: MapCache.Entry): ByteArray {
        val diag = PageDiag()
        var stage = "plan"
        try {
            val planned = planForPage(chapterId, entry, diag)
            stage = "pieces"
            val staged = downloadPiecesWithHeal(chapterId, entry, planned, diag)
            stage = "stitch"
            val bytes = stitchUnderPermit(staged.planned.plan, staged.pieceData, diag)
            if (bytes != null) {
                cachePageResult(chapterId, pageIndex, bytes)
                return bytes
            }
            diag.add("stitch returned no bitmap")
            throw PageBuildFailure(
                "stitch",
                diagBody("could not assemble page (Android 12+ required for AVIF pages)", diag),
                diag,
            )
        } catch (failure: PageBuildFailure) {
            recordFailure(chapterId, pageIndex, failure.stage, failure.diag)
            throw failure
        } catch (t: Throwable) {
            val message = when (t) {
                is IOException -> t.message ?: "page preparation failed (IOException)"
                is OutOfMemoryError ->
                    "device ran out of memory assembling the page bitmap (OutOfMemoryError)"
                else -> "page preparation failed (${t.javaClass.simpleName})"
            }
            recordFailure(chapterId, pageIndex, stage, diag)
            throw PageBuildFailure(stage, diagBody(message, diag), diag, t)
        }
    }

    /** Carries everything a request (or the preloader) needs to report one
     * failed page build: the stage it died at, the capped reader-visible
     * body line and the diag trail for the header + /.diag. */
    private class PageBuildFailure(
        val stage: String,
        val readerBody: String,
        val diag: PageDiag,
        cause: Throwable? = null,
    ) : Exception(readerBody, cause)

    // ==================== in-flight page build dedup ====================

    /** The host app fires several concurrent fetches for the same page
     * (preload window + visible page + its own prefetch) and every one of
     * them used to run its own plan POST and piece downloads — a stampede
     * the plan endpoint throttles and the reader experiences as timeouts.
     * Identical concurrent requests now share one build; late joiners get
     * the finished bytes (or the same failure) without re-running anything.
     * Slots linger [INFLIGHT_GRACE_MS] after completion so stampede joiners
     * still land, then free themselves. */
    private class InFlightBuild {
        @Volatile var done = false
        var bytes: ByteArray? = null
        var failure: Throwable? = null
    }

    private val inFlightBuilds = HashMap<String, InFlightBuild>()

    private val inFlightLock = Any()

    // Kotlin does not surface Object.wait/notifyAll on Any — go through
    // java.lang.Object explicitly for the builder/joiner handshake.
    private fun Any.monitorWait(ms: Long) = (this as java.lang.Object).wait(ms)

    private fun Any.monitorNotifyAll() = (this as java.lang.Object).notifyAll()

    private fun buildPageCoalesced(chapterId: String, pageIndex: Int, build: () -> ByteArray): ByteArray {
        val key = "$chapterId/$pageIndex"
        val created = synchronized(inFlightLock) {
            val existing = inFlightBuilds[key]
            if (existing != null) return existing.join()
            val slot = InFlightBuild()
            inFlightBuilds[key] = slot
            slot
        }
        // Builder path: the monitor is NOT held while the pipeline runs.
        try {
            val bytes = build()
            synchronized(inFlightLock) {
                created.bytes = bytes
                created.done = true
                inFlightLock.monitorNotifyAll()
            }
            scheduleInFlightCleanup(key, created)
            return bytes
        } catch (t: Throwable) {
            synchronized(inFlightLock) {
                created.failure = t
                created.done = true
                inFlightLock.monitorNotifyAll()
            }
            scheduleInFlightCleanup(key, created)
            throw t
        }
    }

    private fun InFlightBuild.join(): ByteArray {
        while (!done) inFlightLock.monitorWait(1000L)
        bytes?.let { return it }
        throw failure ?: IOException("ProComic: page build failed")
    }

    private fun scheduleInFlightCleanup(key: String, slot: InFlightBuild) {
        executor.execute {
            try {
                Thread.sleep(INFLIGHT_GRACE_MS)
            } catch (_: InterruptedException) {
                // daemon pool shutting down with the process — nothing to do
            }
            synchronized(inFlightLock) {
                if (inFlightBuilds[key] === slot && slot.done) inFlightBuilds.remove(key)
            }
        }
    }

    // ====================== background pre-assembly ======================

    /** Chapters whose background pre-assembly already started (bounded). */
    private val preloadStarted = HashSet<String>()

    private val preloadLock = Any()

    /**
     * Kicked when a chapter's page list is served: builds every protected
     * page of the chapter in the background (index order, one at a time,
     * gently paced) so the finished JPEGs are sitting in the result cache
     * BEFORE the reader asks for them. The host's preload stampede then
     * hits instant 200s (or joins an in-flight build) instead of queueing
     * behind full plan+piece+stitch pipelines — the failure mode where the
     * first few pages render and every later one crosses the host's image
     * fetch timeout. Reader requests for skipped-ahead pages still build
     * concurrently: coalescing keeps everyone honest.
     */
    fun preloadChapter(chapterId: String) {
        synchronized(preloadLock) {
            if (preloadStarted.size > 64) preloadStarted.clear()
            if (!preloadStarted.add(chapterId)) return
        }
        executor.execute {
            try {
                for (index in mapCache.pageIndexes(chapterId)) {
                    if (cachedPageResult(chapterId, index) != null) continue
                    val entry = mapCache.get(chapterId, index) ?: break // chapter evicted
                    try {
                        buildPageCoalesced(chapterId, index) {
                            buildPageBytes(chapterId, index, entry)
                        }
                    } catch (t: PageBuildFailure) {
                        Log.i(TAG, "preload $chapterId/$index failed at ${t.stage}")
                    } catch (t: IOException) {
                        Log.i(TAG, "preload $chapterId/$index failed: ${t.message?.substringAfter("ProComic: ")}")
                    }
                    try {
                        Thread.sleep(PRELOAD_PAGE_PAUSE_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
            } finally {
                synchronized(preloadLock) { preloadStarted.remove(chapterId) }
            }
        }
    }

    /**
     * Deterministic device-side failures (decode/stitch) get a legible JPEG
     * instead of a bare 502: most hosts surface only the HTTP code, which
     * left every earlier diagnosis trail invisible in the reader. The drawn
     * page carries the failure reason, the attempt trail, the device build
     * and the /.diag pointer so a screenshot alone is an actionable report.
     * Never cached (a transient OOM must not stick) and JVM-safe: graphics
     * are unavailable there, runCatching returns null and the 502 path stays.
     */
    private fun errorPageJpeg(failure: PageBuildFailure): ByteArray? = runCatching {
        val width = 800
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFF8B1A1A.toInt()
            paint.textSize = 34f
            var y = 90f
            val lines = buildList {
                add("ProComic — this page failed to assemble")
                add("")
                failure.readerBody.split("; ").forEach { add(it) }
                add("")
                add("Device: Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})")
                add("Server build: procomic-stitch/v94")
                add("More: /${DIAG_PATH_PREFIX} in the app logs")
            }.flatMap { line ->
                if (line.length <= 46) listOf(line) else line.chunked(46)
            }
            for (line in lines) {
                if (y > height - 60f) break
                canvas.drawText(line, 44f, y, paint)
                y += 46f
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    /** The reader-visible failure line: the cause message first (readers
     * surface the body text), then the compact attempt trail and the diag
     * route pointer. Single line, capped so no reader UI chokes on it. */
    private fun diagBody(message: String, diag: PageDiag): String {
        val trail = diag.summary()
        val line = if (trail.isEmpty()) message else "$message [$trail; diag=/.diag]"
        return if (line.length > MAX_DIAG_BODY) line.take(MAX_DIAG_BODY - 1) + "\u2026" else line
    }

    /** A plan plus the generation its pieces were bound to when it was
     * fetched, so the stitch-stage heal retries the generations that have
     * not failed yet. */
    private class PlannedPage(val plan: PlanDto, val cdnPath: String?)

    /** Downloaded-and-validated pieces for a page, alongside the plan they
     * came from (the heal ladder may have replaced the original). */
    private class StagedPieces(val planned: PlannedPage, val pieceData: List<ByteArray>)

    /** The page's plan, served from the 90s cache when possible so reader
     * re-fetches (preload + visible-page double loads, retries) never
     * re-run the plan ladder. */
    private fun planForPage(chapterId: String, entry: MapCache.Entry, diag: PageDiag): PlannedPage {
        cachedPlan(chapterId, entry.pageIndex)?.let { return it }
        val planned = planWithHeal(chapterId, entry, diag)
        cachePlan(chapterId, entry.pageIndex, planned)
        return planned
    }

    /** Piece download + the dead-generation heal (a piece 403/404/5xx means
     * this plan's piece set is unusable — re-plan once on another generation
     * exactly like the old stitch-stage heal). Network only, no permits. */
    private fun downloadPiecesWithHeal(
        chapterId: String,
        entry: MapCache.Entry,
        planned: PlannedPage,
        diag: PageDiag,
    ): StagedPieces {
        try {
            return StagedPieces(planned, fetchPlanPieces(planned.plan, diag))
        } catch (t: IOException) {
            if (!isDeadGeneration(t)) throw t
            Log.i(TAG, "pieces dead for generation ${planned.cdnPath} (${t.message}); re-planning")
            var lastError = t
            for (candidate in healGenerations(planned.cdnPath)) {
                try {
                    val healedPlan = fetchPlanWithRetry(chapterId, entry.withCdnPath(candidate), diag, preferSession = true)
                    mapCache.rebindGeneration(chapterId, candidate)
                    val healed = PlannedPage(healedPlan, candidate)
                    cachePlan(chapterId, entry.pageIndex, healed)
                    return StagedPieces(healed, fetchPlanPieces(healed.plan, diag))
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError
        }
    }

    /** The bitmap stage under the fair stitch semaphore: decode + draw +
     * JPEG encode. Piece bytes are already in memory — no network happens
     * while the permit is held. */
    private fun stitchUnderPermit(plan: PlanDto, pieceData: List<ByteArray>, diag: PageDiag): ByteArray? {
        stitchPermits.acquire()
        try {
            return stitchToJpeg(plan, pieceData)
        } catch (t: Throwable) {
            diag.add("stitch threw ${t.javaClass.simpleName}")
            throw t
        } finally {
            stitchPermits.release()
        }
    }

    // ======================= per-page result cache =======================

    /** Finished JPEGs keyed "chapterId/pageIndex". Bounded to a handful of
     * pages (~1 MB each): enough that every page of a reader's preload
     * window that timed out on the wire is instantly available on retry,
     * without holding a chapter's worth of bitmaps-as-JPEG in memory. */
    private val pageResults = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean = size > MAX_CACHED_PAGE_RESULTS
    }

    private val pageResultsLock = Any()

    private fun cachedPageResult(chapterId: String, pageIndex: Int): ByteArray? = synchronized(pageResultsLock) {
        pageResults["$chapterId/$pageIndex"]
    }

    private fun cachePageResult(chapterId: String, pageIndex: Int, bytes: ByteArray) {
        synchronized(pageResultsLock) {
            pageResults["$chapterId/$pageIndex"] = bytes
        }
    }

    // ========================= per-page plan cache =======================

    private class CachedPlan(val planned: PlannedPage, val cachedAt: Long)

    private val planCache = HashMap<String, CachedPlan>()

    private val planCacheLock = Any()

    private fun cachedPlan(chapterId: String, pageIndex: Int): PlannedPage? = synchronized(planCacheLock) {
        val key = "$chapterId/$pageIndex"
        val cached = planCache[key] ?: return null
        if (System.currentTimeMillis() - cached.cachedAt > PLAN_CACHE_TTL_MS) {
            planCache.remove(key)
            return null
        }
        cached.planned
    }

    private fun cachePlan(chapterId: String, pageIndex: Int, planned: PlannedPage) {
        synchronized(planCacheLock) {
            if (planCache.size >= MAX_PLAN_CACHE) {
                val cutoff = System.currentTimeMillis() - PLAN_CACHE_TTL_MS
                planCache.entries.removeAll { it.value.cachedAt < cutoff }
                if (planCache.size >= MAX_PLAN_CACHE) planCache.clear()
            }
            planCache["$chapterId/$pageIndex"] = CachedPlan(planned, System.currentTimeMillis())
        }
    }

    /** Fresh map tokens (flight rebuild) invalidate every cached plan of the
     * chapter — the old plans reference dead tokens. */
    private fun invalidatePlans(chapterId: String) {
        synchronized(planCacheLock) {
            planCache.keys.removeAll { it.startsWith("$chapterId/") }
        }
    }

    /**
     * The storage generation the plan binds its piece tokens to. Live
     * chapters carry it in the flight (extractCdnPath reads it); a flight
     * without the key, a token minted across the site's generation migrations,
     * or a race between the two leaves the plan bound to a DEAD generation —
     * every piece then answers 404 {"error":"not_found"} and the reader
     * shows HTTP 502 on the pages after the public ones.
     *
     * The site is mid-migration between generations — the search API and
     * reader flights carry "cdn2" AND "cdn3" for different series (verified
     * live) — so a single hardcoded heal target can never cover the catalog.
     * The ladder tries every known generation, rebinds the chapter cache
     * only after one actually serves a plan, and — when the proxy endpoint
     * itself is failing (per-POP decryptor lag was observed live as 400
     * map_proxy_plan_failed) — falls back to decrypting the map token
     * locally, exactly like the site's own reader does.
     */
    private fun planWithHeal(chapterId: String, entry: MapCache.Entry, diag: PageDiag): PlannedPage {
        val firstFailure: IOException = try {
            return PlannedPage(fetchPlanWithRetry(chapterId, entry, diag, preferSession = true), entry.cdnPath)
        } catch (t: IOException) {
            t
        }
        Log.i(TAG, "plan failed for generation ${entry.cdnPath} (${firstFailure.message}); trying the generation ladder")
        for (candidate in healGenerations(entry.cdnPath)) {
            try {
                val healed = fetchPlanWithRetry(chapterId, entry.withCdnPath(candidate), diag, preferSession = true)
                mapCache.rebindGeneration(chapterId, candidate)
                Log.i(TAG, "plan healed with generation $candidate")
                return PlannedPage(healed, candidate)
            } catch (e: IOException) {
                diag.add("plan gen=$candidate failed (${describe(e)})")
            }
        }
        try {
            return PlannedPage(rebuildPlan(chapterId, entry), entry.cdnPath)
        } catch (t: IOException) {
            diag.add("rebuild n/a (${describe(t)})")
            Log.i(TAG, "flight rebuild failed (${t.message})")
        }
        throw firstFailure
    }

    /** Short exception description for the diag trail: HTTP codes over
     * class names when the message carries them. */
    private fun describe(t: IOException): String = t.message?.substringAfter("ProComic: ") ?: t.javaClass.simpleName

    /** Generations to try when the entry's own one failed: every generation
     * the site currently issues, skipping the one that just failed. */
    private fun healGenerations(current: String?): List<String> = listOf("cdn2", "cdn3").filter { it != current }

    private val deadPieceCodePattern = Regex("piece download failed with HTTP (\\d{3})")

    /** A piece 404 (removed generation), 403 (revoked token) or 5xx (edge
     * lost the origin) all mean this plan's piece set is unusable and a
     * re-plan with a different generation is worth one more round. */
    private fun isDeadGeneration(t: IOException): Boolean {
        val code = deadPieceCodePattern.find(t.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code == 403 || code == 404 || code in 500..599
    }

    private fun MapCache.Entry.withCdnPath(cdnPath: String) = MapCache.Entry(token, method, cdnPath, pageIndex, readerUrl)

    /**
     * Last-resort plan recovery, used only when every proxy-plan attempt
     * failed: decrypt the map token the way the site's own reader does.
     *
     * Tokens are base64url JSON `{v, m, cid, iv, tag, data}`. The v3
     * "browser_session" key comes from `GET /chapter-map-session-key/
     * {chapterId}?legacy=1` (the non-legacy variant answers 403
     * use_proxy_plan — verified live); the v2 "browser" key is derived as
     * SHA-256("prochan-browser-map:$KEY_SEED:$chapterId"). Both strings
     * were lifted from the site's own reader bundle. The plaintext is the
     * plan JSON ({dim, mode, pieces, order, rects}) the stitcher already
     * consumes.
     */
    private fun localPlan(chapterId: String, entry: MapCache.Entry): PlanDto {
        val token = try {
            b64UrlDecode(entry.token).decodeToString().parseAs<MapTokenDto>()
        } catch (t: Throwable) {
            throw IOException("ProComic: unreadable map token (${t.javaClass.simpleName})")
        }
        val iv = token.iv?.takeIf { it.isNotEmpty() }?.let(::b64UrlDecode)
            ?: throw IOException("ProComic: map token missing iv")
        val tag = token.tag?.takeIf { it.isNotEmpty() }?.let(::b64UrlDecode)
            ?: throw IOException("ProComic: map token missing tag")
        val data = token.data?.takeIf { it.isNotEmpty() }?.let(::b64UrlDecode)
            ?: throw IOException("ProComic: map token missing data")

        val key = when (token.m) {
            "browser_session" -> sessionKey(chapterId)
            "browser" -> sha256("prochan-browser-map:$KEY_SEED:$chapterId".toByteArray(Charsets.UTF_8))
            else -> throw IOException("ProComic: unsupported map method ${token.m}")
        }

        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(data + tag)
        } catch (t: Throwable) {
            throw IOException("ProComic: map decrypt failed (${t.javaClass.simpleName})")
        }
        return plaintext.decodeToString().parseAs<PlanDto>()
    }

    /**
     * Final recovery tier: rebuild the reader flight for a fresh set of map
     * tokens. The site disabled the client-side session-key endpoint (the
     * v3 key path now answers 403 `use_proxy_plan` — verified live
     * 2026-10-22), so when the proxy-plan endpoint rejects a token there is
     * no way to salvage it: the only source of a usable token is the reader
     * page itself. A fresh flight rebinds the whole chapter cache, then the
     * plan is retried with the caller's page token.
     */
    private fun rebuildPlan(chapterId: String, entry: MapCache.Entry): PlanDto {
        val rebuild = flightRebuild
            ?: throw IOException("ProComic: no flight rebuild available")
        val readerUrl = entry.readerUrl
            ?: throw IOException("ProComic: no reader url cached for the flight rebuild")
        val fresh = rebuild(readerUrl, chapterId)
            ?: throw IOException("ProComic: flight rebuild returned nothing")
        if (fresh.maps.isEmpty()) {
            throw IOException("ProComic: flight rebuild carried no maps (chapter may be Turnstile-gated)")
        }
        mapCache.putAll(
            chapterId,
            fresh.maps.mapIndexed { idx, map ->
                MapCache.Entry(map.token, map.method, fresh.cdnPath, idx, readerUrl)
            },
        )
        invalidatePlans(chapterId)
        val mine = fresh.maps.getOrNull(entry.pageIndex)
            ?: throw IOException("ProComic: flight rebuild lost page ${entry.pageIndex}")
        return fetchPlanWithRetry(
            chapterId,
            MapCache.Entry(mine.token, mine.method, fresh.cdnPath, entry.pageIndex, readerUrl),
            preferSession = true,
        )
    }

    /** The v3 session key for a chapter, cached until its server-provided
     * expiry (the endpoint rotates keys with ~1h TTLs — refetch after).
     * ONE GET per chapter is the replacement method's only key network
     * cost. The edge 403s it (`use_proxy_plan`) to OkHttp TLS fingerprints
     * in some regions/POPs (verified live 2026-10-26) while plain browser
     * clients pass — those devices get the key through the WebView tier
     * (webviewSessionKey) instead. */
    private fun sessionKey(chapterId: String): ByteArray {
        synchronized(sessionKeyLock) {
            sessionKeyCache[chapterId]?.let { cached ->
                if (cached.expiresAt > System.currentTimeMillis() + 1_000) return cached.key
                sessionKeyCache.remove(chapterId)
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/chapter-map-session-key/$chapterId?legacy=1")
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .build()

        val client = sessionClient ?: innerClient
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: session key failed with HTTP ${response.code}")
            }
            val data = response.body.string().parseAs<SessionKeyResponseDto>().data
                ?: throw IOException("ProComic: empty session key payload")
            val key = data.key?.takeIf { it.isNotEmpty() }
                ?: throw IOException("ProComic: session key missing")
            val decoded = b64UrlDecode(key)
            synchronized(sessionKeyLock) {
                if (sessionKeyCache.size >= MAX_SESSION_KEYS) sessionKeyCache.clear()
                sessionKeyCache[chapterId] = CachedSessionKey(decoded, data.expiresAt ?: 0L)
            }
            return decoded
        }
    }

    /** Base64url that tolerates missing padding (token fields carry none). */
    private fun b64UrlDecode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.NO_WRAP)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private class CachedSessionKey(val key: ByteArray, val expiresAt: Long)

    private val sessionKeyCache = HashMap<String, CachedSessionKey>()

    private val sessionKeyLock = Any()

    /**
     * Streams the CDN image behind the loopback proxy URL. The caller's own
     * headers are irrelevant by design (that is the whole point of the
     * route): the upstream request always goes out with the browser UA and
     * the site Referer. Bytes pass through untouched, Content-Length is
     * forwarded when the upstream provides one.
     */
    private fun serveImageProxy(path: String, output: java.io.OutputStream) {
        val encoded = path.removePrefix("/$IMAGE_PROXY_PATH_PREFIX/")
        val decoded = encoded.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                String(
                    android.util.Base64.decode(
                        it,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                    ),
                    Charsets.UTF_8,
                )
            }.getOrNull()
        }
        if (decoded == null || !isAllowedImageUrl(decoded)) {
            writeResponse(output, 400, "Bad Request", "text/plain", "unusable image url".toByteArray())
            return
        }

        proxyPermits.acquire()
        try {
            val request = Request.Builder()
                .url(decoded)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .header("Referer", "$baseUrl/")
                .header("User-Agent", BROWSER_UA)
                .build()
            innerClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // The 2026-10 image rework moved the page files onto
                    // cdn{1,2,3,4}.procomic.pro, whose edge answers a bare
                    // nginx 403 to non-browser TLS fingerprints — the direct
                    // fetch that used to pass now fails for whole client
                    // populations. The site's own reader survives because it
                    // re-loads every image through its same-origin signed
                    // proxy (POST /api/cdn-image/sign → /api/cdn-image?url=…
                    // &token=…&expires=…), which serves the bytes to any
                    // client. Retry through exactly that path before 502ing.
                    val upstream = runCatching { fetchSignedCdnImage(decoded) }.getOrNull()
                    if (upstream == null) {
                        writeResponse(
                            output,
                            502,
                            "Bad Gateway",
                            "text/plain",
                            "image fetch failed with HTTP ${response.code}".toByteArray(),
                            stage = "image",
                        )
                        return
                    }
                    upstream.use { signed ->
                        if (!signed.isSuccessful) {
                            writeResponse(
                                output,
                                502,
                                "Bad Gateway",
                                "text/plain",
                                "image fetch failed with HTTP ${signed.code} (signed)".toByteArray(),
                                stage = "image",
                            )
                            return
                        }
                        writeImageHead(
                            output,
                            signed.header("Content-Type") ?: "application/octet-stream",
                            signed.header("Content-Length"),
                        )
                        signed.body.source().inputStream().use { input ->
                            input.copyTo(output, COPY_BUFFER_BYTES)
                        }
                        output.flush()
                    }
                    return
                }
                writeImageHead(
                    output,
                    response.header("Content-Type") ?: "application/octet-stream",
                    response.header("Content-Length"),
                )
                response.body.source().inputStream().use { input ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
                output.flush()
            }
        } catch (t: Throwable) {
            runCatching {
                writeResponse(
                    output,
                    502,
                    "Bad Gateway",
                    "text/plain",
                    "image fetch failed (${t.javaClass.simpleName})".toByteArray(),
                    stage = "image",
                )
            }
        } finally {
            proxyPermits.release()
        }
    }

    /** Signs [imageUrl] with the site's own `POST /api/cdn-image/sign` and
     * opens the signed same-origin proxy response — null when the sign step
     * refuses (unknown host, edge hiccup); the caller keeps the 502. */
    private fun fetchSignedCdnImage(imageUrl: String): Response? {
        if (!isAllowedImageUrl(imageUrl)) return null
        val signRequest = Request.Builder()
            .url("$baseUrl/api/cdn-image/sign")
            .post(SignedImageUrlRequestDto(imageUrl).toJsonString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .build()
        innerClient.newCall(signRequest).execute().use { signResponse ->
            if (!signResponse.isSuccessful) return null
            val signature = runCatching {
                signResponse.body.string().parseAs<CdnImageSignatureDto>()
            }.getOrNull() ?: return null
            val url = "$baseUrl/api/cdn-image".toHttpUrl().newBuilder().apply {
                addQueryParameter("url", imageUrl)
                addQueryParameter("token", signature.token)
                addQueryParameter("expires", signature.expires.toString())
                if (!signature.es.isNullOrBlank()) addQueryParameter("es", signature.es)
                if (!signature.host.isNullOrBlank()) addQueryParameter("host", signature.host)
            }.build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .header("Referer", "$baseUrl/")
                .header("User-Agent", BROWSER_UA)
                .build()
            return innerClient.newCall(request).execute()
        }
    }

    /** Only site-owned image hosts, https only — the proxy is not a general
     * purpose fetcher. The bare and www hosts carry /uploads/ files too. */
    private fun isAllowedImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (
            httpUrl.host == "procomic.pro" ||
                httpUrl.host == "www.procomic.pro" ||
                httpUrl.host == "app.procomic.pro" ||
                httpUrl.host.endsWith(".procomic.pro")
            )
    }.getOrDefault(false)

    /** Response head for a streamed body: Content-Length when known, else
     * the body ends at EOF ("Connection: close" is always set). */
    private fun writeImageHead(output: java.io.OutputStream, contentType: String, contentLength: String?) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength != null) append("Content-Length: ").append(contentLength).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeResponse(
        output: java.io.OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        stage: String? = null,
        diag: PageDiag? = null,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            if (stage != null) append("X-ProComic-Stage: ").append(stage).append("\r\n")
            if (diag != null && !diag.isEmpty()) {
                append("X-ProComic-Diag: ")
                    .append(diag.summary().replace("\r", " ").replace("\n", " "))
                    .append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /**
     * One plan fetch, retried exactly the way the site's own reader does:
     * its plan runner loops the POST with sleep delays [400, 1200] and only
     * re-attempts transient failures (429 / 5xx — matching our
     * isTransientPlanFailure). We mirror that cadence, then fall through
     * to the other client tier.
     *
     * TIER ORDER — session client FIRST by default: the site's reader
     * sends every plan POST with credentials:"include" (cookies), an
     * Origin header and full sec-fetch/client-hint headers (verified in
     * the reader bundle 2026-10-25). The bare inner client sends none of
     * that and works from some regions while failing deterministically
     * from others (the device-side "pages after the public ones 502"
     * report) — the session client carries the browsing context the WAF
     * expects, plus the challenge solver for the region-dependent edge
     * behavior. preferSession=false keeps the fast bare tier first for
     * callers that already know it works (JVM harness).
     *
     * SESSION FALLBACK (preferSession=false): when the bare inner client
     * cannot get the plan through at all, the same request runs through
     * the source's session client before the failure propagates to the
     * generation ladder.
     */
    private fun fetchPlanWithRetry(
        chapterId: String,
        entry: MapCache.Entry,
        diag: PageDiag? = null,
        preferSession: Boolean = true,
    ): PlanDto {
        // v1.6.15 REPLACED METHOD — LOCAL DECRYPT FIRST. The site's own
        // reader bundle decrypts browser_session (v3) map tokens locally with
        // a session key from `GET /chapter-map-session-key/{id}?legacy=1`
        // (ONE cached call per chapter) and only POSTs proxy plans for
        // protectionV2 chapters. The v93-per-page plan POST ladder burned
        // the device's edge goodwill: pages 4+ answered 502 exactly while
        // the real reader (which never POSTs) kept working. So the plan now
        // comes from the local AES-GCM decrypt (the code that used to be the
        // last resort), with the WebView fetching the session key as a real
        // browser when OkHttp's TLS fingerprint gets 403 use_proxy_plan'd,
        // and the POST ladder demoted to the legacy fallback it is.
        try {
            return localPlanWithKeyRecovery(chapterId, entry, diag)
        } catch (e: IOException) {
            diag?.add("local ${label(entry.cdnPath)} ${describe(e)}")
            Log.i(TAG, "local plan unavailable (${e.message}); falling back to the POST ladder")
        }
        val clientTiers = if (preferSession && sessionClient != null) {
            listOf(VIA_SESSION, VIA_INNER)
        } else {
            listOf(VIA_INNER, VIA_SESSION)
        }
        var lastError: IOException? = null
        for (tier in clientTiers) {
            if (tier == VIA_SESSION && sessionClient == null) continue
            for (attempt in 0..PLAN_RETRY_DELAYS_MS.size) {
                try {
                    return fetchPlan(chapterId, entry, tier, diag)
                } catch (e: IOException) {
                    lastError = e
                    diag?.add("plan ${label(entry.cdnPath)}/${clientLabel(tier)} attempt${attempt + 1} ${describe(e)}")
                    if (!isTransientPlanFailure(e)) break // revoked token etc. — next tier
                    if (attempt < PLAN_RETRY_DELAYS_MS.size) {
                        Log.i(TAG, "plan attempt failed transiently (${e.message}); retrying")
                        Thread.sleep(PLAN_RETRY_DELAYS_MS[attempt])
                    }
                }
            }
        }
        throw lastError ?: IOException("ProComic: page plan failed (no client tier available)")
    }

    /**
     * The local decrypt with its key recovery: v2 "browser" tokens derive
     * their AES key with zero network; v3 "browser_session" tokens need the
     * session-key GET, which the edge 403s (`use_proxy_plan`) to OkHttp TLS
     * fingerprints on some regions/POPs. When that happens and a real
     * WebView is available, ONE session-key GET runs inside the WebView —
     * where the request IS the browser's own — and the answer lands in the
     * shared key cache before the decrypt is retried. The key is cached per
     * chapter until its server expiry (~1h), so the WebView cost amortizes
     * across every page of the chapter instead of running per page.
     */
    private fun localPlanWithKeyRecovery(chapterId: String, entry: MapCache.Entry, diag: PageDiag?): PlanDto = try {
        localPlan(chapterId, entry)
    } catch (e: IOException) {
        if (!isWebViewAvailable()) throw e
        diag?.add("skey webview fallback (${describe(e)})")
        val key = webviewSessionKey(chapterId, diag) ?: throw e
        Log.i(TAG, "webview session key earned (${key.size}B) for $chapterId; re-decrypting")
        localPlan(chapterId, entry)
    }

    /** Reflection bridges to the WebView client: the class touches
     * android.webkit and is EXCLUDED from the JVM harness build — there
     * Class.forName answers null and the tiers stay unavailable. On device
     * the methods are the @JvmStatic Pair<Int,String> post/get. */
    private val webviewPostMethod by lazy {
        runCatching {
            Class.forName("eu.kanade.tachiyomi.extension.ar.procomic.ProComicWebViewPlanClient")
                .getDeclaredMethod("post", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private val webviewGetMethod by lazy {
        runCatching {
            Class.forName("eu.kanade.tachiyomi.extension.ar.procomic.ProComicWebViewPlanClient")
                .getDeclaredMethod("get", String::class.java, String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private fun isWebViewAvailable(): Boolean = webviewPostMethod != null

    /**
     * One session-key GET inside the real browser engine; fills the shared
     * per-chapter key cache on success. Null on any failure (the caller
     * falls back to the POST ladder). The WebView's cookies flow back into
     * the session client jar, so the lower tiers inherit the clearance.
     */
    private fun webviewSessionKey(chapterId: String, diag: PageDiag?): ByteArray? {
        val get = webviewGetMethod ?: return null
        return try {
            val result = get.invoke(null, "$baseUrl/chapter-map-session-key/$chapterId?legacy=1", baseUrl) as Pair<*, *>
            val status = result.first as Int
            val body = result.second as String
            diag?.add("skey webview status=$status")
            if (status !in 200..299) return null
            val data = body.parseAs<SessionKeyResponseDto>().data ?: return null
            val key = data.key?.takeIf(String::isNotEmpty) ?: return null
            val decoded = b64UrlDecode(key)
            synchronized(sessionKeyLock) {
                if (sessionKeyCache.size >= MAX_SESSION_KEYS) sessionKeyCache.clear()
                sessionKeyCache[chapterId] = CachedSessionKey(decoded, data.expiresAt ?: 0L)
            }
            decoded
        } catch (t: Throwable) {
            diag?.add("skey webview threw ${t.javaClass.simpleName}")
            null
        }
    }

    private val planFailureCode = Regex("page plan failed with HTTP (\\d{3})")

    private fun isTransientPlanFailure(t: IOException): Boolean {
        val code = planFailureCode.find(t.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code == 400 || code == 408 || code == 429 || code in 500..599
    }

    private fun fetchPlan(chapterId: String, entry: MapCache.Entry, via: Int, diag: PageDiag? = null): PlanDto {
        val payloadJson = PlanRequestDto(
            token = entry.token,
            method = entry.method,
            cdnPath = entry.cdnPath,
            pageIndex = entry.pageIndex,
        ).toJsonString()

        if (via == VIA_WEBVIEW) {
            // Legacy shape kept for the VIA_WEBVIEW constant's only remaining
            // caller (the debug probes); the live ladder no longer posts
            // plans through the WebView — it GETs session keys instead
            // (localPlanWithKeyRecovery).
            val post = webviewPostMethod
                ?: throw IOException("ProComic: page plan failed with HTTP 0")
            val result = post.invoke(
                null,
                "$baseUrl/chapter-map-proxy-plan/$chapterId",
                payloadJson,
                baseUrl,
            ) as Pair<*, *>
            val status = result.first as Int
            val body = result.second as String
            diag?.add("plan ${label(entry.cdnPath)}/webview status=$status")
            if (status in 200..299) {
                return body.parseAs<PlanResponseDto>().data?.map
                    ?: throw IOException("ProComic: empty page plan")
            }
            throw IOException("ProComic: page plan failed with HTTP ${status.coerceAtLeast(0)}")
        }

        // The exact header shape of the site's own reader XHR (page-chapter
        // bundle, fetch to /chapter-map-proxy-plan): cookies come from the
        // client tier's jar (the browser sends credentials:"include"), the
        // UA is BROWSER_UA (the JWT's uah claim binds the token to it — one
        // constant across flight fetch and plan POST keeps the chain
        // consistent), and the sec-*/Origin headers close the gap a bare
        // client leaves that the WAF treats differently per region.
        val payload = payloadJson.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
            .post(payload)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .build()

        clientFor(via).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: page plan failed with HTTP ${response.code}")
            }
            val body = response.body.string()
            return body.parseAs<PlanResponseDto>().data?.map
                ?: throw IOException("ProComic: empty page plan")
        }
    }

    /** Client selectors for the plan tiers: VIA_WEBVIEW runs the POST in a
     * real WebView (device only), VIA_INNER the bare inner client,
     * VIA_SESSION the source's own client (cookies + CF solver). The
     * WebView tier precedes both; the session client is consulted after
     * the inner one failed; with none provided the plan/piece calls are
     * inner-only. */
    private fun clientFor(via: Int): OkHttpClient = if (via == VIA_SESSION) {
        sessionClient ?: innerClient
    } else {
        innerClient
    }

    private fun clientLabel(via: Int): String = when (via) {
        VIA_WEBVIEW -> "webview"
        VIA_SESSION -> "session"
        else -> "inner"
    }

    /** Generation label for the diag trail. */
    private fun label(cdnPath: String?): String = "gen=${cdnPath ?: "null"}"

    /**
     * Network-only half of the stitch: downloads every piece of [plan] and
     * validates them all before any graphics work — a dead generation
     * surfaces here as the piece-404 IOException the caller heals with a
     * re-plan, and no bitmap is ever allocated for a page that cannot be
     * assembled. No bitmaps and no permits here: many pages download their
     * pieces at the same time. Pieces run through a small fixed pool (3
     * wide): six serial downloads cost 6-15s per page — with the host's
     * image-fetch timeout on top that alone could fail the tail of a
     * preload window; three-wide cuts the wall time to ~2-5s while keeping
     * the CDN pressure polite.
     */
    private val piecePool by lazy {
        Executors.newFixedThreadPool(PIECE_PARALLELISM) { runnable ->
            Thread(runnable, "ProComicPieces").apply { isDaemon = true }
        }
    }

    private fun fetchPlanPieces(plan: PlanDto, diag: PageDiag? = null): List<ByteArray> {
        val pieceUrls = plan.order.mapNotNull { plan.pieces.getOrNull(it) }
        if (pieceUrls.isEmpty()) return emptyList()
        if (pieceUrls.size == 1) return listOf(fetchPieceBytes(pieceUrls[0], 0, diag))
        val futures = pieceUrls.mapIndexed { index, pieceUrl ->
            piecePool.submit(java.util.concurrent.Callable { fetchPieceBytes(pieceUrl, index, diag) })
        }
        // Join IN ORDER: rect mapping is positional. The original IOException
        // is rethrown as-is — its canonical message drives the dead-generation
        // heal pattern-match.
        return futures.mapIndexed { index, future ->
            try {
                future.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause
                throw when (cause) {
                    is IOException -> cause
                    is OutOfMemoryError -> cause
                    else -> IOException("ProComic: piece $index failed (${cause?.javaClass?.simpleName ?: e.javaClass.simpleName})", cause)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("ProComic: piece $index interrupted", e)
            }
        }
    }

    /**
     * Bitmap half of the stitch: decode + draw + JPEG encode. Runs under the
     * stitch semaphore (see MAX_CONCURRENT_STITCHES); piece bytes were
     * downloaded and validated before it is acquired.
     */
    private fun stitchToJpeg(plan: PlanDto, pieceData: List<ByteArray>): ByteArray? {
        val dimW = plan.dim.getOrNull(0)?.takeIf { it > 0 } ?: return null
        val dimH = plan.dim.getOrNull(1)?.takeIf { it > 0 } ?: return null

        val rects = plan.rects
            ?.takeIf { it.size == plan.order.size }
            ?: computeGridRects(plan.mode, dimW, dimH, plan.order.size)
            ?: return null

        if (pieceData.isEmpty()) return null

        val result = createStitchBitmap(dimW, dimH)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        try {
            pieceData.forEachIndexed { index, bytes ->
                val rect = rects.getOrNull(index) ?: return@forEachIndexed
                val piece = decodePiece(bytes, index)
                piece.density = Bitmap.DENSITY_NONE
                canvas.drawBitmap(
                    piece,
                    null,
                    android.graphics.RectF(
                        rect.left.toFloat(),
                        rect.top.toFloat(),
                        (rect.left + rect.width).toFloat(),
                        (rect.top + rect.height).toFloat(),
                    ),
                    null,
                )
                if (piece !== result) piece.recycle()
            }

            val output = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            return output.toByteArray()
        } finally {
            if (!result.isRecycled) result.recycle()
        }
    }

    /**
     * The stitch canvas at 800x5000 is a 16 MB ARGB_8888 allocation; on
     * memory-constrained devices that single allocation OOMs and every
     * protected page answers 502 regardless of the plan being healthy.
     * RGB_565 halves the memory (manga pages are opaque — no alpha channel
     * is needed) and only kicks in when the full-quality canvas cannot be
     * allocated at all.
     */
    private fun createStitchBitmap(width: Int, height: Int): Bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (t: OutOfMemoryError) {
        Log.w(TAG, "ARGB_8888 stitch canvas OOM (${width}x$height); falling back to RGB_565")
        Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    }

    /**
     * Piece decode, three steps: BitmapFactory (the fast path, and the only
     * one for non-AVIF pieces), the bundled SOFTWARE AVIF decoder — the
     * platform AVIF codec only ships on Android 12+ and is missing on some
     * OEM builds even after that, so libavif/dav1d carries every device the
     * host app runs on — and finally ImageDecoder (API 28+) for whatever the
     * software decoder refuses. All guarded: any decoder failure surfaces as
     * the explicit IOException the caller turns into the 502 body.
     */
    private fun decodePiece(bytes: ByteArray, index: Int): Bitmap {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        val software = runCatching {
            if (!org.aomedia.avif.android.AvifDecoder.isAvifImage(java.nio.ByteBuffer.wrap(bytes))) return@runCatching null
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            val info = org.aomedia.avif.android.AvifDecoder.Info()
            if (!org.aomedia.avif.android.AvifDecoder.getInfo(buffer, bytes.size, info)) return@runCatching null
            val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
            if (org.aomedia.avif.android.AvifDecoder.decode(java.nio.ByteBuffer.wrap(bytes), bytes.size, bitmap)) {
                bitmap
            } else {
                bitmap.recycle()
                null
            }
        }.getOrNull()
        if (software != null) return software
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (t: Throwable) {
                throw IOException("ProComic: piece $index failed to decode (${t.javaClass.simpleName})")
            }
        }
        throw IOException("ProComic: piece $index failed to decode (Android 12+ required for AVIF pages)")
    }

    /**
     * Piece fetch with the same shape as the plan fetch: one inner retry
     * for transient edge hiccups, then the session client as the fallback
     * tier. The thrown message keeps the canonical
     * "piece download failed with HTTP xxx" tail (the dead-generation heal
     * pattern-matches it) — the full trail lands in the diag record.
     */
    private fun fetchPieceBytes(pieceUrl: String, pieceIndex: Int, diag: PageDiag? = null): ByteArray {
        val host = pieceUrl.toHttpUrl().host
        try {
            return fetchPieceBytesOnce(pieceUrl, VIA_INNER)
        } catch (first: IOException) {
            diag?.add("piece#$pieceIndex/$host ${describe(first)}")
            try {
                return fetchPieceBytesOnce(pieceUrl, VIA_INNER)
            } catch (second: IOException) {
                diag?.add("piece#$pieceIndex/$host retry ${describe(second)}")
                val sessionClient = this.sessionClient ?: throw second
                Thread.sleep(PIECE_RETRY_DELAY_MS)
                try {
                    return fetchPieceBytesOnce(pieceUrl, VIA_SESSION)
                } catch (third: IOException) {
                    diag?.add("piece#$pieceIndex/$host session ${describe(third)}")
                    throw third
                }
            }
        }
    }

    private fun fetchPieceBytesOnce(pieceUrl: String, via: Int): ByteArray {
        val request = Request.Builder()
            .url(pieceUrl)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            // What a browser <img> sends for these pieces (the reader loads
            // them as Image() elements from the chapter page — img hosts are
            // a different subdomain, hence same-site, not same-origin).
            .header("sec-fetch-dest", "image")
            .header("sec-fetch-mode", "no-cors")
            .header("sec-fetch-site", "same-site")
            .build()
        clientFor(via).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: piece download failed with HTTP ${response.code}")
            }
            return response.body.bytes()
        }
    }

    /**
     * mode "grid_{cols}x{rows}" with the site's floor-and-spread remainder
     * distribution (matches the reader's own computation).
     */
    private fun computeGridRects(mode: String, dimW: Int, dimH: Int, count: Int): List<PlanRectDto>? {
        val parts = mode.split("_").getOrNull(1)?.split("x") ?: return null
        val cols = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val rows = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null

        val cellW = dimW / cols
        val cellH = dimH / rows
        val remW = dimW - cellW * cols
        val remH = dimH - cellH * rows
        val widths = List(cols) { i -> cellW + if (i < remW) 1 else 0 }
        val heights = List(rows) { i -> cellH + if (i < remH) 1 else 0 }

        val rects = mutableListOf<PlanRectDto>()
        var y = 0
        for (r in 0 until rows) {
            var x = 0
            for (c in 0 until cols) {
                rects.add(PlanRectDto(left = x, top = y, width = widths[c], height = heights[r]))
                x += widths[c]
            }
            y += heights[r]
        }
        return if (count in 1..rects.size) rects.take(count) else rects
    }

    @kotlinx.serialization.Serializable
    private class PlanRequestDto(
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val pageIndex: Int,
    )

    /** Payload of a deferred map token (base64url JSON). */
    @kotlinx.serialization.Serializable
    private class MapTokenDto(
        val v: Int? = null,
        val m: String? = null,
        val cid: Long? = null,
        val iv: String? = null,
        val tag: String? = null,
        val data: String? = null,
    )

    @kotlinx.serialization.Serializable
    private class SessionKeyDto(
        val key: String? = null,
        val expiresAt: Long? = null,
    )

    @kotlinx.serialization.Serializable
    private class SessionKeyResponseDto(
        val success: Boolean? = null,
        val data: SessionKeyDto? = null,
    )

    /** Body of `POST /api/cdn-image/sign` — the site's own image signing
     * step; the signed fetch appends token/expires (plus es/host when the
     * server sends them) to the same-origin /api/cdn-image route. */
    @kotlinx.serialization.Serializable
    private class SignedImageUrlRequestDto(
        val url: String,
    )

    @kotlinx.serialization.Serializable
    private class CdnImageSignatureDto(
        val token: String,
        val expires: Long,
        val es: String? = null,
        val host: String? = null,
    )

    companion object {
        const val PROTECTED_PATH_PREFIX = ".protected-page"
        const val IMAGE_PROXY_PATH_PREFIX = ".image-proxy"
        const val DIAG_PATH_PREFIX = ".diag"
        const val JPEG_QUALITY = 88

        /**
         * The one User-Agent the site accepts on every path (verified live:
         * its WAF answers 403 to app UAs like "Kotatsu/1.0" on both the
         * reader page and the image CDN, while browser and okhttp UAs
         * pass). Used by the extension's client interceptor and by this
         * server's inner client so the JWT's uah claim stays consistent
         * across the whole chain.
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val BACKLOG = 64
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16_384

        /** Two stitches at a time: the assembled canvas is a tall ARGB_8888
         * bitmap (16 MB at 800x5000) and the software AVIF decode is CPU
         * heavy, but the permit now covers ONLY the bitmap stage (see the
         * field docs) and createStitchBitmap falls back to RGB_565 when the
         * full-quality canvas cannot be allocated. */
        private const val MAX_CONCURRENT_STITCHES = 2

        /** Finished-page result cache (bytes) and plan cache (entries). */
        private const val MAX_CACHED_PAGE_RESULTS = 16
        private const val MAX_PLAN_CACHE = 48

        /** Parallel piece downloads per plan (pool width). */
        private const val PIECE_PARALLELISM = 3

        /** Pause between background pre-assembled pages (gentle pacing —
         * with the per-page plan POST gone (v1.6.15: local decrypt first)
         * the only per-page traffic is the piece CDN, so 250ms keeps the
         * preload window quick and polite; a full chapter still prebuilds
         * in well under a minute). */
        private const val PRELOAD_PAGE_PAUSE_MS = 250L

        /** How long a finished in-flight build slot stays joinable. */
        private const val INFLIGHT_GRACE_MS = 10_000L

        /** How long a cached plan may be reused: piece tokens are
         * short-lived, so beyond this a fresh plan is fetched. */
        private const val PLAN_CACHE_TTL_MS = 90_000L
        private const val MAX_CONCURRENT_IMAGE_PROXIES = 4
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** AES-GCM tag length in bits (the tokens carry 16-byte tags). */
        private const val GCM_TAG_BITS = 128

        /** Key-derivation seed for v2 "browser" map tokens — the exact
         * constant the site's reader bundle ships (see localPlan). */
        private const val KEY_SEED = "2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2"

        private const val MAX_SESSION_KEYS = 32
        private const val TAG = "ProComic"

        /** Backoff schedule between transient plan retries — mirrors the
         * site reader's own [400, 1200] sleep loop. */
        private val PLAN_RETRY_DELAYS_MS = longArrayOf(400L, 1200L)
        private const val PIECE_RETRY_DELAY_MS = 400L

        /** Cap on the reader-visible 502 body line. */
        private const val MAX_DIAG_BODY = 700

        /** Failure ring buffer size behind /.diag. */
        private const val MAX_FAILURE_RECORDS = 64

        /** Client selectors for the plan fetch ladder. */
        private const val VIA_INNER = 0
        private const val VIA_SESSION = 1
        private const val VIA_WEBVIEW = 2

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * A rebuilt set of deferred-media map tokens (the flight-rebuild recovery).
 * The source produces these by re-fetching the chapter's reader page; the
 * server rebinds its chapter cache with them when every plan attempt on the
 * original tokens failed.
 */
class RebuiltFlight(
    val cdnPath: String?,
    val maps: List<RebuiltMap>,
)

class RebuiltMap(
    val token: String,
    val method: String?,
)

/**
 * In-memory store for the deferred-media map tokens fetched while building
 * the page list. Keys are chapter ids; entries preserve the deferred order so
 * `pageIndex` indexes directly into the list. App process death clears the
 * cache — Mihon re-runs getPageList and repopulates it.
 */
class MapCache {
    class Entry(
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val pageIndex: Int,
        /** The chapter's site reader page — the flight-rebuild recovery
         * re-fetches it for fresh tokens when every plan attempt failed. */
        val readerUrl: String? = null,
    )

    private val cache = HashMap<String, List<Entry>>()
    private val lock = Any()

    fun putAll(chapterId: String, maps: List<Entry>) {
        synchronized(lock) {
            if (cache.size >= MAX_CHAPTERS) {
                val iter = cache.entries.iterator()
                repeat(MAX_CHAPTERS / 4) {
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
            cache[chapterId] = maps
        }
    }

    fun get(chapterId: String, pageIndex: Int): Entry? = synchronized(lock) {
        cache[chapterId]?.getOrNull(pageIndex)
    }

    /** Page indexes held for [chapterId] (background pre-assembly order). */
    fun pageIndexes(chapterId: String): List<Int> = synchronized(lock) {
        cache[chapterId]?.map { it.pageIndex } ?: emptyList()
    }

    /** Chapter ids currently holding cached maps (for /.diag). */
    fun chapterIds(): List<String> = synchronized(lock) { cache.keys.toList() }

    /** Per-page metadata for /.diag — sizes only, never the tokens. */
    fun describe(chapterId: String): List<Map<String, Any?>>? = synchronized(lock) {
        cache[chapterId]?.map { entry ->
            mapOf(
                "page" to entry.pageIndex,
                "method" to (entry.method ?: "?"),
                "cdnPath" to (entry.cdnPath ?: "?"),
                "tokenBytes" to entry.token.length,
                "hasReaderUrl" to (entry.readerUrl != null),
            )
        }
    }

    /**
     * Rewrites the storage generation of every cached entry of [chapterId].
     * The dead-generation self-heal calls this after a successful re-plan so
     * the remaining pages of the chapter skip the failed attempt entirely.
     */
    fun rebindGeneration(chapterId: String, cdnPath: String) {
        synchronized(lock) {
            val entries = cache[chapterId] ?: return
            if (entries.all { it.cdnPath == cdnPath }) return
            cache[chapterId] = entries.map { Entry(it.token, it.method, cdnPath, it.pageIndex, it.readerUrl) }
        }
    }

    private companion object {
        const val MAX_CHAPTERS = 24
    }
}
