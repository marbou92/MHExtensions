package eu.kanade.tachiyomi.extension.all.manhuarmtl

import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * v39 — WebView OCR harvest (MHRepo issue #4: "No text appears").
 *
 * The direct OCR path needs two reverse-engineered pieces of the site's
 * obfuscated front end: the `_0xvault` credential array parsed out of the
 * chapter HTML, and the exact gate handshake (`fetch-ocr.php` + X-Gate-*
 * headers). The site owner keeps reshaping both — historically right after
 * every keiyoushi release — and when either piece moves, EVERY chapter
 * renders raw ("no text appears") until the extension is re-reverse-
 * engineered. The direct path stays as the fast path, but it is inherently
 * a step behind the site owner.
 *
 * This harvester is the step that ISN'T behind: instead of parsing the
 * site's obfuscation, it loads the chapter page in a real WebView (attached
 * offscreen by the core [runWebView] machinery, sharing the app's cookies,
 * so an already-cleared session sails straight in) and lets the SITE'S OWN
 * reader JavaScript do what it does for every normal visitor: mint its
 * credentials, call its gate, and receive the OCR payload. We hook XHR and
 * fetch at the page level BEFORE the site's scripts run and simply capture
 * what comes back. Whatever the vault looks like next month, whatever the
 * gate endpoint gets renamed to, whatever envelope wraps the payload — the
 * site's own JS has to understand it anyway, and we read the result off the
 * wire. Capturing the RESPONSE (not replaying a captured request) also
 * sidesteps the single-use-credential race entirely: the site's own request
 * already succeeded, we just pick up its answer.
 *
 * Scope guard for the next round: the hook covers page-context XHR + fetch.
 * If the site ever moves its OCR call into a Web Worker, the payload would
 * bypass this hook — the fix would be mocking Worker creation the way the
 * timer hook below mocks timers (worker scripts are blob URLs we can fetch
 * and re-run with the hook pre-injected). Not built until the field asks
 * for it.
 */
internal object OcrHarvest {

    /** Wire format of a single capture message posted from the page hook. */
    @Serializable
    private class Capture(val k: String = "", val u: String = "", val b: String = "")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** One harvest at a time — reader preloads can open several chapters at once. */
    private val inFlight = AtomicBoolean(false)

    private const val BRIDGE = "mrmOcrHarvest"

    /** Largest response body we are willing to ship across the JS bridge. */
    private const val MAX_CAPTURE_CHARS = 500_000

    /**
     * Loads [chapterUrl] in a hidden WebView, captures the OCR payload the
     * site's own scripts fetch, and returns it in the same [OcrPage] shape
     * the direct gate path produces (so storage/matching/heal are unchanged).
     *
     * Returns null when nothing OCR-shaped was captured (challenge page,
     * worker-context fetch, harvest already running, WebView unavailable) —
     * callers fall back to the self-heal ladder exactly as before. Never
     * throws. Blocking (up to ~[TIMEOUT]); safe to call from any
     * non-main thread.
     */
    fun harvest(chapterUrl: String, userAgent: String): List<OcrPage>? {
        if (!inFlight.compareAndSet(false, true)) return null
        val ua = userAgent
        return try {
            runBlocking { harvestSuspend(chapterUrl, ua) }
        } catch (_: Throwable) {
            null
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun harvestSuspend(chapterUrl: String, ua: String): List<OcrPage>? {
        val captures = ConcurrentLinkedQueue<Capture>()

        return try {
            runWebView(timeout = TIMEOUT) {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Identity coherence with the network client: cf_clearance is
                // bound to the UA, and the WebView shares the app's cookie
                // store — a mismatched UA here would earn a fresh challenge.
                userAgent = ua

                jsBridge(BRIDGE) { message ->
                    runCatching { captures += json.decodeFromString<Capture>(message) }
                }

                onPageStarted { url ->
                    // Navigation start: inject BEFORE the site's own scripts
                    // run, so the hooks wrap XHR/fetch before their first use.
                    if (sameHost(url, chapterUrl)) evaluateJs(hookJs())
                }
                onPageFinished {
                    // Belt and suspenders: after load, in case the start-time
                    // injection raced a fast inline script (idempotent).
                    evaluateJs(hookJs())
                }

                poll(POLL_INTERVAL) {
                    // firstNotNullOfOrNull also drains nothing — captures is
                    // scanned each tick, resolve() is first-wins.
                    val hit = captures.firstNotNullOfOrNull { c ->
                        if (c.k == "body") parseOcrPayload(c.b) else null
                    }
                    if (hit != null) resolve(hit)
                }

                loadUrl(chapterUrl)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun sameHost(url: String, chapterUrl: String): Boolean = runCatching {
        url.startsWith("http") &&
            url.toHttpUrlOrNull()?.host == chapterUrl.toHttpUrlOrNull()?.host
    }.getOrDefault(false)

    // The page hook. Injected as an IIFE; idempotent (guard flag). It:
    //  1. Accelerates the site's timers — its obfuscated reader schedules the
    //     OCR fetch behind setTimeout/setInterval delays; at full delay the
    //     harvest would spend its whole budget literally sleeping. 5% of the
    //     requested delay keeps relative ordering while firing in ~instant.
    //  2. Wraps fetch: reports the body of every JSON-shaped response.
    //  3. Wraps XHR open/send: tags each instance with its URL, reports the
    //     responseText of JSON-shaped responses on load.
    // Only JSON-shaped bodies are shipped (size-capped) — image/binary XHRs
    // would be megabytes of noise across the bridge.
    private fun hookJs(): String = """
        (function() {
          if (window.__mrmHarvestHook) return;
          window.__mrmHarvestHook = true;
          var BR = window['$BRIDGE'];
          if (!BR) return;
          function send(kind, url, body) {
            try {
              BR.post(JSON.stringify({ k: kind, u: String(url), b: body == null ? '' : String(body) }));
            } catch (e) {}
          }
          function looksJson(t) {
            if (!t || t.length > $MAX_CAPTURE_CHARS) return false;
            var c = t.replace(/^\s+/, '').charAt(0);
            return c === '[' || c === '{';
          }
          var _si = window.setInterval;
          window.setInterval = function(cb, d) {
            var rest = [].slice.call(arguments, 2);
            var delay = Math.max(50, (d | 0) * 0.05);
            return _si(function() { cb.apply(null, rest); }, delay);
          };
          var _st = window.setTimeout;
          window.setTimeout = function(cb, d) {
            var rest = [].slice.call(arguments, 2);
            var delay = Math.max(20, (d | 0) * 0.05);
            return _st(function() { cb.apply(null, rest); }, delay);
          };
          var _fetch = window.fetch;
          if (_fetch) {
            window.fetch = function() {
              var input = arguments[0];
              var url = typeof input === 'string' ? input : (input && input.url) || '';
              var p = _fetch.apply(this, arguments);
              try {
                p.then(function(res) {
                  try {
                    res.clone().text().then(function(t) {
                      if (looksJson(t)) send('body', url, t);
                    }).catch(function() {});
                  } catch (e) {}
                }).catch(function() {});
              } catch (e) {}
              return p;
            };
          }
          var _open = XMLHttpRequest.prototype.open;
          var _send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u) {
            try { this.__mrmUrl = String(u); } catch (e) {}
            return _open.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            var self = this;
            var url = this.__mrmUrl || '';
            this.addEventListener('load', function() {
              try {
                var t = self.responseText;
                if (looksJson(t)) send('body', url, t);
              } catch (e) {}
            });
            return _send.apply(this, arguments);
          };
        })();
    """.trimIndent()

    private val TIMEOUT = 30.seconds
    private val POLL_INTERVAL = 1.seconds
}
