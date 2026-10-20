package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Serves ProComic's "protected" reader pages from a loopback HTTP server.
 *
 * The reader SSR exposes the first few pages as plain CDN URLs; the rest
 * arrive as encrypted reconstruction "maps". The site's own client decrypts
 * each map into a plan `{dim, mode, pieces, order, rects}` where every piece
 * is a short-lived `/i/{token}` image on the CDN, and draws the pieces onto a
 * canvas at their rects. The server decrypts on our behalf through
 * `POST /chapter-map-proxy-plan/{chapterId}` (the client-side session-key
 * path answers `use_proxy_plan`), so the flow here is:
 *
 *   getPageList -> deferred-media (map tokens, cached in memory)
 *               -> loopback `http://127.0.0.1:{port}/.protected-page/{chapterId}/{index}` URLs
 *   server      -> proxy-plan POST (server-decrypted plan)
 *               -> download pieces -> stitch at rects -> JPEG response
 *
 * Why a loopback server instead of a client interceptor: host apps differ in
 * how they execute image requests. Several Mihon-compatible readers (Kotatsu's
 * Mihon-compat layer among them) run image requests through their own client
 * without the source's application interceptors, which sent the synthetic
 * protected-page URL to the site raw and got HTTP 403 back. A plain HTTP URL
 * that any HTTP client can fetch works everywhere, with identical behavior in
 * every reader and in downloaders.
 *
 * Piece images are AVIF: decoding requires Android 12 (API 31)+, matching
 * the site's own format choice.
 */
class ProtectedPageServer(
    private val baseUrl: String,
    private val mapCache: MapCache,
    private val requestHeaders: Headers,
) {

    private class Machine(val socket: ServerSocket)

    private val machine = AtomicReference<Machine?>()
    private val startLock = Any()
    private val stitchPermits = Semaphore(MAX_CONCURRENT_STITCHES)

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
        val prefix = "/$PROTECTED_PATH_PREFIX/"
        if (!path.startsWith(prefix)) {
            writeResponse(output, 404, "Not Found", "text/plain", "unknown path".toByteArray())
            return
        }

        val segments = path.removePrefix(prefix).split('/')
        val chapterId = segments.getOrNull(0).orEmpty()
        val pageIndex = segments.getOrNull(1)?.toIntOrNull()
        if (chapterId.isEmpty() || pageIndex == null) {
            writeResponse(output, 400, "Bad Request", "text/plain", "malformed page url".toByteArray())
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

        stitchPermits.acquire()
        var stage = "plan"
        try {
            val plan = fetchPlan(chapterId, entry)
            stage = "stitch"
            val bytes = stitchToJpeg(plan)
            if (bytes != null) {
                writeResponse(output, 200, "OK", "image/jpeg", bytes)
            } else {
                writeResponse(
                    output,
                    502,
                    "Bad Gateway",
                    "text/plain",
                    "could not assemble page (Android 12+ required for AVIF pages)".toByteArray(),
                    stage = "stitch",
                )
            }
        } catch (t: Throwable) {
            val message = (t as? IOException)?.message
                ?: "page preparation failed (${t.javaClass.simpleName})"
            writeResponse(output, 502, "Bad Gateway", "text/plain", message.toByteArray(), stage = stage)
        } finally {
            stitchPermits.release()
        }
    }

    private fun writeResponse(
        output: java.io.OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        stage: String? = null,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            if (stage != null) append("X-ProComic-Stage: ").append(stage).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun fetchPlan(chapterId: String, entry: MapCache.Entry): PlanDto {
        val payload = PlanRequestDto(
            token = entry.token,
            method = entry.method,
            cdnPath = entry.cdnPath,
            pageIndex = entry.pageIndex,
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
            .post(payload)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", requestHeaders["User-Agent"] ?: DEFAULT_UA)
            .build()

        innerClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: page plan failed with HTTP ${response.code}")
            }
            val body = response.body.string()
            return body.parseAs<PlanResponseDto>().data?.map
                ?: throw IOException("ProComic: empty page plan")
        }
    }

    private fun stitchToJpeg(plan: PlanDto): ByteArray? {
        val dimW = plan.dim.getOrNull(0)?.takeIf { it > 0 } ?: return null
        val dimH = plan.dim.getOrNull(1)?.takeIf { it > 0 } ?: return null

        val rects = plan.rects
            ?.takeIf { it.size == plan.order.size }
            ?: computeGridRects(plan.mode, dimW, dimH, plan.order.size)
            ?: return null

        val result = Bitmap.createBitmap(dimW, dimH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val pieceUrls = plan.order.mapNotNull { plan.pieces.getOrNull(it) }
        if (pieceUrls.isEmpty()) return null

        try {
            pieceUrls.forEachIndexed { index, pieceUrl ->
                val rect = rects.getOrNull(index) ?: return@forEachIndexed
                val piece = decodePiece(pieceUrl)
                    ?: throw IOException("ProComic: piece $index failed to decode")
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

    private fun decodePiece(pieceUrl: String): Bitmap? {
        val request = Request.Builder()
            .url(pieceUrl)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", requestHeaders["User-Agent"] ?: DEFAULT_UA)
            .build()
        innerClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: piece download failed with HTTP ${response.code}")
            }
            val bytes = response.body.bytes()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

    companion object {
        const val PROTECTED_PATH_PREFIX = ".protected-page"
        const val JPEG_QUALITY = 88

        private const val BACKLOG = 64
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16_384
        private const val MAX_CONCURRENT_STITCHES = 2

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

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

    private companion object {
        const val MAX_CHAPTERS = 24
    }
}
