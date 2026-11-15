package eu.kanade.tachiyomi.extension.all.comixto

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.cloudflare.CloudflareSolverDiagnostics
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Buffer
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


internal object ComixWebView {

    private const val WEBVIEW_TIMEOUT_SECONDS = 120L

    /** Chapter-list pages fetched per session (matches the old native cap). */
    const val MAX_CHAPTER_PAGES = 20

    /** Polite pacing between batched in-session API calls (ms). */
    private const val BATCH_DELAY_MS = 200

    /** Extra export name injected into the api-client chunk. Site JS never runs here. */
    private const val CLIENT_EXPORT = "__MH_CLIENT__"

    /** The api-client chunk creates its axios instance with an `/api/...` baseURL. */
    private val API_CLIENT_MARKER = Regex("""baseURL\s*:\s*["']/api/""")

    /** `VAR=SomeLib.create({baseURL:"/api/v1"...`. BaseURL first (current minifier output). */
    private val API_CLIENT_VAR = Regex(
        """([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*[A-Za-z_$][A-Za-z0-9_$]*\.create\(\s*\{\s*baseURL\s*:\s*["']/api/""",
    )

    /** Same, but baseURL anywhere inside the first config object (future-proofing). */
    private val API_CLIENT_VAR_LOOSE = Regex(
        """([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*[A-Za-z_$][A-Za-z0-9_$]*\.create\(\s*\{[^{}]{0,400}?baseURL\s*:\s*["']/api/""",
    )

    /**
     * Runs ONE WebView session and performs a GET for every path in [paths],
     * returning the site client's decrypted JSON payload per path (same
     * order). Any path whose call fails bridges an empty string. Callers
     * decide how strict to be.
     */
    fun apiGet(
        client: OkHttpClient,
        baseUrl: String,
        webViewUserAgent: String,
        paths: List<String>,
    ): List<String> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ComixWebView.apiGet must not be called on the main thread"
        }
        require(paths.isNotEmpty()) { "ComixWebView.apiGet needs at least one path" }

        // 1. A live page through the FULL source client. Delivery vehicle
        // for the comix.to origin and the main-bundle URL.
        val document = client.newCall(GET(baseUrl)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Comix WebView bridge could not load the site (HTTP ${response.code})")
            }
            response.asJsoup()
        }

        // 2. Locate the site's module entry (Vite build under /assets/build/
        // or /dist/) and collect every chunk it references.
        val mainScript: Element? = document.selectFirst("script[type=module][src*=\"/dist/main-\"]")
            ?: document.selectFirst("script[type=module][src*=\"/assets/build/\"]")
        val mainScriptUrl = mainScript?.absUrl("src").orEmpty()
        if (mainScriptUrl.isEmpty()) {
            throw IOException("Comix WebView bridge: no main bundle found in the page")
        }
        mainScript?.remove()

        val mainText = client.newCall(GET(mainScriptUrl)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Comix WebView bridge: main bundle fetch failed (HTTP ${response.code})")
            }
            response.body.string()
        }
        val chunkUrls = chunkImports(mainText, mainScriptUrl)
        if (chunkUrls.isEmpty()) {
            throw IOException("Comix WebView bridge: main bundle imports no chunks")
        }

        val patchedChunks = findAndPatchClientChunk(client, chunkUrls)

        // 4. Random bridge names. The site's JS must never poke them.
        val (bridgeName, errorBridgeName) = List(2) {
            (1..(10..20).random()).map { (('a'..'z') + ('A'..'Z')).random() }.joinToString("")
        }

        val bootScript = buildBootScript(chunkUrls, paths, bridgeName, errorBridgeName)

        val head = document.head()
            ?: throw IOException("Comix WebView bridge: page has no <head> to inject into")
        head.prependElement("script").append(
            """
            (function () {
                window.$errorBridgeName = function (error) {
                    window.$bridgeName.post(JSON.stringify({ error: String(error && error.message ? error.message : error) }));
                };
            })();
            """.trimIndent(),
        )

        // 5. Run the WebView (window-attached offscreen by the core helper).
        return runCatching {
            runBlocking {
                runWebView<List<String>>(timeout = WEBVIEW_TIMEOUT_SECONDS.seconds) {
                    userAgent = webViewUserAgent
                    blockImages = true

                    val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
                    interceptRequest { request ->
                        val requestUrl = request.url?.toString()
                        val patched = requestUrl?.let { lookupPatchedChunk(patchedChunks, it) }
                        if (patched != null) {
                            return@interceptRequest WebResourceResponse(
                                "text/javascript",
                                "utf-8",
                                Buffer().writeUtf8(patched).inputStream(),
                            )
                        }
                        val allowed = requestUrl != null && (
                            requestUrl.startsWith("data:") ||
                                requestUrl.startsWith("blob:") ||
                                requestUrl.contains(baseUrl.toHttpHostSuffix()) ||
                                requestUrl.contains("challenges.cloudflare.com")
                            )
                        if (allowed) null else emptyResponse
                    }

                    jsBridge(bridgeName) { message ->
                        runCatching { resolve(parseBridgeMessage(message)) }
                            .onFailure { reject(it) }
                    }
                    jsBridge(errorBridgeName) { message ->
                        reject(IOException("Comix WebView bridge failed: $message"))
                    }

                    onPageStarted { _ -> evaluateJs(bootScript) }
                    onPageFinished { _ -> evaluateJs(bootScript) }
                    poll(300.milliseconds) { evaluateJs(bootScript) }

                    loadData(baseUrl, document.outerHtml())
                }
            }
        }.getOrElse {
            throw IOException("Comix WebView bridge failed: ${it.message}", it)
        }
    }

    /** Single-path convenience. */
    fun apiGet(
        client: OkHttpClient,
        baseUrl: String,
        webViewUserAgent: String,
        path: String,
    ): String = apiGet(client, baseUrl, webViewUserAgent, listOf(path)).first()

    // ------------------------------------------------------------------

    private fun String.toHttpHostSuffix(): String = substringAfter("//", this).substringBefore('/')

    private fun parseBridgeMessage(message: String): List<String> {
        val json = org.json.JSONObject(message)
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        val payloads = json.getJSONArray("payloads")
        return List(payloads.length()) { payloads.getString(it) }
    }

    /** Exact URL match first, filename fallback second (WebView may re-encode the URL). */
    private fun lookupPatchedChunk(patchedChunks: Map<String, String>, requestUrl: String): String? {
        patchedChunks[requestUrl]?.let { return it }
        val file = requestUrl.substringAfterLast('/')
        return patchedChunks.entries.firstOrNull { it.key.substringAfterLast('/') == file }?.value
    }

    /**
     * Downloads the site's chunks (main's imports, in order) looking for the
     * api-client chunk. When found, rewrites its `export{...}` table to also
     * export the signed axios instance as [CLIENT_EXPORT], and returns the
     * URL → patched-text map (empty when the chunk can't be found or patched
     * . The boot script then falls back to duck-typed detection).
     */
    private fun findAndPatchClientChunk(client: OkHttpClient, chunkUrls: List<String>): Map<String, String> {
        for (url in chunkUrls.take(48)) {
            val text = runCatching {
                client.newCall(GET(url)).execute().use { response ->
                    if (!response.isSuccessful) null else response.body.string()
                }
            }.getOrNull() ?: continue

            if (!API_CLIENT_MARKER.containsMatchIn(text)) continue

            val varName = API_CLIENT_VAR.find(text)?.groupValues?.get(1)
                ?: API_CLIENT_VAR_LOOSE.find(text)?.groupValues?.get(1)
            val exportIdx = text.lastIndexOf("export{")
            if (varName == null || exportIdx < 0) {
                CloudflareSolverDiagnostics.record(
                    "COMIX api-client chunk found but not patchable (var=$varName, export=$exportIdx)",
                )
                return emptyMap()
            }

            val patched = StringBuilder(text)
                .insert(exportIdx + "export{".length, "$varName as $CLIENT_EXPORT,")
                .toString()
            CloudflareSolverDiagnostics.record("COMIX api-client chunk patched ($CLIENT_EXPORT = $varName)")
            return mapOf(url to patched)
        }
        CloudflareSolverDiagnostics.record("COMIX api-client chunk NOT found (${chunkUrls.size} chunks scanned)")
        return emptyMap()
    }

    /**
     * Collects every relative chunk import from the main bundle text and
     * resolves them against the bundle's own URL. Handles both static
     * (`from"./x.js"`) and dynamic (`import("./x.js")`) forms; the site's
     * minified output puts a comma between name and specifier.
     */
    internal fun chunkImports(mainText: String, mainScriptUrl: String): List<String> {
        val names = LinkedHashSet<String>()
        Regex("""from\s*["']\./([^"']+\.js)["']""").findAll(mainText).forEach {
            names.add(it.groupValues[1])
        }
        Regex("""import\s*\(\s*["']\./([^"']+\.js)["']\s*\)""").findAll(mainText).forEach {
            names.add(it.groupValues[1])
        }
        val base = mainScriptUrl.substringBeforeLast('/')
        return names.map { "$base/$it" }
    }

    // ------------------------------------------------------------------
    // Boot script (in-WebView)
    // ------------------------------------------------------------------

    private fun buildBootScript(
        chunkUrls: List<String>,
        paths: List<String>,
        bridgeName: String,
        errorBridgeName: String,
    ): String {
        val chunkUrlArray = chunkUrls.joinToString(",") { org.json.JSONObject.quote(it) }
        val pathArray = paths.joinToString(",") { org.json.JSONObject.quote(it) }
        // language=JavaScript
        return """
        (function () {
            const BOOT_KEY = '__comixApiBoot';
            if (window[BOOT_KEY]) return null;
            window[BOOT_KEY] = true;

            (async () => {
                try {
                    const chunkUrls = [$chunkUrlArray];
                    const namespaces = [];
                    for (const url of chunkUrls) {
                        let ns = null;
                        for (let attempt = 0; attempt < 2 && ns === null; attempt++) {
                            try { ns = await import(url); } catch (e) { ns = null; }
                        }
                        namespaces.push(ns);
                    }

                    const EXPORT_NAME = '$CLIENT_EXPORT';

                    // 1. Patched export. The signed axios instance itself.
                    let client = null;
                    for (const ns of namespaces) {
                        if (ns && ns[EXPORT_NAME] && typeof ns[EXPORT_NAME].get === 'function') {
                            client = ns[EXPORT_NAME];
                            break;
                        }
                    }

                    // 2. Legacy shape. An exported object exposing get+post.
                    if (!client) {
                        const candidates = [];
                        for (const ns of namespaces) {
                            if (!ns) continue;
                            for (const key of Object.keys(ns)) {
                                const v = ns[key];
                                if (
                                    v && typeof v === 'object' &&
                                    typeof v.get === 'function' && typeof v.post === 'function'
                                ) {
                                    candidates.push(v);
                                }
                            }
                        }
                        client = candidates.find(c => typeof c.put === 'function' && typeof c.patch === 'function') || candidates[0] || null;
                    }

                    // 3. Last resort. A facade whose every value is a function
                    //    (minified method names churn every build; call the first).
                    if (!client) {
                        outer: for (const ns of namespaces) {
                            if (!ns) continue;
                            for (const key of Object.keys(ns)) {
                                const v = ns[key];
                                if (!v || typeof v !== 'object') continue;
                                const vals = Object.keys(v).map(k => v[k]);
                                if (vals.length >= 2 && vals.length <= 12 && vals.every(f => typeof f === 'function')) {
                                    client = { get: vals[0] };
                                    break outer;
                                }
                            }
                        }
                    }

                    if (!client) {
                        const patched = namespaces.some(ns => ns && ns[EXPORT_NAME] != null);
                        throw new Error('Could not find the site API client (patched export present: ' + patched + ')');
                    }

                    const isAxios = !!(client.defaults && typeof client.defaults.baseURL === 'string');
                    const apiBase = isAxios ? client.defaults.baseURL : null;
                    const strip = (p) => (apiBase && p.indexOf(apiBase) === 0) ? p.slice(apiBase.length) : p;

                    // The site client expects paths relative to its baseURL (axios
                    // re-combines them). Remember which form worked and keep using
                    // it; retry the alternate form once per path on failure.
                    let mode = isAxios ? 'stripped' : 'asis';
                    async function callGet(p) {
                        const s = strip(p);
                        const first = mode === 'stripped' ? s : p;
                        const second = (first === s) ? p : s;
                        const attempts = (first === second) ? [first] : [first, second];
                        let lastErr = null;
                        for (const q of attempts) {
                            try {
                                const data = await client.get(q);
                                mode = (q === p) ? 'asis' : 'stripped';
                                return data;
                            } catch (e) { lastErr = e; }
                        }
                        throw lastErr;
                    }

                    const paths = [$pathArray];
                    const payloads = [];
                    for (let i = 0; i < paths.length; i++) {
                        try {
                            let data = await callGet(paths[i]);
                            if (data && typeof data === 'object' && typeof data.status === 'number' && data.config && data.data !== undefined) {
                                // axios response object. Unwrap to the body
                                data = data.data;
                            }
                            if (data && typeof data === 'object' && data.status === 'ok' && data.result !== undefined) {
                                data = data.result;
                            }
                            payloads.push(typeof data === 'string' ? data : JSON.stringify(data));
                        } catch (e) {
                            payloads.push('');
                        }
                        if (i < paths.length - 1) {
                            await new Promise(resolve => setTimeout(resolve, $BATCH_DELAY_MS));
                        }
                    }
                    window.$bridgeName.post(JSON.stringify({ payloads: payloads }));
                } catch (error) {
                    window.$errorBridgeName(error);
                }
            })();
            return null;
        })();
        """.trimIndent()
    }
}
