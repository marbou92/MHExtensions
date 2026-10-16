package eu.kanade.tachiyomi.extension.en.kagane

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.cloudflare.isCloudflareChallenge
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getStringSafe
import keiyoushi.utils.getStringSetSafe
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException

@Source
abstract class Kagane :
    KeiSource(),
    ConfigurableSource {

    private val kaganeLangs: List<String>
        get() = if (lang == "zh") listOf("zh-Hans", "zh-Hant") else listOf(lang)

    private val prefs = getPreferences()

    // ============================ Mirrors (v72) ============================
    //
    // kagane.to (the v50 domain) is gone — NXDOMAIN. The site now lives on
    // kagane.si with kagane.cx / kagane.moe / kagane.net as mirrors; ALL of
    // them serve the same backend and the same ID space (verified 2026-10:
    // /api/integrity answers on every host, /api/v2/* paths identical), so a
    // mirror switch never invalidates library entries — manga/chapter urls
    // are bare ids.
    //
    // Three layers of fallback, closest to xcomic's mirror system:
    //  1. a fixed mirror list the user can switch in the extension settings
    //     (plus a free-text custom mirror override),
    //  2. an auto-refresh that scrapes kagane.org — the site's OWN
    //     mirror-status page (server-rendered <a> links) — whenever every
    //     known mirror is down, so new domains appear WITHOUT an app update
    //     (throttled to once every 6h; toggle in the extension settings),
    //  3. automatic failover: an interceptor that rewrites a request whose
    //     host is dead (connect failure / DNS NXDOMAIN / CF 521+523) onto
    //     the next mirror and persists the winner.

    private val staticMirrors = listOf(
        "https://kagane.si",
        "https://kagane.cx",
        "https://kagane.moe",
        "https://kagane.net",
    )

    private val discoveredMirrors: List<String>
        get() = prefs.getStringSetSafe(PREF_DISCOVERED_MIRRORS, emptySet())
            .filter { it.startsWith("https://") && it.toHttpUrlOrNull() != null }
            .sorted()

    private val knownMirrors: List<String>
        get() = (staticMirrors + discoveredMirrors).distinct()

    private val knownMirrorHosts: Set<String>
        get() = knownMirrors.map { it.toHttpUrl().host }.toSet()

    /** The active mirror: a user-entered custom URL wins, then the last
     * mirror that worked (switched in settings or picked by failover), then
     * the primary. */
    private val mirror: String
        get() {
            prefs.getStringSafe(PREF_CUSTOM_MIRROR, "")?.trim()?.trimEnd('/')?.let { custom ->
                if (custom.startsWith("https://") && custom.toHttpUrlOrNull() != null) return custom
            }
            val active = prefs.getStringSafe(PREF_MIRROR, "")?.trim()?.trimEnd('/')
            if (!active.isNullOrEmpty() && active in knownMirrors) return active
            return knownMirrors.first()
        }

    private val domain get() = mirror.toHttpUrl().host
    private val apiUrl get() = "https://$domain/api/v2"

    override fun OkHttpClient.Builder.configureClient() = apply {
        // The Cloudflare method (v23): a challenge SOLVER in a window-attached
        // WebView. Research conclusion (2026-09): header hardening can never
        // pass a managed challenge — only a JS-executing browser environment
        // solves them, and the WebView must be ATTACHED to a window: an
        // unattached one reports document.visibilityState="hidden" and the
        // Turnstile widget silently never renders (no token, no auto-solve).
        // runWebView now parks its WebView behind the app's own UI, so the
        // challenge auto-solves exactly like a real browser. Measured on this
        // site: cf_clearance TTL is ONE YEAR — a single solve lasts, and the
        // old "solve it manually every hour" loop was false-positive
        // detection wiping valid clearance (see isCloudflareChallenge).
        // Fingerprint headers stay for the traffic BETWEEN solves (they
        // keep CF's bot score of the cleared session healthy).
        //
        // Kept from the 2026-09 audit:
        // - originSanitizer: KeiSource stamps "Origin: <baseUrl>" onto every
        //   request, but browsers NEVER send Origin on document GETs — an
        //   inconsistent Origin is a bot signal that kept challenging clean
        //   traffic.
        // - refreshTokenInterceptor: Kagane's own auth (token-bearing URLs)
        //   stays handled here; CF-challenge responses pass through it
        //   untouched (they can't be fixed by a token refresh).
        //
        // v72: the mirror-failover interceptor runs FIRST (it rewrites the
        // host before anything else looks at the request); the CF solver
        // covers every known mirror so a challenge on any of them can be
        // solved. The old kagane.to stays swappable so thumbnails stored by
        // previous versions get rewritten instead of erroring forever.
        addInterceptor(::mirrorFailoverInterceptor)

        addInterceptor(CloudflareSolverInterceptor(knownMirrorHosts + "kagane.to"))

        addInterceptor(::originSanitizerInterceptor)
        addInterceptor(::refreshTokenInterceptor)

        rateLimit(3)
    }

    /** The hosts the failover may rewrite onto a mirror: every known mirror
     * plus the dead v50 domain (stored thumbnail urls point there). */
    private fun failoverHosts(): Set<String> = knownMirrorHosts + "kagane.to"

    private fun Request.withMirrorHost(mirrorUrl: String): Request {
        val newHost = mirrorUrl.toHttpUrl().host
        val builder = newBuilder().url(
            url.newBuilder().scheme("https").host(newHost).build(),
        )
        // Keep the Referer consistent with the host we are now talking to.
        if (!header("Referer").isNullOrBlank()) {
            builder.header("Referer", "https://$newHost/")
        }
        return builder.build()
    }

    private fun nextMirror(excludeHosts: Set<String>): String? = knownMirrors.firstOrNull { it.toHttpUrl().host !in excludeHosts }

    /** Lock guarding the throttled auto-refresh (reads-then-writes prefs). */
    private val mirrorRefreshLock = Any()

    /** When every known mirror just failed: fetch kagane.org's current
     * mirror list once (throttled) so the NEXT request can use fresh
     * domains. Returns true when new mirrors appeared. */
    private fun maybeAutoRefreshMirrors(): Boolean {
        if (!prefs.getBoolean(PREF_AUTO_REFRESH, true)) return false
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_MIRROR_REFRESH, 0L) < AUTO_REFRESH_INTERVAL_MS) return false
        synchronized(mirrorRefreshLock) {
            if (now - prefs.getLong(PREF_LAST_MIRROR_REFRESH, 0L) < AUTO_REFRESH_INTERVAL_MS) return false
            prefs.edit().putLong(PREF_LAST_MIRROR_REFRESH, now).apply()
            val knownBefore = knownMirrors.toSet()
            val result = runCatching { refreshMirrorsFromOrg() }
                .onFailure { Log.e(TAG, "Auto mirror refresh failed", it) }
                .getOrNull().orEmpty()
            Log.i(TAG, "Auto mirror refresh: $result")
            return knownMirrors.toSet() != knownBefore
        }
    }

    /**
     * Rewrites a failed request onto the next mirror and retries — mirrors
     * share one backend and one ID space, so only scheme+host change.
     * Triggers: connect-level IOExceptions (DNS-dead hosts — the exact
     * kagane.to failure mode) and Cloudflare's 521/523 origin-down codes.
     * The first mirror that answers is persisted as the active one so later
     * requests start there. Requests whose host is not a mirror host (e.g.
     * the kagane.org mirror-list fetch) pass through untouched.
     */
    private fun mirrorFailoverInterceptor(chain: Interceptor.Chain): Response {
        val originalHost = chain.request().url.host
        if (originalHost !in failoverHosts()) return chain.proceed(chain.request())

        var request = chain.request()
        val tried = mutableSetOf(originalHost)

        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                var next = nextMirror(tried)
                if (next == null) {
                    // All known mirrors failed: maybe the site moved again.
                    // Pull kagane.org's list (throttled) and retry if it grew.
                    maybeAutoRefreshMirrors()
                    next = nextMirror(tried) ?: throw e
                }
                request = request.withMirrorHost(next)
                tried += next.toHttpUrl().host
                continue
            }

            if (response.code == 521 || response.code == 523) {
                response.close()
                var next = nextMirror(tried)
                if (next == null) {
                    maybeAutoRefreshMirrors()
                    next = nextMirror(tried)
                        ?: throw IOException("Kagane: all mirrors are unreachable")
                }
                request = request.withMirrorHost(next)
                tried += next.toHttpUrl().host
                continue
            }

            if (request.url.host != originalHost) {
                prefs.edit()
                    .putString(PREF_MIRROR, "https://" + request.url.host)
                    .apply()
            }
            return response
        }
    }

    /**
     * Strips "Origin" from GET requests — browsers only send Origin on
     * CORS fetch/XHR and POSTs, never on document navigations or <img>
     * loads. Carrying it on every GET is a bot signal that keeps
     * Cloudflare challenging otherwise-clean traffic (the reported
     * "bypass is slow").
     */
    private fun originSanitizerInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "GET" && request.header("Origin") != null) {
            return chain.proceed(request.newBuilder().removeHeader("Origin").build())
        }
        return chain.proceed(request)
    }

    // Cloudflare challenge detection uses the strict core implementation
    // (keiyoushi.cloudflare.isCloudflareChallenge): cf-mitigated, or 403/503
    // from Cloudflare with challenge-page BODY markers. Kagane's own API
    // returns plain-JSON 401/403 auth errors from behind Cloudflare — the old
    // header-only check misread those as challenges, skipped the token
    // refresh and dumped the user into the app-level WebView prompt for a
    // mere expired token.

    private fun refreshTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.queryParameterNames.contains("token")) {
            return chain.proceed(request)
        }

        val chapterId = if (url.pathSegments[4] == "datasaver") {
            url.pathSegments[5]
        } else {
            url.pathSegments[4]
        }

        var response = chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                .build(),
        )

        // Upstream-exact: a 401/403/507 from a token-bearing URL is treated
        // as a Kagane auth failure and triggers a token refresh — EXCEPT
        // Cloudflare challenges (see isCloudflareChallenge), which fall
        // through to the app-level interceptor instead of burning a token
        // refresh per challenged request (the reported slowness).
        if (response.code in listOf(401, 403, 507) && !response.isCloudflareChallenge()) {
            response.close()
            val challenge = runBlocking {
                runCatching { getChallengeResponse(chapterId) }
            }.getOrElse { throw IOException("Failed to retrieve token") }

            accessToken = challenge.accessToken
            response = chain.proceed(
                request.newBuilder()
                    .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                    .build(),
            )
        }

        return response
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(
            SortFilter(Filter.Sort.Selection(1, false)),
            ContentRatingFilter(contentRating.toSet()),
            GenresFilter(emptyList(), excludedGenreIds),

        ),
    )

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(
            SortFilter(Filter.Sort.Selection(6, false)),
            ContentRatingFilter(contentRating.toSet()),
            GenresFilter(emptyList(), excludedGenreIds),
        ),
    )

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sortParam = ""

        val body = buildJsonObject {
            if (query.isNotBlank()) {
                put("title", query)
            }

            val displayMode = sourceDisplayMode
            val sourceTypes = if (displayMode == "official") {
                listOf("Official")
            } else {
                listOf("Official", "Unofficial", "Mixed")
            }
            putJsonArray("source_type") {
                sourceTypes.forEach { add(it) }
            }

            var genresMatchAll: Boolean? = null
            var tagsMatchAll: Boolean? = null

            filters.forEach { filter ->
                when (filter) {
                    is MatchAllGenresFilter -> {
                        genresMatchAll = if (filter.state) true else null
                    }
                    is MatchAllTagsFilter -> {
                        tagsMatchAll = if (filter.state) true else null
                    }
                    is SortFilter -> {
                        sortParam = filter.toUriPart()
                    }
                    else -> {}
                }
            }

            filters.forEach { filter ->
                when (filter) {
                    is GenresFilter -> {
                        filter.addToJsonObject(this, "genres", genresMatchAll)
                    }

                    is TagFilter -> {
                        filter.addToJsonObject(this, "tags", tagsMatchAll)
                    }

                    is SourcesFilter -> {
                        filter.addToJsonObject(this)
                    }

                    is JsonFilter -> {
                        filter.addToJsonObject(this)
                    }

                    else -> {}
                }
            }

            // Add languages specific to this source instance
            putJsonArray("content_lang") {
                kaganeLangs.forEach { add(it) }
            }
        }.toJsonRequestBody()

        val url = "$apiUrl/search/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", (page - 1).toString())
            addQueryParameter("size", 35.toString()) // Default items per request
            if (sortParam.isNotEmpty()) addQueryParameter("sort", sortParam)
        }.build()

        val response = client.post(url, body)
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.content.map { it.toSManga(apiUrl, showSource, sources, cleanTitle) }
        return MangasPage(mangas, hasNextPage = dto.hasNextPage())
    }

    // ====================== Manga Details + Chapters ======================

    private suspend fun getMangaById(seriesId: String): DetailsDto {
        val url = "$apiUrl/series/$seriesId"
        return client.get(url).parseAs<DetailsDto>()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        // Deep links can arrive from any mirror (kagane.org links them all).
        if (url.host !in knownMirrorHosts) return null
        val segments = url.pathSegments
        if (segments.size < 2) return null
        val seriesId = segments[1]
        val dto = getMangaById(seriesId)
        return parseMangaDetails(dto).apply {
            this.url = seriesId
            memo = buildMemo(dto)
            initialized = true
        }
    }

    private suspend fun parseMangaDetails(details: DetailsDto): SManga {
        val sourceName = details.sourceId?.let { sourceId ->
            sources[sourceId]
        }
        return details.toSManga(apiUrl, sourceName, baseUrl, showEdition, showSource, cleanTitle)
    }

    override fun getMangaUrl(manga: SManga): String = "$mirror/series/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val seriesId = manga.url
        val dto = getMangaById(seriesId)

        val updatedManga = parseMangaDetails(dto).apply {
            memo = buildMemo(dto)
        }
        val useSourceChapterNumber = dto.format in setOf(
            "Dark Horse Comics",
            "Flame Comics",
            "MangaDex",
            "Square Enix Manga",
        )
        val updatedChapters = parseChapterList(seriesId, useSourceChapterNumber)

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * Identity data the related-manga route needs later: the tracker id
     * (alternate editions) plus the series' genre/tag ids (client-side
     * similar search — the API has no suggestions endpoint).
     */
    private fun buildMemo(dto: DetailsDto): JsonObject = buildJsonObject {
        if (!dto.trackerId.isNullOrEmpty()) {
            put("trackerId", dto.trackerId)
        }
        val genreIds = dto.genres.mapNotNull { it.genreId }
        val tagIds = dto.tags.mapNotNull { it.tagId }
        if (genreIds.isNotEmpty()) {
            putJsonArray("genreIds") { genreIds.forEach { add(it) } }
        }
        if (tagIds.isNotEmpty()) {
            putJsonArray("tagIds") { tagIds.forEach { add(it) } }
        }
    }

    private fun memoStringList(manga: SManga, key: String): List<String> = manga.memo[key]?.jsonArray
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        .orEmpty()

    /**
     * Chapters come from the books INDEX endpoint (v72): the details
     * response stopped embedding `series_books`, and the plain /books
     * endpoint caps at 100 rows while ignoring its cursor — the index
     * returns every book in one request (live-checked on all mirrors).
     */
    private suspend fun parseChapterList(seriesId: String, useSourceChapterNumber: Boolean): List<SChapter> = client.get("$apiUrl/series/$seriesId/books/index?fields=List")
        .parseAs<BooksIndexDto>()
        .toSChapterList(seriesId, useSourceChapterNumber, chapterTitleMode)

    override fun getChapterUrl(chapter: SChapter): String = "$mirror${chapter.url}"

    // =========================== Related Manga ============================
    override val supportsRelatedMangas = true

    /**
     * Related manga = tracker siblings + client-side "similar".
     *
     * The API has NO suggestions/similar endpoint (route surface verified
     * 2026-09), and the old implementation only ever returned same-tracker
     * siblings (alternate editions of the SAME work — often just itself).
     * The similar list is built by searching with this series' own genres
     * and tags (match ANY), most-viewed first, excluding self and siblings.
     */
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val selfId = manga.url
        val results = LinkedHashMap<String, SManga>()
        val siblings = mutableSetOf<String>()

        // 1) Tracker siblings — alternate editions/translations of the work.
        manga.memo["trackerId"]?.string?.let { trackerId ->
            runCatching {
                client.get("$apiUrl/trackers/$trackerId/series")
                    .parseAs<TrackerDto>()
                    .bookSeries
            }.getOrNull()?.forEach { book ->
                siblings += book.id
                if (book.id != selfId) {
                    results.putIfAbsent(book.id, book.toSManga(apiUrl, showSource, sources, cleanTitle))
                }
            }
        }

        // 2) Similar via genre/tag search, sorted by total views.
        val genreIds = memoStringList(manga, "genreIds")
        val tagIds = memoStringList(manga, "tagIds")
        if (genreIds.isNotEmpty() || tagIds.isNotEmpty()) {
            runCatching {
                val body = buildJsonObject {
                    if (genreIds.isNotEmpty()) {
                        putJsonObject("genres") {
                            putJsonArray("values") { genreIds.forEach { add(it) } }
                            put("match_all", false)
                        }
                    }
                    if (tagIds.isNotEmpty()) {
                        putJsonObject("tags") {
                            putJsonArray("values") { tagIds.forEach { add(it) } }
                            put("match_all", false)
                        }
                    }

                    val sourceTypes = if (sourceDisplayMode == "official") {
                        listOf("Official")
                    } else {
                        listOf("Official", "Unofficial", "Mixed")
                    }
                    putJsonArray("source_type") { sourceTypes.forEach { add(it) } }
                    putJsonArray("content_lang") { kaganeLangs.forEach { add(it) } }
                }.toJsonRequestBody()

                val url = "$apiUrl/search/series".toHttpUrl().newBuilder()
                    .addQueryParameter("page", "0")
                    .addQueryParameter("size", "35")
                    .addQueryParameter("sort", "total_views,desc")
                    .build()

                client.post(url, body)
                    .parseAs<SearchDto>()
                    .content
                    .forEach { book ->
                        if (book.id != selfId && book.id !in siblings) {
                            results.putIfAbsent(book.id, book.toSManga(apiUrl, showSource, sources, cleanTitle))
                        }
                    }
            }
        }

        return results.values.toList().take(25)
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.contains(";")) {
            error("Outdated chapter URL. Please refresh the chapter list")
        }

        val chapterId = "$mirror${chapter.url}".toHttpUrl().pathSegments.last()
        val challengeResp = getChallengeResponse(chapterId)

        accessToken = challengeResp.accessToken
        val cacheUrl = challengeResp.cacheUrl

        val pageList = challengeResp.manifest?.pages ?: emptyList()

        return pageList.map { page ->
            val pageUrl = "$cacheUrl/api/v2/books/page".toHttpUrl().newBuilder().apply {
                if (dataSaver) {
                    addPathSegment("datasaver")
                }
                addPathSegment(chapterId)
                addPathSegment("${page.pageUuid}.${page.ext ?: "jxl"}")
                addQueryParameter("token", accessToken)
            }.build().toString()

            Page(page.pageNumber, url = pageUrl, imageUrl = pageUrl)
        }
    }

    private var accessToken: String = ""
    private var integrityToken: String = ""
    private var integrityExp = System.currentTimeMillis()

    /**
     * Upstream-verbatim keiyoushi behaviour, including the site-root
     * "priming" GET. That GET looks redundant but is load-bearing for
     * Cloudflare: it is a plain GET to kagane.to, so when the clearance
     * cookie is missing the app's CloudflareInterceptor can solve the
     * challenge there (a WebView can only navigate with GET). Without it
     * the FIRST thing to hit Cloudflare is the POST below / the POST to
     * /books/{id} — and a challenged POST cannot be solved by the app's
     * WebView flow, so users saw "Failed to bypass Cloudflare". The v15
     * persistent token cache skipped this priming whenever a stale token
     * was still cached, which is exactly why v15 failed where upstream
     * works.
     */
    private suspend fun getIntegrityToken(): String {
        if (integrityExp < System.currentTimeMillis()) {
            // Site-root "priming" GET (kept from upstream, host now dynamic):
            // a plain GET to the mirror root, so when the clearance cookie is
            // missing the app's CloudflareInterceptor can solve the challenge
            // there (a WebView can only navigate with GET).
            client.get("$mirror/").close()

            val res = client.post(
                "$mirror/api/integrity",
                "".toJsonRequestBody(),
            ).parseAs<IntegrityDto>()
            integrityToken = res.token
            integrityExp = res.exp * 1000
        }

        return integrityToken
    }

    private suspend fun getChallengeResponse(chapterId: String): ChallengeDto {
        val integrityToken = getIntegrityToken()

        val challengeUrl = "$apiUrl/books/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter("is_datasaver", dataSaver.toString())
            .build()

        val challengeBody = "{}".toJsonRequestBody()

        val headers = headers.newBuilder().add("x-integrity-token", integrityToken).build()

        val response = client.post(challengeUrl.toString(), headers, challengeBody)

        return response.parseAs<ChallengeDto>()
    }

    // ============================ Preferences =============================

    private val contentRating: List<String>
        get() {
            val maxRating = prefs.getStringSafe(CONTENT_RATING, CONTENT_RATING_DEFAULT)
            val index = CONTENT_RATINGS.indexOfFirst { it == maxRating }
            return CONTENT_RATINGS.slice(0..index.coerceAtLeast(0))
        }

    private val excludedGenreIds: Set<String>
        get() = prefs.getStringSetSafe(GENRES_ID_PREF, emptySet())

    private val sourceDisplayMode: String
        get() = prefs.getStringSafe(SOURCE_DISPLAY_MODE, SOURCE_DISPLAY_MODE_DEFAULT) ?: SOURCE_DISPLAY_MODE_DEFAULT

    private val cleanTitle: Boolean
        get() = prefs.getBoolean(CLEAN_TITLE, CLEAN_TITLE_DEFAULT)

    private val showEdition: Boolean
        get() = !cleanTitle && prefs.getBoolean(SHOW_EDITION, SHOW_EDITION_DEFAULT)

    private val showSource: Boolean
        get() = !cleanTitle && prefs.getBoolean(SHOW_SOURCE, SHOW_SOURCE_DEFAULT)

    private val dataSaver
        get() = prefs.getBoolean(DATA_SAVER, false)

    private val chapterTitleMode
        get() = prefs.getStringSafe(CHAPTER_TITLE_MODE, CHAPTER_TITLE_MODE_DEFAULT)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_MIRROR
            title = "Mirror"
            val mirrors = knownMirrors
            entries = mirrors.map { it.toHttpUrl().host }.toTypedArray()
            entryValues = mirrors.toTypedArray()
            summary = "All mirrors share the same content and ids (%s)"
            setDefaultValue(mirrors.first())
            setOnPreferenceChangeListener { _, newValue ->
                summary = "All mirrors share the same content and ids ($newValue)"
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_MIRROR
            title = "Custom mirror"
            summary = "Full https:// URL — overrides the mirror list when set (e.g. a brand-new mirror kagane.org lists)"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_REFRESH
            title = "Auto-refresh mirror list"
            summary = "When every known mirror is down, fetch the mirrors currently listed on kagane.org (the site's own mirror page) — new domains appear without an app update (at most once every 6h)"
            setDefaultValue(true)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = CONTENT_RATING
            title = "Content Rating"
            entries =
                CONTENT_RATINGS.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
            entryValues = CONTENT_RATINGS
            summary = "%s"
            setDefaultValue(CONTENT_RATING_DEFAULT)
        }.let(screen::addPreference)

        val genreFilter = getFilterList().firstInstanceOrNull<GenresFilter>()
        val genreMap = genreFilter?.state?.associate { it.id to it.name.replaceFirstChar { c -> c.uppercase() } } ?: emptyMap()

        MultiSelectListPreference(screen.context).apply {
            key = GENRES_ID_PREF
            title = "Exclude Genres"
            entries = genreMap.values.toTypedArray()
            entryValues = genreMap.keys.toTypedArray()
            summary = genreMap.keys
                .filter { excludedGenreIds.contains(it) }
                .map { id -> genreMap[id] }
                .joinToString()
            setDefaultValue(emptySet<String>())
            setEnabled(genreMap.isNotEmpty())
            setOnPreferenceChangeListener { _, values ->
                @Suppress("UNCHECKED_CAST")
                val selected = values as Set<String>
                this.summary = selected.mapNotNull { genreMap[it] }.joinToString()
                true
            }
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = SOURCE_DISPLAY_MODE
            title = "Source Display Selection"
            summary = "%s"
            entries = arrayOf("Show All (Official + Scanlations)", "Official Sources Only")
            entryValues = arrayOf("all", "official")
            setDefaultValue(SOURCE_DISPLAY_MODE_DEFAULT)
        }.let(screen::addPreference)

        val showEdition = SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_EDITION
            title = "Show edition name in title"
            setDefaultValue(SHOW_EDITION_DEFAULT)
            setEnabled(!cleanTitle)
        }.also(screen::addPreference)

        val showSource = SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_SOURCE
            title = "Show source name in title"
            setDefaultValue(SHOW_SOURCE_DEFAULT)
            setEnabled(!cleanTitle)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = CLEAN_TITLE
            title = "Clean title"
            summary = "Removes extra brackets or parentheses in title (Disables others)"
            setDefaultValue(CLEAN_TITLE_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = !(newValue as Boolean)
                showEdition.setEnabled(enabled)
                showSource.setEnabled(enabled)
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DATA_SAVER
            title = "Data saver"
            setDefaultValue(false)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = CHAPTER_TITLE_MODE
            title = "Chapter title format"
            entries = CHAPTER_TITLE_MODE_NAMES
            entryValues = CHAPTER_TITLE_MODES
            summary = "How the chapter title should be displayed"
            setDefaultValue(CHAPTER_TITLE_MODE_DEFAULT)
        }.let(screen::addPreference)
    }

    // ============================= Utilities ==============================

    /**
     * Scrapes the site's own mirror-status page (kagane.org) for the mirrors
     * it currently advertises and persists any that aren't known yet. The
     * page is fully server-rendered — one `<a href="https://kagane.X/">`
     * per mirror — so plain HTML parsing suffices; kagane.org itself is
     * excluded (it's the directory, not a mirror).
     *
     * @return a human-readable result for the settings Toast.
     */
    private fun refreshMirrorsFromOrg(): String {
        val found = try {
            // Plain blocking call — this runs on a worker Thread, not in a
            // coroutine, so the suspend network helpers can't be used here.
            val request = Request.Builder()
                .url(MIRROR_LIST_URL)
                .headers(headers)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "Mirror refresh failed: HTTP ${resp.code}"
                resp.asJsoup()
                    .select("a[href^=https://kagane.]")
                    .mapNotNull { it.attr("href").takeIf { href -> href.startsWith("https://") } }
                    .map { it.trimEnd('/') }
                    .filter { it != MIRROR_LIST_URL.trimEnd('/') && it.toHttpUrlOrNull() != null }
                    .toSortedSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mirror refresh failed", e)
            return "Mirror refresh failed: ${e.message ?: e.javaClass.simpleName}"
        }

        if (found.isEmpty()) return "kagane.org listed no mirrors — is the page up?"

        val known = knownMirrors.toSet()
        val newOnes = found - known
        if (newOnes.isEmpty()) return "No new mirrors — ${found.size} known one(s) confirmed"

        prefs.edit()
            .putStringSet(PREF_DISCOVERED_MIRRORS, discoveredMirrors.toSet() + newOnes)
            .apply()
        // If the active mirror is dead, move to the first fresh one.
        if (prefs.getStringSafe(PREF_MIRROR, "") !in found) {
            prefs.edit().putString(PREF_MIRROR, newOnes.first()).apply()
        }
        return "Added ${newOnes.size} new mirror(s): ${newOnes.joinToString(", ") { it.toHttpUrl().host }}"
    }

    companion object {
        private const val TAG = "Kagane"

        /** The site's own mirror-status page (server-rendered mirror links). */
        private const val MIRROR_LIST_URL = "https://kagane.org/"

        private const val PREF_MIRROR = "kagane_mirror"
        private const val PREF_CUSTOM_MIRROR = "kagane_custom_mirror"
        private const val PREF_DISCOVERED_MIRRORS = "kagane_discovered_mirrors"
        private const val PREF_AUTO_REFRESH = "kagane_auto_refresh"
        private const val PREF_LAST_MIRROR_REFRESH = "kagane_last_mirror_refresh"

        /** Auto-refresh throttle: at most one kagane.org fetch per 6 hours. */
        private const val AUTO_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

        private const val CONTENT_RATING = "kagane_content_rating"
        private const val CONTENT_RATING_DEFAULT = "pornographic"
        internal val CONTENT_RATINGS = arrayOf(
            "safe",
            "suggestive",
            "erotica",
            "pornographic",
        )

        private const val GENRES_ID_PREF = "kagane_genre_id_exclude"

        private const val SOURCE_DISPLAY_MODE = "kagane_source_display_mode"
        private const val SOURCE_DISPLAY_MODE_DEFAULT = "all"

        private const val CLEAN_TITLE = "kagane_clean_title"
        private const val CLEAN_TITLE_DEFAULT = false

        private const val SHOW_SOURCE = "kagane_show_source"
        private const val SHOW_SOURCE_DEFAULT = false

        private const val SHOW_EDITION = "kagane_show_edition"
        private const val SHOW_EDITION_DEFAULT = false

        private const val DATA_SAVER = "kagane_data_saver"

        private const val CHAPTER_TITLE_MODE = "kagane_chapter_title_mode"
        private const val CHAPTER_TITLE_MODE_DEFAULT = "optional"
        internal val CHAPTER_TITLE_MODES = arrayOf(
            "optional",
            "always",
            "vol_local",
            "vol_chapter",
        )
        internal val CHAPTER_TITLE_MODE_NAMES = arrayOf(
            "Title only (e.g. 'Manga Title' / 'Ch.5')",
            "Ch.X + title (e.g. 'Ch.5 Manga Title')",
            "Vol.X Ch.Y (e.g. 'Vol.1 Ch.5')",
            "Vol.X Ch.Y + title (e.g. 'Vol.1 Ch.5 Manga Title')",
        )
    }

    // ============================= Filters ==============================

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        // Fault isolation: each endpoint is independent, so a single failure
        // (rate limit, transient 403) no longer discards the whole filter
        // cache. When EVERYTHING fails we still throw so KeiSource retries
        // its background fetch and keeps its "press Reset" hint instead of
        // caching an empty taxonomy for 3 days.
        val genresDeferred = async {
            runCatching {
                client.get("$apiUrl/genres/list")
                    .parseAs<List<GenreDto>>()
                    .associate { it.id to it.genreName }
            }.getOrElse {
                Log.e("Kagane", "Failed to fetch genres for filters", it)
                emptyMap()
            }
        }

        val tagsDeferred = async {
            runCatching {
                client.get("$apiUrl/tags/list")
                    .parseAs<List<TagDto>>()
                    .associate { it.tagName.lowercase() to it.id }
            }.getOrElse {
                Log.e("Kagane", "Failed to fetch tags for filters", it)
                emptyMap()
            }
        }
        val sourcesDeferred = async {
            runCatching {
                client.post(
                    "$apiUrl/sources/list",
                    buildJsonObject { put("source_types", null) }.toJsonRequestBody(),
                )
                    .parseAs<SourcesDto>().sources
            }.getOrElse {
                Log.e("Kagane", "Failed to fetch sources for filters", it)
                emptyList()
            }
        }

        val genres = genresDeferred.await()
        val tags = tagsDeferred.await()
        val sources = sourcesDeferred.await()
        if (genres.isEmpty() && tags.isEmpty() && sources.isEmpty()) {
            throw IOException("Kagane filter metadata unavailable")
        }

        MetadataDto(genres, tags, sources)
            .toJsonElement()
    }

    var sourceCache: Map<String, String>? = null

    val sources: Map<String, String>
        get() {
            if (sourceCache.isNullOrEmpty()) {
                getFilterList()
            }
            return (sourceCache ?: emptyMap()).also { sourceCache = it }
        }

    override fun getFilterList(data: JsonElement?): FilterList {
        val meta = data?.parseAs<MetadataDto>()

        val filters = mutableListOf(
            SortFilter(),
            ContentRatingFilter(contentRating.toSet()),
            FormatFilter(),
            PublicationStatusFilter(),
            Filter.Separator(),
        )

        if (meta != null) {
            sourceCache = meta.sources.associate { it.sourceId to it.title }

            val displayMode = sourceDisplayMode
            val validSources = meta.sources.filter { source ->
                when (displayMode) {
                    "official" -> source.sourceType.equals("Official", ignoreCase = true)
                    else -> true
                }
            }

            val sourceFilters = validSources
                .map { FilterData(it.sourceId, it.title) }
                .sortedBy { it.name }

            filters.addAll(
                listOf(
                    Filter.Header("Genres (tap to include → exclude)"),
                    MatchAllGenresFilter(),
                    GenresFilter(
                        meta.getGenresList(),
                        excludedGenreIds,
                    ),
                    Filter.Header("Tags (tap to include → exclude)"),
                    MatchAllTagsFilter(),
                    TagFilter(meta.tags),
                    Filter.Separator(),
                    Filter.Header("Sources"),
                    SourcesFilter(sourceFilters),
                ),
            )
        }

        return FilterList(filters)
    }
}
