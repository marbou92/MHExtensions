package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ProComicImageServer — the loopback image pipeline (reconstructed for
 * v1.6.56, behavior-faithful to the shipped v1.6.55 build).
 *
 * Every image the reader requests is served by a loopback HTTP server in
 * this process:
 *
 *   /.image-proxy/<base64url>[?e=1280]  — the COVER ROAD: fetch upstream
 *       (inner client first, then the reader session), detect AVIF, and
 *       hand the reader nothing but JPEG (the platform BitmapFactory
 *       decodes whatever the platform can, then the weserv ladder
 *       transcodes server-side; the reader NEVER sees a raw AVIF byte
 *       mislabeled as jpeg).
 *   /.map-page/<hash>        — protected pages: the reconstruction plan
 *       (freshly-signed piece urls + rects) is fetched through the
 *       source's mapPlanner hook on first request and composed onto one
 *       canvas — pieces are AVIF-detected and transcoded exactly like
 *       every other image, so the canvas only ever receives JPEG.
 *   /.composed/<hash>        — reader-session piece rows composed from the
 *       site's own signed piece urls (same transcode discipline).
 *   /.captured/<hash>        — reader-session in-page captures: JPEG tiles
 *       straight from the session's canvas, stitched JPEG→JPEG.
 *   /.diag                   — the legible state trail the /.diag log
 *       contract reads.
 *
 * v1.6.56 discipline: NOTHING in this class feeds raw AVIF to any
 * decoder without the AVIF-detection gate, and every AVIF byte is
 * transcoded to JPEG (platform on Android 12+, weserv server-side
 * otherwise) before it reaches the reader.
 */
internal class ProComicImageServer(
    private val baseUrl: String,
    private val sessionClient: OkHttpClient?,
    private val nativeCacheDir: java.io.File?,
) {

    // ------------------------------------------------------------------
    // Public API the source wires up (hooks may be null — the JVM harness
    // drives the server bare).
    // ------------------------------------------------------------------

    /** The freshest x-chapter-pass credential, read at fetch time. */
    var chapterPassProvider: (() -> String?)? = null

    /** Re-sign a rejected or expired signed url (the source's resign flow). */
    var deferredResigner: ((String) -> String?)? = null

    /** The reconstruction-plan hook (the source's planChapterMap). */
    var mapPlanner: ((MapPageRequest) -> MapPagePlanResult?)? = null

    data class MapPageRequest(
        val chapterId: Int,
        val pageIndex: Int,
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val referer: String,
    )

    data class MapPagePlanResult(
        val width: Int,
        val height: Int,
        val pieces: List<String>,
        val rects: List<ChapterMapRectDto>,
    )

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "ProComic"
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val COVER_PROXY_MAX_EDGE = 1280
        const val MAP_PAGE_PATH_PREFIX = "map-page"
        const val COMPOSED_PATH_PREFIX = "composed"

        private val PROXY_HOSTS = listOf("images.weserv.nl", "wsrv.nl")
        private val SITE_HOST_SUFFIXES = listOf(
            ".procomic.net",
            ".procomic.pro",
            ".prochan.net",
            ".procomic.im",
        )
        private val SITE_HOST_APEXES = listOf(
            "procomic.net",
            "www.procomic.net",
            "procomic.pro",
            "www.procomic.pro",
            "prochan.net",
            "www.prochan.net",
            "procomic.im",
            "www.procomic.im",
        )
        private const val PREFERRED_PORT = 44097
        private const val IMAGE_PROXY_ROUTE = "/.image-proxy/"
        private const val CACHE_MAX_ENTRIES = 24
        private const val CACHE_MAX_BYTES = 32L * 1024L * 1024L
        private const val MAP_BUDGET_PIXELS = 25_000_000.0
        private const val MAX_CANVAS_W = 2600
        private const val MAX_CANVAS_H = 5200

        /** Android 12+ ships a platform AVIF decoder the BitmapFactory
         * reaches (the decoder that already renders this extension's
         * covers and pages 1-3 on those devices). Below that nothing
         * decodes AVIF in-process. */
        fun avifDecodable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S
    }

    // ------------------------------------------------------------------
    // Clients
    // ------------------------------------------------------------------

    /** The bare inner client (no interceptors, short timeouts) — the
     * first fetch tier for upstream images and the weserv transcode. */
    private val innerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val startedAt = System.currentTimeMillis()
    private val serverRef = AtomicReference<ServerBox?>(null)
    private val serverLock = Any()

    /** Access-ordered jpeg result cache. */
    private val cache = LinkedHashMap<String, Pair<String, ByteArray>>(16, 0.75f, true)
    private var cacheBytes = 0L

    /** Single-flight builders for stateful routes. */
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Pair<String, ByteArray>>>()

    /** Decode concurrency: two transcodes at a time, fair. */
    private val decodePermits = Semaphore(2, true)

    private val executor: ExecutorService by lazy { Executors.newCachedThreadPool() }

    /** Registered map pages (stateful). */
    private val mapPages = LinkedHashMap<String, MapPageRegistration>()

    /** Registered composed piece rows (stateful). */
    private val composedRows = LinkedHashMap<String, ComposedRow>()

    /** Registered captured rows (stateful). */
    private val capturedRows = LinkedHashMap<String, CapturedRow>()

    private var lastDeferred: DeferredTrail? = null
    private val recentFailures = ArrayDeque<RecentFailure>()

    private class ServerBox(val socket: ServerSocket)
    private class DeferredTrail(val chapterId: Int, val trail: List<String>, val at: Long)
    private class RecentFailure(val url: String, val stage: String, val attempts: String, val at: Long)
    private class MapPageRegistration(
        val chapterId: Int,
        val pageIndex: Int,
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val referer: String,
    )
    private class ComposedRow(val width: Int, val height: Int, val pieces: List<PieceRect>)
    private class PieceRect(val url: String, val x: Int, val y: Int, val w: Int, val h: Int)
    private class CapturedRow(val width: Int, val height: Int, val tiles: List<TileRect>)
    private class TileRect(val jpeg: ByteArray, val x: Int, val y: Int, val w: Int, val h: Int)

    /** Per-request diag trail (surfaced on error cards + /.diag). */
    private class Diag {
        val lines = ArrayList<String>()
        val lock = Any()
        fun add(line: String) {
            synchronized(lock) {
                if (lines.size < 24) lines.add(line.take(160))
            }
        }
        fun summary(): String = synchronized(lock) { lines.joinToString("; ") }
    }

    /** A legible build failure carrying its stage + diag trail. */
    private class StageException(val stage: String, message: String, val diag: Diag?, cause: Throwable?) : IOException(message, cause)

    // ------------------------------------------------------------------
    // URL builders (the source-facing half)
    // ------------------------------------------------------------------

    /** The cover road for one upstream url (no max edge). */
    fun imageProxyUrl(url: String): String = imageProxyUrl(url, 0)

    /** The cover road for one upstream url, bounded to [maxEdge]. */
    fun imageProxyUrl(url: String, maxEdge: Int): String {
        if (url.startsWith("http://127.0.0.1:")) return url
        val encoded = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return "http://127.0.0.1:${port()}$IMAGE_PROXY_ROUTE$encoded" + if (maxEdge > 0) "?e=$maxEdge" else ""
    }

    /** Register a protected map page (stateful, own process only). */
    fun registerMapPage(
        chapterId: Int,
        pageIndex: Int,
        token: String,
        method: String?,
        cdnPath: String?,
        referer: String,
    ): String {
        val hash = sha24("$chapterId:$pageIndex:$token")
        synchronized(mapPages) {
            mapPages[hash] = MapPageRegistration(chapterId, pageIndex, token, method, cdnPath, referer)
            while (mapPages.size > 512) mapPages.remove(mapPages.keys.first())
        }
        return "http://127.0.0.1:${statefulPort()}/$MAP_PAGE_PATH_PREFIX/$hash"
    }

    /** Register a reader-session composed piece row. */
    fun composedProxyUrl(row: ProComicReaderSession.HarvestRow): String {
        if (row.pieces.isEmpty()) return imageProxyUrl(row.u ?: return imageProxyUrl(baseUrl))
        val pieces = row.pieces.map { PieceRect(it.u, it.x, it.y, it.w, it.h) }
        val spec = ComposedRow(row.w.coerceIn(8, MAX_CANVAS_W), row.h.coerceIn(8, MAX_CANVAS_H), pieces)
        val digest = sha24(jsonPayload(spec))
        synchronized(composedRows) {
            composedRows[digest] = spec
            while (composedRows.size > 256) composedRows.remove(composedRows.keys.first())
        }
        return "http://127.0.0.1:${statefulPort()}/$COMPOSED_PATH_PREFIX/$digest"
    }

    /** Register a reader-session captured row (JPEG tiles). */
    fun registerCapturedRow(chapterId: Int, index: Int, captured: ProComicReaderSession.CapturedRow): String {
        val tiles = captured.tiles.mapNotNull { tile ->
            val jpeg = runCatching {
                Base64.decode(tile.d.substringAfter("base64,", ""), Base64.DEFAULT)
            }.getOrNull()
            if (jpeg == null || jpeg.isEmpty()) null else TileRect(jpeg, tile.x, tile.y, tile.w, tile.h)
        }
        if (tiles.isEmpty()) return imageProxyUrl(baseUrl)
        val row = CapturedRow(
            captured.w.coerceIn(8, MAX_CANVAS_W),
            captured.h.coerceIn(8, MAX_CANVAS_H),
            tiles,
        )
        val digest = sha24("cap:$chapterId:$index:${row.width}x${row.height}:${tiles.sumOf { it.jpeg.size }}")
        synchronized(capturedRows) {
            capturedRows[digest] = row
            while (capturedRows.size > 256) capturedRows.remove(capturedRows.keys.first())
        }
        return "http://127.0.0.1:${statefulPort()}/.captured/$digest"
    }

    /** Warm the jpeg cache in the background (each chapter once). */
    fun preloadImages(chapterKey: String, urls: List<String>) {
        if (urls.isEmpty()) return
        synchronized(this) {
            if (!preloadedKeys.add(chapterKey)) return
            if (preloadedKeys.size > 64) preloadedKeys.clear()
        }
        executor.execute {
            urls.forEach { url ->
                runCatching {
                    val key = cacheKeyOf(url, 0)
                    if (cacheGet(key) == null) {
                        buildImage(key, url, Diag(), 0)
                    }
                }.onFailure { t ->
                    Log.i(TAG, "preload $chapterKey failed: ${t.message?.substringAfter("ProComic: ") ?: t.javaClass.simpleName}")
                }
                runCatching { Thread.sleep(250L) }
            }
        }
    }
    private val preloadedKeys = HashSet<String>()

    /** Record the deferred walk trail for /.diag. */
    fun recordDeferredTrail(chapterId: Int, trail: List<String>) {
        if (trail.isEmpty()) return
        runCatching {
            lastDeferred = DeferredTrail(chapterId, trail.takeLast(12), System.currentTimeMillis())
        }
    }

    // ------------------------------------------------------------------
    // Loopback server
    // ------------------------------------------------------------------

    private fun statefulPort(): Int = port()

    private fun port(): Int {
        serverRef.get()?.let { box ->
            if (!box.socket.isClosed) return box.socket.localPort
        }
        synchronized(serverLock) {
            serverRef.get()?.let { box ->
                if (!box.socket.isClosed) return box.socket.localPort
            }
            return try {
                startServer(ServerSocket(PREFERRED_PORT, 64, InetAddress.getByName("127.0.0.1")))
                PREFERRED_PORT
            } catch (t: Throwable) {
                if (externalInstanceAlive()) {
                    // Another live instance of this server owns the
                    // preferred port; image-proxy routes are stateless
                    // (self-contained base64) so the urls stay valid.
                    return PREFERRED_PORT
                }
                try {
                    val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
                    startServer(socket)
                    Log.i(TAG, "preferred port busy outside this process — stateful routes own ephemeral port ${socket.localPort}")
                    socket.localPort
                } catch (t2: Throwable) {
                    throw IOException("ProComic: image server could not bind any loopback port", t2)
                }
            }
        }
    }

    /** Probe 127.0.0.1:PREFERRED_PORT/.diag for a live sibling instance. */
    private fun externalInstanceAlive(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(PREFERRED_PORT), 750)
            socket.soTimeout = 750
            socket.getOutputStream().write("GET /.diag HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val body = socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
            body.contains("procomic-image")
        }
    }.getOrDefault(false)

    private fun startServer(socket: ServerSocket) {
        serverRef.set(ServerBox(socket))
        val acceptor = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrElse { return@Thread }
                runCatching {
                    executor.execute { serve(client) }
                }.onFailure {
                    runCatching { client.close() }
                }
            }
        }
        acceptor.name = "ProComicImageServer-accept"
        acceptor.isDaemon = true
        acceptor.setUncaughtExceptionHandler { thread, t ->
            Log.w(TAG, "acceptor ${thread.name} died safely: ${t.javaClass.simpleName}")
        }
        acceptor.start()
    }

    private fun serve(socket: Socket) {
        runCatching {
            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            var headerBytes = 0
            while (headerBytes < 16384) {
                val line = reader.readLine() ?: break
                headerBytes += line.length + 2
                if (line.isEmpty()) break
            }
            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val out = socket.getOutputStream()
            if (method == "GET") {
                route(path, query, out)
            } else {
                respond(out, 405, "Method Not Allowed", "text/plain", "method not allowed".toByteArray(), null, null)
            }
            socket.close()
        }
    }

    private fun route(path: String, query: String, out: OutputStream) {
        when {
            path.startsWith("/.diag") -> {
                val json = runCatching { diagJson() }
                    .getOrElse { "{\"error\":\"diag failed: ${it.javaClass.simpleName}\"}" }
                respond(out, 200, "OK", "application/json", json.toByteArray(), null, null)
            }
            path.startsWith(IMAGE_PROXY_ROUTE) -> handleImageProxy(path, query, out)
            path.startsWith("/.composed/") -> handleComposed(path, out)
            path.startsWith("/$MAP_PAGE_PATH_PREFIX/") -> handleMapPage(path, out)
            path.startsWith("/.captured/") -> handleCaptured(path, out)
            else -> respond(out, 404, "Not Found", "text/plain", "unknown path".toByteArray(), null, null)
        }
    }

    // ------------------------------------------------------------------
    // /.image-proxy — the cover road
    // ------------------------------------------------------------------

    private fun handleImageProxy(path: String, query: String, out: OutputStream) {
        val maxEdge = query.substringAfter("e=", "").substringBefore('&').toIntOrNull()
            ?.takeIf { it >= 64 && it <= 4096 } ?: 0
        val encoded = path.removePrefix(IMAGE_PROXY_ROUTE).trim().takeIf { it.isNotEmpty() }
        val upstream = encoded?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8) }.getOrNull()
        }
        if (upstream == null || !isSiteHost(upstream)) {
            respond(out, 400, "Bad Request", "text/plain", "unusable image url".toByteArray(), null, null)
            return
        }
        // The proxy can also carry a planned loopback route (the walk
        // merges simple rows over planned slots) — re-dispatch stateful.
        if (upstream.startsWith("http://127.0.0.1:")) {
            val innerPath = runCatching { upstream.toHttpUrl().encodedPath }.getOrNull()
            when {
                innerPath != null && innerPath.startsWith("/$MAP_PAGE_PATH_PREFIX/") -> return handleMapPage(innerPath, out)
                innerPath != null && innerPath.startsWith("/.composed/") -> return handleComposed(innerPath, out)
                innerPath != null && innerPath.startsWith("/.captured/") -> return handleCaptured(innerPath, out)
            }
            respond(out, 400, "Bad Request", "text/plain", "unusable image url".toByteArray(), null, null)
            return
        }
        val key = cacheKeyOf(upstream, maxEdge)
        cacheGet(key)?.let { (type, bytes) ->
            respond(out, 200, "OK", type, bytes, null, null)
            return
        }
        try {
            val pair = buildImage(key, upstream, Diag(), maxEdge)
            respond(out, 200, "OK", pair.first, pair.second, null, null)
        } catch (e: StageException) {
            Log.w(TAG, "image ${upstream.take(120)} failed at stage=${e.stage}: ${e.message}")
            recordFailure(upstream, e.stage, e.diag)
            if (e.stage == "decode") {
                errorCard(out, e.message ?: "decode failed")
            } else {
                respond(out, 502, "Bad Gateway", "text/plain", (e.message ?: "failed").toByteArray(), e.stage, e.diag)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "image ${upstream.take(120)} dispatch failed: ${t.javaClass.simpleName}")
            respond(out, 502, "Bad Gateway", "text/plain", "image preparation failed (${t.javaClass.simpleName})".toByteArray(), null, null)
        }
    }

    private fun buildImage(key: String, upstream: String, diag: Diag, maxEdge: Int): Pair<String, ByteArray> = singleFlight(key) {
        val pair: Pair<String, ByteArray> = if (upstream.contains("/api/cdn-image")) {
            signedLadder(upstream, diag, maxEdge)
        } else {
            val (contentType, bytes) = fetchUpstream(upstream, diag)
            val decoded = if (isAvif(contentType, bytes)) {
                transcode(maxEdge, diag, upstream, bytes)
            } else {
                bytes
            }
            contentTypeLabel(contentType, decoded, upstream) to decoded
        }
        cachePut(key, pair.first, pair.second)
        pair
    }

    private fun signedLadder(url: String, diag: Diag, maxEdge: Int): Pair<String, ByteArray> {
        // Rung 1: an unwrapped wide-open app-host url rides the plain
        // fetch+transcode path (the exact pages 1-3 road).
        val inner = runCatching {
            url.toHttpUrl().queryParameter("url")
        }.getOrNull()?.takeIf { it.startsWith("https://") }
        if (inner != null && runCatching { inner.toHttpUrl().host }.getOrDefault("").startsWith("app.")) {
            diag.add("signed rung 1: unwrap → app host (wide-open)")
            try {
                val (contentType, bytes) = fetchUpstream(inner, diag)
                val decoded = if (isAvif(contentType, bytes)) transcode(maxEdge, diag, inner, bytes) else bytes
                diag.add("signed rung 1: served ${decoded.size}B via the unwrapped app url")
                return contentTypeLabel(contentType, decoded, inner) to decoded
            } catch (t: Throwable) {
                diag.add("signed rung 1 failed: ${t.message ?: t.javaClass.simpleName}")
            }
        } else {
            diag.add("signed rung 1: inner host is not a wide-open app host — skipped")
        }
        // Rung 2: weserv on the signed url itself (signed cdn-image urls
        // answer bare — live-proven).
        diag.add("signed rung 2: weserv on the signed url")
        weservLadder(maxEdge, diag, url, null)?.let { jpeg ->
            diag.add("signed rung 2: served ${jpeg.size}B via weserv")
            return "image/jpeg" to jpeg
        }
        // Rung 3: upstream fetch (session-first + chapter pass).
        diag.add("signed rung 3: upstream fetch (session-first + pass)")
        val (contentType, bytes) = fetchUpstream(url, diag)
        val decoded: ByteArray = if (isAvif(contentType, bytes)) {
            // NEVER serve raw AVIF — transcode like every other image
            // (the platform decoder on Android 12+, weserv otherwise).
            transcode(maxEdge, diag, url, bytes)
        } else {
            bytes
        }
        diag.add("signed rung 3: served ${decoded.size}B (${if (contentType.isBlank()) "raster" else contentType})")
        return contentTypeLabel(contentType, decoded, url) to decoded
    }

    /** Fetch one upstream url: inner client first (cdns: session first),
     * then the reader session, then the resign hook. */
    private fun fetchUpstream(url: String, diag: Diag): Pair<String, ByteArray> {
        val isCdnImage = url.contains("/api/cdn-image")
        val pass = if (isCdnImage) chapterPassProvider?.invoke() else null
        val tiers: List<Pair<OkHttpClient, String>> = if (isCdnImage && sessionClient != null) {
            listOf(sessionClient to "session", innerClient to "inner")
        } else {
            val session = sessionClient?.let { listOf(it to "session") } ?: emptyList()
            listOf(innerClient to "inner") + session
        }
        var lastError: IOException? = null
        for ((client, label) in tiers) {
            try {
                return fetchOnce(url, client, pass)
            } catch (e: IOException) {
                diag.add("fetch/${url.hostOf()} $label ${e.shortMessage()}")
                runCatching { Thread.sleep(400L) } ?: break
                lastError = e
            }
        }
        if (isCdnImage) {
            val fresh = runCatching { deferredResigner?.invoke(url) }.getOrNull()
            if (fresh == null || fresh == url) {
                diag.add("resign: no fresh url (the source could not re-sign)")
            } else {
                diag.add("resign: fresh signed url minted")
                for ((client, label) in tiers) {
                    try {
                        return fetchOnce(fresh, client, pass)
                    } catch (e: IOException) {
                        lastError = e
                        diag.add("fetch/${url.hostOf()} resign-$label ${e.shortMessage()}")
                    }
                }
            }
        }
        throw lastError ?: IOException("ProComic: image fetch failed")
    }

    private fun fetchOnce(url: String, client: OkHttpClient, pass: String?): Pair<String, ByteArray> {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .header("sec-fetch-dest", "image")
            .header("sec-fetch-mode", "no-cors")
            .header("sec-fetch-site", "same-site")
        if (pass != null && url.contains("/api/cdn-image")) {
            builder.header("x-chapter-pass", pass)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: image download failed with HTTP ${response.code}")
            }
            val contentType = response.header("Content-Type") ?: ""
            return contentType to (response.body?.bytes() ?: ByteArray(0))
        }
    }

    // ------------------------------------------------------------------
    // Stateful protected routes
    // ------------------------------------------------------------------

    private fun handleMapPage(path: String, out: OutputStream) = handleStateful(
        path, "/$MAP_PAGE_PATH_PREFIX/", "protected page expired — reopen the chapter",
        "map:", "protected page", "Reopen the chapter — the browser page walk may capture them rendered.",
        out,
        lookup = { hash -> synchronized(mapPages) { mapPages[hash] } },
        build = { registration: MapPageRegistration, diag ->
            val planner = mapPlanner
                ?: throw StageException("plan", "ProComic: the reconstruction planner is unavailable", diag, null)
            val request = MapPageRequest(
                registration.chapterId,
                registration.pageIndex,
                registration.token,
                registration.method,
                registration.cdnPath,
                registration.referer,
            )
            var plan = runCatching { planner(request) }
                .onFailure { diag.add("plan threw: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (plan == null) {
                diag.add("plan: no answer — one re-plan before failing")
                plan = runCatching { planner(request) }.getOrNull()
            }
            plan ?: throw StageException(
                "plan",
                "ProComic: the protected page's reconstruction plan failed [${diag.summary()}]",
                diag,
                null,
            )
            if (plan.pieces.isEmpty() || plan.pieces.size != plan.rects.size) {
                throw StageException(
                    "plan",
                    "ProComic: the protected page's reconstruction plan failed [${diag.summary()}]",
                    diag,
                    null,
                )
            }
            val width = plan.width.coerceIn(8, MAX_CANVAS_W)
            val height = plan.height.coerceIn(8, MAX_CANVAS_H)
            val bitmap = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                throw StageException(
                    "decode",
                    "ProComic: the protected page's canvas (${plan.width}x${plan.height}) could not be allocated (${t.javaClass.simpleName})",
                    diag,
                    null,
                )
            }
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(-1)
                val failed = drawPieces(canvas, plan.pieces, plan.rects, null, registration.referer, diag)
                if (failed.isNotEmpty()) {
                    diag.add("compose: ${failed.size}/${plan.pieces.size} pieces failed — one re-plan self-heal")
                    val replanned = runCatching { planner(request) }.getOrNull()
                    if (replanned == null || replanned.pieces.size != plan.pieces.size || replanned.pieces == plan.pieces) {
                        diag.add("compose: the re-plan answered no compatible map")
                    } else {
                        val stillFailed = drawPieces(canvas, replanned.pieces, plan.rects, failed, registration.referer, diag)
                        if (stillFailed.isEmpty()) {
                            diag.add("compose: the re-plan healed every failed piece")
                        } else {
                            diag.add("compose: ${stillFailed.size} pieces still failed after the re-plan")
                        }
                    }
                }
                if (failed.size == plan.pieces.size) {
                    throw StageException(
                        "compose",
                        "ProComic: every piece of the protected page failed [${diag.summary()}]",
                        diag,
                        null,
                    )
                }
                val out2 = ByteArrayOutputStream(plan.pieces.size * 32768)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out2)
                "image/jpeg" to out2.toByteArray()
            } finally {
                runCatching { bitmap.recycle() }
            }
        },
    )

    private fun handleComposed(path: String, out: OutputStream) = handleStateful(
        path, "/.composed/", "composed row expired — reopen the chapter",
        "cmp:", "composed row", "Reopen the chapter — the browser page walk may capture them rendered.",
        out,
        lookup = { hash -> synchronized(composedRows) { composedRows[hash] } },
        build = { spec: ComposedRow, diag ->
            val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(-1)
                var ok = 0
                spec.pieces.forEachIndexed { index, piece ->
                    runCatching {
                        val bytes = buildImage(cacheKeyOf(piece.url, 0), piece.url, diag, 0).second
                        val pieceBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (pieceBitmap != null) {
                            try {
                                canvas.drawBitmap(
                                    pieceBitmap,
                                    null,
                                    RectF(
                                        piece.x.coerceAtLeast(0).toFloat(),
                                        piece.y.coerceAtLeast(0).toFloat(),
                                        (piece.x + piece.w).coerceAtMost(spec.width).toFloat(),
                                        (piece.y + piece.h).coerceAtMost(spec.height).toFloat(),
                                    ),
                                    null,
                                )
                                ok++
                            } finally {
                                runCatching { pieceBitmap.recycle() }
                            }
                        }
                    }.onFailure { t ->
                        Log.w(TAG, "composed piece ${piece.url.take(90)} failed: ${t.javaClass.simpleName}")
                    }
                }
                if (ok == 0) {
                    throw StageException(
                        "compose",
                        "ProComic: every piece of the composed row failed [${diag.summary()}]",
                        diag,
                        null,
                    )
                }
                val out2 = ByteArrayOutputStream(spec.pieces.size * 32768)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out2)
                "image/jpeg" to out2.toByteArray()
            } finally {
                runCatching { bitmap.recycle() }
            }
        },
    )

    private fun handleCaptured(path: String, out: OutputStream) = handleStateful(
        path, "/.captured/", "captured page expired — reopen the chapter",
        "cap:", "captured row", "",
        out,
        lookup = { hash -> synchronized(capturedRows) { capturedRows[hash] } },
        build = { row: CapturedRow, _ ->
            val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(-1)
                row.tiles.forEach { tile ->
                    runCatching {
                        val tileBitmap = BitmapFactory.decodeByteArray(tile.jpeg, 0, tile.jpeg.size)
                        if (tileBitmap != null) {
                            try {
                                canvas.drawBitmap(
                                    tileBitmap,
                                    null,
                                    RectF(
                                        tile.x.coerceIn(0, row.width).toFloat(),
                                        tile.y.coerceIn(0, row.height).toFloat(),
                                        (tile.x + tile.w).coerceIn(0, row.width).toFloat(),
                                        (tile.y + tile.h).coerceIn(0, row.height).toFloat(),
                                    ),
                                    null,
                                )
                            } finally {
                                runCatching { tileBitmap.recycle() }
                            }
                        }
                    }
                }
                val out2 = ByteArrayOutputStream(64 * 1024)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out2)
                "image/jpeg" to out2.toByteArray()
            } finally {
                runCatching { bitmap.recycle() }
            }
        },
    )

    private fun <T> handleStateful(
        path: String,
        prefix: String,
        expiredMessage: String,
        logTag: String,
        logName: String,
        below12Note: String,
        out: OutputStream,
        lookup: (String) -> T?,
        build: (T, Diag) -> Pair<String, ByteArray>,
    ) {
        val hash = path.removePrefix(prefix).trim()
        val registration = lookup(hash)
        if (hash.isEmpty() || registration == null) {
            respond(out, 410, "Gone", "text/plain", expiredMessage.toByteArray(), null, null)
            return
        }
        val key = logTag + hash
        cacheGet(key)?.let { (type, bytes) ->
            respond(out, 200, "OK", type, bytes, null, null)
            return
        }
        if (!avifDecodable(Build.VERSION.SDK_INT) && prefix != "/.captured/") {
            val note = "protected pages need an AVIF-capable device (Android 12+) — the site serves them as AVIF pieces no available decoder can render below that; the framework codec tier is retired (the field verdict on v1.6.52: the device died inside it). $below12Note"
            respond(out, 410, "Gone", "text/plain", note.toByteArray(), null, null)
            return
        }
        try {
            val pair = singleFlight(key) { build(registration, Diag()) }
            respond(out, 200, "OK", pair.first, pair.second, null, null)
        } catch (e: StageException) {
            Log.w(TAG, "$logName $hash failed at stage=${e.stage}: ${e.message}")
            recordFailure(key, e.stage, e.diag)
            respond(out, 502, "Bad Gateway", "text/plain", (e.message ?: "failed").toByteArray(), e.stage, e.diag)
        } catch (t: Throwable) {
            Log.w(TAG, "$logName $hash dispatch failed: ${t.javaClass.simpleName}")
            respond(out, 502, "Bad Gateway", "text/plain", "$logName build failed (${t.javaClass.simpleName})".toByteArray(), null, null)
        }
    }

    // ------------------------------------------------------------------
    // Piece composition
    // ------------------------------------------------------------------

    /** Draw the plan's pieces onto the canvas; every piece is fetched and
     * AVIF-transcoded exactly like any other image (the cover road), so
     * the canvas only ever receives JPEG bytes. Returns the failed set. */
    private fun drawPieces(
        canvas: Canvas,
        pieces: List<String>,
        rects: List<ChapterMapRectDto>,
        retryOnly: Set<Int>?,
        referer: String,
        diag: Diag,
    ): LinkedHashSet<Int> {
        val failed = LinkedHashSet<Int>()
        for (index in pieces.indices) {
            if (retryOnly != null && index !in retryOnly) continue
            val rect = rects.getOrNull(index) ?: continue
            val url = pieces[index]
            try {
                val (contentType, bytes) = fetchPiece(url, referer, diag)
                val decoded = if (isAvif(contentType, bytes)) transcode(0, diag, url, bytes) else bytes
                val pieceBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                if (pieceBitmap == null) {
                    diag.add("piece $index: decode null")
                    failed.add(index)
                    continue
                }
                try {
                    canvas.drawBitmap(
                        pieceBitmap,
                        null,
                        RectF(
                            rect.left.coerceIn(0, canvas.width).toFloat(),
                            rect.top.coerceIn(0, canvas.height).toFloat(),
                            (rect.left + rect.width).coerceIn(0, canvas.width).toFloat(),
                            (rect.top + rect.height).coerceIn(0, canvas.height).toFloat(),
                        ),
                        null,
                    )
                } finally {
                    runCatching { pieceBitmap.recycle() }
                }
            } catch (t: Throwable) {
                diag.add("piece $index: ${t.message ?: t.javaClass.simpleName}".take(120))
                failed.add(index)
            }
        }
        return failed
    }

    /** Fetch one map piece (referer-gated): inner client first, session
     * fallback, AVIF transcoded to JPEG before anything else sees it. */
    private fun fetchPiece(url: String, referer: String, diag: Diag): Pair<String, ByteArray> {
        val tiers: List<Pair<OkHttpClient, String>> = buildList {
            add(innerClient to "inner")
            sessionClient?.let { add(it to "session") }
        }
        var lastError: IOException? = null
        for ((client, label) in tiers) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Referer", referer)
                    .header("User-Agent", BROWSER_UA)
                    .header("sec-fetch-dest", "image")
                    .header("sec-fetch-mode", "no-cors")
                    .header("sec-fetch-site", "cross-site")
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("ProComic: piece download failed with HTTP ${response.code}")
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (isAvif(contentType, bytes)) {
                        return "image/jpeg" to transcode(0, diag, url, bytes)
                    }
                    return (contentType.ifBlank { "application/octet-stream" }) to bytes
                }
            } catch (e: IOException) {
                diag.add("piece fetch $label: ${e.shortMessage()}")
                runCatching { Thread.sleep(400L) } ?: break
                lastError = e
            }
        }
        throw lastError ?: IOException("ProComic: piece fetch failed")
    }

    // ------------------------------------------------------------------
    // The decode ladder — BitmapFactory first (the platform decoder that
    // already renders the covers), weserv server-side as the fallback.
    // ------------------------------------------------------------------

    private fun transcode(maxEdge: Int, diag: Diag, url: String, bytes: ByteArray): ByteArray {
        try {
            decodePermits.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StageException("decode", "ProComic: page transcode interrupted", diag, null)
        }
        try {
            return platformDecode(maxEdge, diag, bytes)
                ?: weservLadder(maxEdge, diag, url, bytes)
                ?: throw IOException("ProComic: page failed to decode (server-side transcode unavailable)")
        } finally {
            decodePermits.release()
        }
    }

    /** Platform BitmapFactory decode → JPEG bytes, with bounds +
     * sample-size discipline (JPEG/WebP/PNG everywhere; AVIF on Android
     * 12+ — the decoder that already renders the covers). Null when the
     * platform has no codec for the bytes. */
    private fun platformDecode(maxEdge: Int, diag: Diag, bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            diag.add("bitmapfactory null (platform has no codec for this format)")
            return null
        }
        val options = BitmapFactory.Options()
        if (maxEdge > 0) {
            var sample = 1
            var max = maxOf(bounds.outWidth, bounds.outHeight)
            while (max / 2 >= maxEdge) {
                max /= 2
                sample *= 2
            }
            options.inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: run {
            diag.add("bitmapfactory null (platform has no codec for this format)")
            return null
        }
        val quality = if (maxEdge > 0) 82 else 88
        try {
            val out = ByteArrayOutputStream(bytes.size / 2)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw IOException("ProComic: page JPEG encode failed")
            }
            return out.toByteArray()
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    // ------------------------------------------------------------------
    // weserv ladder (server-side transcode — the same road as covers)
    // ------------------------------------------------------------------

    private fun weservLadder(maxEdge: Int, diag: Diag, url: String, rawBytes: ByteArray?): ByteArray? {
        if (PROXY_HOSTS.isEmpty()) {
            diag.add("proxy: no hosts configured")
            return null
        }
        val encoded = runCatching { URLEncoder.encode(url, "UTF-8") }.getOrNull() ?: run {
            diag.add("proxy: encode failed")
            return null
        }
        val quality = if (maxEdge > 0) 82 else 88
        val budget = budgetBoxOf(rawBytes, maxEdge, diag)
        for (host in PROXY_HOSTS) {
            val proxied = buildString {
                append("https://").append(host).append("/?url=").append(encoded)
                append("&output=jpg&q=").append(quality)
                if (budget > 0) append("&w=").append(budget).append("&h=").append(budget).append("&fit=inside&we")
            }
            repeat(2) { attempt ->
                try {
                    innerClient.newCall(
                        Request.Builder().url(proxied).header("User-Agent", BROWSER_UA).build(),
                    ).execute().use { response ->
                        if (response.code >= 500) {
                            diag.add("proxy/$host http ${response.code}${if (attempt == 0) " — retrying" else ""}")
                            return@use
                        }
                        if (!response.isSuccessful) {
                            diag.add("proxy/$host http ${response.code}")
                            return@use
                        }
                        val jpegCandidate = response.body?.bytes() ?: ByteArray(0)
                        if (jpegCandidate.isEmpty()) {
                            diag.add("proxy/$host empty body")
                            return@use
                        }
                        if (isAvif("", jpegCandidate)) {
                            diag.add("proxy/$host returned avif")
                            return@use
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpegCandidate, 0, jpegCandidate.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            diag.add("proxy/$host undecodable (${bounds.outMimeType ?: "unknown"})")
                            return@use
                        }
                        diag.add("proxy/$host ok ${bounds.outWidth}x${bounds.outHeight} ${jpegCandidate.size}B")
                        val options = BitmapFactory.Options()
                        if (maxEdge > 0) {
                            var sample = 1
                            var max = maxOf(bounds.outWidth, bounds.outHeight)
                            while (max / 2 >= maxEdge) {
                                max /= 2
                                sample *= 2
                            }
                            options.inSampleSize = sample
                        }
                        val bitmap = BitmapFactory.decodeByteArray(jpegCandidate, 0, jpegCandidate.size, options)
                        if (bitmap == null) {
                            diag.add("proxy/$host decode null")
                            return@use
                        }
                        val out = ByteArrayOutputStream(jpegCandidate.size / 2)
                        try {
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                                throw IOException("ProComic: page JPEG encode failed")
                            }
                            return out.toByteArray()
                        } finally {
                            runCatching { bitmap.recycle() }
                        }
                    }
                } catch (t: Throwable) {
                    diag.add("proxy/$host ${t.javaClass.simpleName}")
                }
            }
        }
        return null
    }

    /** The 25-megapixel budget box: cap the decode surface of a hostile
     * answer before any allocation. */
    private fun budgetBoxOf(rawBytes: ByteArray?, maxEdge: Int, diag: Diag): Int {
        if (maxEdge > 0) return maxEdge
        val dims = rawBytes?.let { raw ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
        } ?: return 0
        val (w, h) = dims
        val pixels = w.toLong() * h.toLong()
        if (pixels <= MAP_BUDGET_PIXELS.toLong()) return 0
        val box = (maxOf(w, h) * kotlin.math.sqrt(MAP_BUDGET_PIXELS / pixels)).toInt()
            .coerceIn(64, 8000)
        diag.add("proxy: budget box ${box}px ($w x $h)")
        return box
    }

    // ------------------------------------------------------------------
    // AVIF detection + helpers
    // ------------------------------------------------------------------

    /** AVIF by content-type or by the ftyp brand box in the bytes. */
    private fun isAvif(contentType: String, bytes: ByteArray): Boolean {
        if (contentType.contains("avif", ignoreCase = true)) return true
        if (bytes.size >= 12 && bytes[4] == 102.toByte() && bytes[5] == 116.toByte() && bytes[6] == 121.toByte() && bytes[7] == 112.toByte()) {
            val brand = String(bytes, 8, 4, Charsets.US_ASCII)
            if (brand.startsWith("av") || brand == "mif1" || brand == "miaf") return true
        }
        return false
    }

    /** The label the reader sees. AVIF bytes NEVER leave this class — the
     * caller transcodes them first; this only labels the rest. */
    private fun contentTypeLabel(contentType: String, bytes: ByteArray, url: String): String = when {
        isAvif(contentType, bytes) -> "image/jpeg" // unreachable: callers transcode first
        contentType.isBlank() -> "application/octet-stream"
        else -> contentType
    }

    private fun isSiteHost(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps &&
            (
                httpUrl.host in SITE_HOST_APEXES ||
                    SITE_HOST_SUFFIXES.any { httpUrl.host.endsWith(it) }
                )
    }.getOrDefault(false)

    private fun cacheKeyOf(url: String, maxEdge: Int): String = sha256Hex(url) + if (maxEdge > 0) "#e$maxEdge" else ""

    private fun sha256Hex(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(value.hashCode().toString())

    private fun sha24(value: String): String = sha256Hex(value).take(24)

    private fun jsonPayload(spec: ComposedRow): String = """{"w":${spec.width},"h":${spec.height},"pieces":""" +
        spec.pieces.joinToString(",", "[", "]") {
            """{"u":"${it.url.replace("\"", "\\\"")}","x":${it.x},"y":${it.y},"w":${it.w},"h":${it.h}}"""
        } + "}"

    // ------------------------------------------------------------------
    // Cache + single-flight
    // ------------------------------------------------------------------

    @Synchronized
    private fun cachePut(key: String, contentType: String, bytes: ByteArray) {
        cache[key]?.let { (_, old) -> cacheBytes -= old.size }
        cache[key] = contentType to bytes
        cacheBytes += bytes.size
        val it = cache.entries.iterator()
        while ((cache.size > CACHE_MAX_ENTRIES || cacheBytes > CACHE_MAX_BYTES) && it.hasNext()) {
            val entry = it.next()
            cacheBytes -= entry.value.second.size
            it.remove()
        }
    }

    @Synchronized
    private fun cacheGet(key: String): Pair<String, ByteArray>? = cache[key]

    private fun singleFlight(key: String, builder: () -> Pair<String, ByteArray>): Pair<String, ByteArray> {
        while (true) {
            val existing = inflight[key]
            if (existing != null) {
                try {
                    return existing.get()
                } catch (t: Throwable) {
                    throw (t.cause ?: t)
                }
            }
            val future = CompletableFuture<Pair<String, ByteArray>>()
            if (inflight.putIfAbsent(key, future) == null) {
                try {
                    val result = builder()
                    future.complete(result)
                    inflight.remove(key)
                    return result
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                    inflight.remove(key)
                    throw t
                }
            }
        }
    }

    private fun recordFailure(url: String, stage: String, diag: Diag?) {
        if (diag?.summary().isNullOrEmpty()) return
        synchronized(recentFailures) {
            if (recentFailures.size >= 64) recentFailures.removeFirst()
            recentFailures.addLast(RecentFailure(url, stage, diag!!.summary(), System.currentTimeMillis()))
        }
    }

    // ------------------------------------------------------------------
    // /.diag
    // ------------------------------------------------------------------

    private fun diagJson(): String {
        val sb = StringBuilder(1024)
        synchronized(cache) {
            sb.append("{\"server\":\"procomic-image\",\"uptimeMs\":").append(System.currentTimeMillis() - startedAt)
            sb.append(",\"cachedResults\":").append(cache.size)
            sb.append(",\"cachedBytes\":").append(cacheBytes)
        }
        sb.append(",\"decoder\":\"bitmapfactory+server-proxy (v111, no on-device AVIF decoder)\",\"proxyHosts\":\"")
        sb.append(PROXY_HOSTS.joinToString(",").replace("\"", "'"))
        sb.append("\",\"nativeCrashState\":\"")
        sb.append(nativeCrashState().replace("\"", "'"))
        sb.append("\"")
        lastDeferred?.let { deferred ->
            sb.append(",\"lastDeferred\":{\"at\":").append(deferred.at)
            sb.append(",\"chapterId\":").append(deferred.chapterId)
            sb.append(",\"trail\":[")
            deferred.trail.forEachIndexed { index, line ->
                if (index > 0) sb.append(',')
                sb.append('"').append(line.take(220).replace("\"", "'")).append('"')
            }
            sb.append("]}")
        }
        sb.append(",\"recentFailures\":[")
        val failures = synchronized(recentFailures) { recentFailures.toList() }
        failures.forEachIndexed { index, failure ->
            if (index > 0) sb.append(',')
            sb.append("{\"at\":").append(failure.at)
            sb.append(",\"url\":\"").append(failure.url.take(160).replace("\"", "'"))
            sb.append("\",\"stage\":\"").append(failure.stage)
            sb.append("\",\"attempts\":\"").append(failure.attempts.replace("\"", "'"))
            sb.append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Best-effort crash-breaker marker readout (diag-only). */
    private fun nativeCrashState(): String {
        val dir = nativeCacheDir ?: return "no-cache-dir"
        return runCatching {
            val markers = dir.listFiles { file -> file.name.startsWith("procomic-decode-crash") }
                ?.sortedBy { it.name }?.map { it.name } ?: emptyList()
            if (markers.isEmpty()) "clean (no decode-crash markers)" else "markers: ${markers.joinToString(",")}"
        }.getOrDefault("marker read failed")
    }

    // ------------------------------------------------------------------
    // The legible error card (drawn text — no decode involved)
    // ------------------------------------------------------------------

    private fun errorCard(out: OutputStream, detail: String) {
        val bitmap = runCatching { Bitmap.createBitmap(800, 1280, Bitmap.Config.RGB_565) }.getOrNull()
        if (bitmap == null) {
            respond(out, 502, "Bad Gateway", "text/plain", detail.toByteArray(), "decode", null)
            return
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(-1)
            val paint = Paint(1)
            paint.color = -7661030
            paint.textSize = 34f
            val lines = buildList {
                add("ProComic — this page failed to render")
                add("")
                detail.split("; ").forEach { add(it) }
                add("")
                add("Device: Android ${Build.VERSION.RELEASE} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})")
                add("Server build: procomic-image")
                add("More: /.diag in the app logs")
            }
            var y = 90f
            for (line in lines) {
                for (chunk in if (line.length <= 46) listOf(line) else line.chunked(46)) {
                    if (y > 1220f) break
                    canvas.drawText(chunk, 44f, y, paint)
                    y += 46f
                }
            }
            val out2 = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out2)
            respond(out, 200, "OK", "image/jpeg", out2.toByteArray(), null, null)
        } catch (t: Throwable) {
            respond(out, 502, "Bad Gateway", "text/plain", detail.toByteArray(), "decode", null)
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    // ------------------------------------------------------------------
    // Raw HTTP response writer
    // ------------------------------------------------------------------

    private fun respond(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        bytes: ByteArray,
        stage: String?,
        diag: Diag?,
    ) {
        val header = StringBuilder(192)
        header.append("HTTP/1.1 ").append(code).append(' ').append(reason)
        header.append("\r\nContent-Type: ").append(contentType)
        header.append("\r\nContent-Length: ").append(bytes.size)
        header.append("\r\nConnection: close\r\n")
        if (stage != null) header.append("X-ProComic-Stage: ").append(stage).append("\r\n")
        diag?.summary()?.takeIf { it.isNotEmpty() }?.let {
            header.append("X-ProComic-Diag: ").append(it.take(300)).append("\r\n")
        }
        header.append("\r\n")
        out.write(header.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun String.hostOf(): String = runCatching { this.toHttpUrl().host }.getOrDefault("?")

    private fun IOException.shortMessage(): String = (message ?: javaClass.simpleName).substringAfter("ProComic: ").take(48)
}
