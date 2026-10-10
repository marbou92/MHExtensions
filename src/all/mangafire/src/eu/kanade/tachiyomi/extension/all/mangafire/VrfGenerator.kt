package eu.kanade.tachiyomi.extension.all.mangafire

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
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
 * native function fingerprints and navigator.appCodeName). The algorithm is
 * deterministic and — critically — does NOT depend on the site's runtime
 * `window.__config`, so the exact bytes of the site's own script can run
 * offline inside a plain WebView.
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

    private const val ASSET = "file:///android_asset/mfvrf.html"
    private const val ATTEMPTS = 12
    private const val RETRY_DELAY_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var webView: WebView? = null

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

        val paramsJson = buildParamsJson(params).toString()
        val pathJson = JsonPrimitive(path).toString()
        val script = "window.__mfVrf($pathJson, $paramsJson)"

        var token: String? = null
        for (attempt in 0 until ATTEMPTS) {
            token = evaluate(script)
            if (token != null) break
            Thread.sleep(RETRY_DELAY_MS)
        }

        token ?: throw IOException(
            "MangaFire: could not sign the request (the site's protection script failed to initialize)",
        )

        synchronized(lock) {
            cache[canonical] = token
        }
        return token
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

        val started = CountDownLatch(1)
        mainHandler.post {
            try {
                val wv = obtain() ?: return@post
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
                    }
                    latch.countDown()
                }
            } catch (_: Throwable) {
                latch.countDown()
            } finally {
                started.countDown()
            }
        }

        started.await()
        latch.await(10, TimeUnit.SECONDS)
        return result.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtain(): WebView? {
        webView?.let { return it }
        return runCatching {
            val wv = WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // The page is a self-contained bundle; it never needs network.
            runCatching { wv.settings.blockNetworkLoads = true }
            wv.loadDataWithBaseURL("https://mangafire.to/", assetHtml(), "text/html", "UTF-8", null)
            webView = wv
            wv
        }.getOrNull()
    }

    private fun assetHtml(): String = applicationContext.assets.open("mfvrf.html").bufferedReader().use { it.readText() }
}
