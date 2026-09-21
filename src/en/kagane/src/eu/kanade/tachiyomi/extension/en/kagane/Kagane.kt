package eu.kanade.tachiyomi.extension.en.kagane

import android.util.Log
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
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.IOException

@Source
abstract class Kagane :
    KeiSource(),
    ConfigurableSource {

    private val kaganeLangs: List<String>
        get() = if (lang == "zh") listOf("zh-Hans", "zh-Hant") else listOf(lang)

    private val domain get() = baseUrl.removePrefix("https://")
    private val apiUrl get() = "https://$domain/api/v2"

    private val prefs = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = apply {
        // Extension-level Cloudflare handling, MangaFire-style: browser
        // fingerprint headers (client hints + sec-fetch-*) so requests score
        // like a browser, WebView cookie sync, and an off-screen WebView
        // solve + single retry when a challenge response slips past the
        // app's own interceptor. Happy path adds no round-trips.
        CloudflareBypass(
            protectedHosts = setOf(domain, "kstatic.to", "cdn.kagane.to"),
        ).install(this)

        addInterceptor(::refreshTokenInterceptor)

        // The old custom bypass (cookie sync + fingerprint headers +
        // site-root priming + retries) added extra round-trips in front of
        // every request — the main reason Kagane felt slow.
        rateLimit(4)
    }

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

        // Cloudflare responses (challenge pages or WAF blocks) are NOT token
        // errors — refreshing the token here would fire a pointless challenge
        // POST while the real fix (the bypass's WebView solve) handles the
        // clearance. Only Kagane's own auth errors trigger a token refresh.
        val isCloudflareResponse =
            response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true ||
                response.header("server")?.contains("cloudflare", ignoreCase = true) == true

        if (response.code in listOf(401, 403, 507) && !isCloudflareResponse) {
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
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.size < 2) return null
        val seriesId = segments[1]
        return parseMangaDetails(getMangaById(seriesId)).apply {
            this.url = seriesId
            initialized = true
        }
    }

    private suspend fun parseMangaDetails(details: DetailsDto): SManga {
        val sourceName = details.sourceId?.let { sourceId ->
            sources[sourceId]
        }
        return details.toSManga(apiUrl, sourceName, baseUrl, showEdition, showSource, cleanTitle)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val seriesId = manga.url
        val dto = getMangaById(seriesId)

        val updatedManga = parseMangaDetails(dto).apply {
            if (!dto.trackerId.isNullOrEmpty()) {
                memo = buildJsonObject {
                    put("trackerId", dto.trackerId)
                }
            }
        }
        val updatedChapters = parseChapterList(dto, seriesId)

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseChapterList(details: DetailsDto, seriesId: String): List<SChapter> {
        val useSourceChapterNumber = details.format in setOf(
            "Dark Horse Comics",
            "Flame Comics",
            "MangaDex",
            "Square Enix Manga",
        )

        return details.seriesBooks.map { book ->
            book.toSChapter(seriesId, useSourceChapterNumber, chapterTitleMode)
        }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =========================== Related Manga ============================
    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val trackerId = manga.memo["trackerId"]?.string ?: return emptyList()

        val series = client.get("$apiUrl/trackers/$trackerId/series")
            .parseAs<TrackerDto>()
            .bookSeries
        return series
            .map { it.toSManga(apiUrl, showSource, sources, cleanTitle) }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.contains(";")) {
            error("Outdated chapter URL. Please refresh the chapter list")
        }

        val chapterId = "$baseUrl${chapter.url}".toHttpUrl().pathSegments.last()
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
     * Integrity token with a persistent cache. The token used to live in
     * memory only, so EVERY app start paid a site-root GET plus an
     * /api/integrity POST before the first details/chapter/pages call could
     * even begin — the main reason entering Kagane felt slow. The token is
     * bound to the device (IP/UA), not to a session, so caching it in the
     * source prefs is safe.
     */
    private suspend fun getIntegrityToken(): String {
        val now = System.currentTimeMillis()
        if (integrityExp > now + INTEGRITY_SAFETY_WINDOW_MS) return integrityToken

        // Memory cache miss — try the persistent cache (survives restarts)
        val cachedToken = prefs.getStringSafe(PREF_INTEGRITY_TOKEN, null)
        val cachedExp = prefs.getStringSafe(PREF_INTEGRITY_EXP, null)?.toLongOrNull() ?: 0L
        if (!cachedToken.isNullOrBlank() && cachedExp > now + INTEGRITY_SAFETY_WINDOW_MS) {
            integrityToken = cachedToken
            integrityExp = cachedExp
            return cachedToken
        }

        // (No site-root "priming" GET here anymore — the app's Cloudflare
        // interceptor solves a challenge on this POST itself, and skipping
        // the extra round-trip makes the first mint noticeably faster.)

        val res = client.post(
            "$baseUrl/api/integrity",
            "".toJsonRequestBody(),
        ).parseAs<IntegrityDto>()
        integrityToken = res.token
        integrityExp = res.exp * 1000
        prefs.edit()
            .putString(PREF_INTEGRITY_TOKEN, res.token)
            .putString(PREF_INTEGRITY_EXP, (res.exp * 1000).toString())
            .apply()
        return integrityToken
    }

    /** Drops a possibly-stale cached token so the next attempt re-mints it. */
    private fun clearIntegrityToken() {
        integrityToken = ""
        integrityExp = System.currentTimeMillis()
        prefs.edit()
            .remove(PREF_INTEGRITY_TOKEN)
            .remove(PREF_INTEGRITY_EXP)
            .apply()
    }

    private suspend fun getChallengeResponse(chapterId: String): ChallengeDto {
        val integrityToken = getIntegrityToken()

        val challengeUrl = "$apiUrl/books/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter("is_datasaver", dataSaver.toString())
            .build()

        val challengeBody = "{}".toJsonRequestBody()

        val headers = headers.newBuilder().add("x-integrity-token", integrityToken).build()

        val response = client.post(challengeUrl.toString(), headers, challengeBody)

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            // 401/403 usually mean the cached integrity token went stale —
            // drop it so the next attempt re-mints instead of failing forever.
            if (code == 401 || code == 403) clearIntegrityToken()
            throw IOException("Kagane returned HTTP $code while requesting the book")
        }

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

    companion object {
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

        private const val PREF_INTEGRITY_TOKEN = "kagane_integrity_token"
        private const val PREF_INTEGRITY_EXP = "kagane_integrity_exp"
        private const val INTEGRITY_SAFETY_WINDOW_MS = 60_000L

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
