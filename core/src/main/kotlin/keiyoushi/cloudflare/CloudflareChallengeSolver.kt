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


/** True when [response] is a Cloudflare managed challenge (official detection). */
fun Response.isCloudflareChallenge(): Boolean {
    // The authoritative marker. Cloudflare sets it on every managed challenge.
    if (header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) return true

    if (header("server")?.contains("cloudflare", ignoreCase = true) != true) return false

    if (code in listOf(403, 503)) {
        // 403/503 from a site's own API behind Cloudflare (JSON errors, rate
        // limits) are header-identical to a challenge page. Require the
        // challenge-page body markers before treating it as one. A false
        // positive here purges a perfectly valid (365-day!) cf_clearance and
        // pushes the user through pointless WebView solves.
        val body = runCatching { peekBody(4096).string() }.getOrNull().orEmpty()
        return CHALLENGE_BODY_MARKERS.any { marker -> body.contains(marker, ignoreCase = true) }
    }

    if (code == 200 && header("Content-Type")?.contains("text/html", ignoreCase = true) == true) {
        // Challenges are occasionally served with HTTP 200 (custom challenge
        // pages, cached interstitials). They used to sail through detection
        // and crash into source-side parsing. MangaBall read such a page as
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

internal class WidgetNotFoundException : IOException("The Cloudflare challenge widget never rendered")

/**
 * Rolling record of what the solver actually did, persisted so it survives
 * process death (the interesting moments are usually right after a restart).
 * Surfaced in the source's settings screen ("tap to copy") so a field report
 * carries evidence instead of guesses. The hidden solve runs without any
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

    /** When the last solve attempt finished. Queued requests skip re-solving. */
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
            // waf block (Attention Required / error 1020): no WebView visit
            // can clear it. Every solve round spent here was pure dead air
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
            // Hand the challenge back untouched. Do NOT wipe the clearance:
            // it may still be valid, and an unattached WebView can never
            // re-mint it.
            return chain.proceed(request1)
        }

        var solvedRecently = false
        solveLock.withLock {
            // Parallel challenged requests queue on this lock; whoever enters
            // right after a successful solve must NOT wipe the fresh cookie. 
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
                        // request retry. Failing FAST with the challenge is
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
                    // Wipe the clearance before the solve UNLESS it is
                    // fresh. An EXPIRED clearance must not ride into the
                    // solve (it feeds CF's bot score, and the manual WebView
                    // the user gets pushed to never carries one), but a
                    // challenge against a minutes-old clearance is a
                    // transient/edge blip whose cookie is almost certainly
                    // still valid. Wiping that one would self-harm.
                    if (!isClearanceFresh(request1.url)) clearStaleClearance(request1.url)
                    val solved = solveInWebView(request1, chain.call(), round, roundBudget)
                    lastSolveAt = System.currentTimeMillis()

                    response = chain.proceed(request1)
                    if (isCloudflareBlock(response)) {
                        CloudflareSolverDiagnostics.record("BLOCK after round $round → handed back unsolved")
                        return@withLock
                    }
                    if (!response.isCloudflareChallenge()) {
                        // The retry worked. The clearance is proven working
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
                // A parallel request solved moments ago. Retry on its cookie.
                solvedRecently = true
            } else {
                // A solve FAILED within the dedupe window (its rounds already
                // burned). Another immediate round cannot win, and retrying
                // the request on the same cookies just buys the same 403 one
                // round trip later. Give up fast. The user's refresh starts
                // a fresh, full-budget solve instead of another silent spin.
                CloudflareSolverDiagnostics.record("DEDUPE fast-fail (failed solve ${SOLVE_DEDUPE_MS / 1000}s ago)")
                response = chain.proceed(request1)
                return@withLock
            }
        }

        if (solvedRecently) {
            // A parallel request solved moments ago. Retry on its cookie.
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
     * Cloudflare waf block page ("Attention Required", error 1020):
     * looks like a challenge in every header scan, but no WebView visit can
     * clear it. It is an IP/firewall verdict, not a clearance gate. Detected
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
     * Runs one solve round. Returns true when the run RESOLVED. A new
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
        // Always solve at the origin root. Exactly what the manual
        // "Open in WebView" visit opens, and the one URL guaranteed to render
        // the challenge page when one is warranted. The challenged deep URL
        // is frequently a JSON/API endpoint: in a WebView it can answer
        // UN-challenged (plain data, JSON 403s). Nothing renders, no
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
                // the current clearance is still valid. Resolve immediately
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

                    if (!reloaded && elapsed > (if (round >= 2) timeoutMs / 2 else timeoutMs * 2 / 3)) {
                        reloaded = true
                        CloudflareSolverDiagnostics.record("WEBVIEW $label stalled — reloading challenge")
                        evaluateJs("location.reload()")
                    }

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
            // runWebViewBlocking only returns normally on resolve(). A new
            // clearance or a clean page.
            true
        } catch (_: InteractiveChallengeException) {
            // Unsolvable headless. The retry below hands the challenge
            // response back to the app-level flow.
            CloudflareSolverDiagnostics.record("WEBVIEW $label interactive-only → aborted")
            false
        } catch (_: WidgetNotFoundException) {
            // Already logged where it was raised. Fail the round fast.
            false
        } catch (_: IOException) {
            // Canceled call or transport failure. Same fallback.
            CloudflareSolverDiagnostics.record("WEBVIEW $label canceled/transport error")
            false
        } catch (_: Exception) {
            // Timeout / render-process death. Same fallback.
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


    /**
     * Runs a silent verify solve when the stored clearance predates
     * [WARMUP_AFTER_MS]. The first request of a session after the user has
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
            recordSolveAt(host)
            return
        }
        if (System.currentTimeMillis() - solvedAt < WARMUP_AFTER_MS) return

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

        val verifyBudget = minOf(VERIFY_TIMEOUT_MS, deadline - System.currentTimeMillis() - ROUND_RESERVE_MS)
        if (verifyBudget < MIN_ROUND_MS) {
            CloudflareSolverDiagnostics.record("VERIFY skipped — ${deadline - System.currentTimeMillis()}ms left of budget")
            return
        }

        solveLock.withLock {
            // Re-check inside the lock. A parallel thread may have warmed up.
            if (System.currentTimeMillis() - persistedSolveAt(host) < WARMUP_AFTER_MS) return
            if (call.isCanceled()) return
            // Re-check the activity inside the lock. The app could have been
            // backgrounded since canSolve was sampled.
            if (!hasForegroundActivity()) return
            // A verify that recently FAILED (site down, solve stalled) backs
            // off. Otherwise every page request would burn a full solve
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
                // NOT recorded as solved. The next request re-verifies after
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
     * within [SOLVE_FRESH_MS]. A challenge against a clearance this fresh is
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
    // Browser fingerprint (kept from the comix method. Consistent headers
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
        // them (image requests do. Keep their real values).
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
        const val SOLVE_BUDGET_MS = 95_000L

        /** Cap for ONE solve round; each round re-checks the shared budget. */
        const val ROUND_TIMEOUT_MS = 45_000L

        /** Reserved for the request retry after the last round. */
        const val ROUND_RESERVE_MS = 12_000L

        /** Never start a round that cannot finish. */
        const val MIN_ROUND_MS = 15_000L

        /** Verify-pass cap (usually a clean root load. Resolves in seconds). */
        const val VERIFY_TIMEOUT_MS = 30_000L

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
         * Left-edge offsets cycled across tap attempts. Turnstile widget
         * variants place the checkbox at slightly different x positions.
         */
        val TAP_OFFSETS_DP = floatArrayOf(22f, 30f, 38f)

        /** A widget iframe or container up to this width carries its checkbox at the left edge. */
        const val WIDGET_MAX_WIDTH_DP = 350f

        /**
         * Center spreads (dp) for WIDE interstitial targets. The checkbox
         * sits near the horizontal center there, not at the left edge.
         */
        val TAP_CENTER_SPREAD_DP = floatArrayOf(0f, -40f, 40f, -90f, 90f)

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
         * `interactiveBegin` means the challenge now requires a human. The
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
