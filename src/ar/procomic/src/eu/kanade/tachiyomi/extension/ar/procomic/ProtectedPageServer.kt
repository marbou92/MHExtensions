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
     * Our own installed APK path (v107: resolved via the package manager
     * from OUR package — the host application context would hand back the
     * host app's apk, which is the wrong zip) + a writable cache dir — the
     * raw material for the explicit native-lib loader (it extracts
     * libavif_android.so from the APK and System.load-s it). Both optional
     * so the JVM harness can drive the server without an Android context.
     */
    private val apkSourceDir: String? = null,
    private val nativeCacheDir: java.io.File? = null,
    /**
     * The host application context (v109) — the WebView decode tier needs a
     * context to build an offscreen WebView on the app's main looper. Optional
     * so the JVM harness can drive the server without one (the tier then
     * declines deterministically with a diag row, never throwing).
     */
    private val webViewContext: android.content.Context? = null,
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

    /** Loopback URL that serves [imageUrl] through the extension's own
     * client. [maxEdge] > 0 asks the transcoder to downscale the decoded
     * image so its long edge fits — covers (v106) request 1280 so a
     * 4000x6000 "originals" AVIF never turns into a ~90 MB ARGB bitmap on
     * a phone; pages keep the full resolution (0). The parameter rides the
     * URL query (?e=) so one route serves both shapes and the cache keys
     * stay distinct. */
    fun imageProxyUrl(imageUrl: String, maxEdge: Int = 0): String {
        val encoded = android.util.Base64.encodeToString(
            imageUrl.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val query = if (maxEdge > 0) "?e=$maxEdge" else ""
        return "http://127.0.0.1:${ensureStarted()}/$IMAGE_PROXY_PATH_PREFIX/$encoded$query"
    }

    /** Starts (or restarts) the loopback server and returns its port.
     *
     * v105: a FIXED preferred port is tried first so proxy URLs embedded in
     * host-app data (cover thumbnail_url persists in the library DB; page
     * URLs live in the reader session) stay valid across process restarts.
     * When the port is taken, a /.diag probe decides: another process of
     * THIS extension (a second host app) serves the identical stateless
     * route and is reused as-is; anything else falls back to an ephemeral
     * port exactly like the pre-v105 behavior.
     */
    private fun ensureStarted(): Int {
        machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
        synchronized(startLock) {
            machine.get()?.takeIf { !it.socket.isClosed }?.let { return it.socket.localPort }
            runCatching {
                val socket = ServerSocket(PREFERRED_PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
                startAcceptor(socket)
            }.onSuccess { return PREFERRED_PORT }
            if (familyServerListening(PREFERRED_PORT)) return PREFERRED_PORT
            // v106: the fallback bind is guarded too — an unguarded bind
            // throw here escapes into whatever thread asked for a cover
            // URL first.
            runCatching {
                val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
                startAcceptor(socket)
                return socket.localPort
            }.getOrElse { throw java.io.IOException("ProComic: image server could not bind any loopback port", it) }
        }
    }

    private fun startAcceptor(socket: ServerSocket) {
        machine.set(Machine(socket))
        val acceptor = Thread({ acceptLoop(socket) }, "ProComicImageServer-accept")
        acceptor.isDaemon = true
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
                val query = rawPath.substringAfter('?', "")
                val output = socket.getOutputStream()

                when (method) {
                    "GET" -> serve(path, query, output)
                    else -> writeResponse(output, 405, "Method Not Allowed", "text/plain", "method not allowed".toByteArray())
                }
            }
        } catch (_: Throwable) {
            // v106: Throwable, not Exception — an Error escaping this worker
            // thread reaches the default uncaught-exception handler and the
            // HOST APP PROCESS DIES (exactly the 1.6.19/1.6.20 field crash
            // shape). Nothing on this thread is allowed to kill the app.
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
    private fun serveImageProxy(path: String, query: String, output: java.io.OutputStream) {
        val encoded = path.removePrefix("/$IMAGE_PROXY_PATH_PREFIX/")
        // ?e=<edge> — the cover-downscale variant (v106). Out-of-range or
        // malformed values fall back to 0 (full resolution) silently.
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
    private fun buildImageBytes(imageUrl: String, diag: ProxyDiag, maxEdge: Int = 0): Pair<String, ByteArray> {
        var stage = "fetch"
        try {
            val (contentType, raw) = fetchUpstreamWithRetry(imageUrl, diag)
            stage = "decode"
            val bytes = if (isAvif(contentType, raw)) {
                transcodeUnderPermit(raw, diag, maxEdge)
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
    private fun transcodeUnderPermit(raw: ByteArray, diag: ProxyDiag, maxEdge: Int): ByteArray {
        transcodePermits.acquire()
        try {
            return avifToJpeg(raw, diag, maxEdge)
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
     * AVIF → JPEG, decode ladder (v109):
     *
     * 1. BitmapFactory — the platform codec (Android 12+), subsampled when
     *    a max edge is requested.
     * 2. The bundled libavif/dav1d software decoder (when the explicit
     *    loader can find + load it).
     * 3. WebView — the host's Chromium engine (AVIF since Chrome 85, and
     *    the WebView package auto-updates via Play even on Android 10). A
     *    FULL image decoder (single items AND grid AVIF) whose every
     *    failure mode is a catchable Java exception or a sandboxed-renderer
     *    crash that never kills the host app.
     * 4. ImageDecoder (API 28+).
     *
     * v110 hardening (the field device STILL died in the v109 WebView
     * tier): one shared WebView behind a process-wide permit, ?e=-bounded
     * draws, identity-guarded callbacks, and a crash-loop breaker that
     * disables the tier after a process death inside it — see
     * decodeWithWebView for the three fixed vectors.
     *
     * v109 REMOVED the MediaCodec "video/av1" tier from this ladder: the
     * field device (Android 10) DIED inside it — c2.av1 is Android 10's
     * brand-new software codec and configuring it at a still image's full
     * size (pages 800x5000, "originals" covers up to 4000x6000) is a
     * native-crash/OOM-kill surface no Java guard can catch; the process
     * dies, the marker-based breaker only gates after two deaths, and the
     * user experiences "the app just crashes". A video codec is simply the
     * wrong tool for a 4 MP still. The WebView tier renders what Android 10
     * has no other way to render, and it CANNOT take the app down. The
     * MediaCodec path remains compiled (debug hook only, never called by
     * this ladder) so the harness can keep pinning its decline behavior.
     *
     * Every failure surfaces in the diag trail with its ACTUAL reason.
     */
    private fun avifToJpeg(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int): ByteArray {
        val quality = if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY
        // Every tier call site is Throwable-tight: an unexpected throw in
        // one tier (JVM stubs, an exotic device API misbehavior) must fall
        // through to the NEXT tier, never abort the ladder — an abort means
        // the request dies with zero tier rows in the diag trail.
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
        val nativeBitmap = try {
            decodeWithLibavif(bytes, diag, maxEdge)
        } catch (t: Throwable) {
            diag.add("libavif tier threw ${t.javaClass.simpleName}")
            null
        }
        nativeBitmap?.let { bitmap ->
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
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            val webviewBitmap = try {
                decodeWithWebView(bytes, diag, maxEdge)
            } catch (t: Throwable) {
                diag.add("webview tier threw ${t.javaClass.simpleName}")
                null
            }
            webviewBitmap?.let { decoded ->
                val bitmap = if (maxEdge > 0) downscaleTo(decoded, maxEdge, diag) else decoded
                try {
                    val output = ByteArrayOutputStream(bitmap.byteCount.coerceAtLeast(16_384))
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw IOException("ProComic: page JPEG encode failed")
                    }
                    return output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
        } else {
            diag.add("webview: skipped (sdk ${android.os.Build.VERSION.SDK_INT})")
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    if (maxEdge > 0) {
                        val longEdge = maxOf(info.size.width, info.size.height)
                        if (longEdge > maxEdge) {
                            val scale = maxEdge.toFloat() / longEdge
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                }
                try {
                    val output = ByteArrayOutputStream(bytes.size / 2)
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw IOException("ProComic: page JPEG encode failed")
                    }
                    return output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            } catch (t: Throwable) {
                diag.add("imagedecoder ${t.javaClass.simpleName}")
            }
        }
        throw IOException("ProComic: page failed to decode (no AVIF decoder on this device)")
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

    /**
     * The v108 framework tier: AVIF → JPEG with zero external state.
     * Gated by the same crash-loop breaker as the bundled-decoder tier (the
     * codec is native too — a c2 crash kills the process exactly like a
     * dav1d one) and the same pixel budget.
     */
    private fun decodeWithMediaCodecFramework(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int): ByteArray? {
        val crashState = nativeCrashState
        if (!crashState.startsWith("ok")) {
            diag.add("mediacodec tier gated: $crashState")
            return null
        }
        val marker = attemptMarker()
        return try {
            marker?.writeText(System.currentTimeMillis().toString())
            val jpeg = AvifMediaCodecDecoder.decodeToJpeg(bytes, { row -> diag.add("mediacodec: ${row.take(48)}") }, if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY)
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

    /** Harness hook for the v108 framework tier: drives the MediaCodec path
     * against a diag trail without an HTTP round-trip. Debug-only — the
     * v109 ladder no longer calls the codec tier (the field device died
     * inside it), but the decline behavior stays pinned. */
    internal fun debugMediaCodecTier(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int = 0): ByteArray? = decodeWithMediaCodecFramework(bytes, diag, maxEdge)

    /** Harness hook for the v110 WebView tier. */
    internal fun debugWebViewTier(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int = 0): Bitmap? = decodeWithWebView(bytes, diag, maxEdge)

    /** Harness hook: the crash-loop breaker state machine (consumes a stale
     * marker exactly like the native tier's hook does). */
    internal fun debugWebViewCrashLoopState(): String = evaluateWebViewCrashState()

    /** Harness hook: holds the shared-WebView permit so the busy-decline
     * path is drivable without a real concurrent decode. */
    internal fun debugHoldWebViewPermit(): java.lang.AutoCloseable {
        webViewDecodePermits.acquire()
        return java.lang.AutoCloseable { webViewDecodePermits.release() }
    }

    /** v110: ALL WebView decodes are serialized process-wide. The host
     * fires covers + reader pages + the preload window CONCURRENTLY; in
     * v109 each of those built its own offscreen WebView — parallel
     * Chromium boots, stacked renderer processes and simultaneous
     * full-size bitmap allocations (two concurrent ~96 MB covers) on a
     * 2-4 GB device: OOM kill, "the app just crashes". One permit, one
     * shared WebView, one decode at a time; a caller that cannot get the
     * permit inside the bounded queue wait declines to the next tier.
     * Fair so the visible page is not starved by the preload window. */
    private val webViewDecodePermits = Semaphore(1, true)

    /** The one shared offscreen WebView (main-looper only). Created lazily,
     * reused for every decode. v109 created + destroyed a WebView per
     * image, and the destroy raced the still-queued JS probe callbacks —
     * evaluateJavascript on a destroyed WebView throws on the MAIN thread
     * with nothing to catch it: process death. Reuse removes
     * destroy-between-decodes entirely; the only destroy left is the
     * post-timeout heal in [dropSharedWebView], which runs while no live
     * job exists and stale callbacks are identity-guarded no-ops. */
    @Volatile
    private var sharedWebView: android.webkit.WebView? = null

    @Volatile
    private var wholeDocumentDrawEnabled = false

    /** The decode job the WebView callbacks currently serve. Identity —
     * not equality — so a recycled job can never resurrect stale
     * callbacks. */
    private val currentWebViewJob = java.util.concurrent.atomic.AtomicReference<WebViewJob?>()

    /** The WebView lives on the app's main looper (Chromium requires it);
     * server threads only await bounded latches — the main thread never
     * blocks on a decode. Built per call (a Handler is a cheap looper
     * wrapper) so no android.os class is ever touched outside the guarded
     * tier path — a property initializer here put the reference on the
     * constructor path and the JVM harness class-load died (the exact
     * failure the harness exists to catch). */
    private fun newMainHandler(): android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** One WebView decode: verdict refs + exactly-once latch. [finish]
     * wins once — later (stale) callbacks see [done] and do nothing. */
    private class WebViewJob {
        val bitmapRef = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        val stageRef = java.util.concurrent.atomic.AtomicReference("load")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)

        fun finish(stage: String, bitmap: Bitmap?) {
            if (!done.compareAndSet(false, true)) return
            stageRef.set(stage)
            bitmapRef.set(bitmap)
            latch.countDown()
        }
    }

    /**
     * v110 WebView crash-loop breaker — the same defense the native tier
     * has, because Chromium has the same failure class: a death inside the
     * WebView stack (provider boot abort, vendor WebView bug, a host-side
     * native OOM) is NATIVE — no Java guard can catch it, and the field
     * device crash-looped through exactly this hole. Files live next to
     * the native tier's tracking dir:
     *
     * - `webview.attempt` — written right before the decode job is posted
     *   to the main looper, deleted right after the verdict lands. A
     *   stale marker = the previous process died INSIDE the WebView stage.
     * - `webview.crashes` — how many stale markers were consumed.
     *
     * One stale marker disables the tier for this session; two disable it
     * permanently (until the user clears the source's cache). The worst
     * case is now two process deaths per install — then legible error
     * cards carrying this exact reason in the diag trail, never a
     * crash loop.
     */
    internal fun evaluateWebViewCrashState(): String {
        val dir = nativeCacheDir?.let { java.io.File(it, "procomic_native") }
            ?: return "ok (no tracking dir)"
        val attempt = java.io.File(dir, "webview.attempt")
        val counter = java.io.File(dir, "webview.crashes")
        if (!attempt.exists()) return "ok"
        val previous = runCatching { counter.readText().trim().toIntOrNull() ?: 0 }.getOrDefault(0)
        val count = previous + 1
        runCatching {
            counter.writeText(count.toString())
            attempt.delete()
        }
        return if (count >= MAX_WEBVIEW_CRASHES) {
            "disabled after $count webview decode crashes (clear the source's cache to re-enable)"
        } else {
            "disabled this session after a webview decode crash (crashes=$count)"
        }
    }

    private val webViewCrashState: String by lazy { evaluateWebViewCrashState() }

    /** The before-decode marker file, or null when there is nowhere to
     * track (harness / restricted storage) — tracking is best-effort. */
    private fun webViewAttemptMarker(): java.io.File? {
        val base = nativeCacheDir ?: return null
        return runCatching {
            val dir = java.io.File(base, "procomic_native")
            dir.mkdirs()
            java.io.File(dir, "webview.attempt")
        }.getOrNull()
    }

    /**
     * The WebView decode tier (v110) — the framework-guaranteed,
     * PROCESS-SAFE AVIF decoder for Android 10/11.
     *
     * Why: the host's Chromium WebView decodes AVIF natively (Chromium 85+;
     * the WebView package auto-updates via Play, including on Android 10),
     * it is a FULL image decoder (single items AND grid AVIF), every
     * catchable failure is caught, and a genuine Chromium death dies inside
     * the sandboxed renderer — or, worst case, is bounded by the v110
     * crash-loop breaker above.
     *
     * v110 — the v109 field device STILL died in this tier, three ways:
     * 1. No concurrency gate: the host fires covers, pages and the preload
     *    window concurrently; each built its own offscreen WebView —
     *    parallel Chromium boots and stacked renderer processes on a
     *    2-4 GB device.
     * 2. Full-size draw allocations: the WebView was measured at the
     *    image's natural size, so a 4000x6000 "originals" cover allocated
     *    a ~96 MB ARGB bitmap per decode — two concurrent decodes (the
     *    transcode stage allows two) was an OOM kill.
     * 3. The probe-after-destroy race: destroy() was posted when the
     *    verdict (or the 20 s timeout) landed while JS probe retries were
     *    still queued — evaluateJavascript on a destroyed WebView throws
     *    on the MAIN thread with nothing to catch it: process death.
     *
     * All three are structurally gone: ONE shared WebView reused across
     * decodes behind a process-wide permit (never destroyed between
     * decodes), draws bounded by the ?e= render target, and every callback
     * identity-checked against the current job and Throwable-guarded. The
     * crash-loop breaker bounds anything native left to two process deaths
     * per install, then legible error cards.
     *
     * Flow: the AVIF bytes ride a data: URL inside a minimal HTML document
     * loaded into the shared offscreen WebView on the app's main looper
     * (server threads only await bounded latches — the main thread never
     * blocks). JavaScript reports the image's naturalWidth x naturalHeight
     * (or an error / pending state), the WebView is measured + laid out at
     * the (possibly ?e=-scaled) render size and drawn into a software
     * ARGB_8888 bitmap. Every stage lands in the diag trail.
     */
    private fun decodeWithWebView(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int): Bitmap? {
        val context = webViewContext ?: run {
            diag.add("webview: unavailable (no context)")
            return null
        }
        val crashState = webViewCrashState
        if (!crashState.startsWith("ok")) {
            diag.add("webview tier gated: $crashState")
            return null
        }
        if (bytes.size > WEBVIEW_MAX_PAYLOAD_BYTES) {
            diag.add("webview: declined (${bytes.size} B payload > cap)")
            return null
        }
        // Pixel budget up front: parse the container in pure Kotlin so an
        // absurd image never allocates a WebView or a bitmap. A parser
        // refusal still tries the WebView — Chromium is a full decoder that
        // handles shapes the lenient parser rejects.
        AvifMediaCodecDecoder.parseAvifImage(bytes)?.let { layout ->
            val pixels = layout.outputWidth.toLong() * layout.outputHeight
            if (pixels > MAX_NATIVE_DECODE_PIXELS) {
                diag.add("webview: declined (${layout.outputWidth}x${layout.outputHeight} exceeds budget)")
                return null
            }
        }
        val base64 = runCatching {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrElse {
            diag.add("webview: base64 ${it.javaClass.simpleName}")
            return null
        }
        // Covers (?e= edge) scale the img via CSS so the DRAW bitmap is the
        // bounded target size — see finishProbe. Pages keep 1:1 px mapping
        // (useWideViewPort stays off) so the natural-size draw is exact.
        val scaleCss = if (maxEdge > 0) "img{max-width:100%;height:auto;display:block}" else "img{display:block}"
        val html = "<html><head><style>html,body{margin:0;padding:0}$scaleCss</style></head>" +
            "<body><img src=\"data:image/avif;base64,$base64\"></body></html>"

        // One decode at a time through the shared WebView; bounded queue
        // wait — a starved caller declines to the next tier instead of
        // pinning a server thread behind a long cover-grid queue.
        val acquired = try {
            webViewDecodePermits.tryAcquire(WEBVIEW_QUEUE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            diag.add("webview: busy (another decode holds the shared WebView)")
            return null
        }
        val job = WebViewJob()
        val marker = webViewAttemptMarker()
        try {
            marker?.writeText(System.currentTimeMillis().toString())
            val posted = try {
                newMainHandler().post { runWebViewDecode(context, job, html, maxEdge) }
                true
            } catch (t: Throwable) {
                job.finish("post ${t.javaClass.simpleName}", null)
                false
            }
            if (posted) {
                try {
                    job.latch.await(WEBVIEW_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (!job.done.get()) job.finish("timeout", null)
                // A timed-out decode may leave a wedged renderer behind —
                // drop the shared WebView so the next decode rebuilds it.
                // Runs after the job is finished: stale callbacks are
                // identity-guarded no-ops, and destroy() itself is on the
                // thread the WebView was created on (the main looper).
                if (job.stageRef.get() == "timeout") {
                    runCatching { newMainHandler().post { dropSharedWebView() } }
                }
            }
            val bitmap = job.bitmapRef.getAndSet(null)
            diag.add("webview: ${job.stageRef.get()}")
            return bitmap
        } finally {
            // Deleted on every HANDLED outcome; only a process death inside
            // the WebView stage (which skips this finally) leaves it stale.
            runCatching { marker?.delete() }
            webViewDecodePermits.release()
        }
    }

    /** Main-looper body of one WebView decode against the SHARED WebView.
     * Everything is guarded: a throw here must decline the decode, never
     * reach the main looper's uncaught-exception handler (that kills the
     * host app — the exact v109 crash shape). */
    private fun runWebViewDecode(context: android.content.Context, job: WebViewJob, html: String, maxEdge: Int) {
        var webView: android.webkit.WebView? = null
        try {
            webView = obtainSharedWebView(context) ?: run {
                job.finish("init failed", null)
                return
            }
            currentWebViewJob.set(job)
            webView.webViewClient = sharedWebViewClient(maxEdge)
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            currentWebViewJob.compareAndSet(job, null)
            job.finish("init ${t.javaClass.simpleName}", null)
            // A half-built or broken instance is destroyed and dropped so
            // the next decode starts clean.
            webView?.let { broken ->
                runCatching { broken.destroy() }
                if (sharedWebView === broken) sharedWebView = null
            }
        }
    }

    private fun obtainSharedWebView(context: android.content.Context): android.webkit.WebView? {
        sharedWebView?.let { return it }
        if (!wholeDocumentDrawEnabled) {
            // Without this Chromium software-draws only the visible
            // viewport — tall-page captures would be blank or partial.
            runCatching { android.webkit.WebView.enableSlowWholeDocumentDraw() }
            wholeDocumentDrawEnabled = true
        }
        val webView = android.webkit.WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.blockNetworkLoads = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.useWideViewPort = false
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        sharedWebView = webView
        return webView
    }

    /** Destroys + clears the shared WebView (main looper only). */
    private fun dropSharedWebView() {
        val webView = sharedWebView ?: return
        sharedWebView = null
        currentWebViewJob.set(null)
        runCatching { webView.destroy() }
    }

    /** Every callback dispatches to the CURRENT job, identity-checked and
     * Throwable-guarded: a stale callback (previous decode, post-destroy
     * delivery) is a no-op, and nothing here can reach the main looper's
     * uncaught-exception handler — that is what killed the v109 app. */
    private fun sharedWebViewClient(maxEdge: Int): android.webkit.WebViewClient = object : android.webkit.WebViewClient() {
        private var probed = false

        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
            if (probed) return
            probed = true
            val job = currentWebViewJob.get() ?: return
            runCatching { probe(view, job, 0) }
        }

        /** The <img> may still be decoding a large data URL when the page
         * settles — 'pending' retries, bounded. */
        private fun probe(view: android.webkit.WebView, job: WebViewJob, attempt: Int) {
            if (job.done.get()) return
            if (attempt > WEBVIEW_PROBE_MAX_RETRIES) {
                job.finish("probe exhausted", null)
                return
            }
            view.evaluateJavascript(WEBVIEW_PROBE_JS) { value ->
                runCatching {
                    if (job.done.get() || currentWebViewJob.get() !== job) return@runCatching
                    val text = value ?: "null"
                    if (text.contains("pending")) {
                        newMainHandler().postDelayed({
                            runCatching {
                                if (!job.done.get() && currentWebViewJob.get() === job) probe(view, job, attempt + 1)
                            }
                        }, WEBVIEW_PROBE_RETRY_MS)
                    } else {
                        finishProbe(view, job, text)
                    }
                }
            }
        }

        private fun finishProbe(view: android.webkit.WebView, job: WebViewJob, text: String) {
            val parsed = Regex("(\\d+)x(\\d+)").find(text)
            val width = parsed?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = parsed?.groupValues?.get(2)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) {
                job.finish("image ${text.take(24)}", null)
                return
            }
            // v110: the DRAW bitmap is bounded. Covers render at the ?e=
            // edge (CSS-scaled above) — a 4000x6000 "originals" cover draws
            // into ~1280x1920 (~9 MB) instead of the ~96 MB full-size ARGB
            // allocation that killed the v109 process. Pages keep the
            // natural size under the 25 MP budget gate above.
            val drawWidth: Int
            val drawHeight: Int
            if (maxEdge > 0) {
                val longEdge = maxOf(width, height)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge
                    drawWidth = (width * scale).toInt().coerceAtLeast(1)
                    drawHeight = (height * scale).toInt().coerceAtLeast(1)
                } else {
                    drawWidth = width
                    drawHeight = height
                }
            } else {
                drawWidth = width
                drawHeight = height
            }
            try {
                view.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(drawWidth, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(drawHeight, android.view.View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, drawWidth, drawHeight)
                val bitmap = Bitmap.createBitmap(drawWidth, drawHeight, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                job.finish("decoded ${drawWidth}x$drawHeight", bitmap)
            } catch (t: Throwable) {
                job.finish("draw ${t.javaClass.simpleName}", null)
            }
        }
    }

    /**
     * The bundled software decoder tier. Every failure mode lands in the
     * diag trail with its ACTUAL reason — the v104 fix exists because the
     * previous bare runCatching swallowed the one fact that mattered (the
     * JNI library never loaded), leaving "bitmapfactory+libavif decode
     * failed" as the only clue.
     *
     * v106 — the tier is now CRASH-LOOP-GATED. A native crash inside the
     * JNI (dav1d/decoder bug, exotic bitstream, OEM-specific abort) cannot
     * be caught in Java and takes the whole host-app process down — that
     * was the 1.6.19 chapter-load crash and the 1.6.20 cover-load crash on
     * Android 10 arm64. Defense: a marker file is written before every
     * native decode and deleted right after it returns; a marker found at
     * process start means the PREVIOUS process died mid-decode, so the
     * native tier is disabled for this session (and permanently after the
     * second death) — the app stays alive and the platform tiers keep the
     * pipeline functional. Also: ARGB_8888 output only (the RGBA_F16 path
     * for 10-bit content is a native-crash surface) and oversized images
     * are refused before any bitmap allocation.
     */
    private fun decodeWithLibavif(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int): Bitmap? {
        val nativeState = avifNativeState
        diag.add("avif native: $nativeState")
        if (nativeState != "system" && !nativeState.startsWith("loaded")) return null
        val crashState = nativeCrashState
        if (!crashState.startsWith("ok")) {
            diag.add("native tier gated: $crashState")
            return null
        }
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
                    val pixels = info.width.toLong() * info.height
                    if (pixels > MAX_NATIVE_DECODE_PIXELS) {
                        diag.add("libavif: refused (" + pixels + " px exceeds the software-decode budget)")
                        null
                    } else {
                        // ARGB_8888 ALWAYS — never RGBA_F16. The F16 path
                        // for >8-bit content was the native-crash suspect
                        // on the field device; 10-bit input decodes into
                        // 8-bit output (tone-truncated) instead of dying.
                        val bitmap = try {
                            Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                        } catch (_: OutOfMemoryError) {
                            diag.add("libavif: OOM at ARGB_8888")
                            null
                        } ?: return null
                        val marker = attemptMarker()
                        val decoded: Boolean = try {
                            marker?.writeText(System.currentTimeMillis().toString())
                            org.aomedia.avif.android.AvifDecoder.decode(java.nio.ByteBuffer.wrap(bytes), bytes.size, bitmap)
                        } finally {
                            // Deleted on every HANDLED outcome; only a native
                            // crash (which skips this finally) leaves it stale.
                            runCatching { marker?.delete() }
                        }
                        if (decoded) {
                            if (maxEdge > 0) downscaleTo(bitmap, maxEdge, diag) else bitmap
                        } else {
                            bitmap.recycle()
                            diag.add("libavif: decode returned false")
                            null
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            diag.add("libavif threw ${t.javaClass.simpleName}: ${t.message?.take(60)}")
            null
        }
    }

    /**
     * Crash-loop breaker state, evaluated ONCE per process on the first
     * software-decode attempt. Files live next to the extracted .so:
     *
     * - `decode.attempt` — written right before a native decode, deleted
     *   right after it returns. Stale marker = the previous process died
     *   INSIDE the decoder.
     * - `decode.crashes` — how many stale markers were consumed.
     *
     * One stale marker disables the native tier for this session; two
     * disable it permanently (until the user clears the app's cache).
     * That bounds a worst-case native crash loop to two process deaths
     * per install while tolerating one false positive (an OOM kill of the
     * process mid-decode looks identical to a native crash).
     */
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
     * track (harness / restricted storage) — tracking is best-effort. */
    private fun attemptMarker(): java.io.File? {
        val base = nativeCacheDir ?: return null
        return runCatching {
            val dir = java.io.File(base, "procomic_native")
            dir.mkdirs()
            java.io.File(dir, "decode.attempt")
        }.getOrNull()
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
     *
     * v107 — "our own installed APK" was resolved from the HOST application
     * context (keiyoushi.utils.applicationContext IS Injekt's Application —
     * extensions have no Context of their own), so the scan always ran
     * against the host apk, never found the entry, and the native tier was
     * dead on every device (the field report: "avif native: unavailable (no
     * libavif_android.so entry)"). The path now arrives from the package
     * manager (ProComic.kt, our own package); this loader additionally
     * derives candidates from its own classloader's dex paths and verifies
     * the .so entry in EVERY candidate zip before loading — a wrong
     * candidate costs one wasted scan, never a silent miss. The scanned
     * zips and their per-file outcomes land in the diag trail so the next
     * field report names the exact zips seen.
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
        val cacheBase = nativeCacheDir ?: return "unavailable (no cache dir)"
        return nativeCandidateWalk(cacheBase)
    }

    /** The candidate walk, probe-free: every apk zip that might carry the
     * bundled .so is scanned in trust order and the first entry-positive
     * one is extracted + loaded. Probe-independent so the JVM harness can
     * drive it deterministically (the probe outcome there depends on
     * whether this particular JVM has an avif_android library reachable,
     * which is an environment artifact, not a pipeline property). */
    internal fun nativeCandidateWalk(cacheBase: java.io.File): String {
        if (android.os.Build.SUPPORTED_ABIS.isEmpty()) return "unavailable (unknown abi)"
        val candidates = apkCandidates()
        if (candidates.isEmpty()) return "unavailable (no apk source)"
        val scanned = StringBuilder()
        for (apkPath in candidates) {
            val outcome = scanAndLoadFromApk(apkPath, cacheBase)
            if (outcome.startsWith("loaded")) return outcome
            scanned.append('[').append(apkPath.substringAfterLast('/').take(40)).append(": ").append(outcome).append("] ")
        }
        return "unavailable (no loadable libavif_android.so; scanned $scanned)"
    }

    /** Extract + System.load the bundled .so out of ONE apk-shaped zip.
     * Returns "loaded from apk (...)" on success, "no entry" when the zip
     * carries no ABI-matching libavif_android.so, or "<SimpleName>: <msg>"
     * for open/extract/load failures. Every outcome except "loaded" lets
     * the caller move on to the next candidate. */
    private fun scanAndLoadFromApk(apkPath: String, cacheBase: java.io.File): String = try {
        java.util.zip.ZipFile(apkPath).use { zip ->
            val entry = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { candidate ->
                zip.getEntry("lib/$candidate/libavif_android.so")
            }
            if (entry == null) {
                "no entry"
            } else {
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
        }
    } catch (t: Throwable) {
        "${t.javaClass.simpleName}: ${t.message?.take(60)}"
    }

    /** Zips that may carry our bundled .so, in trust order: the caller's
     * package-manager-resolved path (the EXTENSION's own apk since v107),
     * then the dex/apk paths this class was actually loaded from (some
     * hosts copy or relocate extension apks). Deduplicated; each candidate
     * is entry-verified before its library loads. */
    private fun apkCandidates(): List<String> = buildList {
        apkSourceDir?.takeIf { it.isNotBlank() }?.let(::add)
        addAll(apkPathsFromCallerClassloader())
    }.distinct()

    /** Best-effort reflection into BaseDexClassLoader.pathList.dexElements
     * to recover the apk paths of THIS classloader. Bounded and safe: on
     * the JVM harness (or any host that blocks these hidden fields) the
     * whole lookup returns an empty list.
     *
     * v108 fix: the v107 walk called loader.javaClass.getDeclaredField —
     * which only sees fields declared on the EXACT class, never inherited
     * ones. pathList is declared on BaseDexClassLoader while the concrete
     * loader is a PathClassLoader/InMemoryDexClassLoader subclass, so the
     * walk threw NoSuchFieldException on every real Android classloader and
     * silently contributed nothing (the field report: exactly one scanned
     * candidate, the pm path). The lookup now walks the superclass chain
     * via [declaredFieldUpTo]. */
    private fun apkPathsFromCallerClassloader(): List<String> = runCatching {
        val loader = this::class.java.classLoader ?: return emptyList()
        val pathList = declaredFieldUpTo(loader.javaClass, "pathList")
            ?.apply { isAccessible = true }?.get(loader) ?: return emptyList()
        val elements = declaredFieldUpTo(pathList.javaClass, "dexElements")
            ?.apply { isAccessible = true }?.get(pathList) as? Array<*> ?: return emptyList()
        elements.mapNotNull { element ->
            if (element == null) return@mapNotNull null
            val field = declaredFieldUpTo(element.javaClass, "path")?.apply { isAccessible = true }
                ?: return@mapNotNull null
            when (val value = runCatching { field.get(element) }.getOrNull()) {
                is java.io.File -> value.absolutePath
                is String -> value
                else -> null
            }
        }.filter { it.endsWith(".apk") }
    }.getOrDefault(emptyList())

    /** getDeclaredField across the superclass chain (Class.getDeclaredField
     * only sees the exact class — see [apkPathsFromCallerClassloader]).
     * Internal so the harness pins the walk itself. */
    internal fun declaredFieldUpTo(start: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = start
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /** Harness hooks for the v107 loader: the resolved candidate list, the
     * per-zip scan outcome ("loaded..." can only happen on a device — the
     * JVM cannot dlopen an ARM ELF, so an entry-positive scan ends at a
     * load-stage failure there), and the fully evaluated native state. */
    internal fun debugApkCandidates(): List<String> = apkCandidates()

    internal fun debugScanApk(apkPath: String, cacheBase: java.io.File): String = scanAndLoadFromApk(apkPath, cacheBase)

    internal fun debugNativeState(): String = avifNativeState
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

    /** Finished results keyed by the sha256 of the upstream URL (+#e<edge>
     * for downscaled cover variants). Bounded by COUNT and by BYTES: tall
     * page JPEGs reach ~2 MB each, so a count-only bound could hold ~48 MB
     * of dead bytes forever (v106 budget: 32 MB) — a real memory-pressure
     * contributor on the devices this source serves. */
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
        sb.append("\"server\":\"procomic-image/v110\"")
        sb.append(",\"uptimeMs\":").append(System.currentTimeMillis() - startedAtMs)
        synchronized(resultsLock) {
            sb.append(",\"cachedResults\":").append(results.size)
            sb.append(",\"cachedBytes\":").append(cachedBytes)
        }
        sb.append(",\"nativeTier\":\"").append(avifNativeState.replace("\"", "'"))
        sb.append(",\"nativeCrashState\":\"").append(nativeCrashState.replace("\"", "'")).append('\"')
        sb.append(",\"webviewCrashState\":\"").append(webViewCrashState.replace("\"", "'")).append('\"')
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
                add("Server build: procomic-image/v110")
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
                httpUrl.host == "prochan.net" ||
                httpUrl.host == "www.prochan.net" ||
                httpUrl.host.endsWith(".procomic.net") ||
                httpUrl.host.endsWith(".procomic.pro") ||
                httpUrl.host.endsWith(".prochan.net")
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

        /** Fixed loopback port tried first (v105): proxy URLs ride in
         * persisted host-app data, so the port must survive restarts. An
         * obscure, IANA-unassigned port on purpose. */
        private const val PREFERRED_PORT = 44097

        /** The /.diag substring that identifies a sibling process of this
         * extension's image server (version-agnostic on purpose). */
        private const val SERVER_FAMILY_MARKER = "procomic-image/"

        private const val FAMILY_PROBE_TIMEOUT_MS = 750

        /** Two transcodes at a time: the decode target is a tall ARGB_8888
         * bitmap (up to ~26 MB at 800x8000) and the software AVIF decode is
         * CPU heavy; the permit covers ONLY the bitmap stage and the decode
         * target falls back to RGB_565 when the full-quality allocation
         * cannot be made. */
        private const val MAX_CONCURRENT_TRANSCODES = 2

        /** Finished-result cache (bytes per entry, URL-keyed). Covers share
         * this pool with pages since v105 (covers are ~65 KB, pages are the
         * heavy tail) — 24 keeps a catalog page's covers plus the reader's
         * preload window resident at once. */
        private const val MAX_CACHED_RESULTS = 24

        /** v106: byte budget on top of the count — 24 entries x 2 MB tall
         * pages could pin ~48 MB forever. 32 MB keeps the same hit-rate
         * behavior while capping the resident cost. */
        private const val MAX_CACHED_BYTES = 32L * 1024 * 1024

        /** Covers (v106) request the proxy with ?e=1280: decoded long edge
         * capped at 1280 px (~6 MB ARGB at 16:9 worst case instead of a
         * 90 MB full "originals" decode) and JPEG-encoded at cover quality. */
        const val COVER_PROXY_MAX_EDGE = 1280
        private const val MIN_DOWNSCALE_EDGE = 64
        private const val MAX_DOWNSCALE_EDGE = 4096
        private const val COVER_JPEG_QUALITY = 82

        /** Software-decode pixel budget: ~25 MP (~100 MB ARGB_8888) is the
         * largest allocation the native tier will even attempt; larger
         * "originals" fall through to the platform tiers or the error JPEG
         * instead of OOM-killing a phone. */
        private const val MAX_NATIVE_DECODE_PIXELS = 25_000_000L

        /** Stale decode-attempt markers tolerated before the native tier is
         * disabled permanently (bounds the native crash loop). */
        private const val MAX_NATIVE_CRASHES = 2

        /** v109 WebView tier: data-URL payload cap. A page's base64 rides
         * in memory inside the WebView — beyond this the tier declines
         * (the libavif tier or the error page carries it instead). */
        private const val WEBVIEW_MAX_PAYLOAD_BYTES = 2_500_000

        /** v109 WebView tier: whole-decode deadline. Chromium's first-ever
         * init on a cold process can take seconds; past this the tier
         * declines and the ladder moves on. */
        private const val WEBVIEW_DEADLINE_MS = 20_000L

        /** v109 WebView tier: the <img> decode probe re-checks while the
         * image is still pending — bounded retries, then decline. v110:
         * the window matches the whole-decode deadline (90 x 200 ms) so a
         * slow first decode is not declined early. */
        private const val WEBVIEW_PROBE_MAX_RETRIES = 90
        private const val WEBVIEW_PROBE_RETRY_MS = 200L

        /** v110 WebView tier: bounded queue wait for the shared-WebView
         * permit. A starved caller declines to the next tier instead of
         * pinning a server thread behind a long cover-grid queue. */
        private const val WEBVIEW_QUEUE_WAIT_MS = 8_000L

        /** v110 WebView tier: stale attempt markers tolerated before the
         * tier is disabled permanently (bounds the Chromium crash loop —
         * the same breaker the native tier has). */
        private const val MAX_WEBVIEW_CRASHES = 2

        /** v109 WebView tier: the size probe — natural dimensions when the
         * image decoded, 'imgerr' when the WebView refused the format
         * (WebView package too old for AVIF), 'pending' while still busy. */
        private const val WEBVIEW_PROBE_JS =
            "(function(){var i=document.images[0];return !i?'noimg':(!i.complete?'pending':(i.naturalWidth>0?i.naturalWidth+'x'+i.naturalHeight:'imgerr'))})()"

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
