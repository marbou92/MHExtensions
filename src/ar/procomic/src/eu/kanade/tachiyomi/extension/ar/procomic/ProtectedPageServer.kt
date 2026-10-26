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

/**
 * Serves ProComic reader images from a loopback HTTP server.
 *
 * v101 shape — one route, no stitching. The site moved to procomic.net and
 * reworked the reader payload: every page now ships as a plain
 * signed-filename AVIF on `app.procomic.net` (the signature rides in the
 * file name, verified live: 200 for plain okhttp/curl clients on every
 * probed chapter), and the old protection stack is gone from the payload
 * (`maps:[]`, `protectionV2:null`) and dead server-side (the deferred-media
 * endpoints are Turnstile-gated, the cdn-image signing answers 403
 * forbidden to non-browser sessions — that is where the device-wide HTTP
 * 502 wave came from: v1.6.16 still talked to the dying .pro origin, whose
 * reader pages are "moved" stubs in migrated regions and whose plan/sign
 * endpoints refuse everything).
 *
 * Route:
 *
 * `/.image-proxy/{base64url}` — the upstream image fetched with the browser
 * UA and the site Referer, then TRANSCODED to JPEG when it is AVIF. Two
 * reasons the bytes cannot just pass through:
 *
 * 1. Host apps fetch image URLs with THEIR OWN client, and the site's WAF
 *    answers 403 to non-browser User-Agents (Kotatsu's app UA among them —
 *    verified live on the new app.<host> CDN too), so direct URLs break in
 *    those readers while the browser works fine. Proxying through the
 *    extension's process makes every page image host-app-proof.
 * 2. The page files are AVIF (tall long-strip pages, up to 8000px). The
 *    platform AVIF codec only exists on Android 12+ and some OEM builds
 *    lack it even after that, so a raw pass-through would fail to decode
 *    on exactly the devices this source serves. The bundled software
 *    decoder (libavif/dav1d, ARM ABIs) plus ImageDecoder carry the
 *    transcode instead, and every host app gets a plain JPEG.
 *
 * Why a loopback server instead of a client interceptor: host apps differ in
 * how they execute image requests. Several Mihon-compatible readers (Kotatsu's
 * Mihon-compat layer among them) run image requests through their own client
 * without the source's application interceptors, which would send the site
 * URL raw and get HTTP 403 back. A plain HTTP URL that any HTTP client can
 * fetch works everywhere, with identical behavior in every reader and in
 * downloaders.
 *
 * Speed model (v101): one upstream GET per page (no plan POSTs, no piece
 * ladders, no session keys), a bounded JPEG result cache keyed by the
 * upstream URL, in-flight coalescing so the host's preload stampede shares
 * one build, and [preloadImages] warming the cache chapter-ahead in the
 * background so the reader's own fetches land on finished bytes.
 */
class ProComicImageServer(
    private val baseUrl: String,
    /**
     * The source's own client (cookie jar, CF solver, browser UA) used as
     * the retry tier: when the bare inner client cannot get an image
     * through, the same request is retried through this client so the site
     * sees the browsing session the reader page established.
     */
    private val sessionClient: OkHttpClient? = null,
    /**
     * Our own installed APK path + a writable cache dir — the raw material
     * for the explicit native-lib loader (it extracts libavif_android.so from
     * the APK and System.load-s it). Both optional so the JVM harness can
     * drive the server without an Android context.
     */
    private val apkSourceDir: String? = null,
    private val nativeCacheDir: java.io.File? = null,
) {

    private class Machine(val socket: ServerSocket)

    private val machine = AtomicReference<Machine?>()
    private val startLock = Any()

    private val startedAtMs = System.currentTimeMillis()

    /** Fair 2-wide semaphore over the TRANSCODE stage only (decode + JPEG
     * encode of tall pages). Fairness keeps a reader's preloaded pages from
     * being starved by late requests; the network fetch happens OUTSIDE the
     * semaphore so a slow edge never serializes the preload window. */
    private val transcodePermits = Semaphore(MAX_CONCURRENT_TRANSCODES, true)

    private val executor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ProComicImageServer").apply { isDaemon = true }
        }
    }

    /**
     * Loopback URL that serves [imageUrl] through the extension's own
     * client (browser User-Agent + site Referer + AVIF→JPEG transcode), so
     * host apps that would get 403 from the CDN with their own headers —
     * or could not decode AVIF at all — still load every page.
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
            val acceptor = Thread({ acceptLoop(socket) }, "ProComicImageServer-accept")
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
            path.startsWith("/$IMAGE_PROXY_PATH_PREFIX/") -> serveImageProxy(path, output)
            else -> writeResponse(output, 404, "Not Found", "text/plain", "unknown path".toByteArray())
        }
    }

    // ====================== image fetch + transcode ======================

    /**
     * Streams [imageUrl] through the loopback route: browser-UA fetch,
     * session-client retry, AVIF→JPEG transcode, result cache. The
     * caller's own headers are irrelevant by design (that is the whole
     * point of the route).
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

        val key = cacheKey(decoded)
        cachedResult(key)?.let {
            writeImageBytes(output, it.first, it.second)
            return
        }

        val diag = ProxyDiag()
        try {
            val (contentType, bytes) = buildImageCoalesced(key, decoded, diag)
            writeImageBytes(output, contentType, bytes)
        } catch (failure: ImageBuildFailure) {
            Log.w(TAG, "image $decoded failed at stage=${failure.stage}: ${failure.readerBody}")
            recordFailure(decoded, failure.stage, failure.diag)
            // Deterministic device-side failures (decode) render a legible
            // JPEG — most hosts surface only the HTTP code, which made every
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

    /** Fetch + transcode for one upstream URL, throwing [ImageBuildFailure]
     * with the reader-visible body. Shared by the request path and the
     * background preloader; runs ONCE per URL even under concurrent host
     * fetches (coalesced by [buildImageCoalesced]). */
    private fun buildImageBytes(imageUrl: String, diag: ProxyDiag): Pair<String, ByteArray> {
        var stage = "fetch"
        try {
            val (contentType, raw) = fetchUpstreamWithRetry(imageUrl, diag)
            stage = "decode"
            val bytes = if (isAvif(contentType, raw)) {
                transcodeUnderPermit(raw, diag)
            } else {
                // JPEG/PNG/WebP pass through untouched — no decode cost.
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

    private fun contentTypeServed(upstreamType: String, bytes: ByteArray): String = if (isAvif(upstreamType, bytes)) "image/jpeg" else upstreamType.ifBlank { "application/octet-stream" }

    /** The transcode stage under the fair semaphore: decode + JPEG encode.
     * Raw bytes are already in memory — no network happens while it's held. */
    private fun transcodeUnderPermit(raw: ByteArray, diag: ProxyDiag): ByteArray {
        transcodePermits.acquire()
        try {
            return avifToJpeg(raw, diag)
        } finally {
            transcodePermits.release()
        }
    }

    /** One upstream fetch attempt with the exact headers a browser <img>
     * sends. Caller retries/cascades. */
    private fun fetchUpstreamOnce(imageUrl: String, client: OkHttpClient): Pair<String, ByteArray> {
        val request = Request.Builder()
            .url(imageUrl)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", BROWSER_UA)
            .header("sec-fetch-dest", "image")
            .header("sec-fetch-mode", "no-cors")
            .header("sec-fetch-site", "same-site")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: image download failed with HTTP ${response.code}")
            }
            return response.header("Content-Type").orEmpty() to response.body.bytes()
        }
    }

    /** Inner client first (no source interceptors, no rate limiter
     * contention, no recursion), then one retry through the session client
     * (cookies, CF clearance, the reader page's browsing session). */
    private fun fetchUpstreamWithRetry(imageUrl: String, diag: ProxyDiag): Pair<String, ByteArray> {
        val host = runCatching { imageUrl.toHttpUrl().host }.getOrDefault("?")
        try {
            return fetchUpstreamOnce(imageUrl, innerClient)
        } catch (first: IOException) {
            diag.add("fetch/$host ${describe(first)}")
            val sessionClient = this.sessionClient ?: throw first
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                return fetchUpstreamOnce(imageUrl, sessionClient)
            } catch (second: IOException) {
                diag.add("fetch/$host session ${describe(second)}")
                throw second
            }
        }
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

    /** AVIF sniffing: the container is ISOBMFF — "ftyp" at offset 4 with an
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

    /**
     * AVIF → JPEG, three decode steps: BitmapFactory (the fast path, and
     * the platform AVIF codec on Android 12+), the bundled SOFTWARE AVIF
     * decoder — libavif/dav1d carries every device the host app runs on —
     * and finally ImageDecoder (API 28+) for whatever the software decoder
     * refuses. All guarded: any decode failure surfaces as the explicit
     * IOException the caller turns into the 502 body (or the legible error
     * JPEG).
     */
    private fun avifToJpeg(bytes: ByteArray, diag: ProxyDiag): ByteArray {
        val bitmap = decodeAvif(bytes, diag)
        try {
            val output = ByteArrayOutputStream(bytes.size / 2)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw IOException("ProComic: page JPEG encode failed")
            }
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeAvif(bytes: ByteArray, diag: ProxyDiag): Bitmap {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        diag.add("bitmapfactory null (platform has no avif codec)")
        decodeWithLibavif(bytes, diag)?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (t: Throwable) {
                diag.add("imagedecoder ${t.javaClass.simpleName}")
                throw IOException("ProComic: page failed to decode (${t.javaClass.simpleName})")
            }
        }
        throw IOException("ProComic: page failed to decode (Android 12+ required for AVIF pages)")
    }

    /**
     * The bundled software decoder tier. Every failure mode lands in the
     * diag trail with its ACTUAL reason — the v104 fix exists because the
     * previous bare runCatching swallowed the one fact that mattered (the
     * JNI library never loaded), leaving "bitmapfactory+libavif decode
     * failed" as the only clue.
     */
    private fun decodeWithLibavif(bytes: ByteArray, diag: ProxyDiag): Bitmap? {
        val nativeState = avifNativeState
        diag.add("avif native: $nativeState")
        if (nativeState != "system" && !nativeState.startsWith("loaded")) return null
        return try {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            if (!org.aomedia.avif.android.AvifDecoder.isAvifImage(buffer)) {
                diag.add("libavif: header refused")
                null
            } else {
                val info = org.aomedia.avif.android.AvifDecoder.Info()
                if (!org.aomedia.avif.android.AvifDecoder.getInfo(buffer, bytes.size, info)) {
                    diag.add("libavif: getInfo refused")
                    null
                } else {
                    diag.add("libavif: ${info.width}x${info.height} depth=${info.depth}")
                    val config = if (info.depth > 8) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    val bitmap = try {
                        Bitmap.createBitmap(info.width, info.height, config)
                    } catch (_: OutOfMemoryError) {
                        diag.add("libavif: OOM at $config")
                        null
                    } ?: return null
                    if (org.aomedia.avif.android.AvifDecoder.decode(java.nio.ByteBuffer.wrap(bytes), bytes.size, bitmap)) {
                        bitmap
                    } else {
                        bitmap.recycle()
                        diag.add("libavif: decode returned false")
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            diag.add("libavif threw ${t.javaClass.simpleName}: ${t.message?.take(60)}")
            null
        }
    }

    /**
     * Why the explicit loader exists: the avif-android library opens its JNI
     * lib in a static initializer via System.loadLibrary, which searches the
     * CLASSLOADER's native paths. Host apps load extension APKs with a bare
     * PathClassLoader (dex path only) and extractNativeLibs=false keeps the
     * .so inside our APK — so the search falls back to the HOST app's native
     * dirs, never finds libavif_android.so, and AvifDecoder's class init
     * throws UnsatisfiedLinkError. On an Android 10/11 device there is no
     * platform AVIF codec behind it either, so every page "failed to decode"
     * while the old runCatching swallowed the error class — the reader
     * screenshot that reported this fix showed exactly that dead end.
     *
     * Fix: before the first AvifDecoder reference, pull the .so out of OUR
     * OWN installed APK (the installed extension APK is a readable zip) and
     * System.load it by absolute path. That binds the library to THIS classloader; the static initializer's
     * System.loadLibrary then resolves against the already-loaded soname.
     */
    private val avifNativeState: String by lazy { ensureAvifNative() }

    private fun ensureAvifNative(): String {
        // Fast path: some hosts create the extension classloader WITH the
        // native lib path — the static init just works there.
        try {
            org.aomedia.avif.android.AvifDecoder.isAvifImage(java.nio.ByteBuffer.wrap(AVIF_PROBE_BYTES))
            return "system"
        } catch (_: Throwable) {
            // fall through to the explicit loader
        }
        val apkPath = apkSourceDir ?: return "unavailable (no apk source)"
        val cacheBase = nativeCacheDir ?: return "unavailable (no cache dir)"
        if (android.os.Build.SUPPORTED_ABIS.isEmpty()) return "unavailable (unknown abi)"
        return try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { candidate ->
                    zip.getEntry("lib/$candidate/libavif_android.so")
                } ?: return "unavailable (no libavif_android.so entry)"
                val dir = java.io.File(cacheBase, "procomic_native").apply { mkdirs() }
                val target = java.io.File(dir, "libavif_android.so")
                if (!target.exists() || target.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                System.load(target.absolutePath)
                "loaded from apk (${entry.name}, ${entry.size} B)"
            }
        } catch (t: Throwable) {
            "unavailable (${t.javaClass.simpleName}: ${t.message?.take(80)})"
        }
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

    // Kotlin does not surface Object.wait/notifyAll on Any — go through
    // java.lang.Object explicitly for the builder/joiner handshake.
    private fun Any.monitorWait(ms: Long) = (this as java.lang.Object).wait(ms)

    private fun Any.monitorNotifyAll() = (this as java.lang.Object).notifyAll()

    private fun buildImageCoalesced(key: String, imageUrl: String, diag: ProxyDiag): Pair<String, ByteArray> {
        val created = synchronized(inFlightLock) {
            val existing = inFlightBuilds[key]
            if (existing != null) return existing.join()
            val slot = InFlightBuild()
            inFlightBuilds[key] = slot
            slot
        }
        // Builder path: the monitor is NOT held while the pipeline runs.
        try {
            val result = buildImageBytes(imageUrl, diag)
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
                // daemon pool shutting down with the process — nothing to do
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

    // ======================== result cache ===============================

    /** Finished results keyed by the sha256 of the upstream URL. Bounded to
     * a handful of pages (tall JPEGs can reach ~2 MB each): enough that
     * every page of a reader's preload window that timed out on the wire is
     * instantly available on retry, without holding a chapter's worth of
     * bitmaps-as-JPEG in memory. */
    private val results = object : LinkedHashMap<String, Pair<String, ByteArray>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ByteArray>>): Boolean = size > MAX_CACHED_RESULTS
    }

    private val resultsLock = Any()

    private fun cacheKey(imageUrl: String): String = MessageDigest.getInstance("SHA-256").digest(imageUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cachedResult(key: String): Pair<String, ByteArray>? = synchronized(resultsLock) {
        results[key]
    }

    private fun cacheResult(key: String, contentType: String, bytes: ByteArray) {
        synchronized(resultsLock) {
            results[key] = contentType to bytes
        }
    }

    // ============================ diagnostics ============================

    /** Failure ring buffer feeding /.diag — the last [MAX_FAILURE_RECORDS]
     * image failures across all chapters, newest last. */
    private class FailureRecord(
        val imageUrl: String,
        val stage: String,
        val attempts: String,
        val at: Long,
    )

    private val failures = ArrayDeque<FailureRecord>()

    private val failuresLock = Any()

    private fun recordFailure(imageUrl: String, stage: String, diag: ProxyDiag) {
        if (diag.isEmpty()) return
        synchronized(failuresLock) {
            if (failures.size >= MAX_FAILURE_RECORDS) failures.removeFirst()
            failures.addLast(FailureRecord(imageUrl, stage, diag.summary(), System.currentTimeMillis()))
        }
    }

    /**
     * `/.diag` — JSON state of the image server for remote diagnosis: the
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
        sb.append("\"server\":\"procomic-image/v104\"")
        sb.append(",\"uptimeMs\":").append(System.currentTimeMillis() - startedAtMs)
        synchronized(resultsLock) { sb.append(",\"cachedResults\":").append(results.size) }
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
                add("Server build: procomic-image/v104")
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

    /** Only site-owned image hosts, https only — the proxy is not a general
     * purpose fetcher. Both apexes (the .net migration keeps .pro library
     * links alive) plus every CDN/app subdomain. */
    private fun isAllowedImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (
            httpUrl.host == "procomic.net" ||
                httpUrl.host == "www.procomic.net" ||
                httpUrl.host == "procomic.pro" ||
                httpUrl.host == "www.procomic.pro" ||
                httpUrl.host.endsWith(".procomic.net") ||
                httpUrl.host.endsWith(".procomic.pro")
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

    companion object {
        const val IMAGE_PROXY_PATH_PREFIX = ".image-proxy"
        const val DIAG_PATH_PREFIX = ".diag"
        const val JPEG_QUALITY = 88

        /**
         * The one User-Agent the site accepts on every path (verified live:
         * its WAF answers 403 to app UAs like "Kotatsu/1.0" on both the
         * reader page and the image CDN — including the new app.<host>
         * files — while browser and okhttp UAs pass). Used by the
         * extension's client interceptor and by this server's inner client
         * so every hop presents the same "browser".
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val BACKLOG = 64
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16_384

        /** Two transcodes at a time: the decode target is a tall ARGB_8888
         * bitmap (up to ~26 MB at 800x8000) and the software AVIF decode is
         * CPU heavy; the permit covers ONLY the bitmap stage and the decode
         * target falls back to RGB_565 when the full-quality allocation
         * cannot be made. */
        private const val MAX_CONCURRENT_TRANSCODES = 2

        /** Finished-result cache (bytes per entry, URL-keyed). */
        private const val MAX_CACHED_RESULTS = 12

        /** Pause between background pre-transcoded pages (gentle pacing —
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

        /** Minimal ftyp/avif header — only exercises AvifDecoder's class
         * init (the JNI load), never a real decode. */
        private val AVIF_PROBE_BYTES = byteArrayOf(
            0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66, 0, 0, 0, 0,
        )
    }
}
