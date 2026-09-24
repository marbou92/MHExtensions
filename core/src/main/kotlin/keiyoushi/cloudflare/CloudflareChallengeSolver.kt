package keiyoushi.cloudflare

import android.annotation.SuppressLint
import android.content.res.Resources
import android.webkit.CookieManager
import keiyoushi.utils.runWebViewBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * Extension-level Cloudflare managed-challenge solver — the "no manual WebView"
 * method. Research finding (2026-09, cross-checked against Mihon main's shipped
 * `CloudflareInterceptor`, keiyoushi core, and Cloudflare's own docs):
 *
 * - Header hardening of plain OkHttp traffic can NEVER pass a managed challenge.
 *   `cf-mitigated: challenge` responses are full HTML challenge pages that only a
 *   JS-executing browser environment can solve.
 * - An off-screen (never-attached) Android WebView DOES pass the auto-solvable
 *   branch of managed challenges — this is exactly what Mihon's own app-level
 *   interceptor has shipped for years, and what keiyoushi's `runWebView`
 *   institutionalizes (WebView laid out at real screen size, never attached).
 * - `cf_clearance` is issued for ~30 min (site-configurable) and is bound to the
 *   user agent of the solving browser — so the WebView MUST present exactly the
 *   UA the retried request will send, and the UA must never be overridden later.
 *
 * What this interceptor does on a challenge response:
 *  1. Single-flight: parallel requests (image loads!) wait on one solve.
 *  2. Deletes the stale `cf_clearance` cookie (a stale cookie only feeds CF's
 *     bot score — Mihon deletes it before solving too).
 *  3. Solves headless in a `runWebViewBlocking` session: loads the challenged
 *     URL (GETs) or the site root (non-GETs), spoofs `Sec-CH-UA` client hints
 *     from the request UA, and — when the challenge escalates to the
 *     interactive Turnstile checkbox — TAPS it: synthetic MotionEvents enter
 *     at the platform input layer and reach through the cross-origin widget
 *     iframe that page JavaScript can never click (the same mechanism MKissa
 *     uses to click its captcha widgets). This turns the once-aborted
 *     interactive branch into a completed solve instead of a user prompt.
 *  4. Retries the original request once with the fresh clearance; if the
 *     retry is challenged AGAIN, one more full solve round runs before the
 *     challenge is handed back (second loads clear far more often than
 *     first loads).
 *  5. Last resort: hands the challenge response back so the app-level
 *     CloudflareInterceptor (or a manual "Open in WebView") can still solve it.
 *
 * 429s without a challenge marker are retried with backoff (rate-limit windows).
 */

/** True when [response] is a Cloudflare managed challenge (official detection). */
fun Response.isCloudflareChallenge(): Boolean {
    if (header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) return true
    if (header("server")?.contains("cloudflare", ignoreCase = true) == true && code in listOf(403, 503)) return true
    return false
}

/** Thrown when Cloudflare escalates to an interactive challenge (unsolvable headless). */
class InteractiveChallengeException : IOException("The Cloudflare challenge requires interaction")

class CloudflareSolverInterceptor(
    /** Hosts (registrable domain + subdomains) this solver handles. */
    private val cookieHosts: Set<String>,
) : Interceptor {

    /** One solve at a time per source; parallel challenged requests queue here. */
    private val solveLock = ReentrantLock()

    /** When the last solve attempt finished — queued requests skip re-solving. */
    @Volatile
    private var lastSolveAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isProtectedHost(request.url.host)) return chain.proceed(request)

        val request1 = fingerprint(request)
        var response = chain.proceed(request1)

        // Rate-limit windows (no challenge marker): brief backoff retries.
        var attempt = 0
        while (isRateLimited(response) && attempt < MAX_RATE_LIMIT_RETRIES) {
            attempt++
            response.close()
            sleepQuietly(retryAfterMs(response, attempt))
            response = chain.proceed(request1)
        }

        if (!response.isCloudflareChallenge()) return response

        // ---- Cloudflare challenge: solve headless, then retry. ----
        response.close()

        var solvedRecently = false
        solveLock.withLock {
            // Parallel challenged requests queue on this lock; whoever enters
            // right after a successful solve must NOT wipe the fresh cookie —
            // just retry on it.
            if (System.currentTimeMillis() - lastSolveAt > SOLVE_DEDUPE_MS) {
                // Up to two rounds: some challenges simply clear on the
                // second load (fresh cookie state, warmed JS). One extra
                // automatic round beats handing a WebView prompt to the user.
                var round = 0
                while (round < SOLVE_ROUNDS) {
                    round++
                    clearStaleClearance(request1.url)
                    solveInWebView(request1, chain.call())
                    lastSolveAt = System.currentTimeMillis()

                    response = chain.proceed(request1)
                    if (!response.isCloudflareChallenge()) return@withLock
                    // Keep the LAST response open — it is what we return.
                    if (round < SOLVE_ROUNDS) response.close()
                }
                return@withLock
            }
            solvedRecently = true
        }

        if (solvedRecently) {
            // A parallel request solved moments ago — retry on its cookie.
            response = chain.proceed(request1)
        }

        if (response.isCloudflareChallenge()) {
            // Last resort: let the app-level interceptor (or a manual WebView
            // visit) solve it. Never swallow the response silently.
            return response
        }
        return response
    }

    // ------------------------------------------------------------------
    // Headless solve
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveInWebView(request: Request, call: Call) {
        val host = request.url.host
        val scheme = request.url.scheme
        val oldCookie = currentClearance(host, scheme)
        val loadUrl = if (request.method == "GET") request.url.toString() else "$scheme://$host/"
        val density = Resources.getSystem().displayMetrics.density

        try {
            runWebViewBlocking(call, timeout = SOLVE_TIMEOUT) {
                // Identity coherence: the WebView must present EXACTLY the UA
                // the retried request will send (cf_clearance is bound to it).
                // The setter also spoofs Sec-CH-UA client hints to match.
                userAgent = request.header("User-Agent") ?: FALLBACK_UA

                // "interactive" no longer aborts: it flips the run into tap
                // mode so the Turnstile checkbox gets tapped (below).
                var interactive = false
                jsBridge(BRIDGE_NAME) { message ->
                    if (message == "interactive") interactive = true
                }

                onPageStarted { _ ->
                    evaluateJs(INTERACTIVE_HOOK_JS)
                }

                var tapAttempts = 0
                var lastTapAt = 0L
                val startedAt = System.currentTimeMillis()
                poll(500.milliseconds) {
                    val clearance = currentClearance(host, scheme)
                    if (clearance != null && clearance != oldCookie) {
                        resolve(Unit)
                        return@poll
                    }

                    // Tap the Turnstile checkbox when the challenge is (or
                    // might be) interactive. The widget lives in a
                    // cross-origin iframe; the synthetic tap enters at the
                    // platform input layer and reaches it anyway. A few
                    // spaced attempts, then give up for this round.
                    val now = System.currentTimeMillis()
                    val shouldTap = interactive || now - startedAt > TAP_AFTER_MS
                    if (shouldTap && tapAttempts < MAX_TAP_ATTEMPTS && now - lastTapAt >= TAP_SPACING_MS) {
                        lastTapAt = now
                        evaluateJs(WIDGET_RECT_JS) { rectJson ->
                            runCatching {
                                // The JS returns the rect object directly (or
                                // null); evaluateJs hands us its JSON text.
                                val element = runCatching { Json.parseToJsonElement(rectJson.orEmpty()) }
                                    .getOrNull()
                                val rect = when (element) {
                                    is JsonObject -> element
                                    is JsonPrimitive -> runCatching { Json.parseToJsonElement(element.content) }
                                        .getOrNull() as? JsonObject
                                    else -> null
                                } ?: return@runCatching
                                val x = (rect["x"] as? JsonPrimitive)?.content?.toFloatOrNull()
                                    ?: return@runCatching
                                val y = (rect["y"] as? JsonPrimitive)?.content?.toFloatOrNull()
                                    ?: return@runCatching
                                val h = (rect["h"] as? JsonPrimitive)?.content?.toFloatOrNull()
                                    ?: 65f
                                // Checkbox sits at the widget's left edge,
                                // mid-height (Turnstile and reCAPTCHA both).
                                dispatchTap(
                                    (x + CHECKBOX_OFFSET_X_DP) * density,
                                    (y + h / 2f) * density,
                                )
                                tapAttempts++
                            }
                        }
                    }
                }

                loadUrl(loadUrl)
            }
        } catch (_: InteractiveChallengeException) {
            // Unsolvable headless — the retry below hands the challenge
            // response back to the app-level flow.
        } catch (_: IOException) {
            // Canceled call or transport failure — same fallback.
        } catch (_: Exception) {
            // Timeout / render-process death — same fallback.
        }
    }

    private fun currentClearance(host: String, scheme: String): String? = runCatching {
        CookieManager.getInstance()
            .getCookie("$scheme://$host/")
            ?.split(";")
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith(CLEARANCE_COOKIE) }
    }.getOrNull()

    /** Deletes the stale clearance cookie so the solve mints a fresh one. */
    private fun clearStaleClearance(url: HttpUrl) {
        runCatching {
            CookieManager.getInstance().setCookie(
                url.toString(),
                "$CLEARANCE_COOKIE=; Path=/; Max-Age=0",
            )
        }
    }

    // ------------------------------------------------------------------
    // Browser fingerprint (kept from the comix method — consistent headers
    // avoid bot-score penalties on cleared traffic between solves)
    // ------------------------------------------------------------------

    private fun isProtectedHost(host: String): Boolean = cookieHosts.any { root -> host == root || host.endsWith(".$root") }

    private fun fingerprint(request: Request): Request {
        val builder = request.newBuilder()

        val userAgent = request.header("User-Agent") ?: FALLBACK_UA
        val chromeMajor = CHROME_VERSION_REGEX.find(userAgent)?.groupValues?.get(1)

        // Client hints must match the UA's Chrome version, otherwise the
        // inconsistency itself becomes a bot signal.
        if (chromeMajor != null && request.header("sec-ch-ua") == null) {
            builder.header(
                "sec-ch-ua",
                "\"Not.A/Brand\";v=\"99\", \"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\"",
            )
            builder.header(
                "sec-ch-ua-mobile",
                if (userAgent.contains("Mobile", ignoreCase = true)) "?1" else "?0",
            )
            builder.header("sec-ch-ua-platform", "\"Android\"")
        }

        val path = request.url.encodedPath
        if (request.header("Accept") == null) {
            val accept = if (path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS) {
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            } else {
                "application/json, text/plain, */*"
            }
            builder.header("Accept", accept)
        }

        if (request.header("accept-language") == null) {
            builder.header("accept-language", "en-US,en;q=0.9")
        }

        // Only fill in sec-fetch-* when the request doesn't already carry
        // them (image requests do — keep their real values).
        if (request.header("sec-fetch-dest") == null) builder.header("sec-fetch-dest", "empty")
        if (request.header("sec-fetch-mode") == null) builder.header("sec-fetch-mode", "cors")

        if (request.header("sec-fetch-site") == null) {
            val refererHost = request.header("Referer")?.let { runCatching { it.toHttpUrl().host }.getOrNull() }
            builder.header(
                "sec-fetch-site",
                when {
                    refererHost == request.url.host -> "same-origin"
                    refererHost != null && request.url.host.endsWith(".${refererHost.substringBefore('.')}") -> "same-site"
                    else -> "cross-site"
                },
            )
        }

        return builder.build()
    }

    private fun isRateLimited(response: Response): Boolean = response.code == 429

    private fun retryAfterMs(response: Response, attempt: Int): Long = response.header("Retry-After")
        ?.trim()?.toLongOrNull()?.times(1000)
        ?: (attempt * 1200L)

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis.coerceAtMost(5_000L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        val SOLVE_TIMEOUT = 45.seconds

        /** Queued requests within this window after a solve skip re-solving. */
        const val SOLVE_DEDUPE_MS = 20_000L

        /** Solve rounds per challenge before handing it to the app flow. */
        const val SOLVE_ROUNDS = 2

        /** Start probing for the widget after this many ms even without an interactive signal. */
        const val TAP_AFTER_MS = 8_000L

        /** Spacing between synthetic widget taps. */
        const val TAP_SPACING_MS = 2_500L

        /** Checkbox position inside the captcha widget (CSS dp, both providers). */
        const val CHECKBOX_OFFSET_X_DP = 30f

        /** Cap for the widget taps within one solve round. */
        const val MAX_TAP_ATTEMPTS = 8

        const val CLEARANCE_COOKIE = "cf_clearance="
        const val BRIDGE_NAME = "mhcfbridge"
        const val MAX_RATE_LIMIT_RETRIES = 2
        val CHROME_VERSION_REGEX = Regex("""Chrome[/ ](\d+)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "svg")
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        /**
         * Cloudflare's challenge iframe posts progress messages to its parent.
         * `interactiveBegin` means the challenge now requires a human — the
         * solve switches to tapping the widget checkbox instead of aborting.
         */
        private val INTERACTIVE_HOOK_JS = """
            (function(){
              try{
                if(window.__mhCfHookInstalled) return;
                window.__mhCfHookInstalled=true;
                window.addEventListener('message',function(e){
                  try{
                    var d=e&&e.data;
                    if(d&&d.source==='cloudflare-challenge'&&d.event==='interactiveBegin'){
                      window.$BRIDGE_NAME.post('interactive');
                    }
                  }catch(err){}
                });
              }catch(e){}
            })();
        """.trimIndent()

        /**
         * Locates the captcha widget iframe (Cloudflare Turnstile / challenge
         * widget / reCAPTCHA) and returns its rect as JSON, for the synthetic
         * tap. Falls back to the widest reasonably-sized iframe on the page.
         */
        private val WIDGET_RECT_JS = """
            (function(){
              try{
                var sel='iframe[src*="challenges.cloudflare.com"],iframe[src*="turnstile"],iframe[src*="/captcha/"],iframe[title*="Cloudflare"],iframe[title*="Security"],iframe[title*="security"],iframe[src*="recaptcha"]';
                var fr=document.querySelector(sel);
                if(!fr){
                  var frs=document.querySelectorAll('iframe');
                  var best=null,bestArea=0;
                  for(var i=0;i<frs.length;i++){
                    var r=frs[i].getBoundingClientRect();
                    var area=r.width*r.height;
                    if(r.width>=200&&r.height>=50&&area>bestArea){ best=frs[i]; bestArea=area; }
                  }
                  fr=best;
                }
                if(!fr) return null;
                var b=fr.getBoundingClientRect();
                if(b.width<40||b.height<20) return null;
                return {x:b.x,y:b.y,w:b.width,h:b.height};
              }catch(e){ return null; }
            })();
        """.trimIndent()
    }
}
