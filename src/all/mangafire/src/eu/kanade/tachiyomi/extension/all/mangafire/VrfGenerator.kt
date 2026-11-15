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

@SuppressLint("SetJavaScriptEnabled")
internal object VrfGenerator {
    private const val READY_TIMEOUT_MS = 15_000L
    private const val READY_POLL_INTERVAL_MS = 250L
    private const val READY_POLL_ATTEMPTS = 40
    private const val EVAL_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var webView: WebView? = null

    /** Set once the WebView has loaded the asset and `window.__mfVrf` exists. */
    private var readyLatch: CountDownLatch? = null

    /** Why the last initialization attempt failed, for error messages. */
    private var initError: Throwable? = null

    /** Last readiness probe detail (`typeof __mfVrf`, glue error, ...). */
    private var lastProbe: String? = null

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
     * Computes the `vrf` token for [path] with [params]. Blocks the caller.
     * call from a worker thread only. Set [forceWebView] (the 403 self-heal
     * re-sign path) to bypass the token cache and mint a fresh token.
     */
    fun token(
        path: String,
        params: Map<String, Param>,
        forceWebView: Boolean = false,
    ): String {
        val canonical = canonicalKey(path, params)
        if (!forceWebView) {
            synchronized(lock) {
                cache[canonical]?.let { return it }
            }
        }

        // The site's glue does `JSON.parse(paramsJson)`. The params must
        // arrive as a JSON *string*, not an object literal. `JsonPrimitive(...)
        // .toString()` produces exactly that: a properly escaped string literal.
        val pathJson = JsonPrimitive(path)
        val paramsJson = JsonPrimitive(buildParamsJson(params).toString())
        val script = "window.__mfVrf($pathJson, $paramsJson)"

        awaitReady()

        // The machine is initialized; a single evaluation is enough. Retry
        // only for transient evaluate failures, briefly.
        for (attempt in 1..3) {
            evaluate(script)?.let { token ->
                synchronized(lock) { cache[canonical] = token }
                return token
            }
            Thread.sleep(150L * attempt)
        }

        // Three null results in a row means the WebView itself is suspect
        // (dead renderer, crashed page). Rebuild it once and try again.
        // this is what recovers a signing machine that died mid-session.
        resetWebView()
        awaitReady()
        evaluate(script)?.let { token ->
            synchronized(lock) { cache[canonical] = token }
            return token
        }

        throw IOException(
            "MangaFire: could not sign the request (the site's protection script failed to initialize" +
                detailSuffix() +
                ")",
        )
    }

    /** Drops every cached token. Used by the 403 self-heal before a re-sign. */
    fun invalidate() {
        synchronized(lock) {
            cache.clear()
        }
    }

    /**
     * Creates and warms the WebView ahead of the first request so the token
     * machine is ready when the UI asks for it. Safe to call from any thread;
     * the actual work happens on the main thread. Idempotent.
     */
    fun warmUp() {
        runCatching { awaitReady() }
    }

    /**
     * Destroys the current WebView (if any) so the next [awaitReady] builds a
     * fresh one. Called after repeated evaluation failures. A dead renderer
     * never answers `evaluateJavascript`, and only a rebuild un-sticks it.
     */
    private fun resetWebView() {
        synchronized(lock) {
            runCatching { webView?.destroy() }
            webView = null
            readyLatch = null
            initError = null
        }
    }

    /** Blocks until the WebView has loaded the signing asset, or throws. */
    private fun awaitReady() {
        val latch = synchronized(lock) {
            readyLatch ?: run {
                val new = CountDownLatch(1)
                readyLatch = new
                initError = null
                mainHandler.post { initialize() }
                new
            }
        }
        if (!latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException(
                "MangaFire: the signing WebView did not initialize in time" + detailSuffix(),
            )
        }
        // initialize() releases the latch early on failure too. With the
        // reason recorded. surface it instead of signing against nothing.
        synchronized(lock) { initError }?.let { error ->
            throw IOException(
                "MangaFire: the signing WebView failed to initialize" + detailSuffix(),
                error,
            )
        }
    }

    /** Runs on the main thread. Creates the WebView, loads the asset, and
     * waits for the page's `__mfVrf` function before releasing callers. */
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
                        // Probe the real signing entry point, and carry back
                        // the glue's own error string when it is not there.
                        view.evaluateJavascript(READY_PROBE) { value ->
                            val probe = parseProbe(value)
                            synchronized(lock) { lastProbe = probe?.toString() }
                            if (probe?.function == "function") {
                                latch.countDown()
                            } else if (attempt < READY_POLL_ATTEMPTS) {
                                mainHandler.postDelayed({ poll(attempt + 1) }, READY_POLL_INTERVAL_MS)
                            } else {
                                // Poll exhausted: never leave waiters hanging.
                                // fail them now with what the page reported,
                                // and drop the machine so the NEXT request
                                // rebuilds a fresh WebView instead of
                                // inheriting this failure forever. (The
                                // destroy is deferred out of this callback.
                                // destroying a view from inside its own
                                // client callback is not safe.)
                                val stale = view
                                mainHandler.post { runCatching { stale.destroy() } }
                                synchronized(lock) {
                                    webView = null
                                    readyLatch = null
                                    initError = IllegalStateException(
                                        "ready flag never appeared (probe: " + (probe?.toString() ?: "no answer") + ")",
                                    )
                                }
                                latch.countDown()
                            }
                        }
                    }
                    poll(0)
                }
            }
            wv.loadDataWithBaseURL("https://mangafire.to/", assetHtml(), "text/html", "UTF-8", null)
        } catch (t: Throwable) {
            // Let awaitReady() fail fast with the real cause instead of
            // leaving callers blocked forever.
            synchronized(lock) {
                initError = t
            }
            latch.countDown()
        }
    }

    /** Assembles the failure detail appended to IOException messages. */
    private fun detailSuffix(): String {
        val parts = buildList {
            synchronized(lock) {
                initError?.let { add("init=" + (it.message ?: it::class.java.simpleName)) }
                lastProbe?.let { add("probe=" + it) }
            }
        }
        return if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
    }

    /**
     * Runs [script] on the WebView and returns `out.vrf`, or null when it
     * did not sign.
     */
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
                        parseVrfResult(value)?.let(result::set)
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        latch.await(EVAL_TIMEOUT_MS, TimeUnit.SECONDS)
        return result.get()
    }

    /**
     * The readiness probe: returns a JSON string (so it crosses
     * `evaluateJavascript` as a quoted string literal, not an object).
     */
    private const val READY_PROBE =
        """JSON.stringify({fn: typeof window.__mfVrf, ready: !!window.__mfVrfReady, err: window.__mfVrfError || null})"""

    private class Probe(val function: String?, val error: String?) {
        override fun toString(): String = "fn=" + (function ?: "?") + (error?.let { ", err=" + it } ?: "")
    }

    /** Unwraps the quoted-string probe result, or null when unparseable. */
    private fun parseProbe(value: String?): Probe? {
        val text = value ?: return null
        return runCatching {
            val inner = Json.parseToJsonElement(text).jsonPrimitive.content
            val obj = Json.parseToJsonElement(inner).jsonObject
            val fn = obj["fn"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
            val err = obj["err"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
            Probe(fn, err)
        }.getOrNull()
    }

    /** Parses the glue's `{ok, vrf}` result, or null when it did not sign. */
    private fun parseVrfResult(raw: String?): String? {
        raw ?: return null
        return runCatching {
            val parsed = Json.parseToJsonElement(raw).jsonObject
            val vrf = parsed["vrf"]
            if (parsed["ok"]?.jsonPrimitive?.content == "true" && vrf != null && vrf !is JsonNull) {
                vrf.jsonPrimitive.content.takeIf(String::isNotEmpty)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun assetHtml(): String = applicationContext.assets.open("mfvrf.html").bufferedReader().use { it.readText() }

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
}
