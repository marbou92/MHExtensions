package eu.kanade.tachiyomi.extension.all.comixto

import android.webkit.WebResourceResponse
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The real-page tee — the bridge that stops depending on the site's bundle.
 *
 * WHY THIS EXISTS
 *
 * The v48 chunk-import bridge drove the site's own signed axios client from
 * a synthetic document: it found the api-client chunk by a plain-text
 * `baseURL:"/api/…"` marker, patched its export table and imported the
 * module graph. Live-verified against the current build: every chunk is now
 * string-table VM-obfuscated — the `baseURL:"/api` marker appears in NO
 * chunk (only a minified defaults object `"baseURL":E` survives), so the
 * patch step silently no-ops and the instance hunt is back to fragile
 * duck-typing. Every native call 403s (`missing_token`) because the signer
 * the extension replicates belongs to the retired env-*.js scheme — so the
 * whole extension was down, browse included.
 *
 * The tee lets the site's OWN page do all of it instead — the same
 * architecture the manhuarmtl harvest runs: load the REAL page (title page,
 * browse page, reader page) in a window-attached offscreen WebView and wrap
 * `XMLHttpRequest` + `fetch` before the SPA boots. The SPA fires its own
 * signed, valid API calls; the tee records the response bodies verbatim and
 * bridges them back. No client discovery, no chunk patching, no signature
 * replication — whatever the page renders, we capture.
 *
 * Safety profile mirrors the manhuarmtl harvest: the core helper attaches
 * the WebView to a real window offscreen with a renderer-death handler and
 * a hard timeout, images are blocked (only JSON metadata flows), the request
 * allow-list admits comix hosts + the Cloudflare challenge frame, and every
 * failure resolves with whatever was captured — callers keep their native
 * error, never a throw into the host.
 */
internal object ComixPageTee {

    private const val POLL_MS = 600L

    /** Quiet ticks (no new captured payload) before the session resolves. */
    private const val QUIET_TICKS = 5

    /** Hard tick cap — a session never outlives ~70 s of polling. */
    private const val MAX_TICKS = 115

    private const val DIAG_TAG = "page-tee"

    /** One-flight guard: never stack two capture sessions. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isBusy(): Boolean = inFlight.get()

    /** One captured API exchange: the request URL plus its response body. */
    @Serializable
    internal class TeeItem(val u: String, val b: String)

    /**
     * Loads [pageUrl] in the offscreen WebView with an XHR/fetch tee
     * injected before the SPA boots, and returns every response whose URL
     * matches [urlPattern]. [autoScroll] drives lazy loading (chapter
     * lists, browse feeds) one scroll step per poll tick.
     * [navigateToSlug] (first page-list run) locates the chapter's own
     * link on the rendered page and follows it — the tee re-injects on the
     * reader page and captures there; [onResolvedLink] receives the href
     * so the caller can learn the route template. Never throws. Blocking;
     * must not run on the main thread.
     */
    fun capture(
        pageUrl: String,
        userAgent: String,
        urlPattern: String,
        maxPayloads: Int = 64,
        autoScroll: Boolean = false,
        navigateToSlug: String? = null,
        onResolvedLink: ((String) -> Unit)? = null,
    ): List<TeeItem> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ComixPageTee.capture must not be called on the main thread"
        }
        if (!inFlight.compareAndSet(false, true)) {
            return emptyList()
        }
        return try {
            runBlocking {
                captureSuspend(pageUrl, userAgent, urlPattern, maxPayloads, autoScroll, navigateToSlug, onResolvedLink)
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun captureSuspend(
        pageUrl: String,
        forcedUa: String,
        urlPattern: String,
        maxPayloads: Int,
        autoScroll: Boolean,
        navigateToSlug: String?,
        onResolvedLink: ((String) -> Unit)?,
    ): List<TeeItem> {
        val json = Json { ignoreUnknownKeys = true }
        val items = LinkedHashMap<String, TeeItem>() // url -> item (first-seen order)

        runWebView<List<TeeItem>>(timeout = 110.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockImages = true
            userAgent = forcedUa

            val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
            interceptRequest { request ->
                val requestUrl = request.url?.toString()
                val allowed = requestUrl != null && (
                    requestUrl.startsWith("data:") ||
                        requestUrl.startsWith("blob:") ||
                        (
                            requestUrl.toHttpUrlOrNull()?.let { http ->
                                http.isHttps && (
                                    http.host == "comix.to" ||
                                        http.host.endsWith(".comix.to") ||
                                        http.host == "comix.ws" ||
                                        http.host.endsWith(".comix.ws") ||
                                        http.host == "challenges.cloudflare.com" ||
                                        http.host.endsWith(".cloudflare.com")
                                    )
                            } ?: false
                            )
                    )
                if (allowed) null else emptyResponse
            }

            onPageStarted { _ -> evaluateJs(teeScript(urlPattern, maxPayloads)) }
            onPageFinished { _ -> evaluateJs(teeScript(urlPattern, maxPayloads)) }

            var quietTicks = 0
            var lastCount = 0
            var ticks = 0
            var navigated = false

            poll(POLL_MS.milliseconds) {
                ticks++
                if (navigateToSlug != null && !navigated && ticks > 2) {
                    evaluateJs(findChapterLinkScript(navigateToSlug)) { raw ->
                        val href = decodeEval(raw).orEmpty().trim()
                        if (href.isNotEmpty()) {
                            navigated = true
                            onResolvedLink?.invoke(href)
                            val full = if (href.startsWith("http")) href else null
                            evaluateJs("location.assign(" + org.json.JSONObject.quote(full ?: href) + ")")
                        }
                    }
                }
                evaluateJs(dumpScript(autoScroll)) { raw ->
                    val decoded = decodeEval(raw).orEmpty()
                    runCatching {
                        val arr = json.parseToJsonElement(decoded) as? kotlinx.serialization.json.JsonArray
                        arr?.forEach { element ->
                            runCatching {
                                val item = json.decodeFromJsonElement(TeeItem.serializer(), element)
                                if (item.b.length >= 2) items.putIfAbsent(item.u, item)
                            }
                        }
                    }
                    if (items.size > lastCount) {
                        lastCount = items.size
                        quietTicks = 0
                    } else {
                        quietTicks++
                    }
                    if (ticks >= MAX_TICKS || items.size >= maxPayloads || (quietTicks >= QUIET_TICKS && items.isNotEmpty())) {
                        resolve(items.values.toList())
                    }
                }
            }

            loadUrl(pageUrl)
        }

        return items.values.toList()
    }

    /** True when [capturedUrl] names the requested API page (the SPA encodes its own page param). */
    internal fun capturedPage(capturedUrl: String): Int = Regex("""[?&]page=(\d+)""")
        .find(capturedUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    // ------------------------------------------------------------------
    // In-page scripts
    // ------------------------------------------------------------------

    /**
     * The tee: wraps XHR + fetch once, records every matching response body
     * (longest body wins per URL — SPA retries dedupe), dumps the current
     * list, and optionally scrolls one step deeper.
     */
    private fun teeScript(pattern: String, max: Int): String {
        val patternJson = org.json.JSONObject.quote(pattern)
        return $$"""
        (function () {
            var PATTERN = $$patternJson;
            var MAX = $$max;
            if (!window.__mhTee) {
                window.__mhTee = { items: [], re: new RegExp(PATTERN) };
                var t = window.__mhTee;
                var record = function (url, text) {
                    try {
                        if (!t.re.test(url)) return;
                        if (!text || text.length < 2) return;
                        for (var i = 0; i < t.items.length; i++) {
                            if (t.items[i].u === url) {
                                if (text.length > t.items[i].b.length) t.items[i].b = text;
                                return;
                            }
                        }
                        if (t.items.length >= MAX) return;
                        t.items.push({ u: String(url), b: String(text) });
                    } catch (e) {}
                };
                var oOpen = XMLHttpRequest.prototype.open;
                var oSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function (m, u) {
                    this.__mhU = u;
                    return oOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function () {
                    var u = this.__mhU;
                    var self = this;
                    this.addEventListener('load', function () {
                        try { record(String(u), self.responseText); } catch (e) {}
                    });
                    return oSend.apply(this, arguments);
                };
                var oFetch = window.fetch;
                if (oFetch) {
                    window.fetch = function (input) {
                        var url = typeof input === 'string' ? input : (input && input.url) || '';
                        return oFetch.apply(this, arguments).then(function (r) {
                            try {
                                var c = r.clone();
                                c.text().then(function (body) { record(String(url), body); }).catch(function () {});
                            } catch (e) {}
                            return r;
                        });
                    };
                }
            }
            return JSON.stringify(window.__mhTee.items);
        })();
        """.trimIndent()
    }

    /** Dump + one scroll step (document flow and any tall inner container). */
    private fun dumpScript(autoScroll: Boolean): String = """
        (function () {
            try {
                if ($autoScroll) {
                    var sc = document.scrollingElement || document.body;
                    window.scrollTo(0, sc.scrollHeight);
                    var groups = document.querySelectorAll('div,section,main');
                    for (var j = 0; j < groups.length; j++) {
                        var el = groups[j];
                        if (el.scrollHeight > el.clientHeight + 200 && el.clientHeight > 300) {
                            el.scrollTop = el.scrollHeight;
                        }
                    }
                }
            } catch (e) {}
            return JSON.stringify((window.__mhTee || { items: [] }).items);
        })();
    """.trimIndent()

    /**
     * Finds the chapter's own link on a rendered title page: the first
     * anchor whose href carries the chapter slug. Returns the raw href
     * (or the empty string).
     */
    internal fun findChapterLinkScript(chapterSlug: String): String {
        val slugJson = org.json.JSONObject.quote(chapterSlug)
        return """
        (function () {
            var slug = $slugJson;
            var links = document.querySelectorAll('a[href]');
            var best = '';
            for (var i = 0; i < links.length; i++) {
                var h = links[i].getAttribute('href') || '';
                if (h === slug || h.indexOf('/' + slug) >= 0 || h.indexOf(slug + '?') >= 0) {
                    if (best === '' || h.length < best.length) best = h;
                }
            }
            return best;
        })();
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    // Eval-result decoding
    // ------------------------------------------------------------------

    private fun decodeEval(raw: String?): String? = runCatching {
        Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.content
    }.getOrNull() ?: raw
}
