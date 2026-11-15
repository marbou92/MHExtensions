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

internal object ComixPageTee {

    private const val POLL_MS = 600L

    /** Quiet ticks (no new captured payload) before the session resolves. */
    private const val QUIET_TICKS = 5

    /** Hard tick cap. A session never outlives ~85 s of polling. */
    private const val MAX_TICKS = 140

    /** Ticks of a Cloudflare challenge before the session reports and gives up. */
    private const val CHALLENGE_TICK_CAP = 90

    private const val CHALLENGE_RELOAD_AT_1 = 25
    private const val CHALLENGE_RELOAD_AT_2 = 60

    private const val PRIME_EVERY_TICKS = 4
    private const val PRIME_MAX_ROUNDS = 10

    private const val DIAG_TAG = "page-tee"

    /** One-flight guard: never stack two capture sessions. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isBusy(): Boolean = inFlight.get()

    /** One captured API exchange: the request URL plus its response body. */
    @Serializable
    internal class TeeItem(val u: String, val b: String)

    fun capture(
        pageUrl: String,
        userAgent: String,
        urlPattern: String,
        maxPayloads: Int = 64,
        autoScroll: Boolean = false,
        clickDrive: Boolean = false,
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
                captureSuspend(pageUrl, userAgent, urlPattern, maxPayloads, autoScroll, clickDrive, navigateToSlug, onResolvedLink)
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
        clickDrive: Boolean,
        navigateToSlug: String?,
        onResolvedLink: ((String) -> Unit)?,
    ): List<TeeItem> {
        val json = Json { ignoreUnknownKeys = true }
        val items = LinkedHashMap<String, TeeItem>() // url -> item (first-seen order)
        var challengeTicks = 0
        var challengeSeen = false
        var challengeReloads = 0

        runWebView<List<TeeItem>>(timeout = 130.seconds) {
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
            var primeRounds = 0

            poll(POLL_MS.milliseconds) {
                ticks++
                evaluateJs("document.title || ''") { raw ->
                    val title = decodeEval(raw).orEmpty()
                    if (title.contains("just a moment", true) || title.contains("attention required", true)) {
                        challengeTicks++
                        challengeSeen = true
                    } else {
                        challengeTicks = 0
                    }
                }
                if (challengeTicks == CHALLENGE_RELOAD_AT_1 || challengeTicks == CHALLENGE_RELOAD_AT_2) {
                    challengeReloads++
                    loadUrl(pageUrl)
                }
                if (challengeTicks > CHALLENGE_TICK_CAP) {
                    resolve(emptyList())
                    return@poll
                }
                if (navigateToSlug != null && !navigated && ticks > 2 && challengeTicks == 0) {
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
                if (clickDrive && challengeTicks == 0 && ticks >= 4 &&
                    primeRounds < PRIME_MAX_ROUNDS && (ticks - 4) % PRIME_EVERY_TICKS == 0
                ) {
                    primeRounds++
                    // The action label is not tracked. The captured payloads
                    // are the truth; the click only nudges the SPA.
                    evaluateJs(primeScript()) { _ -> }
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
                        if (challengeTicks == 0) quietTicks++
                    }
                    if (ticks >= MAX_TICKS || items.size >= maxPayloads || (quietTicks >= QUIET_TICKS && items.isNotEmpty())) {
                        resolve(items.values.toList())
                    }
                }
            }

            loadUrl(pageUrl)
        }

        if (challengeSeen && items.isEmpty()) {
            // One diag marker the caller can surface: the challenge won.
            runCatching { items["challenge-blocked"] = TeeItem("challenge-blocked", "true") }
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
     * (longest body wins per URL. SPA retries dedupe), dumps the current
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

    internal fun primeScript(): String = """
        (function () {
            var clicked = function (el, label) {
                try { el.click(); return 'clicked:' + label; } catch (e) { return 'none'; }
            };
            var isChapterish = function (t) {
                if (!t || t.length === 0 || t.length > 40) return false;
                var low = t.toLowerCase();
                return low.indexOf('chapter') !== -1 || t.indexOf('فصل') !== -1 || t.indexOf('الفصول') !== -1;
            };
            var isLoadMore = function (t) {
                if (!t || t.length === 0 || t.length > 40) return false;
                var low = t.toLowerCase();
                return low.indexOf('load more') !== -1 || low.indexOf('show more') !== -1 ||
                    low.indexOf('view all') !== -1 || low.indexOf('see all') !== -1 || low.indexOf('more chapters') !== -1;
            };
            var candidates = document.querySelectorAll('button, [role=button], [role=tab], a');
            for (var i = 0; i < candidates.length; i++) {
                var el = candidates[i];
                var t = (el.textContent || '').trim();
                if (isLoadMore(t) && el.tagName !== 'A') return clicked(el, t.slice(0, 24));
            }
            for (var j = 0; j < candidates.length; j++) {
                var el2 = candidates[j];
                var t2 = (el2.textContent || '').trim();
                if (isChapterish(t2) && el2.tagName !== 'A') return clicked(el2, t2.slice(0, 24));
            }
            var links = document.querySelectorAll('a[href]');
            for (var k = 0; k < links.length; k++) {
                var h = links[k].getAttribute('href') || '';
                if (h.indexOf('-chapter-') !== -1) return clicked(links[k], 'route');
            }
            for (var m = 0; m < candidates.length; m++) {
                var el3 = candidates[m];
                var t3 = (el3.textContent || '').trim();
                if (isChapterish(t3)) return clicked(el3, t3.slice(0, 24));
            }
            return 'none';
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
