package eu.kanade.tachiyomi.extension.ar.procomic

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * ProComic (procomic.pro/ar) — Arabic Next.js site hosting manga, manhwa,
 * manhua and novels.
 *
 * Data paths:
 *   - browse/search/filter: GET /api/public/series/search (status=approved,
 *     limit, page, search, sort, type, year, includeGenres). The endpoint is
 *     "trusted" for requests that carry a non-empty `search` term and
 *     Turnstile-gated otherwise, so the source always sends one (`e` covers
 *     the overwhelmingly English-titled catalog for the popular tab).
 *     Novel-type rows are dropped everywhere: novel chapters are text and
 *     their pages render client-side, so they are unusable in a reader.
 *   - latest updates: the SSR flight payload of /ar/updates (18 groups with
 *     real chapter dates), with the paginated search API as fallback.
 *   - details: the SSR flight payload of the series page (`{"series":{...}}`
 *     with author/artist/year/descriptions.ar inside `metadata`). Series
 *     whose page renders client-side (no flight payload at all — the old
 *     chapter-list killer) fall back to the search API, matched by id.
 *   - chapters: the reader page of the series' newest chapter embeds the
 *     FULL chapter table of contents (ids, numbers, teams) — the series
 *     page only carries the latest 30 (with dates), which are merged in.
 *     Series that render client-side get their full TOC from the public
 *     chapters API (/api/public/chapters?contentId=...) instead.
 *   - pages: reader SSR exposes a few direct CDN images plus a
 *     `deferredMedia` JWT; the remaining pages are served by a loopback
 *     stitch server (ProtectedPageServer) through /chapter-deferred-media
 *     + /chapter-map-proxy-plan, because host apps cannot be relied on to
 *     run source interceptors on image requests. The direct CDN images go
 *     through the same server's /.image-proxy route, because the site's WAF
 *     403s the app User-Agents that host apps stamp onto their own image
 *     requests; every page image is a loopback URL with the browser UA
 *     applied inside the extension process.
 *
 * Details follow the Comix description layout: the star score and a bold
 * info line rendered once in the configured position, the synopsis, and —
 * behind the same options the Comix source ships — tag and blocked-genre
 * filtering.
 */
@Source
abstract class ProComic :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    private val json = Json { ignoreUnknownKeys = true }

    private val mapCache = MapCache()

    // lazy: the server reads baseUrl, which the @Source-generated subclass
    // supplies via computed getters (safe at any phase) — lazy keeps the
    // construction order a non-issue either way. internal: the JVM harness
    // drives the verification probes on it; harmless in the shipped build.
    // The flight-rebuild lambda is the server's final plan recovery: it
    // re-fetches the chapter's reader page for a fresh set of map tokens
    // (the site's session-key endpoint now 403s use_proxy_plan, so a stale
    // token can no longer be salvaged any other way).
    internal val protectedPageServer by lazy {
        ProtectedPageServer(
            baseUrl = baseUrl,
            mapCache = mapCache,
            // The source's own client as the session fallback tier: when
            // the stitch server's bare inner client cannot get a plan or a
            // piece through (region-dependent edge behavior), the request
            // is retried with the session the reader page established —
            // cookies, CF clearance, the forced browser UA.
            sessionClient = client,
        ) { readerUrl, chapterId ->
            runBlocking { rebuildFlight(readerUrl, chapterId) }
        }
    }

    /** Re-fetches the reader page and extracts a fresh deferred-media map
     * set for [chapterId]; null when the page or the maps are unavailable
     * (the caller surfaces that as the plan failure it already has). */
    private suspend fun rebuildFlight(readerUrl: String, chapterId: String): RebuiltFlight? {
        return try {
            val response = client.newCall(GET(readerUrl, headers)).execute()
            response.use {
                if (!it.isSuccessful) return null
                val flight = RscFlight.extract(it.body.string())
                val media = parseDeferredMedia(flight, chapterId.toInt()) ?: return null
                if (media.maps.isEmpty()) return null
                RebuiltFlight(
                    cdnPath = media.cdnPath,
                    maps = media.maps.map { map -> RebuiltMap(map.token, map.method) },
                )
            }
        } catch (t: Exception) {
            Log.w(TAG, "flight rebuild from $readerUrl failed: ${t.message}")
            null
        }
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        addInterceptor(::userAgentOverrideInterceptor)
        addInterceptor(::originSanitizerInterceptor)
        addInterceptor(CloudflareSolverInterceptor(setOf(baseUrl.toHttpUrl().host)))
        rateLimit(3)
    }

    /**
     * The site's WAF answers 403 to non-browser User-Agents — including the
     * app UA some hosts stamp onto every request (Kotatsu's Mihon-compat
     * layer among them, verified live: "Kotatsu/1.0" gets 403 on the reader
     * page AND on the image CDN while browser UAs pass). One forced browser
     * UA across the whole chain also keeps the deferred-media JWT's uah
     * (user-agent hash) claim consistent, since the loopback server's inner
     * client uses the exact same string (ProtectedPageServer.BROWSER_UA).
     */
    private fun userAgentOverrideInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") == ProtectedPageServer.BROWSER_UA) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("User-Agent", ProtectedPageServer.BROWSER_UA).build())
    }

    /**
     * KeiSource stamps "Origin: <baseUrl>" onto every request, but browsers
     * never send Origin on document GETs or same-origin fetches — carrying it
     * everywhere is a bot signal (same finding as the Kagane source).
     */
    private fun originSanitizerInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "GET" && request.header("Origin") != null) {
            val stripped = request.newBuilder().removeHeader("Origin").build()
            return chain.proceed(stripped)
        }
        return chain.proceed(request)
    }

    // ============================ Formatting =============================

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = true
        }
    }

    private fun parseDate(raw: String?): Long = dateFormat.tryParse(raw?.substringBefore('.')?.take(19))

    private fun seriesPageUrl(mangaUrl: String): String = "$baseUrl/ar/$mangaUrl"

    private fun chapterPageUrl(ref: ChapterRef): String = "$baseUrl/ar/chapter/${ref.seriesSlug}-${ref.chapterNumber}-${ref.chapterId}"

    // ========================= Popular & Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchPage(query = CATALOG_QUERY, sort = "popular", page = page)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page == 1) {
            val updates = runCatching { fetchUpdatesFeed() }
                .onFailure { Log.w(TAG, "updates feed failed, falling back to search", it) }
                .getOrNull()
            if (!updates.isNullOrEmpty()) {
                return MangasPage(updates, false)
            }
        }
        return getSearchPage(query = CATALOG_QUERY, sort = "latest", page = page)
    }

    /**
     * /ar/updates SSR: an array of rows
     * `{mangaId, mangaSlug, mangaTitle, coverImage, type, status, chapters:[...]}`.
     * Parsed straight from the flight stream; any parse hiccup throws and the
     * caller falls back to the search API.
     */
    private suspend fun fetchUpdatesFeed(): List<SManga> {
        val response = client.newCall(GET("$baseUrl/ar/updates", headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} on updates feed")
            val flight = RscFlight.extract(it.body.string())
            val rows = mutableListOf<UpdateRowDto>()
            var index = 0
            while (true) {
                index = flight.indexOf("\"mangaId\":", index)
                if (index < 0) break
                val objStart = flight.lastIndexOf('{', index)
                if (objStart < 0) break
                val obj = RscFlight.balancedObject(flight, objStart) ?: run {
                    index += 10
                    continue
                }
                runCatching { json.decodeFromString(UpdateRowDto.serializer(), obj) }
                    .getOrNull()
                    ?.takeIf { row -> row.chapters.isNotEmpty() && !row.type.equals("novel", true) }
                    ?.let(rows::add)
                index = objStart + obj.length
            }
            if (rows.isEmpty()) throw IOException("updates feed parse produced no rows")
            return rows.map { row -> row.toSManga() }
        }
    }

    private fun UpdateRowDto.toSManga() = SManga.create().apply {
        url = "$mangaSlug-$mangaId"
        title = mangaTitle
        thumbnail_url = coverImage?.takeIf { it.startsWith("http") }
        genre = type?.let { it.replaceFirstChar { c -> c.uppercase() } }
        status = this@toSManga.status.toProComicStatus()
        initialized = true
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "latest"
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selected.orEmpty()
        val genres = filters.firstInstanceOrNull<GenreFilter>()
            ?.included
            .orEmpty()
            .joinToString(",")
        val year = filters.firstInstanceOrNull<YearFilter>()?.state?.trim().orEmpty()

        // Blank queries browse the catalog through the trusted letter query;
        // real queries search verbatim.
        val search = query.trim().ifBlank { CATALOG_QUERY }
        return getSearchPage(search, sort, page, type, genres, year)
    }

    private suspend fun getSearchPage(
        query: String,
        sort: String,
        page: Int,
        type: String = "",
        genres: String = "",
        year: String = "",
    ): MangasPage {
        val data = searchSeries(query, sort, page, type, genres, year)
        // Novel rows are dropped: text chapters, client-rendered pages —
        // nothing a reader can show.
        val rows = data.data.filterNot { it.type.equals("novel", true) }
        val hasNext = when (val pages = data.meta?.pages) {
            null -> rows.size >= PAGE_LIMIT
            else -> page < pages
        }
        return MangasPage(
            rows.map { series -> series.toSManga() },
            hasNext && rows.isNotEmpty(),
        )
    }

    private suspend fun searchSeries(
        query: String,
        sort: String,
        page: Int,
        type: String = "",
        genres: String = "",
        year: String = "",
    ): SearchResponseDto {
        val url = "$baseUrl/api/public/series/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("status", "approved")
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("search", query)
            addQueryParameter("sort", sort)
            if (type.isNotEmpty()) addQueryParameter("type", type)
            if (year.isNotEmpty()) addQueryParameter("year", year)
            if (genres.isNotEmpty()) addQueryParameter("includeGenres", genres)
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/ar/series")
            .build()

        val response = client.newCall(request).execute()
        response.use {
            if (it.code == 403) {
                throw IOException("ProComic: the search endpoint challenged this client (Turnstile)")
            }
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while searching ProComic")
            return json.decodeFromString(SearchResponseDto.serializer(), it.body.string())
        }
    }

    private fun SeriesSearchDto.toSManga() = SManga.create().apply {
        url = "$slug-$id"
        title = this@toSManga.title
        thumbnail_url = this@toSManga.thumbnail?.takeIf { it.startsWith("http") }
        genre = buildList {
            this@toSManga.type?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
            addAll(this@toSManga.metadata?.genres.orEmpty())
        }.distinct().joinToString(", ")
        status = progress.toProComicStatus()
        initialized = true
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        // The browse page's flight payload embeds the canonical genre list
        // as `initialGenres:[{id,en,ar}]`.
        val response = client.newCall(GET("$baseUrl/ar/series", headers)).execute()
        response.use {
            if (!it.isSuccessful) return genresToJsonElement(GENRE_FALLBACK)
            val flight = RscFlight.extract(it.body.string())
            val key = "\"initialGenres\":"
            val arrayStart = flight.indexOf(key)
                .takeIf { idx -> idx >= 0 }
                ?.let { idx -> flight.indexOf('[', idx + key.length) }
                ?: return genresToJsonElement(GENRE_FALLBACK)
            val arrayJson = RscFlight.balancedArray(flight, arrayStart)
                ?: return genresToJsonElement(GENRE_FALLBACK)
            val entries = json.parseToJsonElement(arrayJson).jsonArray
                .mapNotNull { element ->
                    element.jsonObject["en"]?.jsonPrimitive?.content
                }
            return genresToJsonElement(entries.ifEmpty { GENRE_FALLBACK })
        }
    }

    private fun parseGenreFilter(data: JsonElement?): List<String>? = data?.let { element ->
        runCatching {
            element.jsonArray.map { it.jsonPrimitive.content }
        }.getOrNull()
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilterListInternal(parseGenreFilter(data))

    private fun genresToJsonElement(values: List<String>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/ar/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in setOf(baseUrl.toHttpUrl().host, "www.procomic.pro")) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "ar") return null

        val mangaUrl = if (segments.size >= 3 && segments[1] == "chapter") {
            // /ar/chapter/{seriesSlug}-{number}-{id}
            resolveSeriesUrlFromChapterPage(segments[2]) ?: return null
        } else {
            segments[1]
        }

        val tmpManga = SManga.create().apply { this.url = mangaUrl }
        return fetchMangaUpdate(tmpManga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    /** The reader page flight carries `seriesUrl:"/ar/{slug}-{id}"`. */
    private suspend fun resolveSeriesUrlFromChapterPage(chapterSlug: String): String? {
        val response = client.newCall(GET("$baseUrl/ar/chapter/$chapterSlug", headers)).execute()
        response.use {
            if (!it.isSuccessful) return null
            val flight = RscFlight.extract(it.body.string())
            val key = "\"seriesUrl\":"
            val index = flight.indexOf(key)
            if (index < 0) return null
            val start = flight.indexOf('"', index + key.length)
            val end = flight.indexOf('"', start + 1)
            if (start < 0 || end < 0) return null
            return flight.substring(start + 1, end).substringAfterLast('/')
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (fetchDetails && fetchChapters) {
            // One series-page fetch feeds both details and the chapter
            // list — fetching them independently would issue the same
            // GET twice, concurrently.
            val pageData = fetchSeriesPageWithFallback(manga)
            return SMangaUpdate(buildManga(manga, pageData), buildChapters(manga, pageData))
        }
        if (fetchChapters) {
            return SMangaUpdate(manga, buildChapters(manga, fetchSeriesPageWithFallback(manga)))
        }
        if (fetchDetails) {
            return SMangaUpdate(fetchSeriesDetails(manga), chapters)
        }
        return SMangaUpdate(manga, chapters)
    }

    private suspend fun fetchSeriesDetails(manga: SManga): SManga = buildManga(manga, fetchSeriesPageWithFallback(manga))

    /**
     * Some series render client-side: their SSR flight carries no
     * `{"series":{...}}` payload at all (novels are the usual case, and
     * they are filtered out of every list for exactly this reason). When
     * the series page comes back without a payload, fall back to the
     * trusted search API and match the row by id.
     */
    private suspend fun fetchSeriesPageWithFallback(manga: SManga): SeriesPageData = runCatching { fetchSeriesPage(manga.url) }
        .recoverCatching { error ->
            Log.w(TAG, "series page failed (${error.message}); falling back to the search API", error)
            fetchSeriesPageViaSearch(manga) ?: throw error
        }
        .getOrThrow()

    private suspend fun fetchSeriesPageViaSearch(manga: SManga): SeriesPageData? {
        val id = manga.url.substringAfterLast('-').toIntOrNull() ?: return null
        val slug = manga.url.substringBeforeLast('-')
        for (term in listOf(slug, slug.replace('-', ' '))) {
            val row = runCatching { searchSeries(term, sort = "latest", page = 1) }
                .getOrNull()
                ?.data
                ?.firstOrNull { it.id == id }
                ?: continue
            return SeriesPageData(
                series = SeriesDetailsDto(
                    id = row.id,
                    title = row.title,
                    slug = row.slug,
                    type = row.type,
                    description = row.description,
                    thumbnail = row.thumbnail,
                    metadata = row.metadata,
                ),
                recentChapters = emptyList(),
                statusText = row.progress,
            )
        }
        return null
    }

    private fun buildManga(manga: SManga, pageData: SeriesPageData): SManga {
        val series = pageData.series
        val meta = series.metadata
        val showTags = preferences.getBoolean(PREF_SHOW_TAGS, true)

        return SManga.create().apply {
            url = manga.url
            title = series.title
            thumbnail_url = series.thumbnail?.takeIf { it.startsWith("http") }
            author = meta?.authorText()
            artist = meta?.artistText()
            genre = buildList {
                series.type?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
                addAll(meta?.genres.orEmpty())
                if (showTags) {
                    meta?.tags.orEmpty().forEach { add(it.trim()) }
                }
            }.distinct()
                .filterNot { it.lowercase() in blockedGenres() }
                .joinToString(", ")
                .ifBlank { null }
            // The series SSR carries no publish status; the search fallback
            // carries the site's own progress field when available.
            status = pageData.statusText.toProComicStatus()
            description = buildDescription(series)
            initialized = true
        }
    }

    private fun String?.toProComicStatus(): Int = when (this) {
        "مستمر" -> SManga.ONGOING
        "مكتمل" -> SManga.COMPLETED
        "متوقف" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun blockedGenres(): Set<String> = preferences.getString(PREF_BLOCKED_GENRES, "")
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

    /**
     * Comix-style description block (score rendered exactly once, in the
     * configured position):
     *   ★★★★☆ 4.99
     *   **Year:** 2023 · **Chapters:** 307 · **Favorites:** 493 · **1614 ratings**
     *   <Arabic synopsis>
     *   ★★★★☆ 4.99
     *   **Year:** 2023 · **Chapters:** 307 · ...
     */
    private fun buildDescription(series: SeriesDetailsDto): String {
        val showExtraInfo = preferences.getBoolean(PREF_SHOW_EXTRA_INFO, true)
        val scorePosition = preferences.getString(PREF_SCORE_POSITION, "end") ?: "end"

        val rating = series.average_rating?.takeIf { it > 0.0 }
        val stars = rating?.let {
            val full = it.roundToInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " " +
                String.format(Locale.US, "%.2f", it)
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                series.metadata?.year?.takeIf { it.isNotBlank() }?.let { append("**Year:** $it") }
                series.chaptersCount?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**Chapters:** $it")
                }
                series.favorites_count?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**Favorites:** $it")
                }
                series.ratings_count?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**$it ratings**")
                }
            }.ifBlank { null }
        } else {
            null
        }

        return buildString {
            if (scorePosition == "top" && stars != null) {
                append(stars)
                append("\n")
                if (infoLine != null) {
                    append(infoLine)
                    append("\n\n")
                } else {
                    append("\n\n")
                }
            }

            val synopsis = series.metadata?.descriptions?.get("ar")
                ?: series.metadata?.descriptions?.values?.firstOrNull()
                ?: series.description
            synopsis?.let {
                append(
                    it.replace("\r\n", "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim(),
                )
            }

            if (scorePosition == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
                if (infoLine != null) {
                    append("\n")
                    append(infoLine)
                }
            }

            if (scorePosition == "none" && infoLine != null) {
                if (isNotEmpty()) append("\n\n")
                append(infoLine)
            }
        }.trim()
    }

    // ============================= Chapters ==============================

    /** Parsed from the series page flight payload (or the search fallback). */
    private class SeriesPageData(
        val series: SeriesDetailsDto,
        val recentChapters: List<ChapterRowDto>,
        val statusText: String? = null,
    )

    private suspend fun fetchSeriesPage(mangaUrl: String): SeriesPageData {
        val response = client.newCall(GET(seriesPageUrl(mangaUrl), headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} on series page")
            val flight = RscFlight.extract(it.body.string())

            // `{"series":{...}}` — capture the INNER object for the DTO.
            val wrapperKey = "{\"series\":{"
            val wrapperStart = flight.indexOf(wrapperKey)
            if (wrapperStart < 0) throw IOException("series payload missing")
            val innerStart = wrapperStart + wrapperKey.length - 1
            val seriesJson = RscFlight.balancedObject(flight, innerStart)
                ?: throw IOException("series payload unbalanced")
            val series = json.decodeFromString(SeriesDetailsDto.serializer(), seriesJson)

            return SeriesPageData(series, parseChapterRows(flight))
        }
    }

    private fun parseChapterRows(flight: String): List<ChapterRowDto> {
        val rows = mutableListOf<ChapterRowDto>()
        var index = 0
        while (true) {
            index = flight.indexOf("\"chapter_number\":", index)
            if (index < 0) break
            val objStart = flight.lastIndexOf('{', index)
            if (objStart < 0) break
            val obj = RscFlight.balancedObject(flight, objStart) ?: run {
                index += 17
                continue
            }
            runCatching { json.decodeFromString(ChapterRowDto.serializer(), obj) }
                .getOrNull()
                ?.let(rows::add)
            index = objStart + obj.length
        }
        return rows
    }

    private suspend fun buildChapters(manga: SManga, pageData: SeriesPageData): List<SChapter> {
        val recent = pageData.recentChapters
        val newestChapter = recent.maxByOrNull { it.id }
        val seriesSlug = manga.url.substringBeforeLast('-')
        val seriesId = manga.url.substringAfterLast('-').toIntOrNull()

        // Full TOC lives on the newest chapter's reader page (one request,
        // SSR series only). Client-rendered series have no recent rows to
        // anchor that request, and their reader route is client-side too —
        // for those the public chapters API is the full-TOC source.
        var toc: List<TocChapterDto> = newestChapter?.let { chapter ->
            val number = chapter.chapter_number ?: ""
            runCatching {
                fetchReaderToc(chapterPageUrl(ChapterRef(seriesSlug, number, chapter.id)))
            }
                .onFailure { Log.w(TAG, "reader TOC failed; trying the chapters API", it) }
                .getOrNull()
        }.orEmpty()

        if (toc.isEmpty() && seriesId != null) {
            toc = runCatching { fetchChaptersApi(seriesId) }
                .onFailure { Log.w(TAG, "chapters API failed (${it.message}); using the recent list", it) }
                .getOrNull()
                .orEmpty()
        }

        val entries = toc.ifEmpty {
            recent.map { row ->
                TocChapterDto(
                    id = row.id,
                    chapterNumber = row.chapter_number,
                    language = row.language,
                    group = null,
                    translator = row.translator,
                    publishedAt = row.published_at,
                )
            }
        }

        val rowsById = recent.associateBy { it.id }
        val chapters = entries
            .sortedByDescending { it.chapterNumber?.toFloatOrNull() ?: 0f }
            .map { entry ->
                val row = rowsById[entry.id]
                SChapter.create().apply {
                    val number = entry.chapterNumber ?: row?.chapter_number ?: "0"
                    url = ChapterRef(
                        seriesSlug = seriesSlug,
                        chapterNumber = number,
                        chapterId = entry.id,
                    ).toJsonString()
                    name = buildString {
                        append("الفصل ").append(number)
                        val title = row?.title?.trim()
                        if (!title.isNullOrEmpty() && title != number) {
                            append(": ").append(title)
                        }
                        if (row?.lockedByCoins == true || row?.lockedForever == true) {
                            append(" 🔒")
                        }
                    }
                    chapter_number = number.toFloatOrNull() ?: 0f
                    scanlator = entry.group?.takeIf { it.isNotBlank() && it != "null" }
                        ?: entry.translator?.takeIf { it.isNotBlank() && it != "null" }
                        ?: row?.translator?.takeIf { it.isNotBlank() }
                    date_upload = parseDate(row?.published_at ?: entry.publishedAt)
                }
            }
        return combineDuplicateChapters(chapters)
    }

    /**
     * Optional "combine duplicate chapters" (same option the MangaDot source
     * ships): the site re-uploads the same chapter under several teams, so
     * the TOC can carry multiple entries per chapter number. When enabled,
     * one entry per non-zero chapter number survives — the first team in
     * the user's priority list wins, otherwise the first (newest) upload.
     * The zero-width scanlator filler matches MangaDot's behavior of keeping
     * the scanlator row uniform across entries.
     */
    private fun combineDuplicateChapters(chapters: List<SChapter>): List<SChapter> {
        val hasScanlator = chapters.any { it.scanlator != null }
        val result = if (preferences.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)) {
            // UNICODE_CHARACTER_CLASS: team names here are usually Arabic, and
            // \b is ASCII-only without that flag (MangaDot's priority list is
            // all-Latin, so it never hits the difference).
            val priorityPatterns = preferences.getString(PREF_PREFERRED_SCANLATORS, "")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.map {
                    Pattern.compile(
                        "\\b${Regex.escape(it)}\\b",
                        Pattern.UNICODE_CHARACTER_CLASS or Pattern.CASE_INSENSITIVE,
                    )
                }
                .orEmpty()

            mutableListOf<SChapter>().apply {
                for ((_, group) in chapters.groupBy { it.chapter_number }) {
                    if (group.first().chapter_number <= 0f || group.size == 1) {
                        addAll(group)
                        continue
                    }
                    val selected = priorityPatterns.firstNotNullOfOrNull { pattern ->
                        group.firstOrNull { chapter ->
                            chapter.scanlator?.let { name -> pattern.matcher(name).find() } == true
                        }
                    } ?: group.first()
                    add(selected)
                }
            }
        } else {
            chapters
        }

        if (hasScanlator) {
            result.forEach { it.scanlator = it.scanlator ?: "\u200B" }
        }
        return result
    }

    /**
     * The reader page flight embeds `chapters:[{id, chapterNumber, group,
     * translator, metadata:{team}}]` right after the "chapterLabel" key.
     */
    private suspend fun fetchReaderToc(readerUrl: String): List<TocChapterDto> {
        val response = client.newCall(GET(readerUrl, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} on reader TOC")
            val flight = RscFlight.extract(it.body.string())
            val markerIndex = flight.indexOf("\"chapterLabel\":")
            val arrayIndex = flight.indexOf("\"chapters\":[", if (markerIndex >= 0) markerIndex else 0)
            if (arrayIndex < 0) throw IOException("TOC array missing")
            val arrayStart = arrayIndex + "\"chapters\":".length
            val arrayJson = RscFlight.balancedArray(flight, arrayStart)
                ?: throw IOException("TOC array unbalanced")
            return json.parseToJsonElement(arrayJson).jsonArray
                .mapNotNull { element ->
                    runCatching {
                        json.decodeFromString(TocChapterDto.serializer(), element.jsonObject.toString())
                    }.getOrNull()
                }
        }
    }

    /**
     * GET /api/public/chapters?contentId={seriesId}&page={n}&limit=50 —
     * `{chapters:[...], hasMore}` — newest first, dates included. The server
     * caps `limit` at 50; page through until `hasMore` flips false.
     */
    private suspend fun fetchChaptersApi(seriesId: Int): List<TocChapterDto> {
        val entries = mutableListOf<TocChapterDto>()
        var page = 1
        while (page <= 30) { // 30 × 50 = 1500 chapters — far past any series
            val url = "$baseUrl/api/public/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("contentId", seriesId.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", "50")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Referer", "$baseUrl/ar/series")
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code} on chapters API")
                val data = json.decodeFromString(ApiChaptersResponseDto.serializer(), it.body.string())
                entries += data.chapters.map { row ->
                    TocChapterDto(
                        id = row.id,
                        chapterNumber = row.chapter_number,
                        language = row.language,
                        group = null,
                        translator = row.translator,
                        publishedAt = row.published_at,
                    )
                }
                if (data.hasMore != true) return entries
                page++
            }
        }
        return entries
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String {
        val ref = json.decodeFromString(ChapterRef.serializer(), chapter.url)
        return chapterPageUrl(ref)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = json.decodeFromString(ChapterRef.serializer(), chapter.url)
        val readerUrl = chapterPageUrl(ref)
        val response = client.newCall(GET(readerUrl, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} on reader page")
            val flight = RscFlight.extract(it.body.string())
            val images = parseReaderImages(flight)
            val media = parseDeferredMedia(flight, ref.chapterId)

            val pages = mutableListOf<Page>()
            images.forEach { imageUrl ->
                pages.add(Page(index = pages.size, url = readerUrl, imageUrl = protectedPageServer.imageProxyUrl(imageUrl)))
            }

            if (media != null) {
                media.images.forEach { imageUrl ->
                    pages.add(Page(index = pages.size, url = readerUrl, imageUrl = protectedPageServer.imageProxyUrl(imageUrl)))
                }
                if (media.maps.isNotEmpty()) {
                    val chapterIdStr = ref.chapterId.toString()
                    mapCache.putAll(
                        chapterIdStr,
                        media.maps.mapIndexed { idx, map ->
                            MapCache.Entry(
                                token = map.token,
                                method = map.method,
                                cdnPath = media.cdnPath,
                                pageIndex = idx,
                                readerUrl = readerUrl,
                            )
                        },
                    )
                    media.maps.forEachIndexed { idx, _ ->
                        pages.add(
                            Page(
                                index = pages.size,
                                url = readerUrl,
                                // Plain loopback URL: works in every host app
                                // because it needs no source interceptors.
                                imageUrl = protectedPageServer.pageUrl(chapterIdStr, idx),
                            ),
                        )
                    }
                    // v90: pre-assemble every protected page in the background
                    // so the reader's own fetches land on finished JPEGs — the
                    // on-device "first pages load, the rest time out" failure
                    // was the host's preload stampede racing full pipelines.
                    protectedPageServer.preloadChapter(chapterIdStr)
                }
            }

            if (pages.isEmpty()) {
                throw IOException(
                    "ProComic: no pages found — the chapter is locked on the site (unlock with coins or the shortlink there first)",
                )
            }
            return pages
        }
    }

    /** Direct page images embedded in the reader flight (public pages).
     *
     * The 2026-10 reader rework went client-side and moved the direct CDN
     * files from `…/chapters/…` paths to `cdn{1,2,3,4}.procomic.pro/{content}
     * /{chapter}/{file}.avif` — the old `/chapters/` substring filter
     * silently dropped every image and the reader surfaced "no pages found".
     * The payload now carries its own page list as `"images":[…]` arrays
     * (the exact props the site's reader component consumes), so those are
     * parsed directly, keeping any https URL on a site-owned host. */
    private fun parseReaderImages(flight: String): List<String> {
        val urls = mutableListOf<String>()
        val key = "\"images\":["
        var index = 0
        while (true) {
            index = flight.indexOf(key, index)
            if (index < 0) break
            val arrayStart = index + key.length
            val arrayJson = RscFlight.balancedArray(flight, arrayStart) ?: run {
                index += key.length
                continue
            }
            runCatching {
                json.parseToJsonElement(arrayJson).jsonArray.forEach { element ->
                    val imageUrl = (element as? JsonPrimitive)?.content ?: return@forEach
                    if (isReaderImageUrl(imageUrl)) urls.add(imageUrl)
                }
            }
            index = arrayStart + arrayJson.length
        }
        return urls.distinct()
    }

    private fun isReaderImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (
            httpUrl.host == "procomic.pro" ||
                httpUrl.host == "www.procomic.pro" ||
                httpUrl.host.endsWith(".procomic.pro")
            )
    }.getOrDefault(false)

    /**
     * Reads the storage generation ("cdnPath" / legacy "cdn_path") that the
     * deferred map tokens are bound to, searching backwards from [searchLimit]
     * so hydration noise after the chapter payload cannot win. Public so the
     * JVM harness exercises the exact extraction the source ships.
     */
    fun extractCdnPath(flight: String, searchLimit: Int = flight.length): String? {
        val newIndex = flight.lastIndexOf("\"cdnPath\":", searchLimit)
        val legacyIndex = flight.lastIndexOf("\"cdn_path\":", searchLimit)
        val index = maxOf(newIndex, legacyIndex)
        if (index < 0) return null
        val key = if (index == newIndex) "\"cdnPath\":" else "\"cdn_path\":"
        var cursor = index + key.length
        while (cursor < flight.length && flight[cursor].isWhitespace()) cursor++
        // A JSON null / missing string is no generation — refusing here keeps
        // a garbage value (e.g. the next key's name) out of the plan request.
        if (cursor >= flight.length || flight[cursor] != '"') return null
        val end = flight.indexOf('"', cursor + 1)
        if (end < 0) return null
        return flight.substring(cursor + 1, end).takeIf { it.isNotEmpty() }
    }

    private class DeferredWithCdn(
        val images: List<String>,
        val maps: List<DeferredMapDto>,
        val cdnPath: String?,
    )

    private suspend fun parseDeferredMedia(flight: String, chapterId: Int): DeferredWithCdn? {
        val mediaKey = "\"deferredMedia\":{"
        val mediaIndex = flight.indexOf(mediaKey)
        if (mediaIndex < 0) return null
        val mediaStart = mediaIndex + "\"deferredMedia\":".length
        val mediaJson = RscFlight.balancedObject(flight, mediaStart) ?: return null
        val media = runCatching {
            json.decodeFromString(DeferredMediaDto.serializer(), mediaJson)
        }.getOrNull() ?: return null

        // The deferred payload carries the storage generation its map tokens
        // are bound to: "cdnPath" since the site's flight rework (verified
        // live: "cdnPath":"cdn2"), "cdn_path" on legacy chapters. Sending a
        // stale/null generation makes the proxy plan bind the piece tokens
        // to a dead one — every piece then answers 404 {"error":"not_found"}
        // and the stitch server surfaces that as HTTP 502 ("pages 4+ fail").
        val cdnPath = extractCdnPath(flight, mediaIndex)

        if (media.requireTurnstile == true) {
            Log.i(TAG, "deferred media requires Turnstile; serving public pages only")
            return DeferredWithCdn(images = emptyList(), maps = emptyList(), cdnPath = cdnPath)
        }

        val data = fetchDeferredMaps(chapterId, media)
        return DeferredWithCdn(
            images = data?.images.orEmpty(),
            maps = data?.maps.orEmpty(),
            cdnPath = cdnPath,
        )
    }

    /**
     * GET /chapter-deferred-media/{chapterId}?token={jwt}&split={n} ->
     * the remaining pages' reconstruction map tokens. An expired/revoked
     * JWT (the tokens live ~30 minutes) gets ONE renewal round-trip first —
     * `POST /chapter-deferred-media/{chapterId}/refresh` with the old token,
     * the same flow the site reader's DeferredTokenRenewalError handler
     * runs — and the fetch is retried with the fresh JWT before giving up
     * and serving the public pages only.
     */
    private suspend fun fetchDeferredMaps(chapterId: Int, media: DeferredMediaDto): DeferredDataDto? {
        val token = media.token ?: return null
        var currentToken = token
        repeat(2) { attempt ->
            val response = client.newCall(deferredMediaRequest(chapterId, currentToken, media.splitIndex)).execute()
            response.use {
                if (it.isSuccessful) {
                    return json.decodeFromString(DeferredResponseDto.serializer(), it.body.string()).data
                }
                // Only the auth/expiry class of failure is worth a renewal;
                // 404 (unknown chapter) etc. go straight to the fallback.
                if (it.code != 401 && it.code != 403) {
                    Log.w(TAG, "deferred media failed with HTTP ${it.code}; serving public pages only")
                    return null
                }
                if (attempt > 0) {
                    Log.w(TAG, "deferred media still unauthorized after refresh; serving public pages only")
                    return null
                }
            }
            currentToken = refreshToken(chapterId, currentToken) ?: return null
        }
        return null
    }

    private fun deferredMediaRequest(chapterId: Int, token: String, splitIndex: Int?): Request = Request.Builder()
        .url(
            "$baseUrl/chapter-deferred-media/$chapterId".toHttpUrl().newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("split", (splitIndex ?: 0).toString())
                .build(),
        )
        .header("Accept", "application/json")
        .header("Referer", "$baseUrl/")
        .build()

    /** POST /chapter-deferred-media/{chapterId}/refresh {token} -> {token} —
     * the reader's own renewal endpoint; null when the site refuses. */
    private fun refreshToken(chapterId: Int, expiredToken: String): String? {
        val request = Request.Builder()
            .url("$baseUrl/chapter-deferred-media/$chapterId/refresh")
            .post(RenewMediaTokenDto(expiredToken).toJsonString().toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Referer", "$baseUrl/")
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (t: IOException) {
            Log.w(TAG, "deferred-media refresh threw ${t.javaClass.simpleName}")
            return null
        }
        response.use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "deferred-media refresh failed with HTTP ${res.code}")
                return null
            }
            val body = res.body.string()
            val data = runCatching { body.parseAs<RefreshedMediaTokenDto>() }.getOrNull()
            return data?.token?.takeIf { token -> token.isNotEmpty() }
        }
    }

    private val jsonMedia by lazy { "application/json; charset=utf-8".toMediaType() }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ---- Description (the Comix-style options) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display year, chapter count, favorites and ratings"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS
            title = "Show tags in genre chips"
            summary = "Include the title's tags in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from the genre field"
            setDefaultValue("")
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)

        // ---- Chapter list (the duplicate-combining option) ----
        val dedupeSwitch = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Combine duplicate chapters"
            summary = "Keep one entry per chapter number — the site re-uploads " +
                "the same chapter under several teams"
            setDefaultValue(false)
        }
        screen.addPreference(dedupeSwitch)

        val priorityPref = EditTextPreference(screen.context).apply {
            key = PREF_PREFERRED_SCANLATORS
            title = "Team priority"
            summary = "Comma-separated team names, most preferred first; the first " +
                "match wins when combining duplicates. Empty = keep the newest upload"
            setDefaultValue("")
            setEnabled(preferences.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false))
        }
        screen.addPreference(priorityPref)

        dedupeSwitch.setOnPreferenceChangeListener { _, newValue ->
            priorityPref.setEnabled(newValue as Boolean)
            true
        }
    }

    private companion object {
        const val TAG = "ProComic"
        const val PAGE_LIMIT = 24

        /** A single letter keeps the search endpoint in its "trusted" mode
         * (no Turnstile) while matching nearly every English-titled series. */
        const val CATALOG_QUERY = "e"

        const val PREF_SHOW_TAGS = "pref_show_tags"
        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        const val PREF_PREFERRED_SCANLATORS = "pref_preferred_scanlators"

        val GENRE_FALLBACK = listOf(
            "Action", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi",
            "Fantasy", "Gender Bender", "Harem", "Historical", "Horror",
            "Josei", "Martial Arts", "Mecha", "Mystery", "Psychological",
            "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo",
            "Shounen", "Slice of Life", "Sports", "Supernatural", "Tragedy",
        )
    }
}
