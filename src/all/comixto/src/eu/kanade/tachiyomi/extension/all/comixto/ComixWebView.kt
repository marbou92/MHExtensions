package eu.kanade.tachiyomi.extension.all.comixto

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Buffer
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * WebView bridge for comix.to's signed + encrypted JSON API (v47).
 *
 * WHY THIS EXISTS
 *
 * comix.to gates every `/api/v1/...` endpoint behind a per-request signature
 * (the `_` query param). In late 2026 the site rebuilt that layer: the old
 * env-*.js chunk (a base64-decoded 3×S-box chain our native signer
 * replicated) is GONE — replaced by a VM-protected "secure-<build>.js" chunk
 * whose signing core is a hash/MAC computed entirely inside its bytecode VM
 * (avalanche analysis: any input byte flips the whole 12-byte token; no
 * s-box material ever materialises as JS arrays, so nothing can be captured
 * and re-implemented in Kotlin).
 *
 * So v47 stops chasing the algorithm and lets the SITE do all the work —
 * the same architecture the site itself runs:
 *
 *  1. Fetch a live comix.to HTML page through the full source client (the
 *     solver keeps the CF clearance working, the retry interceptor rides out
 *     flaky-origin 5xx). It carries the main bundle's URL.
 *  2. Fetch the main bundle's TEXT and collect EVERY relative chunk it
 *     imports (`from"./x.js"` + `import("./x.js")`) — the bundle layout is
 *     free to rename again; scanning imports instead of matching a chunk
 *     name survives that.
 *  3. Load the document into a window-attached WebView (main app script
 *     REMOVED so the real SPA never boots) and dynamically import every
 *     collected chunk inside it. The chunk graph self-wires: the axios
 *     bundle registers the api client, the client calls the secure module's
 *     init, and the secure module installs its request interceptor — the
 *     site's own signer is now live on our fake-environment boot.
 *  4. Find the HTTP facade among the module namespaces (an object exposing
 *     `get`/`post`, i.e. the site's own axios wrapper), call
 *     `facade.get(path)` — signing AND (if the site re-enables it) response
 *     decryption happen in-JS, on the WebView's cookies and TLS stack — and
 *     bridge the plaintext JSON payloads back to Kotlin.
 *
 * Every `/api/v1` call the source makes (browse, search, details, chapter
 * lists, page lists) flows through [apiGet]; the batch form keeps a whole
 * chapter-list sweep inside ONE WebView session.
 *
 * Request allow-list: only comix hosts and challenges.cloudflare.com may
 * load; everything else gets an empty response.
 */

internal object ComixWebView {

    private const val WEBVIEW_TIMEOUT_SECONDS = 120L

    /** Chapter-list pages fetched per session (matches the old native cap). */
    const val MAX_CHAPTER_PAGES = 20

    /** Polite pacing between batched in-session API calls (ms). */
    private const val BATCH_DELAY_MS = 200

    /**
     * Runs ONE WebView session and performs a GET for every path in [paths],
     * returning the site client's decrypted JSON payload per path (same
     * order). Any path whose call fails bridges an empty string — callers
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

        // 1. A live page through the FULL source client — delivery vehicle
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

        // 3. Random bridge names — the site's JS must never poke them.
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

        // 4. Run the WebView (window-attached offscreen by the core helper).
        return runCatching {
            runBlocking {
                runWebView<List<String>>(timeout = WEBVIEW_TIMEOUT_SECONDS.seconds) {
                    userAgent = webViewUserAgent
                    blockImages = true

                    val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
                    interceptRequest { request ->
                        val requestUrl = request.url?.toString()
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
                    const importBundle = new Function('url', 'return import(url)');
                    for (const url of chunkUrls) {
                        try {
                            namespaces.push(await importBundle(url));
                        } catch (e) {
                            namespaces.push(null);
                        }
                    }

                    // Find the site's HTTP facade: an object exposing get+post
                    // (the site's own axios wrapper — signing + decryption are
                    // wired into its interceptor chain).
                    let client = null;
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
                    // Prefer the full HTTP verb set (get/post/put/patch/delete).
                    client = candidates.find(c => typeof c.put === 'function' && typeof c.patch === 'function') || candidates[0];
                    if (!client) throw new Error('Could not find the site API client');

                    const paths = [$pathArray];
                    const payloads = [];
                    for (let i = 0; i < paths.length; i++) {
                        try {
                            let data = await client.get(paths[i]);
                            if (data && typeof data === 'object' && data.data !== undefined && !(data.data instanceof Blob)) {
                                // axios response object — unwrap to the body
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
