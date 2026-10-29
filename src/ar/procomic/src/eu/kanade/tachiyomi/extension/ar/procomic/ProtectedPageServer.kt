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
 *    on exactly the devices this source serves. v111 (the new way): the
 *    AVIF is re-fetched through the weserv image proxy (`output=jpg`) so
 *    the TRANSCODE HAPPENS SERVER-SIDE and this process only ever decodes
 *    plain JPEG/PNG/WebP with BitmapFactory — on Android 10/11 NO AVIF
 *    decoder runs on the device at all. Every decoder that ever killed
 *    the host app (bundled libavif JNI, framework MediaCodec, the
 *    offscreen WebView) is GONE from the ladder; the worst case is now a
 *    legible error card, never a crash.
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
     * A writable cache dir — the crash-loop breaker's marker files for the
     * DEBUG-ONLY MediaCodec hook live here. The production ladder never
     * runs native code (v111), so tracking is best-effort and optional so
     * the JVM harness can drive the server without one.
     */
    private val nativeCacheDir: java.io.File? = null,
    /**
     * v111: the server-side transcode hosts, tried in order. Overridable
     * so the JVM harness can pin the both-hosts-fail path deterministically
     * without touching the network defaults.
     */
    private val proxyHosts: List<String> = DEFAULT_PROXY_HOSTS,
) {

    private class Machine(val socket: ServerSocket)

    private val machine = AtomicReference<Machine?>()
    private val startLock = Any()

    private val startedAtMs = System.currentTimeMillis()

    /** v116: the current x-chapter-pass credential (the pages-scope p1 JWT
     * the deferred exchange holds and renews). Attached to signed-proxy
     * fetches — the same header the site's own reader sends on protected
     * media. Optional so the JVM harness can drive the server bare. */
    @Volatile
    var chapterPassProvider: (() -> String?)? = null

    /** v116: mints a fresh signed-proxy URL for a rejected or expired one
     * (the source re-signs the wrapped CDN url through the site's own
     * POST /api/cdn-image/sign with its gated session). Returning null
     * keeps the legible failure + diag trail. */
    @Volatile
    var deferredResigner: ((String) -> String?)? = null

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
                transcodeUnderPermit(raw, imageUrl, diag, maxEdge)
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

    /** The transcode stage under the fair semaphore: proxy fetch + decode
     * + JPEG encode. Raw bytes are already in memory — the proxy fetch is
     * a small network hop that shares the permit so a cover grid cannot
     * stampede the transcoder. */
    private fun transcodeUnderPermit(raw: ByteArray, imageUrl: String, diag: ProxyDiag, maxEdge: Int): ByteArray {
        transcodePermits.acquire()
        try {
            return avifToJpeg(raw, imageUrl, diag, maxEdge)
        } finally {
            transcodePermits.release()
        }
    }

    /** v116: the signed-proxy URL class — /api/cdn-image answers mint
     * short-lived (~15 min observed) signed urls whose token rides the
     * query. These get the pass header, the session-first fetch order and
     * the re-sign rung; plain CDN/app-host urls keep the v101 ladder. */
    internal fun isSignedProxyUrl(imageUrl: String): Boolean = imageUrl.contains("/api/cdn-image")

    /** One upstream fetch attempt with the exact headers a browser <img>
     * sends. Caller retries/cascades. v116: [pass] rides as x-chapter-pass
     * on signed-proxy urls — the credential the site's reader attaches to
     * the same requests. */
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

    /** v116 fetch ladder. Signed-proxy urls (/api/cdn-image) try the
     * SESSION client first (the fetch may be cookie-gated exactly like
     * the exchange that minted them), then the bare inner client; every
     * other host keeps the v101 order (inner first, session as the
     * retry). When a signed url is rejected outright by the edge (410
     * expired / 403), the source's re-sign hook mints a fresh signed url
     * out of the gated session and both clients retry it once — an
     * expired mid-read page heals itself instead of dying into an error
     * card. */
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
     * AVIF → JPEG, the v111 ladder — THE NEW WAY. After four field rounds
     * (v107 native loader, v108 MediaCodec, v109+v110 offscreen WebView)
     * every on-device AVIF decoder still found a way to kill the host app
     * on the affected Android 10 device, so the device no longer runs one:
     *
     * 1. BitmapFactory — the platform codec. On Android 12+ this decodes
     *    AVIF natively (and it also decodes the proxy tier's JPEG output
     *    on every version). Subsampled when a max edge is requested.
     * 2. SERVER-SIDE transcode — the same URL re-fetched through the
     *    weserv image proxy (`output=jpg`, both hosts tried in order), so
     *    the AVIF decode happens in their datacenter and this process only
     *    ever touches plain JPEG bytes. On Android 10/11 this is the
     *    production tier — there is NO AVIF decoder on the device at all.
     *
     * Everything else (bundled libavif JNI, framework MediaCodec, the
     * offscreen WebView, ImageDecoder) is REMOVED from the ladder: each was
     * a native-crash/OOM surface no Java guard could catch, and the field
     * device died inside all of them across v108-v110. A total decode
     * failure now throws the legible IOException -> the error card — never
     * a dead process.
     *
     * Every tier call site is Throwable-tight: an unexpected throw in one
     * tier (JVM stubs, an exotic device API misbehavior) must fall through
     * to the NEXT tier, never abort the ladder.
     */
    private fun avifToJpeg(bytes: ByteArray, imageUrl: String, diag: ProxyDiag, maxEdge: Int): ByteArray {
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
        // v111: the server-side transcode tier — weserv decodes the AVIF
        // in its datacenter and hands back a plain JPEG this process CAN
        // decode (BitmapFactory handles JPEG on every Android version).
        val proxyBitmap = try {
            fetchProxyJpeg(imageUrl, bytes, diag, maxEdge)
        } catch (t: Throwable) {
            diag.add("proxy tier threw ${t.javaClass.simpleName}")
            null
        }
        proxyBitmap?.let { bitmap ->
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
        throw IOException("ProComic: page failed to decode (server-side transcode unavailable)")
    }

    /**
     * The server-side transcode tier (v111): re-fetch [imageUrl] through
     * the weserv image proxy with `output=jpg` so the AVIF decode happens
     * on their infrastructure, then decode the returned JPEG with
     * BitmapFactory — the one decoder that exists and is safe on EVERY
     * Android version. Both proxy hosts are tried in order; every failure
     * lands in the diag trail and the tier returns null (the ladder then
     * throws the legible decode failure — the error card, never a crash).
     *
     * Sizing: covers ride their ?e= box (`w`+`h`+`fit=inside`+`we` — the
     * long edge is capped server-side, so a 4000x6000 "originals" cover
     * never even crosses the network at full size); pages without a max
     * edge get a box ONLY when the container (parsed in pure Kotlin from
     * the already-downloaded bytes) declares more pixels than the decode
     * budget — the proxy downscales instead of this process ever seeing a
     * budget-busting bitmap.
     */
    private fun fetchProxyJpeg(imageUrl: String, raw: ByteArray, diag: ProxyDiag, maxEdge: Int): Bitmap? {
        if (proxyHosts.isEmpty()) {
            diag.add("proxy: no hosts configured")
            return null
        }
        val encoded = runCatching { java.net.URLEncoder.encode(imageUrl, "UTF-8") }.getOrNull() ?: run {
            diag.add("proxy: encode failed")
            return null
        }
        val quality = if (maxEdge > 0) COVER_JPEG_QUALITY else JPEG_QUALITY
        val box = proxyBoxFor(proxyPixelBudgetGate(raw), maxEdge, diag)
        for (host in proxyHosts) {
            val url = proxyTranscodeUrl(imageUrl, encoded, quality, box, host)
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .build()
                innerClient.newCall(request).execute().use { response ->
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
                    // raster — an AVIF answer would send us in circles.
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
                    if (maxEdge > 0) options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
                    BitmapFactory.decodeByteArray(body, 0, body.size, options)?.let { return it }
                    diag.add("proxy/$host decode null")
                }
            } catch (t: Throwable) {
                diag.add("proxy/$host ${t.javaClass.simpleName}")
            }
        }
        return null
    }

    /** Pixels the raw container declares (pure-Kotlin ISOBMFF parse of the
     * already-downloaded bytes), or null when the parser refuses a shape —
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
     * inside it) and v111 removed every remaining on-device decoder, but
     * the decline behavior stays pinned. */
    internal fun debugMediaCodecTier(bytes: ByteArray, diag: ProxyDiag, maxEdge: Int = 0): ByteArray? = decodeWithMediaCodecFramework(bytes, diag, maxEdge)

    /**
     * Crash-loop breaker state for the DEBUG-ONLY MediaCodec hook,
     * evaluated ONCE per process on its first (debug-only) invocation.
     * The production v111 ladder never runs native code, so this only
     * matters to the harness hooks. Files live in the tracking dir:
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

    // v115: the last deferred-media attempt trail (the 3-page wall's
    // diagnosis). The reader only sees logcat; this ring gives /.diag
    // the full trail so a screenshot alone answers "mint fail or
    // exchange reject?".
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
        sb.append("\"server\":\"procomic-image/v116\"")
        sb.append(",\"uptimeMs\":").append(System.currentTimeMillis() - startedAtMs)
        synchronized(resultsLock) {
            sb.append(",\"cachedResults\":").append(results.size)
            sb.append(",\"cachedBytes\":").append(cachedBytes)
        }
        sb.append(",\"decoder\":\"bitmapfactory+server-proxy (v111, no on-device AVIF decoder)\"")
        sb.append(",\"proxyHosts\":\"").append(proxyHosts.joinToString(",").replace("\"", "'")).append('\"')
        sb.append(",\"nativeCrashState\":\"").append(nativeCrashState.replace("\"", "'")).append('\"')
        // v115: the last deferred-media attempt trail (the 3-page wall
        // diagnosis) — mint-fail vs exchange-reject at a glance.
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
                add("Server build: procomic-image/v116")
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
         * largest bitmap this process will even attempt; when the container
         * declares more, the v111 proxy tier downscales SERVER-SIDE (the
         * device never sees a budget-busting bitmap) and larger "originals"
         * fall through to the error JPEG instead of OOM-killing a phone. */
        private const val MAX_NATIVE_DECODE_PIXELS = 25_000_000L

        /** Stale decode-attempt markers tolerated before the DEBUG-ONLY
         * MediaCodec hook's tier is disabled permanently (bounds a native
         * crash loop for the hook; the production ladder never runs it). */
        private const val MAX_NATIVE_CRASHES = 2

        /** v111 server-side transcode hosts, tried in order. wsrv.nl is
         * weserv's alias domain — same service, second routing domain for
         * a network-level block of the primary host. */
        val DEFAULT_PROXY_HOSTS = listOf("images.weserv.nl", "wsrv.nl")

        /** Hard ceiling for the server-side downscale box derived from the
         * pixel budget (never larger than the cover edge cap allows). */
        private const val MAX_PROXY_BOX_EDGE = 8000

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
    }
}
