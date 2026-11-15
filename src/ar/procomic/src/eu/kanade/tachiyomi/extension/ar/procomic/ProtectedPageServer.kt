package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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

/**
 * Compact attempt trail for one image request. The trail goes into the 502
 * body (reader-visible), the X-ProComic-Diag header, logcat and the /.diag
 * route, so a device-side failure report pinpoints the failing stage WITHOUT
 * a debugger.
 */
internal class ProxyDiag {

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

class ProComicImageServer(
    private val baseUrl: String,
    /**
     * The source's own client (cookie jar, CF solver, browser UA) used as
     * the retry tier: when the bare inner client cannot get an image
     * through, the same request is retried through this client so the site
     * sees the browsing session the reader page established.
     */
    private val sessionClient: OkHttpClient? = null,
    private val nativeCacheDir: java.io.File? = null,
    private val proxyHosts: List<String> = DEFAULT_PROXY_HOSTS,
) {

    private class Machine(val socket: ServerSocket)

    private val machine = AtomicReference<Machine?>()
    private val startLock = Any()

    private val startedAtMs = System.currentTimeMillis()

    private val avifRenderable: Boolean by lazy {
        runCatching { avifDecodable(android.os.Build.VERSION.SDK_INT) }.getOrDefault(true)
    }

    @Volatile
    var chapterPassProvider: (() -> String?)? = null

    @Volatile
    var deferredResigner: ((String) -> String?)? = null

    @Volatile
    internal var mapPlanner: ((MapPageRequest) -> MapPagePlanResult?)? = null

    internal fun codecPieceTierReady(): Boolean = false

    /** Fair 2-wide semaphore over the TRANSCODE stage only (decode + JPEG
     * encode of tall pages). Fairness keeps a reader's preloaded pages from
     * being starved by late requests; the network fetch happens OUTSIDE the
     * semaphore so a slow edge never serializes the preload window. */
    private val transcodePermits = Semaphore(MAX_CONCURRENT_TRANSCODES, true)

    private val executor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ProComicImageServer").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                    Log.w(TAG, "server thread ${t.name} died safely: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Loopback URL that serves [imageUrl] through the extension's own
     * client (browser User-Agent + site Referer + AVIF→JPEG transcode), so
     * host apps that would get 403 from the CDN with their own headers. 
     * or could not decode AVIF at all. Still load every page.
     */
    fun imageProxyUrl(imageUrl: String): String {
        if (imageUrl.startsWith("http://127.0.0.1:")) return imageUrl
        val encoded = android.util.Base64.encodeToString(
            imageUrl.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        return "http://127.0.0.1:${ensureStarted()}/$IMAGE_PROXY_PATH_PREFIX/$encoded"
    }

    fun imageProxyUrl(imageUrl: String, maxEdge: Int = 0): String {
        if (imageUrl.startsWith("http://127.0.0.1:")) return imageUrl
        val encoded = android.util.Base64.encodeToString(
            imageUrl.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val query = if (maxEdge > 0) "?e=$maxEdge" else ""
        return "http://127.0.0.1:${ensureStarted()}/$IMAGE_PROXY_PATH_PREFIX/$encoded$query"
    }

    internal fun composedProxyUrl(row: ProComicReaderSession.HarvestRow): String? {
        if (row.pieces.isEmpty()) return null
        val spec = ComposedSpec(row.w, row.h, row.pieces.map { ComposedPiece(it.u, it.x, it.y, it.w, it.h) })
        val specJson = runCatching {
            composedJson.encodeToString(ComposedSpec.serializer(), spec)
        }.getOrNull() ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(specJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
        synchronized(composedLock) {
            composedSpecs[key] = spec
            val iterator = composedSpecs.entries.iterator()
            while (iterator.hasNext() && composedSpecs.size > MAX_COMPOSED_SPECS) {
                iterator.next()
                iterator.remove()
            }
        }
        return "http://127.0.0.1:${ensureOwnedPort()}/$COMPOSED_PATH_PREFIX/$key"
    }

    /** One fetchable layer of a composed row: source + destination rect. */
    @kotlinx.serialization.Serializable
    internal data class ComposedPiece(val u: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** A whole composed row: canvas size + the piece layers. */
    @kotlinx.serialization.Serializable
    internal data class ComposedSpec(val w: Int, val h: Int, val pieces: List<ComposedPiece>)


    internal class MapPageRequest(
        val chapterId: Int,
        val pageIndex: Int,
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val referer: String,
    )

    internal class MapPagePlanResult(
        val width: Int,
        val height: Int,
        val pieces: List<String>,
        val rects: List<eu.kanade.tachiyomi.extension.ar.procomic.ChapterMapRectDto>,
    )

    private class MapPageEntry(
        val chapterId: Int,
        val pageIndex: Int,
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val referer: String,
    )

    private val mapPagesLock = Any()

    private val mapPages = LinkedHashMap<String, MapPageEntry>()

    internal fun registerMapPage(
        chapterId: Int,
        pageIndex: Int,
        token: String,
        method: String?,
        cdnPath: String?,
        referer: String,
    ): String {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$chapterId:$pageIndex:$token".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        synchronized(mapPagesLock) {
            mapPages[key] = MapPageEntry(chapterId, pageIndex, token, method, cdnPath, referer)
            val iterator = mapPages.entries.iterator()
            while (iterator.hasNext() && mapPages.size > MAX_MAP_PAGES) {
                iterator.next()
                iterator.remove()
            }
        }
        return "http://127.0.0.1:${ensureOwnedPort()}/$MAP_PAGE_PATH_PREFIX/$key"
    }

    /**
     * Serves one protected map page: plan (the source's [mapPlanner] hook
     * . The freshly-signed piece urls + rects) → fetch every piece with the
     * CHAPTER referer (the img{1-4} piece edge 403s without
     * it) → local AVIF→JPEG transcode → stitch onto the map's canvas → JPEG.
     * Coalesced per page; ONE re-plan self-heal when the short-lived piece
     * signatures lapse between listing and the first request (fresh plan,
     * only the failed slots redrawn. The site's own reader heals the same
     * way, re-planning a lapsed map mid-read). Every failure surfaces as a
     * legible 502 or a partially-blank canvas. Never a crash.
     */
    private fun serveMapPage(path: String, output: java.io.OutputStream) {
        val key = path.removePrefix("/$MAP_PAGE_PATH_PREFIX/").trim()
        val entry = synchronized(mapPagesLock) { mapPages[key] }
        if (key.isEmpty() || entry == null) {
            writeResponse(output, 410, "Gone", "text/plain", "protected page expired — reopen the chapter".toByteArray())
            return
        }
        val cacheId = "map:$key"
        cachedResult(cacheId)?.let {
            writeImageBytes(output, it.first, it.second)
            return
        }
        if (!avifRenderable && !codecPieceTierReady()) {
            writeResponse(
                output,
                410,
                "Gone",
                "text/plain",
                (
                    "protected pages need an AVIF-capable device (Android 12+) — the site serves them as AVIF pieces " +
                        "no available decoder can render below that; the framework codec tier is retired (the field " +
                        "verdict on v1.6.52: the device died inside it). Reopen the chapter — the reader session may " +
                        "capture them rendered."
                    ).toByteArray(),
                stage = "decode-wall",
            )
            return
        }
        val (contentType, bytes) = try {
            composedCoalesced(cacheId) {
                buildMapPage(entry)
            }
        } catch (failure: ImageBuildFailure) {
            Log.w(TAG, "map page $key failed at stage=${failure.stage}: ${failure.readerBody}")
            recordFailure(cacheId, failure.stage, failure.diag)
            writeResponse(
                output,
                502,
                "Bad Gateway",
                "text/plain",
                failure.readerBody.toByteArray(),
                stage = failure.stage,
                diag = failure.diag,
            )
            return
        } catch (t: Throwable) {
            Log.w(TAG, "map page $key dispatch failed: ${t.javaClass.simpleName}")
            writeResponse(
                output,
                502,
                "Bad Gateway",
                "text/plain",
                "protected page build failed (${t.javaClass.simpleName})".toByteArray(),
            )
            return
        }
        writeImageBytes(output, contentType, bytes)
    }

    /** Plan + compose one protected page. The re-plan self-heal lives
     * here (one extra planner call, failed slots only). */
    private fun buildMapPage(entry: MapPageEntry): Pair<String, ByteArray> {
        val diag = ProxyDiag()
        val planner = mapPlanner
            ?: throw ImageBuildFailure("plan", "ProComic: the reconstruction planner is unavailable", diag)
        val request = MapPageRequest(
            chapterId = entry.chapterId,
            pageIndex = entry.pageIndex,
            token = entry.token,
            method = entry.method,
            cdnPath = entry.cdnPath,
            referer = entry.referer,
        )
        var plan = runCatching { planner(request) }
            .onFailure { diag.add("plan threw: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (plan == null) {
            diag.add("plan: no answer — one re-plan before failing")
            plan = runCatching { planner(request) }.getOrNull()
        }
        if (plan == null || plan.pieces.isEmpty() || plan.pieces.size != plan.rects.size) {
            throw ImageBuildFailure(
                "plan",
                "ProComic: the protected page's reconstruction plan failed [${diag.summary()}]",
                diag,
            )
        }
        val width = plan.width.coerceIn(8, MAX_COMPOSED_EDGE)
        val height = plan.height.coerceIn(8, MAX_COMPOSED_EDGE * 2)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            throw ImageBuildFailure(
                "decode",
                "ProComic: the protected page's canvas (${plan.width}x${plan.height}) could not be allocated " +
                    "(${t.javaClass.simpleName})",
                diag,
            )
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            var failed = drawMapPieces(canvas, plan.pieces, plan.rects, null, entry.referer, diag)
            if (failed.isNotEmpty()) {
                diag.add("compose: ${failed.size}/${plan.pieces.size} pieces failed — one re-plan self-heal")
                val healed = runCatching { planner(request) }.getOrNull()
                if (healed != null && healed.pieces.size == plan.pieces.size && healed.pieces != plan.pieces) {
                    failed = drawMapPieces(canvas, healed.pieces, plan.rects, failed, entry.referer, diag)
                    if (failed.isEmpty()) {
                        diag.add("compose: the re-plan healed every failed piece")
                    } else {
                        diag.add("compose: ${failed.size} pieces still failed after the re-plan")
                    }
                } else {
                    diag.add("compose: the re-plan answered no compatible map")
                }
            }
            if (failed.size == plan.pieces.size) {
                throw ImageBuildFailure(
                    "compose",
                    "ProComic: every piece of the protected page failed [${diag.summary()}]",
                    diag,
                )
            }
            val out = ByteArrayOutputStream(plan.pieces.size * 32 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return "image/jpeg" to out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Draws [pieces] at their rects; [only] restricts the work to the
     * given slot indices (the self-heal's failed set). Returns the slots
     * that still failed (a failed piece leaves its rect blank. The page
     * stays readable). */
    private fun drawMapPieces(
        canvas: Canvas,
        pieces: List<String>,
        rects: List<ChapterMapRectDto>,
        only: Set<Int>?,
        referer: String,
        diag: ProxyDiag,
    ): Set<Int> {
        val failed = LinkedHashSet<Int>()
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        for (slot in pieces.indices) {
            if (only != null && slot !in only) continue
            val url = pieces[slot]
            val rect = rects.getOrNull(slot)
            if (rect == null || !isAllowedImageUrl(url)) {
                diag.add("piece $slot: unusable (${isAllowedImageUrl(url)})")
                failed.add(slot)
                continue
            }
            try {
                val (_, raw) = fetchPieceBytes(url, referer, diag)
                val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (decoded == null) {
                    diag.add("piece $slot: decode null")
                    failed.add(slot)
                    continue
                }
                try {
                    canvas.drawBitmap(
                        decoded,
                        null,
                        android.graphics.RectF(
                            rect.left.toFloat().coerceIn(0f, width),
                            rect.top.toFloat().coerceIn(0f, height),
                            (rect.left + rect.width).toFloat().coerceIn(0f, width),
                            (rect.top + rect.height).toFloat().coerceIn(0f, height),
                        ),
                        null,
                    )
                } finally {
                    decoded.recycle()
                }
            } catch (t: Throwable) {
                diag.add("piece $slot: ${t.message ?: t.javaClass.simpleName}".take(120))
                failed.add(slot)
            }
        }
        return failed
    }

    private fun fetchPieceBytes(pieceUrl: String, referer: String, diag: ProxyDiag): Pair<String, ByteArray> {
        val tiers = listOf(innerClient to "inner") +
            (sessionClient?.let { listOf(it to "session") } ?: emptyList())
        var last: IOException? = null
        for ((client, tag) in tiers) {
            try {
                val request = Request.Builder()
                    .url(pieceUrl)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Referer", referer)
                    .header("User-Agent", BROWSER_UA)
                    .header("sec-fetch-dest", "image")
                    .header("sec-fetch-mode", "no-cors")
                    .header("sec-fetch-site", "cross-site")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("ProComic: piece download failed with HTTP ${response.code}")
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    val raw = response.body.bytes()
                    if (isAvif(contentType, raw)) {
                        return "image/jpeg" to transcodeUnderPermit(raw, pieceUrl, diag, 0, allowWebViewDecode = true)
                    }
                    return contentType.ifBlank { "application/octet-stream" } to raw
                }
            } catch (e: IOException) {
                last = e
                diag.add("piece fetch $tag: ${describe(e)}")
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw last ?: IOException("ProComic: piece fetch interrupted")
                }
            }
        }
        throw last ?: IOException("ProComic: piece fetch failed")
    }

    private val composedLock = Any()

    private val composedJson = kotlinx.serialization.json.Json

    private val composedSpecs = LinkedHashMap<String, ComposedSpec>()

    private fun ensureStarted(): Int {
        machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
        synchronized(startLock) {
            machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
            runCatching {
                val socket = ServerSocket(PREFERRED_PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
                startAcceptor(socket)
            }.onSuccess { return PREFERRED_PORT }
            if (familyServerListening(PREFERRED_PORT)) return PREFERRED_PORT
            runCatching {
                val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
                startAcceptor(socket)
                return socket.localPort
            }.getOrElse { throw java.io.IOException("ProComic: image server could not bind any loopback port", it) }
        }
    }

    private fun ensureOwnedPort(): Int {
        machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
        synchronized(startLock) {
            machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
            runCatching {
                val socket = ServerSocket(PREFERRED_PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
                startAcceptor(socket)
            }.onSuccess { return PREFERRED_PORT }
            runCatching {
                val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
                startAcceptor(socket)
                Log.i(TAG, "preferred port busy outside this process — stateful routes own ephemeral port ${socket.localPort}")
                return socket.localPort
            }.getOrElse { throw java.io.IOException("ProComic: image server could not bind any loopback port", it) }
        }
    }

    private fun startAcceptor(socket: ServerSocket) {
        machine.set(Machine(socket))
        val acceptor = Thread({ acceptLoop(socket) }, "ProComicImageServer-accept").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                Log.w(TAG, "acceptor ${t.name} died safely: ${e.javaClass.simpleName}")
            }
        }
        acceptor.start()
    }

    /** True when 127.0.0.1:[port] answers /.diag with THIS extension's
     * server family (a sibling process of the same extension). Bounded so
     * an unrelated listener costs at most one short timeout. */
    private fun familyServerListening(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), FAMILY_PROBE_TIMEOUT_MS)
            socket.soTimeout = FAMILY_PROBE_TIMEOUT_MS
            val output = socket.getOutputStream()
            output.write("GET /.diag HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            val input = socket.getInputStream().readBytes()
            String(input, Charsets.US_ASCII).contains(SERVER_FAMILY_MARKER)
        }
    }.getOrDefault(false)

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                return // socket closed or unrecoverable; a new one starts on demand
            }
            try {
                executor.execute { handleConnection(connection) }
            } catch (_: Throwable) {
                runCatching { connection.close() }
            }
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
                val query = rawPath.substringAfter('?', "")
                val output = socket.getOutputStream()

                when (method) {
                    "GET" -> serve(path, query, output)
                    else -> writeResponse(output, 405, "Method Not Allowed", "text/plain", "method not allowed".toByteArray())
                }
            }
        } catch (_: Throwable) {
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

    private fun serve(path: String, query: String, output: java.io.OutputStream) {
        when {
            path.startsWith("/$DIAG_PATH_PREFIX") -> serveDiag(path, output)
            path.startsWith("/$IMAGE_PROXY_PATH_PREFIX/") -> serveImageProxy(path, query, output)
            path.startsWith("/$COMPOSED_PATH_PREFIX/") -> serveComposed(path, output)
            path.startsWith("/$MAP_PAGE_PATH_PREFIX/") -> serveMapPage(path, output)
            path.startsWith("/$CAPTURED_PATH_PREFIX/") -> serveCaptured(path, output)
            else -> writeResponse(output, 404, "Not Found", "text/plain", "unknown path".toByteArray())
        }
    }

    /**
     * Serves a composed reader row: fetch every piece through the normal
     * proxy ladder, draw each at its harvested rectangle, JPEG the canvas.
     * Coalesced per key; every failure surfaces as a legible 502.
     */
    private fun serveComposed(path: String, output: java.io.OutputStream) {
        val key = path.removePrefix("/$COMPOSED_PATH_PREFIX/").trim()
        val spec = synchronized(composedLock) { composedSpecs[key] }
        if (key.isEmpty() || spec == null) {
            writeResponse(output, 410, "Gone", "text/plain", "composed row expired — reopen the chapter".toByteArray())
            return
        }
        val cacheId = "cmp:$key"
        cachedResult(cacheId)?.let {
            writeImageBytes(output, it.first, it.second)
            return
        }
        if (!avifRenderable) {
            writeResponse(
                output,
                410,
                "Gone",
                "text/plain",
                "protected pages need an AVIF-capable device (Android 12+) — the site serves them as AVIF pieces no older decoder can render".toByteArray(),
                stage = "decode-wall",
            )
            return
        }
        val (contentType, bytes) = try {
            composedCoalesced(cacheId) {
                composeRow(spec)
            }
        } catch (failure: ImageBuildFailure) {
            Log.w(TAG, "composed row $key failed at stage=${failure.stage}: ${failure.readerBody}")
            recordFailure(cacheId, failure.stage, failure.diag)
            writeResponse(
                output,
                502,
                "Bad Gateway",
                "text/plain",
                failure.readerBody.toByteArray(),
                stage = failure.stage,
                diag = failure.diag,
            )
            return
        } catch (t: Throwable) {
            Log.w(TAG, "composed row $key dispatch failed: ${t.javaClass.simpleName}")
            writeResponse(
                output,
                502,
                "Bad Gateway",
                "text/plain",
                "composed row build failed (${t.javaClass.simpleName})".toByteArray(),
            )
            return
        }
        writeImageBytes(output, contentType, bytes)
    }

    /** Per-key coalescing for composed builds (same shape as the image
     * coalescer, minus the URL bookkeeping). */
    private val composedInFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Pair<String, ByteArray>>>()

    private fun composedCoalesced(cacheId: String, build: () -> Pair<String, ByteArray>): Pair<String, ByteArray> {
        while (true) {
            val existing = composedInFlight[cacheId]
            if (existing != null) {
                return runCatching { existing.get() }.getOrElse {
                    throw IOException("composed row failed: ${it.javaClass.simpleName}", it)
                }
            }
            val future = java.util.concurrent.CompletableFuture<Pair<String, ByteArray>>()
            if (composedInFlight.putIfAbsent(cacheId, future) == null) {
                try {
                    val result = build()
                    cacheResult(cacheId, result.first, result.second)
                    future.complete(result)
                    return result
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                    throw t
                } finally {
                    composedInFlight.remove(cacheId)
                }
            }
        }
    }

    /** Fetches every piece (through the full proxy ladder. Signed wraps
     * included), draws them at their harvested rects, JPEGs the canvas.
     * A single failed piece leaves its rect blank (the page stays
     * readable); a fully failed row throws. */
    private fun composeRow(spec: ComposedSpec): Pair<String, ByteArray> {
        val width = spec.w.coerceIn(8, MAX_COMPOSED_EDGE)
        val height = spec.h.coerceIn(8, MAX_COMPOSED_EDGE * 2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var drawn = 0
        val diag = ProxyDiag()
        try {
            for (piece in spec.pieces) {
                runCatching {
                    val (_, bytes) = buildImageBytes(piece.u, diag)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching
                    canvas.drawBitmap(
                        decoded,
                        null,
                        android.graphics.RectF(
                            piece.x.toFloat().coerceAtLeast(0f),
                            piece.y.toFloat().coerceAtLeast(0f),
                            (piece.x + piece.w).toFloat().coerceAtMost(width.toFloat()),
                            (piece.y + piece.h).toFloat().coerceAtMost(height.toFloat()),
                        ),
                        null,
                    )
                    drawn++
                    decoded.recycle()
                }.onFailure { t ->
                    Log.w(TAG, "composed piece ${piece.u.take(90)} failed: ${t.javaClass.simpleName}")
                }
            }
            if (drawn == 0) throw ImageBuildFailure("compose", "every piece of the row failed", diag)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return "image/jpeg" to out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }


    /** One already-decoded tile of a captured row: image bytes + dest rect. */
    internal class CapturedTileSpec(
        val bytes: ByteArray,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    /** A whole captured row: canvas size + the decoded tile layers. */
    internal class CapturedRowSpec(
        val w: Int,
        val h: Int,
        val tiles: List<CapturedTileSpec>,
    )

    private val capturedLock = Any()

    private val capturedRows = LinkedHashMap<String, CapturedRowSpec>()

    internal fun registerCapturedRow(
        chapterId: Int,
        pageIndex: Int,
        captured: ProComicReaderSession.CapturedRow,
    ): String? {
        if (captured.tiles.isEmpty()) return null
        val tiles = ArrayList<CapturedTileSpec>(captured.tiles.size)
        for (tile in captured.tiles) {
            val base64 = tile.d.substringAfter("base64,", "")
            if (base64.isEmpty()) return null
            val bytes = runCatching {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            }.getOrNull() ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_CAPTURED_TILE_BYTES) return null
            tiles.add(CapturedTileSpec(bytes, tile.x, tile.y, tile.w, tile.h))
        }
        if (tiles.isEmpty()) return null
        val key = MessageDigest.getInstance("SHA-256")
            .digest(
                "$chapterId:$pageIndex:${captured.w}x${captured.h}:${tiles.sumOf { it.bytes.size }}"
                    .toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }
            .take(24)
        synchronized(capturedLock) {
            capturedRows[key] = CapturedRowSpec(captured.w, captured.h, tiles)
            val iterator = capturedRows.entries.iterator()
            while (iterator.hasNext() && capturedRows.size > MAX_CAPTURED_ROWS) {
                iterator.next()
                iterator.remove()
            }
        }
        return "http://127.0.0.1:${ensureOwnedPort()}/$CAPTURED_PATH_PREFIX/$key"
    }

    /**
     * Serves one captured protected page: decode every tile with
     * BitmapFactory (JPEG/PNG. The capture already did the AVIF work in
     * the WebView), draw at the harvested rects, JPEG the canvas. No
     * fetch, no referer, no AVIF decoder. The one page class that renders
     * identically on every Android version. Coalesced per key; every
     * failure surfaces as a legible 502. Never a crash.
     */
    private fun serveCaptured(path: String, output: java.io.OutputStream) {
        val key = path.removePrefix("/$CAPTURED_PATH_PREFIX/").trim()
        val spec = synchronized(capturedLock) { capturedRows[key] }
        if (key.isEmpty() || spec == null) {
            writeResponse(output, 410, "Gone", "text/plain", "captured page expired — reopen the chapter".toByteArray())
            return
        }
        val cacheId = "cap:$key"
        cachedResult(cacheId)?.let {
            writeImageBytes(output, it.first, it.second)
            return
        }
        val (contentType, bytes) = try {
            composedCoalesced(cacheId) {
                buildCapturedRow(spec)
            }
        } catch (failure: ImageBuildFailure) {
            Log.w(TAG, "captured row $key failed at stage=${failure.stage}: ${failure.readerBody}")
            recordFailure(cacheId, failure.stage, failure.diag)
            writeResponse(
                output,
                502,
                "Bad Gateway",
                "text/plain",
                failure.readerBody.toByteArray(),
                stage = failure.stage,
                diag = failure.diag,
            )
            return
        } catch (t: Throwable) {
            Log.w(TAG, "captured row $key dispatch failed: ${t.javaClass.simpleName}")
            writeResponse(
                output,
                502,
                "Bad Gateway",
                "text/plain",
                "captured page build failed (${t.javaClass.simpleName})".toByteArray(),
            )
            return
        }
        writeImageBytes(output, contentType, bytes)
    }

    /** Stitches the captured tiles at their rects. Plain raster work. */
    private fun buildCapturedRow(spec: CapturedRowSpec): Pair<String, ByteArray> {
        val width = spec.w.coerceIn(8, MAX_COMPOSED_EDGE)
        val height = spec.h.coerceIn(8, MAX_COMPOSED_EDGE * 2)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            throw ImageBuildFailure(
                "compose",
                "ProComic: the captured page's canvas (${spec.w}x${spec.h}) could not be allocated " +
                    "(${t.javaClass.simpleName})",
                ProxyDiag(),
            )
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            var drawn = 0
            for (tile in spec.tiles) {
                runCatching {
                    val decoded = BitmapFactory.decodeByteArray(tile.bytes, 0, tile.bytes.size)
                        ?: return@runCatching
                    try {
                        canvas.drawBitmap(
                            decoded,
                            null,
                            android.graphics.RectF(
                                tile.x.toFloat().coerceIn(0f, width.toFloat()),
                                tile.y.toFloat().coerceIn(0f, height.toFloat()),
                                (tile.x + tile.w).toFloat().coerceIn(0f, width.toFloat()),
                                (tile.y + tile.h).toFloat().coerceIn(0f, height.toFloat()),
                            ),
                            null,
                        )
                        drawn++
                    } finally {
                        decoded.recycle()
                    }
                }
            }
            if (drawn == 0) {
                throw ImageBuildFailure("compose", "every captured tile failed to decode", ProxyDiag())
            }
            val out = ByteArrayOutputStream(spec.tiles.size * 32 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            return "image/jpeg" to out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    // ====================== image fetch + transcode ======================

    /**
     * Streams [imageUrl] through the loopback route: browser-UA fetch,
     * session-client retry, AVIF→JPEG transcode, result cache. The
     * caller's own headers are irrelevant by design (that is the whole
     * point of the route).
     */
    private fun serveImageProxy(path: String, query: String, output: java.io.OutputStream) {
        val encoded = path.removePrefix("/$IMAGE_PROXY_PATH_PREFIX/")
        val maxEdge = query.substringAfter("e=", "").substringBefore('&').toIntOrNull()
            ?.takeIf { it in MIN_DOWNSCALE_EDGE..MAX_DOWNSCALE_EDGE }
            ?: 0
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
            if (decoded != null && decoded.startsWith("http://127.0.0.1:")) {
                val route = runCatching { decoded.toHttpUrl().encodedPath }.getOrNull()
                when {
                    route != null && route.startsWith("/$MAP_PAGE_PATH_PREFIX/") -> {
                        serveMapPage(route, output)
                        return
                    }
                    route != null && route.startsWith("/$COMPOSED_PATH_PREFIX/") -> {
                        serveComposed(route, output)
                        return
                    }
                    route != null && route.startsWith("/$CAPTURED_PATH_PREFIX/") -> {
                        serveCaptured(route, output)
                        return
                    }
                }
            }
            writeResponse(output, 400, "Bad Request", "text/plain", "unusable image url".toByteArray())
            return
        }

        val key = cacheKey(decoded) + if (maxEdge > 0) "#e$maxEdge" else ""
        cachedResult(key)?.let {
            writeImageBytes(output, it.first, it.second)
            return
        }

        val diag = ProxyDiag()
        try {
            val (contentType, bytes) = buildImageCoalesced(key, decoded, diag, maxEdge)
            writeImageBytes(output, contentType, bytes)
        } catch (failure: ImageBuildFailure) {
            Log.w(TAG, "image $decoded failed at stage=${failure.stage}: ${failure.readerBody}")
            recordFailure(decoded, failure.stage, failure.diag)
            // Deterministic device-side failures (decode) render a legible
            // JPEG. Most hosts surface only the HTTP code, which made every
            // earlier diagnosis trail invisible to the user.
            val image = if (failure.stage == "decode") errorPageJpeg(failure) else null
            if (image != null) {
                writeImageBytes(output, "image/jpeg", image)
            } else {
                writeResponse(
                    output,
                    502,
                    "Bad Gateway",
                    "text/plain",
                    failure.readerBody.toByteArray(),
                    stage = failure.stage,
                    diag = failure.diag,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "image $decoded dispatch failed: ${t.javaClass.simpleName}")
            writeResponse(output, 502, "Bad Gateway", "text/plain", "image preparation failed (${t.javaClass.simpleName})".toByteArray())
        }
    }

    private class ImageBuildFailure(
        val stage: String,
        val readerBody: String,
        val diag: ProxyDiag,
        cause: Throwable? = null,
    ) : Exception(readerBody, cause)

    private fun buildImageBytes(imageUrl: String, diag: ProxyDiag, maxEdge: Int = 0): Pair<String, ByteArray> {
        if (isSignedProxyUrl(imageUrl)) {
            var stage = "signed"
            try {
                val (contentType, bytes) = buildSignedImageBytes(imageUrl, diag, maxEdge)
                stage = "cache"
                cacheResult(cacheKey(imageUrl), contentTypeServed(contentType, bytes), bytes)
                return contentTypeServed(contentType, bytes) to bytes
            } catch (failure: ImageBuildFailure) {
                throw failure
            } catch (t: Throwable) {
                val message = when (t) {
                    is IOException -> t.message ?: "image preparation failed (IOException)"
                    is OutOfMemoryError -> "device ran out of memory decoding the page (OutOfMemoryError)"
                    else -> "image preparation failed (${t.javaClass.simpleName})"
                }
                throw ImageBuildFailure(stage, diagBody(message, diag), diag, t)
            }
        }
        var stage = "fetch"
        try {
            val (contentType, raw) = fetchUpstreamWithRetry(imageUrl, diag)
            stage = "decode"
            val bytes = if (isAvif(contentType, raw)) {
                transcodeUnderPermit(raw, imageUrl, diag, maxEdge)
            } else {
                // JPEG/PNG/WebP pass through untouched. No decode cost.
                raw
            }
            stage = "cache"
            cacheResult(cacheKey(imageUrl), contentTypeServed(contentType, bytes), bytes)
            return contentTypeServed(contentType, bytes) to bytes
        } catch (failure: ImageBuildFailure) {
            throw failure
        } catch (t: Throwable) {
            val message = when (t) {
                is IOException -> t.message ?: "image preparation failed (IOException)"

                is OutOfMemoryError ->
                    "device ran out of memory decoding the page (OutOfMemoryError)"

                else -> "image preparation failed (${t.javaClass.simpleName})"
            }
            throw ImageBuildFailure(stage, diagBody(message, diag), diag, t)
        }
    }

    /** The signed url's inner target (the `url` query param) when it is a
     * WIDE-OPEN app host. Those files are served to any client (the
     * signature rides in the file name), so the wrap can be skipped
     * entirely and the plain p1-p3-style ladder used. Null for any other
     * host (cdn2 and friends stay behind the signed route). Internal +
     * pure for the harness pins. */
    internal fun signedInnerAppUrl(imageUrl: String): String? = runCatching {
        val inner = imageUrl.toHttpUrl().queryParameter("url")
            ?.takeIf { it.startsWith("https://") }
            ?: return@runCatching null
        val host = inner.toHttpUrl().host
        if (host.startsWith("app.")) inner else null
    }.getOrNull()

    private fun buildSignedImageBytes(imageUrl: String, diag: ProxyDiag, maxEdge: Int): Pair<String, ByteArray> {
        // Rung 1. Unwrap to the wide-open app host.
        signedInnerAppUrl(imageUrl)?.let { inner ->
            diag.add("signed rung 1: unwrap → ${runCatching { inner.toHttpUrl().host }.getOrDefault("?")} (wide-open app host)")
            try {
                val (ct, raw) = fetchUpstreamWithRetry(inner, diag)
                val bytes = if (isAvif(ct, raw)) transcodeUnderPermit(raw, inner, diag, maxEdge) else raw
                diag.add("signed rung 1: served ${bytes.size}B via the unwrapped app url")
                return contentTypeServed(ct, bytes) to bytes
            } catch (t: Throwable) {
                diag.add("signed rung 1 failed: ${t.message ?: t.javaClass.simpleName}")
            }
        } ?: diag.add("signed rung 1: inner host is not a wide-open app host — skipped")

        // Rung 2. Weserv fetches the signed url itself.
        diag.add("signed rung 2: weserv on the signed url")
        try {
            val jpeg = fetchProxyJpegBytes(imageUrl, raw = null, diag = diag, maxEdge = maxEdge)
            if (jpeg != null) {
                diag.add("signed rung 2: served ${jpeg.size}B via weserv")
                return "image/jpeg" to jpeg
            }
        } catch (t: Throwable) {
            diag.add("signed rung 2 threw: ${t.javaClass.simpleName}")
        }

        diag.add("signed rung 3: upstream fetch (session-first + pass)")
        val (ct, raw) = fetchUpstreamWithRetry(imageUrl, diag)
        if (!isAvif(ct, raw)) {
            diag.add("signed rung 3: served ${raw.size}B directly (${ct.ifBlank { "raster" }})")
            return contentTypeServed(ct, raw) to raw
        }
        val fresh = runCatching { deferredResigner?.invoke(imageUrl) }.getOrNull()
        if (fresh != null && fresh != imageUrl) {
            diag.add("signed rung 3: re-signed → weserv on the fresh url")
            fetchProxyJpegBytes(fresh, raw = null, diag = diag, maxEdge = maxEdge)?.let { jpeg ->
                diag.add("signed rung 3: served ${jpeg.size}B via the re-signed url")
                return "image/jpeg" to jpeg
            }
        } else {
            diag.add("signed rung 3: no fresh url (the source could not re-sign)")
        }
        transcodeUnderPermit(raw, imageUrl, diag, maxEdge).let { bytes ->
            diag.add("signed rung 3: served ${bytes.size}B via the local transcode tier")
            return "image/jpeg" to bytes
        }
    }

    private fun contentTypeServed(upstreamType: String, bytes: ByteArray): String = if (isAvif(upstreamType, bytes)) "image/jpeg" else upstreamType.ifBlank { "application/octet-stream" }

    /** The transcode stage under the fair semaphore: proxy fetch + decode
     * + JPEG encode. Raw bytes are already in memory. The proxy fetch is
     * a small network hop that shares the permit so a cover grid cannot
     * stampede the transcoder. */
    private fun transcodeUnderPermit(
        raw: ByteArray,
        imageUrl: String,
        diag: ProxyDiag,
        maxEdge: Int,
        allowWebViewDecode: Boolean = false,
    ): ByteArray {
        try {
            transcodePermits.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ImageBuildFailure("decode", "ProComic: page transcode interrupted", diag)
        }
        try {
            return avifToJpeg(raw, imageUrl, diag, maxEdge, allowWebViewDecode)
        } finally {
            transcodePermits.release()
        }
    }

    internal fun isSignedProxyUrl(imageUrl: String): Boolean = imageUrl.contains("/api/cdn-image")

    private fun fetchUpstreamOnce(imageUrl: String, client: OkHttpClient, pass: String? = null): Pair<String, ByteArray> {
        val request = Request.Builder()
            .url(imageUrl)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .header("sec-fetch-dest", "image")
            .header("sec-fetch-mode", "no-cors")
            .header("sec-fetch-site", "same-site")
            .apply {
                if (pass != null && isSignedProxyUrl(imageUrl)) header("x-chapter-pass", pass)
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: image download failed with HTTP ${response.code}")
            }
            return response.header("Content-Type").orEmpty() to response.body.bytes()
        }
    }

    private fun fetchUpstreamWithRetry(imageUrl: String, diag: ProxyDiag): Pair<String, ByteArray> {
        val host = runCatching { imageUrl.toHttpUrl().host }.getOrDefault("?")
        val signed = isSignedProxyUrl(imageUrl)
        val pass = runCatching { chapterPassProvider?.invoke() }.getOrNull()
        val tiers: List<Pair<OkHttpClient, String>> = if (signed && sessionClient != null) {
            listOf(sessionClient to "session", innerClient to "inner")
        } else {
            listOf(innerClient to "inner") + (sessionClient?.let { listOf(it to "session") } ?: emptyList())
        }
        var last: IOException? = null
        for ((client, tag) in tiers) {
            try {
                return fetchUpstreamOnce(imageUrl, client, pass)
            } catch (e: IOException) {
                last = e
                diag.add("fetch/$host $tag ${describe(e)}")
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        if (signed) {
            val fresh = runCatching { deferredResigner?.invoke(imageUrl) }.getOrNull()
            if (fresh != null && fresh != imageUrl) {
                diag.add("resign: fresh signed url minted after ${describe(last ?: IOException("fetch failed"))}")
                for ((client, tag) in tiers) {
                    try {
                        return fetchUpstreamOnce(fresh, client, pass)
                    } catch (e: IOException) {
                        last = e
                        diag.add("fetch/$host resign-$tag ${describe(e)}")
                    }
                }
            } else {
                diag.add("resign: no fresh url (the source could not re-sign)")
            }
        }
        throw last ?: IOException("ProComic: image fetch failed")
    }

    private val innerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun describe(t: IOException): String {
        val message = t.message ?: t.javaClass.simpleName
        return message.substringAfter("ProComic: ").take(48)
    }

    /** AVIF sniffing: the container is ISOBMFF. "ftyp" at offset 4 with an
     * "av" family brand (avif/avis) at 8, or one of the heic-style brands
     * the CDN sometimes puts in Content-Type. Content-Type first, magic as
     * the tiebreaker (some edges serve application/octet-stream). */
    internal fun isAvif(contentType: String, bytes: ByteArray): Boolean {
        if (contentType.contains("avif", ignoreCase = true)) return true
        if (bytes.size < 12) return false
        if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
        ) {
            return false
        }
        val brand = String(bytes, 8, 4, Charsets.US_ASCII)
        return brand.startsWith("av") || brand == "mif1" || brand == "miaf"
    }

    private fun avifToJpeg(
        bytes: ByteArray,
        imageUrl: String,
        diag: ProxyDiag,
        maxEdge: Int,
        allowWebViewDecode: Boolean = false,
    ): ByteArray {
        val quality = if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY
        val factoryBitmap = try {
            decodeWithBitmapFactory(bytes, diag, maxEdge)
        } catch (t: Throwable) {
            diag.add("bitmapfactory threw ${t.javaClass.simpleName}")
            null
        }
        factoryBitmap?.let { bitmap ->
            try {
                val output = ByteArrayOutputStream(bytes.size / 2)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IOException("ProComic: page JPEG encode failed")
                }
                return output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        val proxyBytes = try {
            fetchProxyJpegBytes(imageUrl, raw = bytes, diag = diag, maxEdge = maxEdge)
        } catch (t: Throwable) {
            diag.add("proxy tier threw ${t.javaClass.simpleName}")
            null
        }
        if (proxyBytes != null) return proxyBytes
        throw IOException("ProComic: page failed to decode (server-side transcode unavailable)")
    }

    private fun fetchProxyJpegBytes(imageUrl: String, raw: ByteArray?, diag: ProxyDiag, maxEdge: Int): ByteArray? {
        if (proxyHosts.isEmpty()) {
            diag.add("proxy: no hosts configured")
            return null
        }
        val encoded = runCatching { java.net.URLEncoder.encode(imageUrl, "UTF-8") }.getOrNull() ?: run {
            diag.add("proxy: encode failed")
            return null
        }
        val quality = if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY
        val box = proxyBoxFor(raw?.let(::proxyPixelBudgetGate), maxEdge, diag)
        for (host in proxyHosts) {
            val url = proxyTranscodeUrl(imageUrl, encoded, quality, box, host)
            for (attempt in 0..1) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", BROWSER_UA)
                        .build()
                    innerClient.newCall(request).execute().use { response ->
                        if (response.code >= 500) {
                            diag.add("proxy/$host http ${response.code}${if (attempt == 0) " — retrying" else ""}")
                            return@use
                        }
                        if (!response.isSuccessful) {
                            diag.add("proxy/$host http ${response.code}")
                            return@use
                        }
                        val body = response.body.bytes()
                        if (body.isEmpty()) {
                            diag.add("proxy/$host empty body")
                            return@use
                        }
                        // Loop guard: the proxy must hand back a decodable
                        // raster. An AVIF answer would send us in circles.
                        if (isAvif("", body)) {
                            diag.add("proxy/$host returned avif")
                            return@use
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            diag.add("proxy/$host undecodable (${bounds.outMimeType ?: "unknown"})")
                            return@use
                        }
                        diag.add("proxy/$host ok ${bounds.outWidth}x${bounds.outHeight} ${body.size}B")
                        val options = BitmapFactory.Options()
                        options.inSampleSize = when {
                            maxEdge > 0 -> sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
                            raw == null && (bounds.outWidth.toLong() * bounds.outHeight) > MAX_NATIVE_DECODE_PIXELS ->
                                sampleSizeFor(bounds.outWidth, bounds.outHeight, proxyBoxFor(bounds.outWidth to bounds.outHeight, 0, null))
                            else -> 1
                        }
                        val bitmap = BitmapFactory.decodeByteArray(body, 0, body.size, options) ?: run {
                            diag.add("proxy/$host decode null")
                            return@use
                        }
                        val output = ByteArrayOutputStream(body.size / 2)
                        try {
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                                throw IOException("ProComic: page JPEG encode failed")
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        return output.toByteArray()
                    }
                } catch (t: Throwable) {
                    diag.add("proxy/$host ${t.javaClass.simpleName}")
                    break
                }
            }
        }
        return null
    }

    /** Pixels the raw container declares (pure-Kotlin ISOBMFF parse of the
     * already-downloaded bytes), or null when the parser refuses a shape. 
     * a refusal simply means no server-side downscale, the same policy the
     * WebView tier had (a full decoder handles what the lenient parser
     * rejects). Never throws. */
    private fun proxyPixelBudgetGate(raw: ByteArray): Pair<Int, Int>? = runCatching {
        AvifMediaCodecDecoder.parseAvifImage(raw)?.let { layout -> layout.outputWidth to layout.outputHeight }
    }.getOrNull()

    /** The server-side downscale box: the cover's ?e= edge wins outright;
     * pages get a box only when the declared pixels exceed the decode
     * budget (the box caps the long edge so the proxy hands back a bitmap
     * this device can actually hold). Internal + pure for the harness. */
    internal fun proxyBoxFor(dims: Pair<Int, Int>?, maxEdge: Int, diag: ProxyDiag? = null): Int {
        if (maxEdge > 0) return maxEdge
        if (dims == null) return 0
        val (width, height) = dims
        val pixels = width.toLong() * height.toLong()
        if (pixels <= MAX_NATIVE_DECODE_PIXELS) return 0
        val scale = kotlin.math.sqrt(MAX_NATIVE_DECODE_PIXELS.toDouble() / pixels)
        val box = (maxOf(width, height) * scale).toInt().coerceIn(MIN_DOWNSCALE_EDGE, MAX_PROXY_BOX_EDGE)
        diag?.add("proxy: budget box ${box}px (${width}x$height)")
        return box
    }

    /** The weserv transcode URL (internal + pure for the harness pins):
     * url-encoded origin, forced JPEG output, the tier's quality, and the
     * server-side fit-inside box when one is set (`we` = never upscale). */
    internal fun proxyTranscodeUrl(imageUrl: String, encoded: String, quality: Int, box: Int, host: String): String {
        require(imageUrl.isNotBlank()) { "imageUrl required" }
        require(encoded.isNotBlank()) { "encoded required" }
        require(host.isNotBlank()) { "host required" }
        return buildString {
            append("https://").append(host).append("/?url=").append(encoded)
            append("&output=jpg&q=").append(quality)
            if (box > 0) append("&w=").append(box).append("&h=").append(box).append("&fit=inside&we")
        }
    }

    /** The platform BitmapFactory tier (the fast path, and the platform AVIF
     * codec on Android 12+). The bounds probe also avoids a second full
     * decode attempt when the platform has no codec for the container. */
    private fun decodeWithBitmapFactory(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val options = BitmapFactory.Options()
            if (maxEdge > 0) options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { return it }
        }
        diag.add("bitmapfactory null (platform has no avif codec)")
        return null
    }

    private fun decodeWithMediaCodecFramework(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int, pieceGated: Boolean = false): ByteArray? {
        val crashState = nativeCrashState
        if (!crashState.startsWith("ok")) {
            diag.add("mediacodec tier gated: $crashState")
            return null
        }
        val marker = attemptMarker()
        return try {
            marker?.writeText(System.currentTimeMillis().toString())
            val jpeg =
                if (pieceGated) {
                    AvifMediaCodecDecoder.decodePieceToJpeg(bytes, { row -> diag.add("mediacodec: ${row.take(48)}") }, if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY)
                } else {
                    AvifMediaCodecDecoder.decodeToJpeg(bytes, { row -> diag.add("mediacodec: ${row.take(48)}") }, if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY)
                }
            when {
                jpeg == null -> null
                maxEdge > 0 -> downscaleJpeg(jpeg, maxEdge, diag)
                else -> jpeg
            }
        } catch (t: Throwable) {
            diag.add("mediacodec threw ${t.javaClass.simpleName}")
            null
        } finally {
            // Deleted on every HANDLED outcome; only a native crash (which
            // skips this finally) leaves it stale.
            runCatching { marker?.delete() }
        }
    }

    /** JPEG downscale for the cover variant: decode → proportional scale →
     * re-encode at the cover quality. */
    private fun downscaleJpeg(jpeg: ByteArray, maxEdge: Int, diag: ProxyDiag): ByteArray? {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: run {
            diag.add("mediacodec: cover jpeg decode null")
            return null
        }
        val scaled = downscaleTo(source, maxEdge, diag)
        try {
            val output = ByteArrayOutputStream(scaled.byteCount.coerceAtLeast(16_384))
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)) {
                diag.add("mediacodec: cover jpeg encode false")
                return null
            }
            return output.toByteArray()
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    /** Largest power-of-two inSampleSize whose resulting long edge still
     * covers [maxEdge] (the decode keeps >= maxEdge pixels, halving memory
     * per step). Only used on the platform-decode path. */
    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    /** Proportional max-edge downscale for decoders that cannot subsample
     * (the software AVIF path decodes full-size first). Recycles the
     * source when a new bitmap was produced. */
    private fun downscaleTo(bitmap: Bitmap, maxEdge: Int, diag: ProxyDiag): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longEdge
        val width = (bitmap.width * scale).toInt().coerceIn(1, bitmap.width)
        val height = (bitmap.height * scale).toInt().coerceIn(1, bitmap.height)
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (t: Throwable) {
            diag.add("downscale ${t.javaClass.simpleName}")
            return bitmap
        }
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    internal fun debugMediaCodecTier(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int = 0): ByteArray? = decodeWithMediaCodecFramework(bytes, diag, maxEdge)

    internal fun evaluateNativeCrashState(): String {
        val dir = nativeCacheDir?.let { java.io.File(it, "procomic_native") }
            ?: return "ok (no tracking dir)"
        val attempt = java.io.File(dir, "decode.attempt")
        val counter = java.io.File(dir, "decode.crashes")
        if (!attempt.exists()) return "ok"
        val previous = runCatching { counter.readText().trim().toIntOrNull() ?: 0 }.getOrDefault(0)
        val count = previous + 1
        runCatching {
            counter.writeText(count.toString())
            attempt.delete()
        }
        return if (count >= MAX_NATIVE_CRASHES) {
            "disabled after $count native decode crashes (clear the source's cache to re-enable)"
        } else {
            "disabled this session after a native decode crash (crashes=$count)"
        }
    }

    private val nativeCrashState: String by lazy { evaluateNativeCrashState() }

    /** The before-decode marker file, or null when there is nowhere to
     * track (harness / restricted storage). Tracking is best-effort. */
    private fun attemptMarker(): java.io.File? {
        val base = nativeCacheDir ?: return null
        return runCatching {
            val dir = java.io.File(base, "procomic_native")
            dir.mkdirs()
            java.io.File(dir, "decode.attempt")
        }.getOrNull()
    }

    // ===================== in-flight build dedup =========================

    /** The host app fires several concurrent fetches for the same page
     * (preload window + visible page + its own prefetch); identical
     * concurrent requests share ONE fetch+transcode. Slots linger
     * [INFLIGHT_GRACE_MS] after completion so stampede joiners still land,
     * then free themselves. */
    private class InFlightBuild {
        @Volatile var done = false
        var result: Pair<String, ByteArray>? = null
        var failure: Throwable? = null
    }

    private val inFlightBuilds = HashMap<String, InFlightBuild>()

    private val inFlightLock = Any()

    // Kotlin does not surface Object.wait/notifyAll on Any. Go through
    // java.lang.Object explicitly for the builder/joiner handshake.
    private fun Any.monitorWait(ms: Long) = (this as java.lang.Object).wait(ms)

    private fun Any.monitorNotifyAll() = (this as java.lang.Object).notifyAll()

    private fun buildImageCoalesced(key: String, imageUrl: String, diag: ProxyDiag, maxEdge: Int = 0): Pair<String, ByteArray> {
        val created = synchronized(inFlightLock) {
            val existing = inFlightBuilds[key]
            if (existing != null) return existing.join()
            val slot = InFlightBuild()
            inFlightBuilds[key] = slot
            slot
        }
        // Builder path: the monitor is NOT held while the pipeline runs.
        try {
            val result = buildImageBytes(imageUrl, diag, maxEdge)
            synchronized(inFlightLock) {
                created.result = result
                created.done = true
                inFlightLock.monitorNotifyAll()
            }
            scheduleInFlightCleanup(key, created)
            return result
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

    private fun InFlightBuild.join(): Pair<String, ByteArray> {
        while (!done) inFlightLock.monitorWait(1000L)
        result?.let { return it }
        throw failure ?: IOException("ProComic: image build failed")
    }

    private fun scheduleInFlightCleanup(key: String, slot: InFlightBuild) {
        executor.execute {
            try {
                Thread.sleep(INFLIGHT_GRACE_MS)
            } catch (_: InterruptedException) {
                // daemon pool shutting down with the process. Nothing to do
            }
            synchronized(inFlightLock) {
                if (inFlightBuilds[key] === slot && slot.done) inFlightBuilds.remove(key)
            }
        }
    }

    // ==================== background pre-transcode =======================

    /** Chapters whose background pre-transcode already started (bounded). */
    private val preloadStarted = HashSet<String>()

    private val preloadLock = Any()

    /**
     * Kicked when a chapter's page list is served: fetches + transcodes
     * every page of the chapter in the background (index order, gently
     * paced) so the finished JPEGs are sitting in the result cache BEFORE
     * the reader asks for them. The host's preload stampede then hits
     * instant 200s (or joins an in-flight build) instead of queueing behind
     * full fetch+transcode pipelines.
     */
    fun preloadImages(chapterId: String, imageUrls: List<String>) {
        synchronized(preloadLock) {
            if (preloadStarted.size > 64) preloadStarted.clear()
            if (!preloadStarted.add(chapterId)) return
        }
        executor.execute {
            try {
                for (imageUrl in imageUrls) {
                    val key = cacheKey(imageUrl)
                    if (cachedResult(key) != null) continue
                    try {
                        buildImageCoalesced(key, imageUrl, ProxyDiag())
                    } catch (t: ImageBuildFailure) {
                        Log.i(TAG, "preload $chapterId failed at ${t.stage}")
                    } catch (t: IOException) {
                        Log.i(TAG, "preload $chapterId failed: ${t.message?.substringAfter("ProComic: ")}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "preload $chapterId page died safely: ${t.javaClass.simpleName}")
                    }
                    try {
                        Thread.sleep(PRELOAD_PAGE_PAUSE_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
            } catch (outer: Throwable) {
                Log.w(TAG, "preload $chapterId stopped safely: ${outer.javaClass.simpleName}")
            } finally {
                synchronized(preloadLock) { preloadStarted.remove(chapterId) }
            }
        }
    }

    // ======================== result cache ===============================

    private val results = LinkedHashMap<String, Pair<String, ByteArray>>(16, 0.75f, true)

    /** Total bytes held in [results]; accessed only under [resultsLock]. */
    private var cachedBytes = 0L

    private val resultsLock = Any()

    private fun cacheKey(imageUrl: String): String = MessageDigest.getInstance("SHA-256").digest(imageUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cachedResult(key: String): Pair<String, ByteArray>? = synchronized(resultsLock) {
        results[key]
    }

    private fun cacheResult(key: String, contentType: String, bytes: ByteArray) {
        synchronized(resultsLock) {
            results[key]?.let { existing -> cachedBytes -= existing.second.size }
            results[key] = contentType to bytes
            cachedBytes += bytes.size
            // Evict least-recently-used entries while over either bound.
            val iterator = results.entries.iterator()
            while (iterator.hasNext() && (results.size > MAX_CACHED_RESULTS || cachedBytes > MAX_CACHED_BYTES)) {
                cachedBytes -= iterator.next().value.second.size
                iterator.remove()
            }
        }
    }

    // ============================ diagnostics ============================

    /** Failure ring buffer feeding /.diag. The last [MAX_FAILURE_RECORDS]
     * image failures across all chapters, newest last. */
    private class FailureRecord(
        val imageUrl: String,
        val stage: String,
        val attempts: String,
        val at: Long,
    )

    private val failures = ArrayDeque<FailureRecord>()

    private val failuresLock = Any()

    private class DeferredRecord(
        val chapterId: Int,
        val trail: List<String>,
        val at: Long,
    )

    @Volatile private var lastDeferred: DeferredRecord? = null

    /** Mirrors the deferred-media attempt trail into /.diag state.
     * JVM-safe, never throws (the extension calls it from pageList). */
    internal fun recordDeferredTrail(chapterId: Int, trail: List<String>) {
        if (trail.isEmpty()) return
        try {
            lastDeferred = DeferredRecord(chapterId, trail.takeLast(12), System.currentTimeMillis())
        } catch (_: Throwable) {
        }
    }

    private fun recordFailure(imageUrl: String, stage: String, diag: ProxyDiag) {
        if (diag.isEmpty()) return
        synchronized(failuresLock) {
            if (failures.size >= MAX_FAILURE_RECORDS) failures.removeFirst()
            failures.addLast(FailureRecord(imageUrl, stage, diag.summary(), System.currentTimeMillis()))
        }
    }

    /**
     * `/.diag`. JSON state of the image server for remote diagnosis: the
     * result cache size and the last failures with their full attempt
     * trails. Plain JSON on purpose: the user can paste it as-is.
     */
    private fun serveDiag(path: String, output: java.io.OutputStream) {
        val body = try {
            buildDiagJson()
        } catch (t: Throwable) {
            "{\"error\":\"diag failed: ${t.javaClass.simpleName}\"}"
        }
        writeResponse(output, 200, "OK", "application/json", body.toByteArray())
    }

    private fun buildDiagJson(): String {
        val sb = StringBuilder(1024)
        sb.append('{')
        sb.append("\"server\":\"procomic-image\"")
        sb.append(",\"uptimeMs\":").append(System.currentTimeMillis() - startedAtMs)
        synchronized(resultsLock) {
            sb.append(",\"cachedResults\":").append(results.size)
            sb.append(",\"cachedBytes\":").append(cachedBytes)
        }
        sb.append(",\"decoder\":\"bitmapfactory+server-proxy (v111, no on-device AVIF decoder)\"")
        sb.append(",\"proxyHosts\":\"").append(proxyHosts.joinToString(",").replace("\"", "'")).append('\"')
        sb.append(",\"nativeCrashState\":\"").append(nativeCrashState.replace("\"", "'")).append('\"')
        lastDeferred?.let { d ->
            sb.append(",\"lastDeferred\":{\"at\":").append(d.at)
                .append(",\"chapterId\":").append(d.chapterId)
                .append(",\"trail\":[")
            d.trail.forEachIndexed { ti, t ->
                if (ti > 0) sb.append(',')
                sb.append('"').append(t.take(220).replace("\"", "'")).append('"')
            }
            sb.append(']').append('}')
        }
        sb.append(",\"recentFailures\":[")
        val records = synchronized(failuresLock) { failures.toList() }
        records.forEachIndexed { fi, f ->
            if (fi > 0) sb.append(',')
            sb.append("{\"at\":").append(f.at)
                .append(",\"url\":\"").append(f.imageUrl.take(160).replace("\"", "'"))
                .append("\",\"stage\":\"").append(f.stage)
                .append("\",\"attempts\":\"").append(f.attempts.replace("\"", "'"))
                .append("\"}")
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }

    /**
     * Deterministic device-side failures (decode) get a legible JPEG
     * instead of a bare 502: most hosts surface only the HTTP code, which
     * left every earlier diagnosis trail invisible in the reader. The drawn
     * page carries the failure reason, the attempt trail and the /.diag
     * pointer so a screenshot alone is an actionable report. Never cached
     * (a transient OOM must not stick) and JVM-safe: graphics are
     * unavailable there, runCatching returns null and the 502 path stays.
     */
    private fun errorPageJpeg(failure: ImageBuildFailure): ByteArray? = runCatching {
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
                add("ProComic — this page failed to render")
                add("")
                failure.readerBody.split("; ").forEach { add(it) }
                add("")
                add("Device: Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})")
                add("Server build: procomic-image")
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
    private fun diagBody(message: String, diag: ProxyDiag): String {
        val trail = diag.summary()
        val line = if (trail.isEmpty()) message else "$message [$trail; diag=/.diag]"
        return if (line.length > MAX_DIAG_BODY) line.take(MAX_DIAG_BODY - 1) + "\u2026" else line
    }

    private fun isAllowedImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (
            httpUrl.host == "procomic.net" ||
                httpUrl.host == "www.procomic.net" ||
                httpUrl.host == "procomic.pro" ||
                httpUrl.host == "www.procomic.pro" ||
                httpUrl.host == "prochan.net" ||
                httpUrl.host == "www.prochan.net" ||
                httpUrl.host == "procomic.im" ||
                httpUrl.host == "www.procomic.im" ||
                httpUrl.host.endsWith(".procomic.net") ||
                httpUrl.host.endsWith(".procomic.pro") ||
                httpUrl.host.endsWith(".prochan.net") ||
                httpUrl.host.endsWith(".procomic.im")
            )
    }.getOrDefault(false)

    private fun writeImageBytes(output: java.io.OutputStream, contentType: String, bytes: ByteArray) {
        writeResponse(output, 200, "OK", contentType, bytes)
    }

    private fun writeResponse(
        output: java.io.OutputStream,
        code: Int,
        message: String,
        contentType: String,
        body: ByteArray,
        stage: String? = null,
        diag: ProxyDiag? = null,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            if (stage != null) append("X-ProComic-Stage: ").append(stage).append("\r\n")
            if (diag != null && !diag.isEmpty()) {
                append("X-ProComic-Diag: ").append(diag.summary().take(300)).append("\r\n")
            }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    internal fun debugFetchUpstream(imageUrl: String): Pair<String, ByteArray> = fetchUpstreamWithRetry(imageUrl, ProxyDiag())

    internal fun debugCachedCount(): Int = synchronized(resultsLock) { results.size }

    internal fun debugCachedBytes(): Long = synchronized(resultsLock) { cachedBytes }

    /** Harness access to the crash-loop breaker state machine. */
    internal fun debugCrashLoopState(): String = evaluateNativeCrashState()

    companion object {
        const val IMAGE_PROXY_PATH_PREFIX = ".image-proxy"
        const val COMPOSED_PATH_PREFIX = ".composed"
        const val DIAG_PATH_PREFIX = ".diag"
        const val MAP_PAGE_PATH_PREFIX = ".map-page"

        const val CAPTURED_PATH_PREFIX = ".captured"

        /** Bounded registry for composed-row specs (one chapter ~= 40 rows). */
        private const val MAX_COMPOSED_SPECS = 256

        private const val MAX_MAP_PAGES = 512

        private const val MAX_CAPTURED_ROWS = 256

        private const val MAX_CAPTURED_TILE_BYTES = 6 * 1024 * 1024

        /** Composed canvas bounds (a reader row is ~800x1300 CSS px). */
        private const val MAX_COMPOSED_EDGE = 2600
        const val JPEG_QUALITY = 88

        /**
         * The one User-Agent the site accepts on every path. The WAF
         * its WAF answers 403 to app UAs like "Kotatsu/1.0" on both the
         * reader page and the image CDN. Including the new app.<host>
         * files. While browser and okhttp UAs pass). Used by the
         * extension's client interceptor and by this server's inner client
         * so every hop presents the same "browser".
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val BACKLOG = 64
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16_384

        private const val PREFERRED_PORT = 44097

        /** The /.diag substring that identifies a sibling process of this
         * extension's image server (version-agnostic on purpose). */
        private const val SERVER_FAMILY_MARKER = "procomic-image"

        internal fun avifDecodable(sdkInt: Int): Boolean = sdkInt >= 31

        internal fun codecTierPlatformSupported(sdkInt: Int): Boolean = sdkInt >= 29

        private const val FAMILY_PROBE_TIMEOUT_MS = 750

        /** Two transcodes at a time: the decode target is a tall ARGB_8888
         * bitmap (up to ~26 MB at 800x8000) and the software AVIF decode is
         * CPU heavy; the permit covers ONLY the bitmap stage and the decode
         * target falls back to RGB_565 when the full-quality allocation
         * cannot be made. */
        private const val MAX_CONCURRENT_TRANSCODES = 2

        private const val MAX_CACHED_RESULTS = 24

        private const val MAX_CACHED_BYTES = 32L * 1024 * 1024

        const val COVER_PROXY_MAX_EDGE = 1280
        private const val MIN_DOWNSCALE_EDGE = 64
        private const val MAX_DOWNSCALE_EDGE = 4096
        private const val COVER_JPEG_QUALITY = 82

        private const val MAX_NATIVE_DECODE_PIXELS = 25_000_000L

        /** Stale decode-attempt markers tolerated before the DEBUG-ONLY
         * MediaCodec hook's tier is disabled permanently (bounds a native
         * crash loop for the hook; the production ladder never runs it). */
        private const val MAX_NATIVE_CRASHES = 2

        val DEFAULT_PROXY_HOSTS = listOf("images.weserv.nl", "wsrv.nl")

        /** Hard ceiling for the server-side downscale box derived from the
         * pixel budget (never larger than the cover edge cap allows). */
        private const val MAX_PROXY_BOX_EDGE = 8000

        /** Pause between background pre-transcoded pages (gentle pacing. 
         * with one GET per page a full chapter prebuilds in seconds). */
        private const val PRELOAD_PAGE_PAUSE_MS = 250L

        /** How long a finished in-flight build slot stays joinable. */
        private const val INFLIGHT_GRACE_MS = 10_000L

        /** Delay before the session-client retry. */
        private const val RETRY_DELAY_MS = 400L

        /** Cap on the reader-visible 502 body line. */
        private const val MAX_DIAG_BODY = 700

        /** Failure ring buffer size behind /.diag. */
        private const val MAX_FAILURE_RECORDS = 64

        private val TAG = "ProComic"
    }
}
