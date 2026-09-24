package eu.kanade.tachiyomi.extension.en.mkissa

import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MKissa :
    KeiSource(),
    ConfigurableSource {

    private val preferences = getPreferences()

    private val apiHost = "api.mkissa.net"
    private val apiUrl = "https://$apiHost/api"

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

        // v15: plain client, exactly like keiyoushi sources. Browsing goes to
        // api.mkissa.net (open), reading happens inside the site's own reader
        // WebView (which solves Cloudflare + Turnstile + AA crypto itself),
        // and page images load from the CDN with browser-like image headers
        // (see imageRequest). The v14 CloudflareBypass forced fetch-like
        // sec-fetch-* headers onto every request — a bot signal that made
        // Cloudflare challenges MORE likely, not less.
        rateLimit(2)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        add("Referer", "$baseUrl/")
        add("Origin", baseUrl)
        add("Accept", "application/json")
    }

    /**
     * Browser-like headers for page images. The source-wide headers above
     * send `Accept: application/json` (right for GraphQL, wrong for <img>);
     * an image CDN that content-negotiates on Accept can return an HTML/JSON
     * error for such requests, which the reader then fails to decode
     * ("Failed to initialize decoder"). Mirror what a real <img> load sends.
     */
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(
            headers.newBuilder()
                .removeAll("Origin")
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                .set("Accept-Language", "en-US,en;q=0.9")
                .set("Sec-Fetch-Dest", "image")
                .set("Sec-Fetch-Mode", "no-cors")
                .set("Sec-Fetch-Site", "cross-site")
                .build(),
        )
        .get()
        .build()

    /**
     * Executes a plain GraphQL query against the API. The API supports
     * introspection and full queries, so we do not depend on fragile
     * persisted-query hashes.
     *
     * (Browsing/details/chapters are not captcha-gated; the captcha+AA-crypto
     * gates only apply to `chapterPages`, which is now harvested from the
     * reader WebView — see getPageList.)
     */
    private suspend fun graphql(
        query: String,
        variables: JsonObject,
    ): Response = client.post(
        apiUrl,
        buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toJsonRequestBody(),
    )

    private val allowAdult: Boolean
        get() = preferences.defaultContentRatings().contains("Pornographic") ||
            preferences.defaultContentRatings().contains("pornographic")

    private fun baseSearchVariables(
        page: Int,
        sortBy: String,
        ascending: Boolean,
        query: String,
        genres: List<String>,
        genresExcluded: List<String>,
        genresMatchAll: Boolean,
        tags: List<String>,
        tagsExcluded: List<String>,
        authors: List<String>,
        year: Int?,
        season: String?,
        minChapters: Int?,
    ): JsonObject = buildJsonObject {
        putJsonObject("search") {
            put("sortBy", sortBy)
            put("sortDirection", if (ascending) "ASC" else "DSC")
            put("isManga", true)
            if (query.isNotBlank()) put("query", query)
            if (genres.isNotEmpty()) putJsonArray("genres") { genres.forEach { add(it) } }
            if (genresExcluded.isNotEmpty()) putJsonArray("excludeGenres") { genresExcluded.forEach { add(it) } }
            // includeGenres=true (default) matches ALL selected genres,
            // false matches ANY of them. Verified live.
            if (genres.isNotEmpty()) put("includeGenres", genresMatchAll)
            if (tags.isNotEmpty()) putJsonArray("tags") { tags.forEach { add(it) } }
            if (tagsExcluded.isNotEmpty()) putJsonArray("excludeTags") { tagsExcluded.forEach { add(it) } }
            if (authors.isNotEmpty()) putJsonArray("authors") { authors.forEach { add(it) } }
            if (year != null) put("year", year)
            if (season != null) put("season", season)
            if (minChapters != null) put("epRangeStart", minChapters)
            put("listProfile", "browse")
            put("allowAdult", allowAdult)
            put("allowUnknown", false)
            put("denyEcchi", false)
            put("lite", false)
        }
        put("page", page)
        put("limit", PAGE_SIZE)
        put("translationType", "sub")
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = graphql(
            query = QUERY_POPULAR,
            variables = buildJsonObject {
                put("type", "manga")
                put("size", PAGE_SIZE)
                put("page", page)
                put("dateRange", 1)
                put("allowAdult", allowAdult)
                put("allowUnknown", false)
                put("denyEcchi", false)
            },
        )

        val popular = response.parseAs<PopularDto>().data?.queryPopular
        val cards = popular?.recommendations?.mapNotNull { it.anyCard }.orEmpty()

        val mangas = cards.map { card ->
            SManga.create().apply {
                url = card._id
                title = card.title()
                thumbnail_url = card.coverUrl()
            }
        }

        return MangasPage(mangas, cards.size >= PAGE_SIZE)
    }

    // =============================== Latest ===============================

    // "Latest_Update" is the API's own sort value for recently-updated manga.
    // The old build used "Trending" here, which surfaced almost the same
    // entries as the Popular tab.
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = graphql(
            query = QUERY_MANGA_LIST,
            variables = baseSearchVariables(
                page = page,
                sortBy = "Latest_Update",
                ascending = false,
                query = "",
                genres = emptyList(),
                genresExcluded = emptyList(),
                genresMatchAll = true,
                tags = emptyList(),
                tagsExcluded = emptyList(),
                authors = emptyList(),
                year = null,
                season = null,
                minChapters = null,
            ),
        )

        return mangaListParse(response)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sortBy = "Top"
        var ascending = false
        val genres = mutableListOf<String>()
        val genresExcluded = mutableListOf<String>()
        var genresMatchAll = true
        val tags = mutableListOf<String>()
        val tagsExcluded = mutableListOf<String>()
        val authors = mutableListOf<String>()
        var year: Int? = null
        var season: String? = null
        var minChapters: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    sortBy = SORT_OPTIONS[filter.state?.index ?: 0]
                    ascending = filter.state?.ascending == true
                }
                is GenreFilter -> {
                    genres.addAll(filter.included())
                    genresExcluded.addAll(filter.excluded())
                }
                is GenreMatchModeFilter -> genresMatchAll = filter.matchAll()
                is TagFilter -> {
                    tags.addAll(filter.included())
                    tagsExcluded.addAll(filter.excluded())
                }
                is AuthorFilter -> {
                    filter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .let { authors.addAll(it) }
                }
                is SeasonFilter -> season = filter.toValue()
                is YearFilter -> year = filter.state.trim().toIntOrNull()
                is MinChaptersFilter -> minChapters = filter.state.trim().toIntOrNull()
                else -> {}
            }
        }

        val response = graphql(
            query = QUERY_MANGA_LIST,
            variables = baseSearchVariables(
                page = page,
                sortBy = sortBy,
                ascending = ascending,
                query = query,
                genres = genres,
                genresExcluded = genresExcluded,
                genresMatchAll = genresMatchAll,
                tags = tags,
                tagsExcluded = tagsExcluded,
                authors = authors,
                year = year,
                season = season,
                minChapters = minChapters,
            ),
        )

        return mangaListParse(response)
    }

    private fun mangaListParse(response: Response): MangasPage {
        val mangas = response.parseAs<MangaListDto>()
            .data?.mangas?.edges
            .orEmpty()
            .map { edge ->
                SManga.create().apply {
                    url = edge._id
                    title = edge.title()
                    thumbnail_url = edge.coverUrl()
                }
            }
        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    // ====================== Manga Details + Chapters ======================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.removePrefix("https://")) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "manga") return null
        val mangaId = segments[1]

        return fetchMangaDetails(mangaId).apply { initialized = true }
    }

    private suspend fun fetchMangaDetails(mangaId: String): SManga {
        val response = graphql(
            query = QUERY_MANGA_DETAILS,
            variables = buildJsonObject { put("_id", mangaId) },
        )

        val detail = response.parseAs<MangaDetailDto>().data?.manga
            ?: throw IOException("Manga not found")

        return detail.toSManga(
            showAltNames = preferences.showAltNames(),
            showExtraInfo = preferences.showExtraInfo(),
            showTagsInGenre = preferences.showTagsInGenre(),
            blockedGenres = preferences.blockedGenres(),
            scorePosition = preferences.scorePosition(),
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val response = graphql(
            query = QUERY_MANGA_DETAILS,
            variables = buildJsonObject { put("_id", manga.url) },
        )

        val detail = response.parseAs<MangaDetailDto>().data?.manga
            ?: throw IOException("Manga not found")

        val detailsDeferred = async {
            if (fetchDetails) {
                detail.toSManga(
                    showAltNames = preferences.showAltNames(),
                    showExtraInfo = preferences.showExtraInfo(),
                    showTagsInGenre = preferences.showTagsInGenre(),
                    blockedGenres = preferences.blockedGenres(),
                    scorePosition = preferences.scorePosition(),
                )
            } else {
                manga
            }
        }

        val chaptersDeferred = async {
            if (fetchChapters) {
                detail.toChapterList()
            } else {
                chapters
            }
        }

        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    // =============================== Pages ===============================
    //
    // The reader is opened in an off-screen WebView and the page list is
    // captured from the site's OWN pipeline. Live-verified against the site
    // and its bundle, 2026-09 (this is why every previous build dead-ended
    // on "open the chapter in WebView" — and why opening WebView never
    // actually fixed it):
    //
    // 1. The reader document route is Cloudflare-challenged for non-browser
    //    TLS, but the SPA path is NOT: loading the SERIES page and clicking
    //    the chapter row (div.media-ep-item[data-href=…]) navigates to the
    //    reader client-side without ever fetching the challenged document.
    //    So the capture WebView loads the series page, never the reader URL.
    // 2. `chapterPages` answers NEED_CAPTCHA until a fresh captcha token is
    //    presented — Turnstile on production (rendered directly into
    //    #captcha-root; the token lands in input[name="cf-turnstile-response"]).
    //    A Cloudflare/WebView solve alone does NOT produce that token, which
    //    is exactly why "go to webview, solve, come back" never helped.
    // 3. Once a token exists the site re-executes the query itself
    //    (POST + extensions.captcha) and the response carries the FULL page
    //    list per source offering (edges[].pictureUrls) — our fetch/XHR hook
    //    tees that response. Everything below the token already worked.
    //
    // So the capture flow is now an active pipeline, not a passive wait:
    //   series page → SPA-click the chapter row → captcha modal →
    //   token arrives (Turnstile auto-solve, or reCAPTCHA switch, or a
    //   synthetic checkbox tap that reaches through the cross-origin iframe)
    //   → site retries → hook captures the payload → resolve.
    // As a safety net, the harvested token + the site's own `aaReq` crypto
    // proof (teed from its requests) are replayed in OUR own POST if the
    // site's retry does not produce a payload within a few seconds.
    //
    // Successful results are cached in the source prefs — chapter pages are
    // immutable, so re-opening a chapter is instant.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // url format: "/manga/<mangaId>/chapter-<chapterString>-<translation>"
        // (blank segments filtered so leading/trailing slashes don't matter)
        val parts = chapter.url.split("/").filter(String::isNotBlank)
        if (parts.size < 3) throw IOException("Outdated chapter URL. Refresh the chapter list.")
        val chapterPath = "/" + parts.joinToString("/")
        val readerUrl = "$baseUrl$chapterPath"

        // Chapter pages are immutable — serve the cached list instantly when
        // we already captured it once (covers re-opens and retries).
        val cached = readPageCache(chapter.url)
        if (cached != null) return cached.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }

        val imageUrls = try {
            collectReaderPages(chapterPath, readerUrl)
        } catch (e: WebViewTimeoutException) {
            throw IOException(readerHelpMessage, e)
        }

        if (imageUrls.isEmpty()) {
            throw IOException(readerHelpMessage)
        }

        writePageCache(chapter.url, imageUrls)
        return imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    /**
     * The user-facing guidance for every chapter-load failure. The site's
     * own security check (Turnstile) sits on the chapter API; the extension
     * solves it automatically in a background WebView. Manual advice stays
     * as the last resort for interactive-captcha days.
     */
    private val readerHelpMessage =
        "MKissa's chapter reader is gated by the site's security check. This build solves it " +
            "automatically in a background WebView — if you still see this, open the site in " +
            "WebView (Browse → Sources → MKissa → ⋮ → Open in WebView), open any chapter there " +
            "once, then reload the chapter here."

    // ----------------------------- page cache -----------------------------

    private fun readPageCache(chapterUrl: String): List<String>? {
        val raw = preferences.getString("$PAGE_CACHE_PREFIX$chapterUrl", null) ?: return null
        return raw.split('\n').filter(String::isNotBlank).takeIf { it.isNotEmpty() }
    }

    private fun writePageCache(chapterUrl: String, urls: List<String>) {
        val editor = preferences.edit()

        // Purge lists captured by older builds — v14 harvested partially-
        // rendered DOM trees (wrong counts); v15/v16 could capture nothing
        // but the browser's favicon request (one "page" per chapter).
        preferences.all.keys
            .filter { it.startsWith(LEGACY_PAGE_CACHE_PREFIX) && !it.startsWith(PAGE_CACHE_PREFIX) }
            .forEach { editor.remove(it) }

        // Keep the cache bounded — drop the oldest entries beyond the cap.
        val keys = preferences.all.keys
            .filter { it.startsWith(PAGE_CACHE_PREFIX) }
            .toMutableSet()
        if (keys.size >= PAGE_CACHE_MAX_ENTRIES) {
            keys.take(keys.size - PAGE_CACHE_MAX_ENTRIES + 1).forEach { key ->
                editor.remove(key)
            }
        }
        editor.putString("$PAGE_CACHE_PREFIX$chapterUrl", urls.joinToString("\n"))
            .apply()
    }

    // --------------------------- WebView capture ---------------------------

    /**
     * Runs the whole reader pipeline in one off-screen WebView:
     *
     * 1. Loads the SERIES page (the reader document itself is CF-challenged;
     *    the SPA route to it is not).
     * 2. SPA-clicks the target chapter row (div.media-ep-item[data-href]).
     * 3. Waits for the site's Security Check modal, helps the captcha finish
     *    (auto-switch to reCAPTCHA, synthetic taps through the cross-origin
     *    widget iframe) and watches for the token in the parent DOM.
     * 4. The site re-runs its chapterPages query with the token; the fetch/
     *    XHR hook tees the response (which carries the FULL page list per
     *    source offering) and resolves.
     * 5. If the site's retry doesn't land within a few seconds of the token,
     *    replays token + harvested `aaReq` in our own POST as a safety net.
     */
    private suspend fun collectReaderPages(chapterPath: String, readerUrl: String): List<String> = runWebView(timeout = 3.minutes) {
        val collected = Collections.synchronizedSet(LinkedHashSet<String>())
        // Whether we saw at least one REAL page candidate (extension-based
        // image URL or an extensionless non-site-host CDN URL). Fallback
        // resolution may only fire on real evidence — never on UI assets
        // like favicons (the v15 bug).
        val sawRealCandidate = java.util.concurrent.atomic.AtomicBoolean(false)
        val captchaToken = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val aaReq = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val buildId = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val activePostFired = java.util.concurrent.atomic.AtomicBoolean(false)

        var lastCount = 0
        var stablePolls = 0
        var finished = false
        var challengePolls = 0
        var challengeReloaded = false
        var modalPolls = 0
        var tokenSeenAt = -1
        var providerSwitched = false
        var tapAttempts = 0

        fun consider(url: String) {
            val cleaned = url.trim().takeIf { it.startsWith("http") } ?: return
            if (isHarvestablePageUrl(cleaned) && isPageImageUrl(cleaned)) {
                collected.add(cleaned)
                sawRealCandidate.set(true)
            }
        }

        // Active safety net: POST chapterPages ourselves with the harvested
        // token + aaReq when the site's own retry doesn't produce a payload.
        fun fireActivePost() {
            if (!activePostFired.compareAndSet(false, true)) return
            val token = captchaToken.get() ?: return
            val aa = aaReq.get() ?: return
            val ref = chapterRefFromUrl(readerUrl) ?: return
            val bid = buildId.get()
            Thread({
                runCatching {
                    val body = buildJsonObject {
                        putJsonObject("variables") {
                            put("mangaId", ref.mangaId)
                            put("translationType", ref.translation)
                            put("chapterString", ref.chapterString)
                            put("limit", 10)
                            put("offset", 0)
                        }
                        putJsonObject("extensions") {
                            putJsonObject("persistedQuery") {
                                put("version", 1)
                                put("sha256Hash", CHAPTER_PAGES_HASH)
                            }
                            put("k", CHAPTER_PAGES_LANE)
                            put("aaReq", aa)
                            put("captcha", token)
                        }
                    }.toString()

                    val request = Request.Builder()
                        .url(apiUrl)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .headers(headers)
                        .apply { if (bid != null) header("x-build-id", bid) }
                        .build()

                    client.newCall(request).execute().use { resp ->
                        val text = resp.body.string()
                        if (text.isNullOrEmpty()) return@use
                        val pages = extractPageUrls(text, body, readerUrl)
                        if (pages.isNotEmpty()) resolve(pages)
                    }
                }
            }, "MKissa-ActivePost").start()
        }

        // Receives our JSON envelopes from the injected hooks:
        //  - {kind:"captcha"|"flutter", payload:…} → captcha token capture
        //  - {kind:"aareq", url, req} → aaReq + buildId harvest
        //  - {url, req, res} (default) → chapterPages payload from the
        //    fetch/XHR hook (the primary capture path).
        jsBridge("mhbridge") { message ->
            runCatching {
                val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject
                when ((root?.get("kind") as? JsonPrimitive)?.content) {
                    "captcha", "flutter" -> {
                        findTokenPayload(root)?.let { token ->
                            if (token.length >= TOKEN_MIN_LENGTH) captchaToken.compareAndSet(null, token)
                        }
                    }
                    "aareq" -> {
                        val url = (root["url"] as? JsonPrimitive)?.content.orEmpty()
                        val req = (root["req"] as? JsonPrimitive)?.content.orEmpty()
                        harvestBuildId(url, buildId)
                        harvestAaReq(url, req, aaReq)
                    }
                    else -> {
                        val (reqBody, resBody) = parseBridgeEnvelope(message)
                        val pages = extractPageUrls(resBody, reqBody, readerUrl)
                        if (pages.isNotEmpty()) resolve(pages)
                    }
                }
            }
        }

        interceptRequest { request ->
            // NEVER harvest the reader page itself: that single URL serves
            // the entire chapter, and treating it as "page 1" is exactly the
            // reported bug (page 1 just opens the chapter on the site; once
            // cached, the HTML bytes hit the image decoder and fail with
            // "Failed to initialize decoder"). Main-frame navigations and
            // site-host URLs are not page images.
            if (!request.isForMainFrame) {
                val url = request.url.toString()
                if (isHarvestablePageUrl(url)) {
                    consider(url)
                    // WebView "Accept: image/..." header is a solid image signal
                    // even when the CDN path has no file extension. STRICTLY
                    // GATED now: extensionless URLs only count on non-site
                    // hosts (real page images come from the CDN).
                    val accept = request.requestHeaders?.get("Accept")
                    if (accept?.contains("image/") == true &&
                        url.toHttpUrlOrNull()?.let { !isSiteHost(it.host) } == true
                    ) {
                        collected.add(url)
                        sawRealCandidate.set(true)
                    }
                }
            }
            null
        }

        onPageStarted { _ ->
            evaluateJs(FETCH_HOOK_JS)
            evaluateJs(CAPTCHA_HOOK_JS)
        }

        onPageFinished { _ ->
            finished = true
            // Hedge: the reader can embed the chapterPages payload directly
            // in the page (SSR state / inline JSON). Scan inline scripts and
            // push any embedded payload through the same bridge path.
            evaluateJs(INLINE_PAYLOAD_JS)
        }

        poll(1.seconds) {
            // Re-install the hooks if the page re-navigated before they ran.
            evaluateJs("window.__mhHookInstalled===true||(${FETCH_HOOK_JS})")
            evaluateJs("window.__mhCaptchaHook===true||(${CAPTCHA_HOOK_JS})")

            // ---- Phase 1: SPA-navigate to the chapter ----
            evaluateJs(spaClickJs(chapterPath)) { value ->
                // 'clicked' → the site fires its chapterPages query and
                // (on NEED_CAPTCHA) opens the Security Check modal.
            }

            // ---- Phase 2: captcha pipeline ----
            evaluateJs(CAPTCHA_STATE_JS) { value ->
                runCatching {
                    val inner = (Json.parseToJsonElement(value) as? JsonPrimitive)?.content ?: value
                    val state = Json.parseToJsonElement(inner) as? JsonObject ?: return@runCatching
                    val token = (state["token"] as? JsonPrimitive)?.content
                    if (!token.isNullOrBlank() && token.length >= TOKEN_MIN_LENGTH) {
                        if (captchaToken.compareAndSet(null, token)) {
                            tokenSeenAt = modalPolls
                        }
                    }

                    val overlayVisible = state["overlay"] == JsonPrimitive(true)
                    if (overlayVisible) {
                        modalPolls++

                        // 12s without a token: switch the modal to the Google
                        // reCAPTCHA provider (the site's own footer button —
                        // plain DOM, fully scriptable).
                        if (modalPolls == PROVIDER_SWITCH_POLL && !providerSwitched) {
                            providerSwitched = true
                            evaluateJs(SWITCH_RECAPTCHA_JS)
                        }

                        // 15s and still no token: tap the widget checkbox.
                        // The checkbox lives inside a cross-origin iframe no
                        // page script can click — the synthetic tap enters at
                        // the platform input layer and reaches it anyway.
                        if (modalPolls >= FIRST_TAP_POLL && tapAttempts < MAX_TAP_ATTEMPTS &&
                            modalPolls % TAP_RETRY_POLL == 0
                        ) {
                            val rect = state["rect"] as? JsonObject ?: return@evaluateJs
                            val x = (rect["x"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return@evaluateJs
                            val y = (rect["y"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return@evaluateJs
                            val h = (rect["h"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 65f
                            // Checkbox sits at the widget's left edge, mid-height
                            // (~28-30 CSS px in, for both Turnstile and reCAPTCHA).
                            val density = android.content.res.Resources.getSystem().displayMetrics.density
                            dispatchTap(
                                (x + CHECKBOX_OFFSET_X) * density,
                                (y + h / 2f) * density,
                            )
                            tapAttempts++
                        }
                    } else {
                        modalPolls = 0
                    }
                }
            }

            // ---- Phase 3: active POST fallback ----
            // Only when the site's own retry has had its grace period after
            // the token appeared and still nothing resolved. The token is
            // single-use, so this races the site's retry deliberately: whose
            // POST verifies it first, that payload resolves the chapter.
            if (tokenSeenAt >= 0 && aaReq.get() != null &&
                modalPolls >= tokenSeenAt + ACTIVE_POST_GRACE
            ) {
                fireActivePost()
            }

            // Nudge lazy loaders — jump to the bottom of the strip.
            evaluateJs(
                "try{window.scrollTo(0,(document.scrollingElement||document.body).scrollHeight)}catch(e){}",
            )

            evaluateJs(DOM_IMAGES_JS) { value ->
                runCatching {
                    val element = Json.parseToJsonElement(value)
                    val inner = (element as? JsonPrimitive)?.content ?: value
                    (Json.parseToJsonElement(inner) as? JsonArray)?.forEach { item ->
                        (item as? JsonPrimitive)?.content?.let(::consider)
                    }
                }
            }

            val count = collected.size

            // Cloudflare check handling (the SERIES page itself can in theory
            // be challenged). A managed challenge often clears on a second
            // load, so reload ONCE after ~25s of challenge title. If the
            // check is still there 45s after that with zero candidates, fail
            // fast with the actionable message instead of spinning.
            evaluateJs(DOM_TITLE_JS) { value ->
                val title = value
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    .orEmpty()
                val challengeTitle = CHALLENGE_TITLE_REGEX.containsMatchIn(title)
                if (challengeTitle) {
                    challengePolls++
                } else if (title.isNotEmpty()) {
                    challengePolls = 0
                }

                if (challengeTitle && challengePolls >= 25 && !challengeReloaded) {
                    challengeReloaded = true
                    challengePolls = 0
                    evaluateJs("try{location.reload()}catch(e){}")
                    return@evaluateJs
                }

                if (challengePolls >= 45 && count == 0 && challengeReloaded) {
                    reject(IOException(readerHelpMessage))
                }
            }

            // Fallback resolution: real image harvesting stable after page
            // finish. Never resolves on UI-asset-only collections (favicon
            // etc.) — if the reader produced no real candidates we keep
            // waiting (the timeout then fails with the actionable message).
            if (finished && sawRealCandidate.get() && count > 0 && count == lastCount) {
                stablePolls++
                if (stablePolls >= 4) resolve(collected.toList())
            } else {
                stablePolls = 0
                lastCount = count
            }
        }

        loadUrl("$baseUrl$chapterPath".substringBefore("/chapter-"))
    }

    /**
     * Finds a captcha token inside the captcha/flutter bridge envelopes:
     * `{kind:"captcha", payload:"{\"token\":…}"}` or the Flutter-bridge
     * variant `{kind:"flutter", name:"rechapterReady", payload:"[{token:…}]"}`.
     * Walks the decoded payload and returns the first "token" string long
     * enough to be real (Turnstile/reCAPTCHA tokens are 100+ chars).
     */
    private fun findTokenPayload(root: JsonObject?): String? {
        val payload = (root?.get("payload") as? JsonPrimitive)?.content ?: return null
        val element = runCatching { Json.parseToJsonElement(payload) }.getOrNull() ?: return null
        return findTokenIn(element)
    }

    private fun findTokenIn(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.entries.asSequence()
            .mapNotNull { (key, value) ->
                if (key == "token" && value is JsonPrimitive && value.isString) {
                    value.content.takeIf { it.length >= TOKEN_MIN_LENGTH }
                } else {
                    findTokenIn(value)
                }
            }
            .firstOrNull()
        is JsonArray -> element.asSequence().mapNotNull(::findTokenIn).firstOrNull()
        else -> null
    }

    /** Harvests the API build id from the site's own bootstrap/client URLs. */
    private fun harvestBuildId(url: String, target: java.util.concurrent.atomic.AtomicReference<String?>) {
        if (target.get() != null) return
        Regex("""buildId=(\d+)""").find(url)?.groupValues?.get(1)?.let { target.compareAndSet(null, it) }
    }

    /**
     * Harvests the site's own `aaReq` crypto proof (and the chapterPages
     * hash) out of the requests our hooks teed — the proof is minted by
     * obfuscated WebCrypto we deliberately do NOT reimplement. It travels
     * either in the GET query (`extensions={"aaReq":…}`) or in a POST body.
     */
    private fun harvestAaReq(
        url: String,
        req: String,
        target: java.util.concurrent.atomic.AtomicReference<String?>,
    ) {
        if (target.get() != null) return

        val fromUrl = runCatching {
            url.toHttpUrlOrNull()?.queryParameter("extensions")
        }.getOrNull()
        val fromBody = req.takeIf { it.trimStart().startsWith("{") }

        for (candidate in listOf(fromUrl, fromBody)) {
            val raw = candidate ?: continue
            val extensions = runCatching {
                val element = Json.parseToJsonElement(raw)
                val obj = element as? JsonObject
                // Envelope bodies: {"query":…, "variables":…} → extensions
                (obj?.get("extensions") as? JsonObject) ?: (element as? JsonObject)
                    ?.takeIf { it.containsKey("aaReq") }
            }.getOrNull() ?: continue

            val aa = (extensions["aaReq"] as? JsonPrimitive)?.content
            if (!aa.isNullOrBlank()) {
                target.compareAndSet(null, aa)
                return
            }
        }
    }

    /**
     * The bridge message is our own JSON envelope {url, req, res} (v15+).
     * Anything that isn't the envelope is treated as a raw response body
     * (defensive compatibility).
     */
    private fun parseBridgeEnvelope(message: String): Pair<String?, String> {
        val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject
            ?: return null to message
        val res = (root["res"] as? JsonPrimitive)?.content
            ?: return null to message
        val req = (root["req"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return req to res
    }

    /**
     * GraphQL variables from the captured request: a POST body
     * {"query":..., "variables":{...}} or a GET "...variables=<urlencoded>".
     */
    private fun requestVariables(rawRequest: String?): JsonObject? {
        val raw = rawRequest?.trim().orEmpty()
        if (raw.isEmpty()) return null

        if (raw.startsWith("{")) {
            val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
            return (obj["variables"] as? JsonObject) ?: obj.takeIf { it.containsKey("chapterString") }
        }

        val marker = "variables="
        val index = raw.indexOf(marker)
        if (index >= 0) {
            val encoded = raw.substring(index + marker.length).substringBefore('&')
            val decoded = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
            val obj = decoded?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
            if (obj != null) return (obj["variables"] as? JsonObject) ?: obj
        }
        return null
    }

    private data class ChapterRef(val mangaId: String, val chapterString: String, val translation: String)

    /** Parses "/manga/<mangaId>/chapter-<chapterString>-<translation>". */
    private fun chapterRefFromUrl(readerUrl: String): ChapterRef? {
        val segments = runCatching { readerUrl.toHttpUrl().pathSegments.filter(String::isNotBlank) }.getOrNull()
        if (segments == null || segments.size < 3 || segments[0] != "manga") return null
        val segment = segments[2]
        if (!segment.startsWith("chapter-")) return null
        val body = segment.removePrefix("chapter-")
        val translation = body.substringAfterLast('-')
        val chapterString = body.removeSuffix("-$translation")
        if (chapterString.isBlank() || translation !in TRANSLATION_TYPES) return null
        return ChapterRef(segments[1], chapterString, translation)
    }

    /**
     * Pulls the page list out of a captured chapterPages GraphQL response,
     * mirroring the site reader's own pipeline (verified against the site
     * bundle, 2026-09):
     *
     * - `chapterPages` returns `edges[]` where EACH edge is one page-source
     *   offering (streamerId + sourceName + priority) carrying the chapter's
     *   FULL page list in `pictureUrls`. `pageInfo.total` counts SOURCES,
     *   and the site requests them in batches (`limit: 10, offset`) to fill
     *   its source picker — the first response already carries every page.
     * - `pictureUrls` is a list of OBJECTS `{num, url}` (a.k.a. `{n, u}`),
     *   sometimes wrapped as a JSON string, ordered by page number — NOT a
     *   list of plain URL strings. (This is exactly what v14 got wrong: it
     *   read the entries as strings, dropped them all, and fell back to
     *   partial DOM harvesting — hence wrong page counts.)
     * - Source choice (the reader's `foe`/`goe`): skip edges whose
     *   sourceName is missing/"unkonw"/"Wp-*", dedupe by
     *   `streamerId|sourceName` keeping the highest priority, sort by
     *   priority descending, and take the first edge whose normalized list
     *   is non-empty.
     * - URL joining (the reader's `KP`/`yP`): absolute and `//`-prefixed
     *   URLs pass through; otherwise they join `pictureUrlHead` — after
     *   `https://` is added when the head carries no scheme. Relative URLs
     *   without a head resolve against the reader page origin.
     */
    private fun extractPageUrls(rawBody: String, rawRequestBody: String?, readerUrl: String): List<String> {
        if (!rawBody.contains("chapterPages") || !rawBody.contains("pictureUrls")) return emptyList()

        val root = runCatching { Json.parseToJsonElement(rawBody) }.getOrNull()
        if (root == null) {
            // Inline-script/SSR text (not pure JSON): the regex path inside
            // deepScanPictureUrls is the only chance to recover the payload.
            return deepScanPictureUrls(null, rawBody, readerUrl)?.let { sanitizePageUrls(it, readerUrl) }
                ?: emptyList()
        }
        val edges = root.jsonObjectOrNull("data")
            ?.jsonObjectOrNull("chapterPages")
            ?.jsonArrayOrNull("edges")
            ?: return deepScanPictureUrls(root, rawBody, readerUrl)?.let { sanitizePageUrls(it, readerUrl) }
                ?: emptyList()

        val chapterSegment = runCatching { readerUrl.toHttpUrl().pathSegments }
            .getOrNull()
            ?.filter(String::isNotBlank)
            ?.getOrNull(2)
            .orEmpty()

        // Strong request-side match: reject payloads minted for another
        // manga/chapter (the variables echo the reader's own query).
        requestVariables(rawRequestBody)?.let { vars ->
            fun str(key: String) = (vars[key] as? JsonPrimitive)?.content?.trim()
            val ref = chapterRefFromUrl(readerUrl)
            val mangaId = str("mangaId")
            if (mangaId != null && ref != null && !mangaId.equals(ref.mangaId, ignoreCase = true)) return emptyList()
            val chapterString = str("chapterString")
            if (!chapterString.isNullOrBlank() && chapterSegment.isNotBlank() && !chapterSegment.contains(chapterString)) {
                return emptyList()
            }
        }

        // Soft response-side match: when every edge names a chapterString and
        // none appears in our reader URL, this payload is another chapter's.
        val edgeChapterStrings = edges.mapNotNull { edge ->
            ((edge as? JsonObject)?.get("chapterString") as? JsonPrimitive)?.content?.trim()
        }.filter { it.isNotBlank() }
        if (edgeChapterStrings.isNotEmpty() && chapterSegment.isNotBlank() &&
            edgeChapterStrings.none(chapterSegment::contains)
        ) {
            return emptyList()
        }

        // foe(): keep the highest-priority edge per source group.
        val groups = LinkedHashMap<String, JsonObject>()
        for (element in edges) {
            val edge = element as? JsonObject ?: continue
            val sourceName = (edge["sourceName"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (sourceName.isEmpty() || sourceName == "unkonw" || sourceName.contains("Wp-")) continue
            val streamerId = (edge["streamerId"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val key = "$streamerId|$sourceName"
            val existing = groups[key]
            if (existing == null || edgePriority(edge) > edgePriority(existing)) groups[key] = edge
        }

        // goe(): first source (highest priority) with a usable page list,
        // AFTER sanitising every candidate (drops reader-page links and any
        // site-host entry — the payload can hand out a link back to the
        // reader as "page 1"). Some streamers also expose the ENTIRE
        // chapter as a single strip; the site reader effectively prefers
        // proper per-page sources, and so do we: a multi-entry list beats
        // a single-entry one unless nothing else exists.
        val candidates = groups.values.sortedByDescending(::edgePriority)
            .map { edge -> sanitizePageUrls(normalizeEdgePages(edge, readerUrl), readerUrl) }
            .filter { it.isNotEmpty() }

        return candidates.firstOrNull { it.size > 1 }
            ?: candidates.firstOrNull()
            ?: deepScanPictureUrls(root, rawBody, readerUrl)?.let { sanitizePageUrls(it, readerUrl) }
            ?: emptyList()
    }

    /**
     * Shape-drift safety net: recursively walks ANY JSON payload and
     * collects every object that carries a "pictureUrls" array, wherever
     * it now lives (the site actively reshapes its payloads to break
     * extension scrapers — the structured path above can come up empty
     * while the data is right there under a new envelope). Falls back to
     * regex extraction when the body isn't pure JSON (inline script text).
     */
    private fun deepScanPictureUrls(root: JsonElement?, rawBody: String, readerUrl: String): List<String>? {
        if (root != null) {
            val found = LinkedHashMap<String, List<String>>()
            fun walk(element: JsonElement?) {
                when (element) {
                    is JsonObject -> {
                        if (element.containsKey("pictureUrls") || element.containsKey("pictureUrlsProcessed")) {
                            val joined = normalizeEdgePages(element, readerUrl)
                            if (joined.isNotEmpty()) {
                                found.putIfAbsent(element.hashCode().toString(), joined)
                            }
                        }
                        element.values.forEach(::walk)
                    }
                    is JsonArray -> element.forEach(::walk)
                    else -> {}
                }
            }
            walk(root)
            if (found.isNotEmpty()) {
                return found.values.firstOrNull { it.size > 1 } ?: found.values.first()
            }
        }

        // Not parseable as JSON with pictureUrls anywhere — try regexing
        // "pictureUrls"/"pictureUrlsProcessed" fragments out of the raw
        // text (inline scripts, JSON-string-encoded payloads). Entries are
        // flat {num,url} objects, so a bracket-balanced-enough scan is
        // workable.
        val regex = Regex("\"(?:pictureUrls|pictureUrlsProcessed)\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(rawBody)) {
            val arrayText = match.groupValues[1].takeIf { it.length < 400_000 } ?: continue
            val array = runCatching { Json.parseToJsonElement(arrayText) }.getOrNull() as? JsonArray ?: continue
            val urls = parsePictureUrlEntries(array)
                .mapNotNull { (num, raw) ->
                    val url = when {
                        raw.startsWith("http://") || raw.startsWith("https://") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        else -> runCatching { readerUrl.toHttpUrl().resolve(raw)?.toString() }.getOrNull()
                    }
                    if (url != null && url.startsWith("http")) num to url else null
                }
                .sortedBy { it.first }
                .map { it.second }
            if (urls.isNotEmpty()) return urls
        }
        return null
    }

    private fun edgePriority(edge: JsonObject): Float = (edge["priority"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f

    /**
     * The reader's KP()/yP(): normalise every entry of one edge's
     * pictureUrls into an absolute URL, ordered by page number.
     * Some streamers only fill `pictureUrlsProcessed` (the site's own
     * chapterPages query selects both fields) — it carries the same
     * entries, so it is used as a fallback when pictureUrls is empty.
     */
    private fun normalizeEdgePages(edge: JsonObject, readerUrl: String): List<String> {
        val rawHead = (edge["pictureUrlHead"] as? JsonPrimitive)?.content?.trim().orEmpty()
        // yP: a head without "//" gets https:// prepended.
        val head = when {
            rawHead.isEmpty() -> ""
            rawHead.contains("//") -> rawHead
            else -> "https://$rawHead"
        }

        val pages = parsePictureUrlEntries(edge["pictureUrls"])
            .ifEmpty { parsePictureUrlEntries(edge["pictureUrlsProcessed"]) }

        return pages
            .mapNotNull { (num, raw) ->
                val url = when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("blob:") -> return@mapNotNull null // can't be fetched outside the page
                    head.isNotBlank() -> head.trimEnd('/') + "/" + raw.trimStart('/')
                    else -> runCatching { readerUrl.toHttpUrl().resolve(raw)?.toString() }.getOrNull()
                }
                if (url != null && url.startsWith("http")) num to url else null
            }
            .sortedBy { it.first } // stable sort by page number, site order within equals
            .map { it.second }
    }

    /**
     * The reader's GE()/VE(): pictureUrls is a list of `{num, url}` objects
     * (aliases `{n, u}`), possibly JSON-encoded as a string; plain string
     * entries are accepted as a fallback with their index as page number.
     */
    private fun parsePictureUrlEntries(element: JsonElement?): List<Pair<Float, String>> {
        val array = when (element) {
            is JsonArray -> element
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(element.content) }
                .getOrNull()
                .asOrNull<JsonArray>()
                ?: return emptyList()
            else -> return emptyList()
        }

        return array.mapIndexedNotNull { index, item ->
            when (item) {
                is JsonObject -> {
                    val url = ((item["url"] ?: item["u"]) as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (url.isEmpty()) return@mapIndexedNotNull null
                    val num = ((item["num"] ?: item["n"]) as? JsonPrimitive)?.content?.toFloatOrNull()
                        ?: index.toFloat()
                    num to url
                }
                is JsonPrimitive -> item.content.trim().takeIf(String::isNotEmpty)?.let { index.toFloat() to it }
                else -> null
            }
        }
    }

    private inline fun <reified T> JsonElement?.asOrNull(): T? = this as? T

    private fun JsonElement?.jsonObjectOrNull(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun JsonElement?.jsonArrayOrNull(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    /** True for URLs that look like page images — never site UI, API or captcha traffic. */
    private fun isPageImageUrl(url: String): Boolean {
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return false
        if (host == "challenges.cloudflare.com" || host == apiHost) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (isSiteUiAssetPath(path)) return false
        return path.substringAfterLast('.').substringBefore('%') in PAGE_IMAGE_EXTENSIONS
    }

    /**
     * Site chrome assets that browsers auto-request but are NOT chapter
     * pages: favicons of every flavour, PWA icons, manifest. This is what
     * the v15 build harvested as "page 1" for EVERY chapter (the browser
     * requests /favicon.ico with an Accept: image/... header, and the old
     * interceptRequest added any accept-image URL without an extension
     * check) — the page list then collapsed to a single favicon image.
     */
    private fun isSiteUiAssetPath(path: String): Boolean {
        if (path.endsWith(".ico") || path.endsWith(".icon")) return true
        return path.substringAfterLast('/').let { name ->
            name.startsWith("favicon") ||
                name.startsWith("apple-touch-icon") ||
                name.startsWith("android-chrome") ||
                name.startsWith("mstile") ||
                name == "site.webmanifest" ||
                name == "manifest.json"
        }
    }

    /** The site itself and any of its subdomains (www., cdn. UI, …). */
    private fun isSiteHost(host: String): Boolean = host == baseUrl.removePrefix("https://") || host.endsWith(".${baseUrl.removePrefix("https://")}")

    /**
     * True when a URL may enter the harvested/extracted page list at all.
     * The site reader hands out payload entries that link BACK to the
     * reader page ("/manga/..." — that single URL serves the WHOLE
     * chapter; loading it as an image is the "first page is the entire
     * chapter" bug and, once cached, the "Failed to initialize decoder"
     * HTML-into-decoder error). Filter by path, not by host: page images
     * live on CDN hosts via pictureUrlHead, but keeping the door open for
     * site-hosted image paths is safer than dropping them blindly.
     */
    private fun isHarvestablePageUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        if (httpUrl.host == "challenges.cloudflare.com") return false
        if (httpUrl.host == apiHost || httpUrl.host.endsWith(".$apiHost")) return false
        if (isSiteUiAssetPath(httpUrl.encodedPath.lowercase())) return false
        val path = httpUrl.encodedPath.lowercase()
        if (path == "/" || path.startsWith("/manga/") || path.startsWith("/api")) return false
        return true
    }

    /** Applies [isHarvestablePageUrl] to extractor output, preserving order. */
    private fun sanitizePageUrls(pages: List<String>, readerUrl: String): List<String> = pages.filter { url -> isHarvestablePageUrl(url) && url.substringBefore('#') != readerUrl }

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        SortFilter(),
        GenreMatchModeFilter(),
        GenreFilter(),
        Filter.Separator(),
        Filter.Header("Tags (site tags like \"theme:monsters\")"),
        TagFilter(),
        Filter.Separator(),
        Filter.Header("Filter by author name"),
        AuthorFilter("Author"),
        SeasonFilter(),
        YearFilter("Year (e.g. 2024)"),
        MinChaptersFilter("Minimum chapters (e.g. 50)"),
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf(
                "Top",
                "Trending",
                "Popular",
                "Latest update",
                "Recently added",
                "Release year",
                "Name",
                "Random",
            ),
            Filter.Sort.Selection(0, false),
        )

    /** ALL (default) = manga must have every selected genre; ANY = at least one. */
    private class GenreMatchModeFilter :
        Filter.Select<String>(
            "Genre match mode",
            arrayOf("All selected genres", "Any selected genre"),
        ) {
        fun matchAll(): Boolean = state == 0
    }

    private class GenreTriState(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it, it) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

        private companion object {
            // Site genre/tag names (verified live against the API, 2026-09).
            val GENRES = listOf(
                "Action", "Adult", "Adventure", "Comedy", "Cooking", "Crossdressing",
                "Demons", "Doujinshi", "Drama", "Ecchi", "Fantasy", "Gender Bender",
                "Harem", "Hentai", "Historical", "Horror", "Isekai", "Josei",
                "Magic", "Manhwa", "Martial Arts", "Mature", "Mecha", "Medical",
                "Military", "Music", "Mystery", "One Shot", "Parody", "Philosophical",
                "Police", "Psychological", "Reincarnation", "Romance", "Samurai",
                "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
                "Space", "Sports", "Super Power", "Supernatural", "Thriller",
                "Tragedy", "Webtoons",
            )
        }
    }

    private class TagTriState(name: String, val value: String) : Filter.TriState(name)

    /**
     * Tags carry the site's own values ("theme:monsters", "format:full_color"
     * plus plain AniList-style names) — verified live. Sending display names
     * that don't exist server-side simply matches nothing, never errors.
     */
    private class TagFilter :
        Filter.Group<TagTriState>(
            "Tags",
            TAGS.map { TagTriState(it.first, it.second) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

        private companion object {
            val TAGS = listOf(
                "Full color" to "format:full_color",
                "Web comic" to "format:web_comic",
                "Long strip" to "format:long_strip",
                "Adaptation" to "format:adaptation",
                "Award winning" to "format:award_winning",
                "Episodic" to "format:episodic",
                "Fan colored" to "format:fan_colored",
                "Isekai" to "theme:isekai",
                "Reincarnation" to "theme:reincarnation",
                "Cultivation" to "theme:cultivation",
                "Monsters" to "theme:monsters",
                "Vampires" to "theme:vampires",
                "Ghosts" to "theme:ghost",
                "Witches" to "theme:witch",
                "Gods" to "theme:gods",
                "Demons" to "theme:demons",
                "Elves" to "theme:elf",
                "Mythology" to "theme:mythology",
                "Superhero" to "theme:superhero",
                "Time travel" to "theme:time_travel",
                "Time skip" to "theme:time_skip",
                "Alternate universe" to "theme:alternate_universe",
                "Post-apocalyptic" to "theme:post_apocalyptic",
                "Dystopian" to "theme:dystopian",
                "Survival" to "theme:survival",
                "War" to "theme:war",
                "Military" to "theme:military",
                "Politics" to "theme:politics",
                "Conspiracy" to "theme:conspiracy",
                "Revenge" to "theme:revenge",
                "Crime" to "theme:crime",
                "Detective" to "theme:detective",
                "Noir" to "theme:noir",
                "Assassins" to "theme:assassins",
                "Martial arts" to "theme:martial_arts",
                "Swordplay" to "theme:swordplay",
                "Boxing" to "theme:boxing",
                "Athletics" to "theme:athletics",
                "Video games" to "theme:video_games",
                "Game elements" to "theme:game_elements",
                "Virtual world" to "theme:virtual_world",
                "School life" to "theme:school_life",
                "School club" to "theme:school_club",
                "Delinquents" to "theme:delinquents",
                "Family life" to "theme:family_life",
                "Parenthood" to "theme:parenthood",
                "Childcare" to "theme:childcare",
                "Found family" to "theme:found_family",
                "Romance" to "theme:romance",
                "Love triangle" to "theme:love_triangle",
                "Yuri" to "theme:yuri",
                "Yaoi" to "theme:yaoi",
                "LGBTQIA themes" to "theme:lgbtq_themes",
                "Crossdressing" to "theme:crossdressing",
                "Gender bender" to "theme:gender_bender",
                "Harem" to "theme:harem",
                "Reverse harem" to "theme:reverse_harem",
                "Villainess" to "theme:villainess",
                "Royal affairs" to "theme:royal_affairs",
                "Kingdom management" to "theme:kingdom_management",
                "Ancient china" to "theme:ancient_china",
                "Historical" to "theme:historical",
                "Rural" to "theme:rural",
                "Urban" to "theme:urban",
                "Office workers" to "theme:office_workers",
                "Cooking" to "theme:cooking",
                "Medicine" to "theme:medicine",
                "Music" to "theme:music",
                "Meta" to "theme:meta",
                "4-koma" to "theme:4_koma",
                "Gore" to "theme:gore",
                "Body horror" to "theme:body_horror",
                "Tragedy" to "theme:tragedy",
                "Suicide" to "theme:suicide",
                "Female protagonist" to "theme:female_protagonist",
                "Male protagonist" to "theme:male_protagonist",
                "Anti-hero" to "theme:anti_hero",
                "Clever protagonist" to "theme:clever_protagonist",
                "Vampires (plain)" to "vampires",
            )
        }
    }

    private class AuthorFilter(title: String) : Filter.Text(title)

    private class SeasonFilter :
        Filter.Select<String>(
            "Season",
            arrayOf("Any", "Winter", "Spring", "Summer", "Fall"),
        ) {
        fun toValue(): String? = when (state) {
            1 -> "Winter"
            2 -> "Spring"
            3 -> "Summer"
            4 -> "Fall"
            else -> null
        }
    }

    private class YearFilter(title: String) : Filter.Text(title)

    private class MinChaptersFilter(title: String) : Filter.Text(title)

    // ========================================================================
    // Settings (Comix-style)
    // ========================================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        MultiSelectListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Default content rating"
            summary = "Ratings to show by default (selecting Pornographic enables adult content)"
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf("safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(setOf("safe", "suggestive"))
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display year, type, status and views"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genre chips"
            summary = "Include tags (theme, format) in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga rating"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("top")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.blockedGenres(): Set<String> = getString(PREF_BLOCKED_GENRES, "")
        ?.split(",")
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.scorePosition(): String = getString(PREF_SCORE_POSITION, "top") ?: "top"

    private fun android.content.SharedPreferences.defaultContentRatings(): Set<String> = getStringSet(PREF_CONTENT_RATING, setOf("safe", "suggestive")) ?: setOf("safe", "suggestive")

    companion object {
        private const val PAGE_SIZE = 24

        private val SORT_OPTIONS = arrayOf(
            "Top",
            "Trending",
            "Popular",
            "Latest_Update",
            "Recent",
            "Release_Year",
            "Name_ASC",
            "Random",
        )
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"

        // GraphQL documents ------------------------------------------------

        private const val QUERY_POPULAR = """
            query(${'$'}type: VaildPopularTypeEnumType!, ${'$'}size: Int!, ${'$'}page: Int, ${'$'}dateRange: Int, ${'$'}allowAdult: Boolean, ${'$'}allowUnknown: Boolean, ${'$'}denyEcchi: Boolean) {
              queryPopular(type: ${'$'}type, size: ${'$'}size, page: ${'$'}page, dateRange: ${'$'}dateRange, allowAdult: ${'$'}allowAdult, allowUnknown: ${'$'}allowUnknown, denyEcchi: ${'$'}denyEcchi) {
                recommendations {
                  anyCard {
                    _id
                    name
                    englishName
                    nativeName
                    thumbnail
                    tbObj { u }
                  }
                }
              }
            }
        """

        private const val QUERY_MANGA_LIST = """
            query(${'$'}search: SearchInput, ${'$'}page: Int, ${'$'}limit: Int) {
              mangas(search: ${'$'}search, page: ${'$'}page, limit: ${'$'}limit, translationType: sub) {
                edges {
                  _id
                  name
                  englishName
                  nativeName
                  thumbnail
                  tbObj { u }
                }
              }
            }
        """

        // IMPORTANT: `airedStart` and `availableChaptersDetail` are opaque
        // "Object" scalar fields — they must NOT carry a sub-selection.
        // Selecting subfields on them is a GraphQL validation error and the
        // whole details query answers HTTP 400 (details/chapters never loaded).
        private const val QUERY_MANGA_DETAILS = """
            query(${'$'}_id: String!) {
              manga(_id: ${'$'}_id) {
                _id
                name
                englishName
                nativeName
                altNames
                authors
                description
                status
                type
                genres
                tags
                thumbnail
                tbObj { u }
                airedStart
                lastChapterDate
                score
                averageScore
                pageStatus { userScoreAverValue }
                availableChaptersDetail
              }
            }
        """

        // (The chapter-pages GraphQL document was removed: the server now
        // answers AA_CRYPTO_MISSING to any request lacking the site's own
        // obfuscated anti-abuse crypto proof, so pages are collected from the
        // real reader in an off-screen WebView instead — see getPageList.)

        // v17: prefix bumped again so page lists captured by the v15/v16
        // builds (favicon-only lists — the harvester picked up the browser's
        // /favicon.ico request as "page 1" for EVERY chapter) are never
        // served.
        private const val PAGE_CACHE_PREFIX = "mkissa_pages_v4_"
        private const val LEGACY_PAGE_CACHE_PREFIX = "mkissa_pages_"
        private const val PAGE_CACHE_MAX_ENTRIES = 60

        /** Reader URL translation suffixes ("chapter-<cs>-<tt>"). */
        private val TRANSLATION_TYPES = setOf("sub", "dub", "raw")

        /** chapterPages persisted-query identity (captured live, 2026-09). */
        private const val CHAPTER_PAGES_HASH = "d5de96b785ca8e8b94aaeec261a864ed5d58be45e671585dbc9a4421824d6d0d"
        private const val CHAPTER_PAGES_LANE = "k9"

        /** Minimum length for a plausible captcha token (real ones are 100+). */
        private const val TOKEN_MIN_LENGTH = 40

        /** Modal-watcher thresholds, in 1-second polls. */
        private const val PROVIDER_SWITCH_POLL = 12
        private const val FIRST_TAP_POLL = 15
        private const val TAP_RETRY_POLL = 5
        private const val MAX_TAP_ATTEMPTS = 3

        /** Seconds to wait after a token appeared before the active POST fires. */
        private const val ACTIVE_POST_GRACE = 6

        /** Checkbox position inside the captcha widget (CSS px, both providers). */
        private const val CHECKBOX_OFFSET_X = 30f

        /** Challenge-page title markers used by the stuck-detection poll. */
        private val CHALLENGE_TITLE_REGEX = Regex(
            "just a moment|attention required|security verification|verify you are human|checking your browser",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Injected at page start (and re-installed by the poll loop): wraps
         * window.fetch and XMLHttpRequest so every API call is reported:
         *  - responses carrying the chapterPages payload → {url,req,res}
         *    (the primary capture path — one response carries the FULL list),
         *  - every request containing `aaReq` (or the client-crypto bootstrap)
         *    → {kind:"aareq",url,req} so we can harvest the site's own crypto
         *    proof + build id for the active fallback POST.
         * Our .then handler is attached before the promise is handed back to
         * the site, so the response body is teed before the site consumes it.
         */
        private val FETCH_HOOK_JS = """
            (function(){
              if(window.__mhHookInstalled) return;
              window.__mhHookInstalled=true;
              function sendReq(url, req){
                try{
                  var u=String(url||''), r=String(req||'');
                  if(u.indexOf('aaReq')===-1 && r.indexOf('aaReq')===-1 && u.indexOf('client-crypto')===-1) return;
                  window.mhbridge.post(JSON.stringify({kind:'aareq',url:u.slice(0,4000),req:r.slice(0,4000)}));
                }catch(e){}
              }
              function sendPair(url, req, res){
                try{
                  res=String(res||'');
                  if(res.indexOf('chapterPages')===-1 || res.indexOf('pictureUrls')===-1) return;
                  window.mhbridge.post(JSON.stringify({url:String(url||''),req:String(req||''),res:res.slice(0,2000000)}));
                }catch(e){}
              }
              function isApiUrl(u){
                u=String(u||'');
                return u.indexOf('mkissa')!==-1 || u.indexOf('graphql')!==-1;
              }
              var of=window.fetch;
              if(of){
                window.fetch=function(){
                  var p=of.apply(this,arguments);
                  try{
                    var a0=arguments[0], a1=arguments[1];
                    var url=typeof a0==='string'?a0:(a0&&a0.url)||'';
                    var req=(a1&&a1.body!=null)?String(a1.body):'';
                    if(isApiUrl(url)){
                      sendReq(url,req);
                      p.then(function(r){
                        try{ r.clone().text().then(function(txt){ sendPair(url,req,txt); }).catch(function(){}); }catch(e){}
                      }).catch(function(){});
                    }
                  }catch(e){}
                  return p;
                };
              }
              var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open=function(m,u){
                this.__mhUrl=u; return oo.apply(this,arguments);
              };
              XMLHttpRequest.prototype.send=function(){
                var x=this;
                var req=(arguments[0]!=null)?String(arguments[0]):'';
                try{
                  x.addEventListener('load',function(){
                    try{
                      var u=String(x.__mhUrl||'');
                      if(isApiUrl(u)){ sendReq(u,req); sendPair(u,req,x.responseText); }
                    }catch(e){}
                  });
                }catch(e){}
                return os.apply(this,arguments);
              };
            })();
        """.trimIndent()

        /**
         * Injected at page start (and re-installed by the poll loop):
         *  1. Shims window.flutter_inappwebview.callHandler so the site's
         *     Flutter-bridge captcha handoff (dev-variant) reaches our bridge.
         *  2. Listens for the sitea-captcha-ready postMessage the
         *     iframe-provider variant emits, forwarding {token, provider}.
         * (On production mkissa.to the token is read directly from the
         * parent-DOM inputs — see CAPTCHA_STATE_JS; these hooks are the
         * belt-and-braces for the other variants.)
         */
        private val CAPTCHA_HOOK_JS = """
            (function(){
              try{
                if(window.__mhCaptchaHook) return;
                window.__mhCaptchaHook=true;
                if(!window.flutter_inappwebview){
                  window.flutter_inappwebview={
                    callHandler:function(name){
                      try{
                        var args=Array.prototype.slice.call(arguments,1);
                        window.mhbridge.post(JSON.stringify({kind:'flutter',name:String(name||''),payload:JSON.stringify(args)}));
                      }catch(e){}
                      return Promise.resolve(null);
                    }
                  };
                }
                window.addEventListener('message',function(e){
                  try{
                    var d=e&&e.data;
                    if(d&&typeof d==='object'&&(d.type==='sitea-captcha-ready'||(d.token&&d.provider))){
                      window.mhbridge.post(JSON.stringify({kind:'captcha',payload:JSON.stringify(d)}));
                    }
                  }catch(err){}
                });
              }catch(e){}
            })();
        """.trimIndent()

        /**
         * Reads the captcha state from the PARENT document: the site renders
         * Turnstile directly into #captcha-root and the token lands in
         * input[name="cf-turnstile-response"] (or textarea#g-recaptcha-response
         * after switching providers) — both readable cross-page without
         * touching the cross-origin widget iframe. Also reports the widget
         * iframe rect for the synthetic-tap fallback.
         */
        private val CAPTCHA_STATE_JS = """
            (function(){
              try{
                var root=document.getElementById('captcha-root');
                var token='';
                if(root){
                  var els=root.querySelectorAll('input[name="cf-turnstile-response"],input[id^="cf-chl-widget"][id$="_response"],textarea[name="g-recaptcha-response"],textarea#g-recaptcha-response');
                  for(var i=0;i<els.length;i++){
                    var v=String(els[i].value||'');
                    if(v.length>token.length) token=v;
                  }
                }
                var overlay=document.querySelector('.captcha-overlay--visible')!=null;
                var rect=null;
                if(root){
                  var fr=root.querySelector('iframe');
                  if(fr){ var r=fr.getBoundingClientRect(); if(r.width>10&&r.height>10){ rect={x:r.x,y:r.y,w:r.width,h:r.height}; } }
                }
                return JSON.stringify({token:token,overlay:overlay,rect:rect});
              }catch(e){ return JSON.stringify({token:'',overlay:false,rect:null}); }
            })();
        """.trimIndent()

        /**
         * Clicks the modal's "Use Google reCAPTCHA" footer button — the
         * site's own provider switch (plain DOM, scriptable) that re-renders
         * the widget as a reCAPTCHA checkbox when Turnstile won't complete.
         */
        private val SWITCH_RECAPTCHA_JS = """
            (function(){
              try{
                var btns=document.querySelectorAll('.captcha-footer button, button.captcha-btn--link');
                for(var i=0;i<btns.length;i++){
                  var t=(btns[i].textContent||'').toLowerCase();
                  if(t.indexOf('recaptcha')!==-1){ btns[i].click(); return 'switched'; }
                }
                return 'nobutton';
              }catch(e){ return 'err'; }
            })();
        """.trimIndent()

        /**
         * SPA-clicks the target chapter row on the series page. Reader rows
         * are div.media-ep-item[data-href=…] (NOT links) handled by the
         * site's client-side router — clicking one navigates to the reader
         * WITHOUT fetching the Cloudflare-challenged reader document.
         */
        private fun spaClickJs(chapterPath: String) = """
            (function(){
              try{
                if(window.__mhSpaDone) return 'done';
                var target='$chapterPath';
                var rows=document.querySelectorAll('div.media-ep-item[data-href], a.media-ep-item[href], .route-link[data-href]');
                for(var i=0;i<rows.length;i++){
                  var href=rows[i].getAttribute('data-href')||rows[i].getAttribute('href')||'';
                  if(href===target){ rows[i].click(); window.__mhSpaDone=true; return 'clicked'; }
                }
                if(!window.__mhCollTried){
                  window.__mhCollTried=true;
                  var coll=document.querySelector('.collapser,[class*="collapser"]');
                  if(coll){ coll.click(); return 'collapser'; }
                }
                return 'norows';
              }catch(e){ return 'err'; }
            })();
        """.trimIndent()

        /** Collects every page-image URL currently in the DOM. */
        private val DOM_IMAGES_JS = """
            (function () {
              try {
                var out = [];
                var imgs = document.images;
                for (var i = 0; i < imgs.length; i++) {
                  var im = imgs[i];
                  var s = im.currentSrc || im.src || im.getAttribute('data-src') || '';
                  if (s) out.push(String(s));
                }
                return JSON.stringify(out);
              } catch (e) { return '[]'; }
            })();
        """.trimIndent()

        /**
         * Scans inline <script> payloads (SSR state / embedded JSON) for the
         * chapterPages payload and pushes hits through the same bridge path
         * as the fetch hook. The site pins realm-isolated primitives so
         * extension fetch hooks can't see its API calls — embedded state is
         * the remaining in-page copy of the same data.
         */
        private val INLINE_PAYLOAD_JS = """
            (function(){
              try{
                if(window.__mhInlineScan) return;
                window.__mhInlineScan=true;
                var scripts=document.querySelectorAll('script:not([src])');
                for(var i=0;i<scripts.length;i++){
                  var t=String(scripts[i].textContent||'');
                  if(t.indexOf('chapterPages')!==-1 && t.indexOf('pictureUrls')!==-1){
                    try{
                      window.mhbridge.post(JSON.stringify({url:location.href,req:'',res:t.slice(0,2000000)}));
                    }catch(e){}
                  }
                }
              }catch(e){}
            })();
        """.trimIndent()

        /** Current page title — used to detect a stuck Cloudflare challenge. */
        private const val DOM_TITLE_JS = "document.title"

        /** File extensions the chapter-page image harvester accepts. */
        private val PAGE_IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "avif", "gif", "jfif")
    }
}
