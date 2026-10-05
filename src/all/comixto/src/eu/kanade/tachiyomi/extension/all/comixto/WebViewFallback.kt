package eu.kanade.tachiyomi.extension.all.comixto

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Buffer
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * WebView fallback for comix.to's signed + encrypted JSON API (v46).
 *
 * WHY THIS EXISTS
 *
 * comix.to gates every `/api/v1/...` endpoint behind a per-request signature
 * (the `_` query param, computed in-JS from three S-box/key pairs the site's
 * bundle base64-decodes at boot) AND encrypts API response bodies
 * ({"e":"<blob>"}, decrypted only in-JS). The extension replicates both over
 * plain OkHttp (signRequestInterceptor + decryptResponseInterceptor), but the
 * site ROTATES its S-box/key material — a rotation silently invalidates every
 * native call (the origin answers 403 "Invalid token"), and hardcoding fresh
 * constants means another extension release every time.
 *
 * The fallback (same architecture the official keiyoushi Comix extension
 * ships) lets the SITE do the work instead of chasing it:
 *
 *  1. Fetch a live comix.to HTML page through the full source client (the
 *     solver keeps the CF clearance working, the retry interceptor rides out
 *     the flaky-origin 5xx).
 *  2. Load that document into a window-attached WebView with comix.to as the
 *     base URL (main app script REMOVED, so the page never boots its own SPA).
 *  3. A bootstrap script hooks `window.atob` — when the site's bundle decodes
 *     its cipher material, the 256-byte S-boxes and 24/32-byte keys are
 *     captured and shipped back alongside the payload. The source rebuilds its
 *     native signer from that material, so the FAST native path self-heals
 *     after a single fallback run.
 *  4. A capture script dynamically imports the site's own env bundle (found
 *     via the page's /dist/main- module script) and drives the site's OWN api
 *     objects — signing and decryption happen in-JS, on the WebView's cookies
 *     and TLS stack (exactly the environment the user sees working when they
 *     open the site).
 *
 * Request allow-list mirrors the official extension: only comix hosts and
 * challenges.cloudflare.com may load; everything else gets an empty response.
 */

@Serializable
internal class WebViewCipherMaterial(
    val sboxes: List<List<Int>> = emptyList(),
    val keys: List<List<Int>> = emptyList(),
) {
    fun isValid(): Boolean = sboxes.size == 3 &&
        sboxes.all { it.size == 256 } &&
        keys.size == 3 &&
        keys.all { it.isNotEmpty() }
}

@Serializable
internal class WebViewCapture(
    val payload: String,
    val material: WebViewCipherMaterial? = null,
)

/** Envelope-tolerant view of the chapter-pages payload the site client returns. */
@Serializable
internal class WebViewPagesPayload(
    val pages: ComixPagesContainerDto? = null,
    val result: WebViewPagesResult? = null,
) {
    fun container(): ComixPagesContainerDto? = pages ?: result?.pages
}

@Serializable
internal class WebViewPagesResult(
    val pages: ComixPagesContainerDto? = null,
)

internal object ComixWebView {

    private const val WEBVIEW_TIMEOUT_SECONDS = 120L

    /** Chapter list pages fetched per fallback run (matches the native cap). */
    const val MAX_CHAPTER_PAGES = 20

    /**
     * Runs one WebView capture session and returns the bridged payload plus
     * any cipher material the site's bundle happened to decode.
     *
     * [buildCaptureScript] receives the resolved main-bundle URL (possibly
     * empty when the page shipped no module script) and the random bridge
     * callback names, and must return a self-contained, idempotent script
     * that eventually calls `window[passName](payloadString)` or
     * `window[rejectName](error)`.
     */
    fun capture(
        client: OkHttpClient,
        baseUrl: String,
        webViewUserAgent: String,
        buildCaptureScript: (mainScriptUrl: String, passPayloadName: String, rejectName: String) -> String,
    ): WebViewCapture {
        // Must not run on the main thread (runBlocking + busy-poll inside).
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ComixWebView.capture must not be called on the main thread"
        }

        // 1. A live page through the FULL source client: the solver keeps the
        // CF clearance alive on this path and the retry interceptor rides out
        // flaky-origin 5xx. The document is only the delivery vehicle for the
        // comix.to origin + the main-bundle URL.
        val document = client.newCall(GET(baseUrl)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Comix WebView fallback could not load the site (HTTP ${response.code})")
            }
            response.asJsoup()
        }

        // 2. Locate the site's module entry (Vite build under /assets/build/),
        // remember its absolute URL, then REMOVE it so the real SPA never
        // boots inside the WebView and races its own API calls.
        val mainScript: Element? = document.selectFirst("script[type=module][src*=\"/dist/main-\"]")
            ?: document.selectFirst("script[type=module][src*=\"/assets/build/\"]")
        val mainScriptUrl = mainScript?.absUrl("src").orEmpty()
        if (mainScriptUrl.isNotEmpty()) mainScript?.remove()

        // 3. Random bridge names — the site's JS must never poke them.
        val (bridgeName, errorBridgeName, passPayloadName, rejectName) = List(4) {
            (1..(10..20).random()).map { (('a'..'z') + ('A'..'Z')).random() }.joinToString("")
        }

        val bootstrapScript = """
            (function () {
                const captures = (window.__comixCipherCaptures = []);
                const originalAtob = window.atob.bind(window);
                window.atob = function (value) {
                    const decoded = originalAtob(value);
                    try {
                        const bytes = Array.from(decoded, char => char.charCodeAt(0) & 255);
                        if (bytes.length === 256 || bytes.length === 24 || bytes.length === 32) {
                            captures.push(bytes);
                        }
                    } catch (e) {}
                    return decoded;
                };
                window.$passPayloadName = function (payload) {
                    const sboxes = captures.filter(item => item.length === 256).slice(0, 3);
                    const keys = captures.filter(item => item.length === 24 || item.length === 32).slice(0, 3);
                    const material = sboxes.length === 3 && keys.length === 3
                        ? { sboxes: sboxes, keys: keys }
                        : null;
                    window.$bridgeName.post(JSON.stringify({ payload: payload, material: material }));
                };
                window.$rejectName = function (error) {
                    window.$errorBridgeName.post(String(error && error.message ? error.message : error));
                };
            })();
        """.trimIndent()

        val captureScript = buildCaptureScript(mainScriptUrl, passPayloadName, rejectName)

        val head = document.head()
            ?: throw IOException("Comix WebView fallback: page has no <head> to inject into")
        head.prependElement("script").append(bootstrapScript)

        // 4. Run the WebView (window-attached offscreen by the core helper).
        return runCatching {
            runBlocking {
                runWebView<WebViewCapture>(timeout = WEBVIEW_TIMEOUT_SECONDS.seconds) {
                    userAgent = webViewUserAgent
                    blockImages = true

                    val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
                    interceptRequest { request ->
                        val requestUrl = request.url?.toString()?.toHttpUrlOrNull()
                            ?: return@interceptRequest emptyResponse
                        val sourceHost = baseUrl.toHttpUrl().host
                        val allowed = requestUrl.host == sourceHost ||
                            requestUrl.host.endsWith(".$sourceHost") ||
                            requestUrl.host == "comix.ws" ||
                            requestUrl.host.endsWith(".comix.ws") ||
                            requestUrl.host == "challenges.cloudflare.com"
                        if (allowed) null else emptyResponse
                    }

                    jsBridge(bridgeName) { message ->
                        runCatching { resolve(message.parseAs<WebViewCapture>()) }
                            .onFailure { reject(it) }
                    }
                    jsBridge(errorBridgeName) { message ->
                        reject(IOException("Comix WebView fallback failed: $message"))
                    }

                    onPageStarted { _ -> evaluateJs(captureScript) }
                    onPageFinished { _ -> evaluateJs(captureScript) }
                    poll(300.milliseconds) { evaluateJs(captureScript) }

                    loadData(baseUrl, document.outerHtml())
                }
            }
        }.getOrElse {
            throw IOException("Comix WebView fallback failed: ${it.message}", it)
        }
    }
}
