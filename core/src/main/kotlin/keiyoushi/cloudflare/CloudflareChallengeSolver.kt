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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
 *  9. v35 (bypass-tool research round — FlareSolverr, CF-Clearance-Scraper,
 *     Pydoll, NoCaptcha AI + Cloudflare's own docs): (a) the verify pass is
 *     PRECEDED by a cheap XmlHTTP-shaped probe — GET site root with the
 *     current clearance set as an explicit Cookie header on a bare client —
 *     so a healthy session confirms with one small request instead of a 60 s
 *     WebView load, and XmlHTTP traffic earns Cloudflare's documented +1 h
 *     validation grace, which is what keeps a long reading session past the
 *     default 30-min Challenge Passage from ever surfacing a challenge;
 *     (b) the warmup threshold drops 45 → 25 min to sit UNDER that default
 *     TTL (v33's 45 min straddled a 30-min passage and expired 15 min before
 *     every verify). Verified live on manhuarmtl.com: a stale cf_clearance
 *     sent probe-shaped still answers 403 + cf-mitigated: challenge, so a
 *     dead clearance can never be confirmed healthy; a network-failed probe
 *     is "inconclusive" and defers to the next request instead of burning a
 *     WebView round on a dead connection.
 *
 * 10. v36 (the "loads until it says timeout, refresh gives 403 check-webview"
 *     report): (a) ALL solve work on behalf of ONE call — warmup verify plus
 *     challenge rounds — now runs under a hard 95 s BUDGET (Mihon kills a call
 *     at 2 min; the free-running 60 s verify + 2 × 60 s rounds chain got the
 *     call killed mid-solve with a timeout error, and the user's refresh then
 *     landed inside the 20 s dedupe window and surfaced a raw 403); (b) after
 *     a FAILED solve the dedupe window now fast-fails instead of silently
 *     retrying the same doomed request — the refresh starts a fresh, full-
 *     budget solve instead; (c) the Turnstile tap is HUMANIZED (Pydoll's
 *     lesson): a real finger-shaped gesture — tool type, pressure, positional
 *     scatter, micro-drift while pressed, 95-160 ms hold — instead of an
 *     instant DOWN/UP pair, with the tap point cycling across the widget's
 *     left-edge offsets; (d) a round whose widget made no progress past half
 *     its budget gets ONE reload (FlareSolverr's "second loads clear far more
 *     often"); (e) Cloudflare WAF blocks (Attention Required / error 1020)
 *     are handed back UNSOLVED immediately — no WebView visit can clear them,
 *     and every round spent on one was pure dead air; (f) everything above is
 *     written to a persisted diagnostics log surfaced in the source settings.
 *
 * 429s without a challenge marker are retried with backoff (rate-limit windows).
 *
 * 11. v37 (field diagnostics from the 17:24 log: "interactive challenge — tap
 *     mode" followed by "timeout/render failure" — and NOT ONE "widget found"
 *     line, i.e. the tap loop ran 24 s + 38 s without ever locating the
 *     Turnstile widget, then the call spun a full 77 s before the handoff):
 *     (a) the widget search is WIDENED — same-origin challenge-platform
 *     iframes, cross-origin-isolation iframes, challenge-stage descendants,
 *     the .cf-turnstile container div, and a relaxed biggest-iframe fallback
 *     (the old 200x50 floor could miss real widgets); (b) tap geometry is
 *     TARGET-AWARE — widget-sized targets keep the classic left-edge offsets,
 *     wide interstitial iframes/containers get center-spread probes plus
 *     left-edge alternates (the checkbox is centered there, not at x+22);
 *     (c) an INTERACTIVE challenge whose widget never renders fast-fails the
 *     round ~10 s after the interactive signal (after one DOM dump for the
 *     next field report) instead of burning its whole budget — the app
 *     handoff now happens while the user is still looking, and the verify
 *     pass no longer eats 30 s of the call budget on a challenge it cannot
 *     confirm; (d) round 1 also gets the stall reload (at 2/3 budget, no
 *     progress) — second loads clear far more often, first loads included.
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

/**
 * Thrown when an interactive challenge runs but its widget never renders into
 * the main-frame DOM — the tap loop has no target, so the round is failed
 * FAST (v37) instead of spinning to its timeout.
 */
internal class WidgetNotFoundException : IOException("The Cloudflare challenge widget never rendered")

/**
 * Rolling record of what the solver actually did, persisted so it survives
 * process death (the interesting moments are usually right after a restart).
 * Surfaced in the source's settings screen ("tap to copy") so a field report
 * carries evidence instead of guesses — the hidden solve runs without any
 * visible surface, and on-device behavior is the one thing the sandbox can
 * never measure.
 */
object CloudflareSolverDiagnostics {
    private const val MAX_LINES = 40
    private const val PREFS = "keiyoushi_cf_solver"
    private const val KEY = "diagnostics"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var loaded = false

    @Volatile
    private var lines: String = ""

    fun record(event: String) {
        ensureLoaded()
        val stamped = "[${fmt.format(Date())}] $event"
        synchronized(this) {
            lines = (lines + "\n" + stamped)
                .split('\n')
                .takeLast(MAX_LINES)
                .joinToString("\n")
            persist()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            lines = runCatching {
                applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "")
                    .orEmpty()
            }.getOrDefault("")
            loaded = true
        }
    }

    private fun persist() {
        runCatching {
            applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, lines)
                .apply()
        }
    }

    fun snapshot(): String = synchronized(this) { lines }

    fun lastLine(): String = synchronized(this) { lines.lineSequence().lastOrNull().orEmpty() }
}

class CloudflareSolverInterceptor(
    /** Hosts (registrable domain + subdomains) this solver handles. */
    private val cookieHosts: Set<String>,
) : Interceptor {

    /** One solve at a time per source; parallel challenged requests queue here. */
    private val solveLock = ReentrantLock()

    /** When the last solve attempt finished — queued requests skip re-solving. */
    @Volatile
    private var lastSolveAt = 0L

    /** Whether that last solve actually cleared the challenge. */
    @Volatile
    private var lastSolveSucceeded = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isProtectedHost(request.url.host)) return chain.proceed(request)

        val request1 = fingerprint(request)

        // WebView solves can only ever succeed with a foreground activity to
        // attach to (visibilityState=hidden pages can never pass Turnstile).
        // Background-triggered fetches (library updates, downloads) therefore
        // skip BOTH the self-heal and the challenge solve: without the gate a
        // background challenge would wipe a still-valid clearance and spin
        // for nothing before failing. The next foreground request picks the
        // solve up instead.
        val canSolve = hasForegroundActivity()

        // v36: every solve phase on behalf of THIS call — warmup verify plus
        // challenge rounds — must finish inside the app's call timeout (Mihon:
        // 2 min) with room for the request round trips. Phases below check the
        // clock and hand back early instead of letting the app kill the call.
        val deadline = System.currentTimeMillis() + SOLVE_BUDGET_MS

        // Self-heal: a stale-but-present clearance gets a silent verify solve
        // BEFORE the request, so expiry never surfaces as a failed fetch.
        if (canSolve) maybeWarmUpClearance(request1, chain.call(), deadline)

        var response = chain.proceed(request1)

        // Rate-limit windows (no challenge marker): brief backoff retries.
        var attempt = 0
        while (isRateLimited(response) && attempt < MAX_RATE_LIMIT_RETRIES) {
            attempt++
            response.close()
            if (System.currentTimeMillis() >= deadline) break
            sleepQuietly(retryAfterMs(response, attempt))
            response = chain.proceed(request1)
        }

        if (isCloudflareBlock(response)) {
            // WAF BLOCK (Attention Required / error 1020): no WebView visit
            // can clear it — every solve round spent here was pure dead air
            // before the 403 dialog appeared. Hand it back immediately,
            // clearance untouched (a block is not an expired clearance).
            CloudflareSolverDiagnostics.record(
                "BLOCK ${response.code} ${request1.url.host}${request1.url.encodedPath} → handed back unsolved",
            )
            return response
        }

        if (!response.isCloudflareChallenge()) return response

        // ---- Cloudflare challenge: solve headless, then retry. ----
        CloudflareSolverDiagnostics.record(
            "CHALLENGE ${response.code} ${request1.url.host}${request1.url.encodedPath} " +
                "(clearance ${clearanceAgeLabel(request1.url)})",
        )
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
                    val remaining = deadline - System.currentTimeMillis()
                    val roundBudget = minOf(ROUND_TIMEOUT_MS, remaining - ROUND_RESERVE_MS)
                    if (roundBudget < MIN_ROUND_MS) {
                        // Not enough budget for another full round plus a
                        // request retry — failing FAST with the challenge is
                        // strictly better than the app killing the call with
                        // a timeout (a fast 403 dialog invites a refresh that
                        // starts with a fresh budget).
                        CloudflareSolverDiagnostics.record("BUDGET stop after $round round(s), ${remaining}ms left")
                        break
                    }
                    round++
                    // Close the PREVIOUS retry before the next solve (the
                    // initial challenge response was already closed above).
                    if (round > 1) response.close()
                    // Wipe the old clearance before the solve UNLESS it is
                    // fresh. An EXPIRED clearance must not ride into the
                    // solve (it feeds CF's bot score, and the manual WebView
                    // the user gets pushed to never carries one), but a
                    // challenge against a minutes-old clearance is a
                    // transient/edge blip whose cookie is almost certainly
                    // still valid — wiping that one would self-harm.
                    if (!isClearanceFresh(request1.url)) clearStaleClearance(request1.url)
                    val solved = solveInWebView(request1, chain.call(), round, roundBudget)
                    lastSolveAt = System.currentTimeMillis()

                    response = chain.proceed(request1)
                    if (isCloudflareBlock(response)) {
                        CloudflareSolverDiagnostics.record("BLOCK after round $round → handed back unsolved")
                        return@withLock
                    }
                    if (!response.isCloudflareChallenge()) {
                        // The retry worked — the clearance is proven working
                        // NOW. Remember when, or the self-heal would fire a
                        // pointless verify pass on every later request.
                        recordSolveAt(request1.url.host)
                        lastSolveAt = System.currentTimeMillis()
                        lastSolveSucceeded = true
                        CloudflareSolverDiagnostics.record("ROUND $round SOLVED (retry ${response.code})")
                        return@withLock
                    }
                    lastSolveSucceeded = false
                    CloudflareSolverDiagnostics.record("ROUND $round failed (webview=$solved, retry still challenged)")
                }
                return@withLock
            }
            if (lastSolveSucceeded) {
                // A parallel request solved moments ago — retry on its cookie.
                solvedRecently = true
            } else {
                // A solve FAILED within the dedupe window (its rounds already
                // burned). Another immediate round cannot win, and retrying
                // the request on the same cookies just buys the same 403 one
                // round trip later. Give up fast — the user's refresh starts
                // a fresh, full-budget solve instead of another silent spin.
                CloudflareSolverDiagnostics.record("DEDUPE fast-fail (failed solve ${SOLVE_DEDUPE_MS / 1000}s ago)")
                response = chain.proceed(request1)
                return@withLock
            }
        }

        if (solvedRecently) {
            // A parallel request solved moments ago — retry on its cookie.
            response = chain.proceed(request1)
        }

        if (response.isCloudflareChallenge()) {
            // Last resort: let the app-level interceptor (or a manual WebView
            // visit) solve it. Never swallow the response silently.
            CloudflareSolverDiagnostics.record("HANDOFF challenge to app (${response.code})")
            return response
        }
        return response
    }

    private fun clearanceAgeLabel(url: HttpUrl): String {
        val solvedAt = persistedSolveAt(url.host)
        return if (solvedAt == 0L) "none" else "${(System.currentTimeMillis() - solvedAt) / 1000}s old"
    }

    // ------------------------------------------------------------------
    // Headless solve
    // ------------------------------------------------------------------

    /**
     * Cloudflare WAF BLOCK page ("Attention Required", error 1020):
     * looks like a challenge in every header scan, but no WebView visit can
     * clear it — it is an IP/firewall verdict, not a clearance gate. Detected
     * strictly (both markers) so a rate-limit 403 from the site's own API is
     * never misread as one; the caller hands it back immediately instead of
     * burning the whole solve budget on rounds that cannot win.
     */
    private fun isCloudflareBlock(response: Response): Boolean {
        if (response.code != 403) return false
        if (response.header("server")?.contains("cloudflare", ignoreCase = true) != true) return false
        val body = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
        val attention = body.contains("Attention Required", ignoreCase = true)
        val denied = body.contains("cf-error-details", ignoreCase = true) ||
            body.contains("error code: 1020", ignoreCase = true)
        return attention && denied
    }

    /**
     * Runs one solve round. Returns true when the run RESOLVED — a new
     * clearance was minted or the page loaded clean (verify mode); false on
     * timeout/transport/render failure (the caller retries or hands the
     * challenge response back).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun solveInWebView(
        request: Request,
        call: Call,
        round: Int,
        timeoutMs: Long,
        verifyOnly: Boolean = false,
    ): Boolean {
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
        val label = if (verifyOnly) "verify" else "round $round"
        CloudflareSolverDiagnostics.record(
            "WEBVIEW $label start (${timeoutMs}ms, had-clearance=${oldCookie != null})",
        )

        return try {
            runWebViewBlocking(call, timeout = timeoutMs.milliseconds) {
                // Identity coherence: the WebView must present EXACTLY the UA
                // the retried request will send (cf_clearance is bound to it).
                // The setter also spoofs Sec-CH-UA client hints to match.
                userAgent = request.header("User-Agent") ?: FALLBACK_UA

                // "interactive" no longer aborts: it flips the run into tap
                // mode so the Turnstile checkbox gets tapped (below).
                var interactive = false
                var interactiveAt = 0L
                jsBridge(BRIDGE_NAME) { message ->
                    if (message == "interactive") {
                        interactive = true
                        if (interactiveAt == 0L) {
                            interactiveAt = System.currentTimeMillis()
                            CloudflareSolverDiagnostics.record("WEBVIEW $label interactive challenge — tap mode")
                        }
                    }
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
                var reloaded = false
                var dumpDeadline = 0L
                val startedAt = System.currentTimeMillis()
                poll(500.milliseconds) {
                    val clearance = currentClearance(host, scheme)
                    if (clearance != null && clearance != oldCookie) {
                        CloudflareSolverDiagnostics.record("WEBVIEW $label cleared — clearance minted")
                        resolve(Unit)
                        return@poll
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = now - startedAt

                    // Clean-load probe: check at most twice a second, from the
                    // first finished load. Challenge pages keep rendering the
                    // widget, so "clean" can only mean we are through.
                    if (pageLoaded && now - lastCleanCheckAt >= 1000) {
                        lastCleanCheckAt = now
                        evaluateJs(CLEAN_PAGE_JS) { result ->
                            if (result?.contains("clean") == true) {
                                CloudflareSolverDiagnostics.record("WEBVIEW $label cleared — page clean")
                                resolve(Unit)
                            }
                        }
                    }

                    // v36 (FlareSolverr's "second loads clear far more
                    // often"), widened in v37 to round 1: a challenge that has
                    // made NO progress gets ONE reload — CF re-issues the
                    // challenge and the fresh load frequently clears where the
                    // stalled one never would. Round 2+ reloads at half the
                    // round budget, round 1 at 2/3 (keeps the first visit
                    // shape longer).
                    if (!reloaded && elapsed > (if (round >= 2) timeoutMs / 2 else timeoutMs * 2 / 3)) {
                        reloaded = true
                        CloudflareSolverDiagnostics.record("WEBVIEW $label stalled — reloading challenge")
                        evaluateJs("location.reload()")
                    }

                    // v37: an INTERACTIVE challenge whose widget never
                    // rendered into the main-frame DOM — the exact field case
                    // behind the 17:24 diagnostics (77 s of silent spinning,
                    // zero taps landed). Dump the challenge DOM once for the
                    // next field report, then fail the round FAST so the app
                    // handoff happens while the user is still looking.
                    if (interactive && tapAttempts == 0 && interactiveAt > 0L &&
                        now - interactiveAt > NO_WIDGET_GIVEUP_MS
                    ) {
                        if (dumpDeadline == 0L) {
                            dumpDeadline = now + DOM_DUMP_LINGER_MS
                            CloudflareSolverDiagnostics.record(
                                "WEBVIEW $label no widget ${now - interactiveAt}ms after interactive — dumping DOM",
                            )
                            evaluateJs(DOM_DUMP_JS) { dump ->
                                CloudflareSolverDiagnostics.record(
                                    "WEBVIEW $label DOM ${dump?.take(320).orEmpty()}",
                                )
                            }
                        } else if (now >= dumpDeadline) {
                            CloudflareSolverDiagnostics.record(
                                "WEBVIEW $label widget never rendered — fast-fail to handoff",
                            )
                            reject(WidgetNotFoundException())
                        }
                        return@poll
                    }

                    // Tap the Turnstile checkbox when the challenge is (or
                    // might be) interactive. The widget lives in a
                    // cross-origin iframe; the synthetic tap enters at the
                    // platform input layer and reaches it anyway. A few
                    // spaced attempts, then give up for this round.
                    val shouldTap = interactive || elapsed > TAP_AFTER_MS
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
                                val w = (rect["w"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
                                val kind = (rect["k"] as? JsonPrimitive)?.content.orEmpty()
                                // v37 target-aware geometry: a widget-sized
                                // target (or an explicitly matched widget
                                // iframe) carries its checkbox at the LEFT
                                // edge (Turnstile and reCAPTCHA both); a WIDE
                                // interstitial iframe or a container div is
                                // centered — alternate center-spread probes
                                // with left-edge alternates so every plausible
                                // box position is visited within a few
                                // attempts. The humanized gesture keeps its
                                // own jitter either way.
                                val baseX: Float
                                val offsetDp: Float
                                if (kind != "container" && w <= WIDGET_MAX_WIDTH_DP) {
                                    baseX = x
                                    offsetDp = TAP_OFFSETS_DP[tapAttempts % TAP_OFFSETS_DP.size]
                                } else if (tapAttempts % 2 == 0) {
                                    baseX = x + w / 2f
                                    offsetDp = TAP_CENTER_SPREAD_DP[(tapAttempts / 2) % TAP_CENTER_SPREAD_DP.size]
                                } else {
                                    baseX = x
                                    offsetDp = TAP_OFFSETS_DP[tapAttempts % TAP_OFFSETS_DP.size]
                                }
                                val tapX = (baseX + offsetDp + (tapAttempts % 3 - 1) * 2f) * density
                                val tapY = (y + h / 2f + (tapAttempts % 2 - 1) * 3f) * density
                                dispatchTap(tapX, tapY)
                                tapAttempts++
                                if (tapAttempts == 1) {
                                    CloudflareSolverDiagnostics.record(
                                        "WEBVIEW $label widget found — tapping ($kind ${w.toInt()}x${h.toInt()})",
                                    )
                                }
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
            CloudflareSolverDiagnostics.record("WEBVIEW $label interactive-only → aborted")
            false
        } catch (_: WidgetNotFoundException) {
            // Already logged where it was raised — fail the round fast.
            false
        } catch (_: IOException) {
            // Canceled call or transport failure — same fallback.
            CloudflareSolverDiagnostics.record("WEBVIEW $label canceled/transport error")
            false
        } catch (_: Exception) {
            // Timeout / render-process death — same fallback.
            CloudflareSolverDiagnostics.record("WEBVIEW $label timeout/render failure")
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

    /**
     * v35: XmlHTTP-shaped clearance probe — GET the site root carrying the
     * current cf_clearance. Returns:
     *  - true  → the clearance VALIDATES (record it; no WebView verify needed)
     *  - false → the answer is a challenge (stale/absent clearance; caller
     *            re-mints via the WebView verify)
     *  - null  → transport error (inconclusive; caller skips this round —
     *            burning a WebView solve on a dead connection helps nobody)
     *
     * The probe rides a bare cookie-less OkHttpClient with the clearance set
     * as an explicit Cookie header, so neither an app-level Cloudflare
     * interceptor nor this interceptor itself can react to (or wipe anything
     * over) the probe's own response. XmlHTTP-shaped requests also earn
     * Cloudflare's documented +1 h validation grace on the clearance — the
     * cheap way to keep a multi-hour session past the 30-min Challenge
     * Passage without a single visible solve.
     */
    private fun probeClearanceByXmlHttp(host: String, scheme: String, userAgent: String): Boolean? {
        val cookie = currentClearance(host, scheme)
        if (cookie.isNullOrEmpty()) return false

        return try {
            probeClient.newCall(
                Request.Builder()
                    .url("$scheme://$host/")
                    .header("User-Agent", userAgent)
                    .header("Cookie", cookie)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .build(),
            ).execute().use { response ->
                response.isSuccessful && !response.isCloudflareChallenge()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Bare client for the XmlHTTP clearance probe (never solves, never wipes). */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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
    private fun maybeWarmUpClearance(request: Request, call: Call, deadline: Long) {
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

        // v35: cheap XmlHTTP probe first. Healthy clearance → confirmed with
        // one small request (and CF's +1 h XmlHTTP validation grace earned);
        // challenged clearance → fall through to the WebView verify; network
        // error → inconclusive, skip this round (the next request re-probes).
        when (probeClearanceByXmlHttp(host, scheme, request.header("User-Agent") ?: FALLBACK_UA)) {
            true -> {
                CloudflareSolverDiagnostics.record("PROBE healthy — session confirmed")
                recordSolveAt(host)
                lastSolveAt = System.currentTimeMillis()
                return
            }
            null -> {
                CloudflareSolverDiagnostics.record("PROBE network error — round skipped")
                return
            }
            false -> CloudflareSolverDiagnostics.record("PROBE challenged → WebView verify")
        }

        // v36: the verify must leave room for the request itself plus a full
        // challenge round under the app's call timeout — with less than a
        // minimal round left, skip the verify and let the request's own
        // challenge path use what remains.
        val verifyBudget = minOf(VERIFY_TIMEOUT_MS, deadline - System.currentTimeMillis() - ROUND_RESERVE_MS)
        if (verifyBudget < MIN_ROUND_MS) {
            CloudflareSolverDiagnostics.record("VERIFY skipped — ${deadline - System.currentTimeMillis()}ms left of budget")
            return
        }

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
            val ok = solveInWebView(request, call, round = 0, timeoutMs = verifyBudget, verifyOnly = true)
            if (ok) {
                recordSolveAt(host)
                lastSolveAt = System.currentTimeMillis()
                CloudflareSolverDiagnostics.record("VERIFY ok")
            } else {
                // NOT recorded as solved — the next request re-verifies after
                // the backoff window instead of trusting a clearance we could
                // not confirm.
                recordVerifyFail(host)
                CloudflareSolverDiagnostics.record("VERIFY failed — backoff ${VERIFY_FAIL_BACKOFF_MS / 60000}min")
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
        // v36: total per-call solve budget. The host app kills a call at its
        // call timeout (Mihon: 2 min) — the free-running chain (60 s verify +
        // 2 × 60 s rounds) got killed mid-solve with a timeout error, and the
        // user's refresh then landed inside the dedupe window and surfaced a
        // raw 403. 95 s leaves ~25 s of headroom for request round trips.
        const val SOLVE_BUDGET_MS = 95_000L

        /** Cap for ONE solve round; each round re-checks the shared budget. */
        const val ROUND_TIMEOUT_MS = 45_000L

        /** Reserved for the request retry after the last round. */
        const val ROUND_RESERVE_MS = 12_000L

        /** Never start a round that cannot finish. */
        const val MIN_ROUND_MS = 15_000L

        /** Verify-pass cap (usually a clean root load — resolves in seconds). */
        const val VERIFY_TIMEOUT_MS = 30_000L

        /**
         * Clearances older than this get a silent verify pass. Cloudflare's
         * Challenge Passage defaults to 30 min (site-tunable; users measure
         * ~1 h on manhuarmtl.com today, 365 d once). The threshold must sit
         * UNDER the real TTL — at 3.5 h the verify never fired before a 1 h
         * expiry, and at 45 min it straddles the 30-min default, so a
         * 30-min-passage session expired 15 min BEFORE every verify. 25 min
         * stays under both; the v35 XmlHTTP probe makes each pass cheap
         * enough to afford the tighter cadence.
         */
        val WARMUP_AFTER_MS = 25.minutes.inWholeMilliseconds

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
        const val TAP_SPACING_MS = 1_600L

        /**
         * Left-edge offsets cycled across tap attempts — Turnstile widget
         * variants place the checkbox at slightly different x positions.
         */
        val TAP_OFFSETS_DP = floatArrayOf(22f, 30f, 38f)

        /** A widget iframe or container up to this width carries its checkbox at the left edge. */
        const val WIDGET_MAX_WIDTH_DP = 350f

        /**
         * Center spreads (dp) for WIDE interstitial targets — the checkbox
         * sits near the horizontal center there, not at the left edge.
         */
        val TAP_CENTER_SPREAD_DP = floatArrayOf(0f, -40f, 40f, -90f, 90f)

        /**
         * v37: an interactive challenge gets this long to produce a widget
         * target after the interactive signal before the round fast-fails
         * (the checkbox IS the interactive UI — if it is not in the main
         * frame DOM by then, tapping is doing nothing).
         */
        const val NO_WIDGET_GIVEUP_MS = 10_000L

        /** Linger this long for the DOM dump callback before failing the round. */
        const val DOM_DUMP_LINGER_MS = 1_500L

        /** Cap for the widget taps within one solve round. */
        const val MAX_TAP_ATTEMPTS = 18

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
         * Locates the captcha widget (Cloudflare Turnstile / challenge widget /
         * reCAPTCHA) and returns its rect + kind as JSON for the synthetic tap.
         * v37 search order: explicit widget iframes → same-origin challenge-
         * platform iframes → cross-origin-isolation iframes → challenge-stage
         * descendants → the .cf-turnstile container div ("container") → the
         * biggest visible iframe with a relaxed 40x40 floor ("fallback" — the
         * old 200x50 floor could miss real widgets).
         */
        private val WIDGET_RECT_JS = """
            (function(){
              try{
                var sel='iframe[src*="challenges.cloudflare.com"],iframe[src*="challenge-platform"],iframe[src*="turnstile"],iframe[src*="/captcha/"],iframe[title*="Cloudflare"],iframe[title*="Security"],iframe[title*="security"],iframe[allow*="cross-origin-isolation"],#challenge-stage iframe,#turnstile-wrapper iframe,[class*="turnstile"] iframe,iframe[src*="recaptcha"]';
                var fr=document.querySelector(sel), kind='iframe';
                if(!fr){
                  var box=document.querySelector('#cf-turnstile,[class*="cf-turnstile"],#challenge-stage > div,#turnstile-wrapper');
                  if(box){
                    var bb=box.getBoundingClientRect();
                    if(bb.width>=40&&bb.height>=20) return {x:bb.x,y:bb.y,w:bb.width,h:bb.height,k:'container'};
                  }
                }
                if(!fr){
                  var frs=document.querySelectorAll('iframe');
                  var best=null,bestArea=0;
                  for(var i=0;i<frs.length;i++){
                    var r=frs[i].getBoundingClientRect();
                    var area=r.width*r.height;
                    if(r.width>=40&&r.height>=40&&area>bestArea){ best=frs[i]; bestArea=area; }
                  }
                  fr=best; kind='fallback';
                }
                if(!fr) return null;
                var b=fr.getBoundingClientRect();
                if(b.width<40||b.height<20) return null;
                return {x:b.x,y:b.y,w:b.width,h:b.height,k:kind};
              }catch(e){ return null; }
            })();
        """.trimIndent()

        /**
         * v37: one-line dump of the challenge DOM for the diagnostics log —
         * recorded when an interactive challenge produced NO widget target.
         * This is what turns the next "still 403" field report into a
         * precise fix instead of another guess.
         */
        private val DOM_DUMP_JS = """
            (function(){
              try{
                var frs=document.querySelectorAll('iframe');
                var parts=[];
                for(var i=0;i<frs.length&&i<4;i++){
                  var b=frs[i].getBoundingClientRect();
                  var src=(frs[i].src||'').slice(0,48);
                  parts.push(src+' '+Math.round(b.width)+'x'+Math.round(b.height));
                }
                var t=(document.title||'').slice(0,40);
                var q=function(s){try{return document.querySelector(s)?1:0}catch(e){return 0}};
                var stage=q('#challenge-stage')+q('#challenge-form')+q('[class*="cf-turnstile"]')+q('[id*="challenge"]');
                var txt=(document.body&&document.body.innerText||'').replace(/\s+/g,' ').slice(0,60);
                return 'iframes='+frs.length+'['+parts.join(' | ')+'] title="'+t+'" stage='+stage+' txt="'+txt+'"';
              }catch(e){ return 'dump-failed'; }
            })();
        """.trimIndent()
    }
}
