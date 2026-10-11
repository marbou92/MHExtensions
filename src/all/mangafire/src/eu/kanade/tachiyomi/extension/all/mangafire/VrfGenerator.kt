package eu.kanade.tachiyomi.extension.all.mangafire

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Protection-token ("vrf") generator for mangafire.to.
 *
 * The site's React SPA signs every protected /api call with a `vrf` query
 * parameter produced by an obfuscated, VM-protected script (the "polyfill"
 * chunk: a bytecode VM with WASM-backed transforms, XOR keys derived from
 * native function arity fingerprints and navigator.appCodeName — all values
 * that are identical on every JS engine, so the exact bytes of the site's
 * script can run offline inside a plain WebView).
 *
 * This generator ships the site's polyfill chunk (transformed: its ESM
 * export is re-pointed at a global) inside a self-contained HTML asset,
 * drives it with the same mock axios the SPA bootstraps it with
 * (`extendClient(instance)` registers the site's own request interceptor),
 * and asks that interceptor for the token — the site's code does the signing.
 *
 * For a call with path P and params K→V the interceptor computes:
 *
 *   canonical = P + "?" + sorted("k=v" pairs; arrays as "k[i]=v", objects
 *                            as "k[key]=v"; raw, unencoded values)
 *   vrf       = dynamicEncrypt(canonical)
 *
 * and the wire request carries the same params in standard encoding plus
 * `&vrf=<token>`; the server rebuilds the canonical from its parsed query
 * and compares. `dynamicEncrypt` is length-preserving byte mixing over a
 * static key with unpadded base64url output.
 *
 * The WebView is created once and kept warm; tokens are cached per canonical
 * request (the algorithm is a pure function of the input).
 */
@SuppressLint("SetJavaScriptEnabled")
internal object VrfGenerator {

    private const val READY_TIMEOUT_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var webView: WebView? = null

    /** Set once the WebView has loaded the asset and `window.__mfVrf` exists. */
    private var readyLatch: CountDownLatch? = null

    /** canonical request string -> vrf token (the algorithm is pure). */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 400
    }

    /** Raw (unencoded) parameter value. */
    internal sealed class Param {
        data class Text(val value: String) : Param()
        data class ListValue(val values: List<String>) : Param()
        data class MapValue(val values: Map<String, String>) : Param()
    }

    internal fun scalar(value: String) = Param.Text(value)
    internal fun list(values: List<String>) = Param.ListValue(values)
    internal fun obj(values: Map<String, String>) = Param.MapValue(values)

    /**
     * Computes the `vrf` token for [path] with [params]. Blocks the caller —
     * call from a worker thread only.
     */
    fun token(path: String, params: Map<String, Param>): String {
        val canonical = canonicalKey(path, params)
        synchronized(lock) {
            cache[canonical]?.let { return it }
        }

        awaitReady()

        val paramsJson = buildParamsJson(params).toString()
        val pathJson = JsonPrimitive(path).toString()
        val script = "window.__mfVrf($pathJson, $paramsJson)"

        // The machine is initialized; a single evaluation is enough. Retry
        // only for transient evaluate failures, briefly.
        var token: String? = null
        for (attempt in 1..3) {
            token = evaluate(script)
            if (token != null) break
            Thread.sleep(150L * attempt)
        }

        token ?: throw IOException(
            "MangaFire: could not sign the request (the site's protection script failed to initialize)",
        )

        synchronized(lock) {
            cache[canonical] = token
        }
        return token
    }

    /**
     * Creates and warms the WebView ahead of the first request so the token
     * machine is ready when the UI asks for it. Safe to call from any thread;
     * the actual work happens on the main thread. Idempotent.
     */
    fun warmUp() {
        runCatching { awaitReady() }
    }

    /** Blocks until the WebView has loaded the signing asset, or throws. */
    private fun awaitReady() {
        val latch = synchronized(lock) {
            readyLatch ?: run {
                val new = CountDownLatch(1)
                readyLatch = new
                mainHandler.post { initialize() }
                new
            }
        }
        if (!latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException(
                "MangaFire: the signing WebView did not initialize in time — try again",
            )
        }
    }

    /** Runs on the main thread. Creates the WebView, loads the asset, and
     * waits for the page's own `__mfVrfReady` flag before releasing callers. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initialize() {
        val latch = synchronized(lock) { readyLatch } ?: return
        try {
            val wv = webView ?: WebView(applicationContext).also { wv ->
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                // The page is a self-contained bundle; it never needs network.
                runCatching { wv.settings.blockNetworkLoads = true }
                webView = wv
            }
            // Release the waiters only after the page's scripts have actually
            // executed - not on a timer, not on a best-effort guess. All the
            // polling stays on the main thread and fully async (never block
            // the UI thread on a WebView callback).
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun poll(attempt: Int) {
                        view.evaluateJavascript("!!window.__mfVrfReady") { value ->
                            if (value?.contains("true") == true) {
                                latch.countDown()
                            } else if (attempt < 25) {
                                mainHandler.postDelayed({ poll(attempt + 1) }, 200)
                            }
                        }
                    }
                    poll(0)
                }
            }
            wv.loadDataWithBaseURL("https://mangafire.to/", assetHtml(), "text/html", "UTF-8", null)
        } catch (_: Throwable) {
            // Let awaitReady() time out with a clear IOException instead of
            // leaving callers blocked forever.
            latch.countDown()
        }
    }

    /** Same ordering rule the site's interceptor applies to its own input. */
    private fun canonicalKey(path: String, params: Map<String, Param>): String {
        if (params.isEmpty()) return path
        val serialized = params.entries.flatMap { (key, value) ->
            when (value) {
                is Param.Text -> listOf("$key=${value.value}")
                is Param.ListValue -> value.values.mapIndexed { i, v -> "$key[$i]=$v" }
                is Param.MapValue -> value.values.map { (k, v) -> "$key[$k]=$v" }
            }
        }.sorted()
        return "$path?${serialized.joinToString("&")}"
    }

    private fun buildParamsJson(params: Map<String, Param>) = buildJsonObject {
        params.forEach { (key, value) ->
            when (value) {
                is Param.Text -> put(key, JsonPrimitive(value.value))
                is Param.ListValue -> put(
                    key,
                    buildJsonArray { value.values.forEach { add(JsonPrimitive(it)) } },
                )
                is Param.MapValue -> put(
                    key,
                    buildJsonObject { value.values.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
                )
            }
        }
    }

    /** Runs [script] on the WebView and returns `out.vrf`, or null. */
    private fun evaluate(script: String): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        mainHandler.post {
            try {
                val wv = webView
                if (wv == null) {
                    latch.countDown()
                    return@post
                }
                wv.evaluateJavascript(script) { value ->
                    try {
                        // evaluateJavascript returns a quote-wrapped JSON string.
                        val payload = Json.parseToJsonElement(value).jsonPrimitive.content
                        val parsed = Json.parseToJsonElement(payload).jsonObject
                        val vrf = parsed["vrf"]
                        if (parsed["ok"]?.jsonPrimitive?.content == "true" && vrf != null && vrf !is JsonNull) {
                            result.set(vrf.jsonPrimitive.content)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return result.get()
    }

    private fun assetHtml(): String = applicationContext.assets.open("mfvrf.html").bufferedReader().use { it.readText() }
}
