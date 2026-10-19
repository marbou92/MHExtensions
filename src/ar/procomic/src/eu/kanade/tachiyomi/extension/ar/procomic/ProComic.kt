package eu.kanade.tachiyomi.extension.ar.procomic

import android.content.SharedPreferences
import android.util.Log
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
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
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
 *   - latest updates: the SSR flight payload of /ar/updates (18 groups with
 *     real chapter dates), with the paginated search API as fallback.
 *   - details: the SSR flight payload of the series page (`{"series":{...}}`
 *     with author/artist/year/descriptions.ar inside `metadata`).
 *   - chapters: the reader page of the series' newest chapter embeds the
 *     FULL chapter table of contents (ids, numbers, teams) — the series
 *     page only carries the latest 30 (with dates), which are merged in.
 *   - pages: reader SSR exposes a few direct CDN images plus a
 *     `deferredMedia` JWT; ProtectedPageInterceptor stitches the remaining
 *     pages through /chapter-deferred-media + /chapter-map-proxy-plan.
 *
 * Details follow the MangaDot description layout: star rating header, a meta
 * info line, the synopsis, a Links section.
 */
@Source
abstract class ProComic :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    private val json = Json { ignoreUnknownKeys = true }

    private val mapCache = MapCache()

    // lazy: the interceptor reads baseUrl, which the @Source-generated
    // subclass supplies via computed getters (safe at any phase) — lazy
    // keeps the construction order a non-issue either way.
    private val protectedPageInterceptor by lazy { ProtectedPageInterceptor(baseUrl, mapCache) { client } }

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        addInterceptor(::originSanitizerInterceptor)
        addInterceptor(protectedPageInterceptor)
        addInterceptor(CloudflareSolverInterceptor(setOf(baseUrl.toHttpUrl().host)))
        rateLimit(3)
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
                    ?.takeIf { row -> row.chapters.isNotEmpty() }
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
        status = when (this@toSManga.status) {
            "مستمر" -> SManga.ONGOING
            "مكتمل" -> SManga.COMPLETED
            "متوقف" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
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
            val data = json.decodeFromString(SearchResponseDto.serializer(), it.body.string())
            val hasNext = when (val pages = data.meta?.pages) {
                null -> data.data.size >= PAGE_LIMIT
                else -> page < pages
            }
            return MangasPage(
                data.data.map { series -> series.toSManga() },
                hasNext && data.data.isNotEmpty(),
            )
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
        status = when (this@toSManga.progress) {
            "مستمر" -> SManga.ONGOING
            "مكتمل" -> SManga.COMPLETED
            "متوقف" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
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
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) fetchSeriesDetails(manga) else manga
        }
        val chaptersDeferred = async {
            if (fetchChapters) fetchChapterListInternal(manga) else chapters
        }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun fetchSeriesDetails(manga: SManga): SManga {
        val pageData = fetchSeriesPage(manga.url)
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
            }.distinct().joinToString(", ")
            // The series SSR carries no publish status — leave it unknown
            // (browse rows carry the site's own progress field instead).
            status = SManga.UNKNOWN
            description = buildDescription(series)
            initialized = true
        }
    }

    /**
     * MangaDot-style description block:
     *   ★★★★★ 4.99
     *   **Year:** 2023 · **Chapters:** 307 · 1614 ratings
     *   ---
     *   <Arabic synopsis>
     *   **Links:** - [Source](...)
     */
    private fun buildDescription(series: SeriesDetailsDto): String = buildString {
        val rating = series.average_rating
        if (rating != null && rating > 0.0) {
            val stars = rating.roundToInt().coerceIn(0, 5)
            append(
                "${"★".repeat(stars)}${"☆".repeat(5 - stars)} " +
                    String.format(Locale.US, "%.2f", rating),
                "\n\n",
            )
        }

        val metaInfo = buildList {
            series.metadata?.year?.takeIf { it.isNotBlank() }?.let { add("**Year:** $it") }
            series.chaptersCount?.let { add("**Chapters:** $it") }
            series.favorites_count?.takeIf { it > 0 }?.let { add("$it favorites") }
            series.ratings_count?.takeIf { it > 0 }?.let { add("$it ratings") }
        }
        if (metaInfo.isNotEmpty()) {
            append(metaInfo.joinToString(" · "), "\n\n")
        }

        if (isNotEmpty()) {
            append("---\n\n")
        }

        val synopsis = series.metadata?.descriptions?.get("ar")
            ?: series.metadata?.descriptions?.values?.firstOrNull()
            ?: series.description
        synopsis?.let {
            append(
                it.replace("\r\n", "\n")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim(),
                "\n\n",
            )
        }

        listOfNotNull(
            "[Source](${seriesPageUrl(series.slug + "-" + series.id)})",
        ).also { links ->
            if (links.isNotEmpty()) {
                append("\n**Links:**\n")
                links.forEach { link -> append("- ", link, "\n") }
            }
        }
    }.trim()

    // ============================= Chapters ==============================

    /** Parsed from the series page flight payload. */
    private class SeriesPageData(
        val series: SeriesDetailsDto,
        val recentChapters: List<ChapterRowDto>,
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

    private suspend fun fetchChapterListInternal(manga: SManga): List<SChapter> {
        val pageData = fetchSeriesPage(manga.url)
        val recent = pageData.recentChapters
        val newestChapter = recent.maxByOrNull { it.id }
        val seriesSlug = manga.url.substringBeforeLast('-')

        // Full TOC lives on the newest chapter's reader page.
        val toc: List<TocChapterDto> = newestChapter?.let { chapter ->
            val number = chapter.chapter_number ?: ""
            runCatching {
                fetchReaderToc(chapterPageUrl(ChapterRef(seriesSlug, number, chapter.id)))
            }
                .onFailure { Log.w(TAG, "reader TOC failed; using the recent-30 list", it) }
                .getOrNull()
        }.orEmpty()

        val entries = toc.ifEmpty {
            recent.map { row ->
                TocChapterDto(
                    id = row.id,
                    chapterNumber = row.chapter_number,
                    language = row.language,
                    group = null,
                    translator = row.translator,
                )
            }
        }

        val rowsById = recent.associateBy { it.id }
        return entries
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
                    date_upload = parseDate(row?.published_at)
                }
            }
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
                pages.add(Page(index = pages.size, url = readerUrl, imageUrl = imageUrl))
            }

            if (media != null) {
                media.images.forEach { imageUrl ->
                    pages.add(Page(index = pages.size, url = readerUrl, imageUrl = imageUrl))
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
                            )
                        },
                    )
                    media.maps.forEachIndexed { idx, _ ->
                        pages.add(
                            Page(
                                index = pages.size,
                                url = readerUrl,
                                imageUrl = "$baseUrl/.protected-page/$chapterIdStr/$idx",
                            ),
                        )
                    }
                }
            }

            if (pages.isEmpty()) throw IOException("ProComic: no pages found (chapter may be paywalled)")
            return pages
        }
    }

    /** Direct CDN images embedded in the reader flight (public pages). */
    private fun parseReaderImages(flight: String): List<String> {
        val urls = mutableListOf<String>()
        val key = "\"desktop\":"
        var index = 0
        while (true) {
            index = flight.indexOf(key, index)
            if (index < 0) break
            val start = flight.indexOf('"', index + key.length)
            val end = flight.indexOf('"', start + 1)
            if (start < 0 || end < 0) break
            val imageUrl = flight.substring(start + 1, end)
            if (imageUrl.startsWith("http") && imageUrl.contains("/chapters/")) {
                urls.add(imageUrl)
            }
            index = end
        }
        return urls.distinct()
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

        // The chapter object around deferredMedia carries cdn_path.
        val cdnKey = "\"cdn_path\":"
        val cdnIndex = flight.lastIndexOf(cdnKey, mediaIndex)
        var cdnPath: String? = null
        if (cdnIndex >= 0) {
            val start = flight.indexOf('"', cdnIndex + cdnKey.length)
            val end = flight.indexOf('"', start + 1)
            if (start >= 0 && end > start) cdnPath = flight.substring(start + 1, end)
        }

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
     * the remaining pages' reconstruction map tokens.
     */
    private suspend fun fetchDeferredMaps(chapterId: Int, media: DeferredMediaDto): DeferredDataDto? {
        val token = media.token ?: return null
        val request = Request.Builder()
            .url(
                "$baseUrl/chapter-deferred-media/$chapterId".toHttpUrl().newBuilder()
                    .addQueryParameter("token", token)
                    .addQueryParameter("split", (media.splitIndex ?: 0).toString())
                    .build(),
            )
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .build()

        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                Log.w(TAG, "deferred media failed with HTTP ${it.code}; serving public pages only")
                return null
            }
            return json.decodeFromString(DeferredResponseDto.serializer(), it.body.string()).data
        }
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ---- Details screen ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS
            title = "Show tags in details"
            summary = "List the title's tags after the genres in its description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private companion object {
        const val TAG = "ProComic"
        const val PAGE_LIMIT = 24

        /** A single letter keeps the search endpoint in its "trusted" mode
         * (no Turnstile) while matching nearly every English-titled series. */
        const val CATALOG_QUERY = "e"

        const val PREF_SHOW_TAGS = "pref_show_tags"

        val GENRE_FALLBACK = listOf(
            "Action", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi",
            "Fantasy", "Gender Bender", "Harem", "Historical", "Horror",
            "Josei", "Martial Arts", "Mecha", "Mystery", "Psychological",
            "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo",
            "Shounen", "Slice of Life", "Sports", "Supernatural", "Tragedy",
        )
    }
}
