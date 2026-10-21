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
 * Serves ProComic reader images from a loopback HTTP server.
 *
 * Two routes:
 *
 * 1. `/.protected-page/{chapterId}/{index}` — "protected" pages. The reader
 *    SSR exposes the first few pages as plain CDN URLs; the rest arrive as
 *    encrypted reconstruction "maps". The site's own client decrypts each
 *    map into a plan `{dim, mode, pieces, order, rects}` where every piece
 *    is a short-lived `/i/{token}` image on the CDN, and draws the pieces
 *    onto a canvas at their rects. The server decrypts on our behalf through
 *    `POST /chapter-map-proxy-plan/{chapterId}` (the client-side session-key
 *    path answers `use_proxy_plan`), so the flow here is:
 *
 *      getPageList -> deferred-media (map tokens, cached in memory)
 *                  -> loopback `http://127.0.0.1:{port}/.protected-page/{chapterId}/{index}` URLs
 *      server      -> proxy-plan POST (server-decrypted plan)
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
 * Piece images are AVIF: decoding requires Android 12 (API 31)+, matching
 * the site's own format choice. The image-proxy route streams bytes without
 * decoding, so it has no API-level requirement.
 */
class ProtectedPageServer(
    private val baseUrl: String,
    private val mapCache: MapCache,
) {

    private class Machine(val socket: ServerSocket)

    private val machine = AtomicReference<Machine?>()
    private val startLock = Any()
    private val stitchPermits = Semaphore(MAX_CONCURRENT_STITCHES)
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
            path.startsWith("/$PROTECTED_PATH_PREFIX/") -> serveProtectedPage(path, output)
            path.startsWith("/$IMAGE_PROXY_PATH_PREFIX/") -> serveImageProxy(path, output)
            else -> writeResponse(output, 404, "Not Found", "text/plain", "unknown path".toByteArray())
        }
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
            val plan = planWithHeal(chapterId, entry)
            stage = "stitch"
            val bytes = stitchWithHeal(chapterId, entry, plan)
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

    /**
     * The storage generation the plan binds its piece tokens to. Live
     * chapters carry it in the flight (extractCdnPath reads it); a flight
     * without the key, a token minted across the site's generation migrations,
     * or a race between the two leaves the plan bound to a DEAD generation —
     * every piece then answers 404 {"error":"not_found"} and the reader
     * shows HTTP 502 on the pages after the public ones. The current live
     * generation is the value the site's own reader hardcodes today.
     */
    private fun planWithHeal(chapterId: String, entry: MapCache.Entry): PlanDto = try {
        fetchPlan(chapterId, entry)
    } catch (t: IOException) {
        if (entry.cdnPath == HEAL_CDN_PATH) throw t
        Log.i(TAG, "plan failed for generation ${entry.cdnPath} (${t.message}); re-planning with $HEAL_CDN_PATH")
        val healed = fetchPlan(chapterId, entry.withCdnPath(HEAL_CDN_PATH))
        mapCache.rebindGeneration(chapterId, HEAL_CDN_PATH)
        healed
    }

    /** One re-plan + re-stitch when the plan's pieces turn out dead (404 not_found). */
    private fun stitchWithHeal(chapterId: String, entry: MapCache.Entry, plan: PlanDto): ByteArray? = try {
        stitchToJpeg(plan)
    } catch (t: IOException) {
        if (!isDeadGeneration(t) || entry.cdnPath == HEAL_CDN_PATH) throw t
        Log.i(TAG, "pieces dead for generation ${entry.cdnPath}; re-planning with $HEAL_CDN_PATH")
        val healed = fetchPlan(chapterId, entry.withCdnPath(HEAL_CDN_PATH))
        mapCache.rebindGeneration(chapterId, HEAL_CDN_PATH)
        stitchToJpeg(healed)
    }

    private fun isDeadGeneration(t: IOException): Boolean = t.message?.contains("piece download failed with HTTP 404") == true

    private fun MapCache.Entry.withCdnPath(cdnPath: String) = MapCache.Entry(token, method, cdnPath, pageIndex)

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

    /** Only site-owned image hosts, https only — the proxy is not a general
     * purpose fetcher. */
    private fun isAllowedImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (httpUrl.host == "app.procomic.pro" || httpUrl.host.endsWith(".procomic.pro"))
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

        // BROWSER_UA, not the host-supplied headers: the site's WAF 403s
        // non-browser User-Agents, and the JWT's uah claim binds the token
        // to the UA that fetched the reader page — one constant everywhere
        // keeps the whole chain consistent.
        val request = Request.Builder()
            .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
            .post(payload)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
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

        val pieceUrls = plan.order.mapNotNull { plan.pieces.getOrNull(it) }
        if (pieceUrls.isEmpty()) return null

        // Download and validate EVERY piece before any graphics work: a dead
        // generation surfaces here as the piece-404 IOException the caller
        // heals with a re-plan — and no bitmap is allocated for a page that
        // cannot be assembled.
        val pieceData = pieceUrls.map { pieceUrl -> fetchPieceBytes(pieceUrl) }

        val result = Bitmap.createBitmap(dimW, dimH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        try {
            pieceData.forEachIndexed { index, bytes ->
                val rect = rects.getOrNull(index) ?: return@forEachIndexed
                val piece = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

    private fun fetchPieceBytes(pieceUrl: String): ByteArray {
        val request = Request.Builder()
            .url(pieceUrl)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .build()
        innerClient.newCall(request).execute().use { response ->
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

    companion object {
        const val PROTECTED_PATH_PREFIX = ".protected-page"
        const val IMAGE_PROXY_PATH_PREFIX = ".image-proxy"
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
        private const val MAX_CONCURRENT_STITCHES = 2
        private const val MAX_CONCURRENT_IMAGE_PROXIES = 4
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** See planWithHeal / stitchWithHeal. */
        private const val HEAL_CDN_PATH = "cdn2"
        private const val TAG = "ProComic"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
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

    /**
     * Rewrites the storage generation of every cached entry of [chapterId].
     * The dead-generation self-heal calls this after a successful re-plan so
     * the remaining pages of the chapter skip the failed attempt entirely.
     */
    fun rebindGeneration(chapterId: String, cdnPath: String) {
        synchronized(lock) {
            val entries = cache[chapterId] ?: return
            if (entries.all { it.cdnPath == cdnPath }) return
            cache[chapterId] = entries.map { Entry(it.token, it.method, cdnPath, it.pageIndex) }
        }
    }

    private companion object {
        const val MAX_CHAPTERS = 24
    }
}
