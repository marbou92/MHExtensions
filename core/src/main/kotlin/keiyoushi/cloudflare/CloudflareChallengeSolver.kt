package keiyoushi.cloudflare

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.webkit.CookieManager
import keiyoushi.utils.applicationContext
import keiyoushi.utils.hasForegroundActivity
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Extension-level Cloudflare managed-challenge solver — the "no manual WebView"
 * method. Research finding (2026-09, cross-checked against Mihon main's shipped
 * `CloudflareInterceptor`, keiyoushi core, and Cloudflare's own docs):
 *
 * - Header hardening of plain OkHttp traffic can NEVER pass a managed challenge.
 *   `cf-mitigated: challenge` responses are full HTML challenge pages that only a
 *   JS-executing browser environment can solve.
 * - The solving WebView MUST be attached to a real window: a never-attached
 *   WebView reports `document.visibilityState = "hidden"` and Turnstile (both
 *   the managed-challenge widget and the site's own security checks) silently
 *   stalls forever — no token, no auto-solve, no interactive branch (measured
 *   live, 2026-09: the widget iframe never even renders). runWebView therefore
 *   attaches its WebViews to the foreground activity's window, hidden behind
 *   the app's own content.
 * - `cf_clearance` is bound to the user agent of the solving browser — so the
 *   WebView MUST present exactly the UA the retried request will send, and the
 *   UA must never be overridden later. On both kagane.to and manhuarmtl.com the
 *   measured TTL is ONE YEAR (365 d), so a single solve lasts: what used to
 *   look like hourly expiry was the clearance being wiped by false-positive
 *   challenge detection — hence the strict isCloudflareChallenge below.
 *
 * What this interceptor does on a challenge response:
 *  1. Strict detection: only `cf-mitigated: challenge`, or 403/503 from
 *     Cloudflare whose BODY carries challenge-page markers. A plain JSON 403
 *     from the site's own API behind Cloudflare is NOT a challenge — treating
 *     it as one used to purge a perfectly valid clearance and force the user
 *     through pointless WebView solves.
 *  2. Single-flight: parallel requests (image loads!) wait on one solve.
 *  3. Deletes the stale `cf_clearance` cookie (a stale cookie only feeds CF's
 *     bot score — Mihon deletes it before solving too).
 *  4. Solves in a `runWebViewBlocking` session (now window-attached, so the
 *     widget really renders): loads the challenged URL (GETs) or the site root
 *     (non-GETs), spoofs `Sec-CH-UA` client hints from the request UA, and —
 *     when the challenge escalates to the interactive Turnstile checkbox —
 *     TAPS it: synthetic MotionEvents enter at the platform input layer and
 *     reach through the cross-origin widget iframe that page JavaScript can
 *     never click (the same mechanism MKissa uses to click its captcha
 *     widgets). This turns the once-aborted interactive branch into a
 *     completed solve instead of a user prompt.
 *  5. Retries the original request once with the fresh clearance; if the
 *     retry is challenged AGAIN, one more full solve round runs before the
 *     challenge is handed back (second loads clear far more often than
 *     first loads).
 *  6. Last resort: hands the challenge response back so the app-level
 *     CloudflareInterceptor (or a manual "Open in WebView") can still solve it.
 *
 *  7. SELF-HEALING (v26, re-tuned v33): sites tune their clearance lifetime —
 *     users measure ~1 h on manhuarmtl.com today (365 d once). The interceptor
 *     persists the time of the last confirmed-working clearance per host and,
 *     when a request carries a clearance OLDER than 45 min, runs a SILENT
 *     VERIFY pass: the site root loads in the window-attached WebView; if a
 *     challenge appears it is solved (same as below), if the page loads clean
 *     the existing clearance was still valid. The threshold now sits UNDER
 *     the measured ~1 h TTL so expiry is re-minted before it can bite, and a
 *     failed verify backs off instead of re-running on every request.
 *
 *  8. WHY THE HIDDEN SOLVE USED TO LOSE TO THE MANUAL WEBVIEW (v33 findings,
 *     after "startup = endless load → 502 → enter webview; every ~1 h the
 *     same"): (a) the pre-solve cookie wipe was a SILENT NO-OP — CF sets
 *     cf_clearance with a Domain attribute, and Android's CookieManager only
 *     removes the exact cookie variant a setCookie call describes, so the
 *     attribute-less expired cookie written by the wipe never matched the
 *     domain cookie and every round solved on the STALE clearance; (b) the
 *     attached-but-unfocused WebView never had real web-document focus —
 *     Chromium derives document.hasFocus() from the VIEW's focus, the DOM
 *     patch does not reach the cross-origin challenge iframe, and an
 *     unfocused challenge iframe stalls exactly like a background tab (the
 *     manual WebView the user is pushed to is focused and visible, so it
 *     always passed). The WebView now takes real view focus on attach, and
 *     the wipe deletes every attribute variant of the cookie.
 *
 * 429s without a challenge marker are retried with backoff (rate-limit windows).
 */

/** True when [response] is a Cloudflare managed challenge (official detection). */
fun Response.isCloudflareChallenge(): Boolean {
    // The authoritative marker — Cloudflare sets it on every managed challenge.
    if (header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) return true

    if (header("server")?.contains("cloudflare", ignoreCase = true) != true) return false

    if (code in listOf(403, 503)) {
        // 403/503 from a site's own API behind Cloudflare (JSON errors, rate
        // limits) are header-identical to a challenge page. Require the
        // challenge-page body markers before treating it as one — a false
        // positive here purges a perfectly valid (365-day!) cf_clearance and
        // pushes the user through pointless WebView solves.
        val body = runCatching { peekBody(4096).string() }.getOrNull().orEmpty()
        return CHALLENGE_BODY_MARKERS.any { marker -> body.contains(marker, ignoreCase = true) }
    }

    if (code == 200 && header("Content-Type")?.contains("text/html", ignoreCase = true) == true) {
        // Challenges are occasionally served with HTTP 200 (custom challenge
        // pages, cached interstitials). They used to sail through detection
        // and crash into source-side parsing — MangaBall read such a page as
        // the site homepage and failed with "couldn't read the CSRF token".
        // Peek is cheap (does not consume the body) and HTML-only. Only
        // Cloudflare-PROPRIETARY markers count here: a real page that merely
        // mentions "Just a moment" must never trigger a solve.
        val body = runCatching { peekBody(8192).string() }.getOrNull().orEmpty()
        return body.contains("_cf_chl_opt", ignoreCase = true) ||
            (body.contains("challenge-platform", ignoreCase = true) && body.contains("cf-chl", ignoreCase = true))
    }

    return false
}

private val CHALLENGE_BODY_MARKERS = listOf(
    "Just a moment",
    "challenge-platform",
    "_cf_chl_opt",
    "cf-chl",
    "Attention Required",
    "cf-browser-verification",
    "cf-error-details",
    "cf-turnstile",
)

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

        // WebView solves can only ever succeed with a foreground activity to
        // attach to (visibilityState=hidden pages can never pass Turnstile).
        // Background-triggered fetches (library updates, downloads) therefore
        // skip BOTH the self-heal and the challenge solve: without the gate a
        // background challenge would wipe a still-valid clearance and spin
        // 2 × 60 s for nothing before failing. The next foreground request
        // picks the solve up instead.
        val canSolve = hasForegroundActivity()

        // Self-heal: a stale-but-present clearance gets a silent verify solve
        // BEFORE the request, so expiry never surfaces as a failed fetch.
        if (canSolve) maybeWarmUpClearance(request1, chain.call())

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

        if (!canSolve) {
            // Hand the challenge back untouched — do NOT wipe the clearance:
            // it may still be valid, and an unattached WebView can never
            // re-mint it.
            return chain.proceed(request1)
        }

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
                    // Wipe the old clearance before the solve UNLESS it is
                    // fresh. An EXPIRED clearance must not ride into the
                    // solve (it feeds CF's bot score, and the manual WebView
                    // the user gets pushed to never carries one), but a
                    // challenge against a minutes-old clearance is a
                    // transient/edge blip whose cookie is almost certainly
                    // still valid — wiping that one would self-harm.
                    if (!isClearanceFresh(request1.url)) clearStaleClearance(request1.url)
                    solveInWebView(request1, chain.call())
                    lastSolveAt = System.currentTimeMillis()

                    response = chain.proceed(request1)
                    if (!response.isCloudflareChallenge()) {
                        // The retry worked — the clearance is proven working
                        // NOW. Remember when, or the self-heal would fire a
                        // pointless verify pass on every later request.
                        recordSolveAt(request1.url.host)
                        return@withLock
                    }
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

    /**
     * Runs one solve round. Returns true when the run RESOLVED — a new
     * clearance was minted or the page loaded clean (verify mode); false on
     * timeout/transport/render failure (the caller retries or hands the
     * challenge response back).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun solveInWebView(request: Request, call: Call, verifyOnly: Boolean = false): Boolean {
        val host = request.url.host
        val scheme = request.url.scheme
        val oldCookie = currentClearance(host, scheme)
        // ALWAYS solve at the origin root — exactly what the manual
        // "Open in WebView" visit opens, and the one URL guaranteed to render
        // the challenge page when one is warranted. The challenged deep URL
        // is frequently a JSON/API endpoint: in a WebView it can answer
        // UN-challenged (plain data, JSON 403s) — nothing renders, no
        // clearance is minted, and the whole round burns on a page that never
        // needed a browser.
        val loadUrl = "$scheme://$host/"
        val density = Resources.getSystem().displayMetrics.density

        return try {
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

                // VERIFY mode: when the root page finishes WITHOUT a challenge
                // the current clearance is still valid — resolve immediately
                // instead of spinning to the timeout waiting for a cookie
                // that will never change. Also rescues the solve path whenever
                // the page navigates to real content.
                var pageLoaded = false
                onPageFinished { _ ->
                    pageLoaded = true
                }

                var tapAttempts = 0
                var lastTapAt = 0L
                var lastCleanCheckAt = 0L
                val startedAt = System.currentTimeMillis()
                poll(500.milliseconds) {
                    val clearance = currentClearance(host, scheme)
                    if (clearance != null && clearance != oldCookie) {
                        resolve(Unit)
                        return@poll
                    }

                    val now = System.currentTimeMillis()

                    // Clean-load probe: check at most twice a second, from the
                    // first finished load. Challenge pages keep rendering the
                    // widget, so "clean" can only mean we are through.
                    if (pageLoaded && now - lastCleanCheckAt >= 1000) {
                        lastCleanCheckAt = now
                        evaluateJs(CLEAN_PAGE_JS) { result ->
                            if (result?.contains("clean") == true) {
                                resolve(Unit)
                            }
                        }
                    }

                    // Tap the Turnstile checkbox when the challenge is (or
                    // might be) interactive. The widget lives in a
                    // cross-origin iframe; the synthetic tap enters at the
                    // platform input layer and reaches it anyway. A few
                    // spaced attempts, then give up for this round.
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
            // runWebViewBlocking only returns normally on resolve() — a new
            // clearance or a clean page.
            true
        } catch (_: InteractiveChallengeException) {
            // Unsolvable headless — the retry below hands the challenge
            // response back to the app-level flow.
            false
        } catch (_: IOException) {
            // Canceled call or transport failure — same fallback.
            false
        } catch (_: Exception) {
            // Timeout / render-process death — same fallback.
            false
        }
    }

    private fun currentClearance(host: String, scheme: String): String? = runCatching {
        CookieManager.getInstance()
            .getCookie("$scheme://$host/")
            ?.split(";")
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith(CLEARANCE_COOKIE) }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Self-heal (v26): silent clearance verification
    // ------------------------------------------------------------------

    /**
     * Runs a silent verify solve when the stored clearance predates
     * [WARMUP_AFTER_MS] — the first request of a session after the user has
     * been away for a few hours. The verify pass either re-mints a rotated
     * clearance or confirms the old one still works; the request then just
     * proceeds. Single-flight via the same solve lock as real challenges.
     */
    private fun maybeWarmUpClearance(request: Request, call: Call) {
        val host = request.url.host
        val scheme = request.url.scheme

        val clearance = currentClearance(host, scheme) ?: return
        if (clearance.isEmpty()) return

        val solvedAt = persistedSolveAt(host)
        if (solvedAt == 0L) {
            // No solve history (fresh install or pre-v26): record now and let
            // the normal flow handle anything that comes up.
            recordSolveAt(host)
            return
        }
        if (System.currentTimeMillis() - solvedAt < WARMUP_AFTER_MS) return

        solveLock.withLock {
            // Re-check inside the lock — a parallel thread may have warmed up.
            if (System.currentTimeMillis() - persistedSolveAt(host) < WARMUP_AFTER_MS) return
            if (call.isCanceled()) return
            // Re-check the activity inside the lock — the app could have been
            // backgrounded since canSolve was sampled.
            if (!hasForegroundActivity()) return
            // A verify that recently FAILED (site down, solve stalled) backs
            // off — otherwise every page request would burn a full solve
            // round trip on a clearance we already know we can't refresh.
            val lastFail = verifyFailAt(host)
            if (lastFail != 0L && System.currentTimeMillis() - lastFail < VERIFY_FAIL_BACKOFF_MS) return

            // Verify-only: never wipe the cookie first (if the clearance is
            // still valid, wiping it would CONVERT a working session into a
            // challenge for no reason).
            val ok = solveInWebView(request, call, verifyOnly = true)
            if (ok) {
                recordSolveAt(host)
                lastSolveAt = System.currentTimeMillis()
            } else {
                // NOT recorded as solved — the next request re-verifies after
                // the backoff window instead of trusting a clearance we could
                // not confirm.
                recordVerifyFail(host)
            }
        }
    }

    private fun persistedSolveAt(host: String): Long = runCatching {
        solveStore().getLong(keyFor(host), 0L)
    }.getOrDefault(0L)

    private fun recordSolveAt(host: String) {
        runCatching {
            solveStore().edit().putLong(keyFor(host), System.currentTimeMillis()).apply()
        }
    }

    private fun verifyFailAt(host: String): Long = runCatching {
        solveStore().getLong("verify_fail_$host", 0L)
    }.getOrDefault(0L)

    private fun recordVerifyFail(host: String) {
        runCatching {
            solveStore().edit().putLong("verify_fail_$host", System.currentTimeMillis()).apply()
        }
    }

    /**
     * True when the existing clearance was minted or last CONFIRMED working
     * within [SOLVE_FRESH_MS] — a challenge against a clearance this fresh is
     * a transient/edge blip, and the cookie must survive the solve. An old or
     * history-less clearance is treated as stale (wipe before solving).
     */
    private fun isClearanceFresh(url: HttpUrl): Boolean {
        if (currentClearance(url.host, url.scheme) == null) return false
        val solvedAt = persistedSolveAt(url.host)
        return solvedAt != 0L && System.currentTimeMillis() - solvedAt < SOLVE_FRESH_MS
    }

    /** Tiny app-level prefs file shared by every source's solver instance. */
    private fun solveStore() = applicationContext
        .getSharedPreferences("keiyoushi_cf_solver", Context.MODE_PRIVATE)

    private fun keyFor(host: String) = "last_solve_$host"

    /**
     * Deletes the stale clearance cookie so the solve mints a fresh one.
     *
     * The pre-v33 version was a SILENT NO-OP for exactly the cookies that
     * matter: Cloudflare sets cf_clearance with a Domain attribute, and
     * Android's CookieManager only removes the cookie variant a setCookie
     * call literally describes — an attribute-less expired cookie is
     * HOST-ONLY, which never matches the domain cookie, so the real
     * clearance survived every wipe and every "fresh cookie state" round ran
     * on the stale one. Delete ALL realistic attribute variants instead.
     */
    private fun clearStaleClearance(url: HttpUrl) {
        runCatching {
            val host = url.host
            val labels = host.split('.')
            val apex = if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
            val expired = "$CLEARANCE_COOKIE=; Path=/; Max-Age=0"
            val variants = buildList {
                add(expired) // host-only
                add("$CLEARANCE_COOKIE=; Path=/; Domain=$host; Max-Age=0")
                add("$CLEARANCE_COOKIE=; Path=/; Domain=.$host; Max-Age=0")
                if (apex != host) {
                    add("$CLEARANCE_COOKIE=; Path=/; Domain=$apex; Max-Age=0")
                    add("$CLEARANCE_COOKIE=; Path=/; Domain=.$apex; Max-Age=0")
                }
            }
            for (cookie in variants) {
                CookieManager.getInstance().setCookie(url.toString(), cookie)
            }
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
        // 60s: with real web-document focus (v33) the challenge iframe no
        // longer stalls, so completes are fast; 60 s still covers the full
        // interactive tap sequence (6 s wait + 12 taps × 2 s) with margin.
        val SOLVE_TIMEOUT = 60.seconds

        /**
         * Clearances older than this get a silent verify pass. Users measure
         * the manhuarmtl.com clearance TTL at ~1 h today (Cloudflare lets
         * site owners tune it; it once measured a year). The threshold must
         * sit UNDER the real TTL — at 3.5 h the verify pass never fired
         * before a 1 h expiry, so every session past an hour opened with a
         * hard challenge instead of a silent re-mint.
         */
        val WARMUP_AFTER_MS = 45.minutes.inWholeMilliseconds

        /** A clearance minted/confirmed within this window survives a solve (transient blip). */
        val SOLVE_FRESH_MS = 15.minutes.inWholeMilliseconds

        /** After a FAILED verify, wait this long before the next attempt. */
        val VERIFY_FAIL_BACKOFF_MS = 5.minutes.inWholeMilliseconds

        /** Queued requests within this window after a solve skip re-solving. */
        const val SOLVE_DEDUPE_MS = 20_000L

        /** Solve rounds per challenge before handing it to the app flow. */
        const val SOLVE_ROUNDS = 2

        /** Start probing for the widget after this many ms even without an interactive signal. */
        const val TAP_AFTER_MS = 6_000L

        /** Spacing between synthetic widget taps. */
        const val TAP_SPACING_MS = 2_000L

        /** Checkbox position inside the captcha widget (CSS dp, both providers). */
        const val CHECKBOX_OFFSET_X_DP = 30f

        /** Cap for the widget taps within one solve round. */
        const val MAX_TAP_ATTEMPTS = 12

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
         * Clean-load probe for the verify pass: "clean" only when the document
         * is real site content (challenge pages carry a challenge title and a
         * challenge/widget DOM that never goes away until solved).
         */
        private val CLEAN_PAGE_JS = """
            (function(){
              try{
                var t=document.title||'';
                if(/just a moment|attention required|checking your browser|access denied/i.test(t)) return 'challenge';
                if(document.querySelector('#challenge-form,#challenge-running,#cf-challenge-running,#cf-turnstile,[class*="cf-turnstile"],#challenge-error-text,#cf-wrapper--challenge')) return 'challenge';
                return 'clean';
              }catch(e){ return 'unknown'; }
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
