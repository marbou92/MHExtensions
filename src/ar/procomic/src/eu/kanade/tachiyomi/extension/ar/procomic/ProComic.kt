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
import keiyoushi.utils.applicationContext
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
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

@Source
abstract class ProComic :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    private val json = Json { ignoreUnknownKeys = true }

    // lazy: the server reads baseUrl, which the @Source-generated subclass
    // supplies via computed getters (safe at any phase). Lazy keeps the
    // construction order a non-issue either way. internal: the JVM harness
    // drives the verification probes on it; harmless in the shipped build.
    internal val imageServer by lazy {
        ProComicImageServer(
            baseUrl = baseUrl,
            // The source's own client as the retry tier: when the server's
            // bare inner client cannot get an image through (region-
            // dependent edge behavior), the fetch is retried with the
            // session the reader page established. Cookies, CF clearance,
            // the forced browser UA.
            sessionClient = client,
            nativeCacheDir = applicationContext.cacheDir,
        ).apply {
            chapterPassProvider = { latestImagePass }
            deferredResigner = { rejected -> resignDeferredImageUrl(rejected) }
            mapPlanner = { request -> planChapterMap(request) }
        }
    }

    @Volatile
    private var latestImagePass: String? = null

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        addInterceptor(::userAgentOverrideInterceptor)
        addInterceptor(::originSanitizerInterceptor)
        addInterceptor(
            ProComicChallengeInterceptor(setOf(baseUrl.toHttpUrl().host)) { safeFetchEnabled },
        )
        rateLimit(3)
    }

    private val safeFetchEnabled: Boolean
        get() = preferences.getBoolean(PREF_SAFE_FETCH, true)

    @Volatile
    private var mintFruitless = 0

    @Volatile
    private var mintPausedUntil = 0L

    /** The reader-session breaker (see SESSION_FAILURE_LIMIT): consecutive
     * sessions that produced nothing usable pause the session for the
     * cooldown window. Reset on the first session that helps. */
    @Volatile
    private var sessionFailures = 0

    @Volatile
    private var sessionPausedUntil = 0L

    private val readerSessionEnabled: Boolean
        get() = preferences.getBoolean(PREF_READER_SESSION, false)

    /**
     * The site's WAF answers 403 to non-browser User-Agents. Including the
     * app UA some hosts stamp onto every request (Kotatsu's Mihon-compat
     * layer among them. "Kotatsu/1.0" gets 403 on the reader
     * page AND on the image CDN while browser UAs pass). One forced browser
     * UA across the whole chain also keeps the deferred-media JWT's uah
     * (user-agent hash) claim consistent, since the loopback server's inner
     * client uses the exact same string (ProComicImageServer.BROWSER_UA).
     */
    private fun userAgentOverrideInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") == ProComicImageServer.BROWSER_UA) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("User-Agent", ProComicImageServer.BROWSER_UA).build())
    }

    /**
     * KeiSource stamps "Origin: <baseUrl>" onto every request, but browsers
     * never send Origin on document GETs or same-origin fetches. Carrying it
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


    private fun chapterPageUrl(ref: ChapterRef): String = "$baseUrl/ar/chapter/${ref.seriesSlug}-${ref.chapterNumber}-${ref.chapterId}"

    internal fun apexOrderFor(rawBaseUrl: String): List<String> {
        val base = rawBaseUrl.trimEnd('/')
        val host = base.substringAfter("//")
        val alt = when {
            host.startsWith("www.") -> null
            host.endsWith(".net") -> host.removeSuffix(".net") + ".pro"
            host.endsWith(".pro") -> host.removeSuffix(".pro") + ".net"
            else -> null
        }
        return listOfNotNull(base, alt?.let { "https://$it" }).distinct()
    }

    private val readerApexes: List<String> by lazy { apexOrderFor(baseUrl) }

    /**
     * Fetches a reader-side path across both apexes, extracts the RSC
     * flight payload and parses it with [parse]. A null [parse] result
     * (redirect-to-series, payload missing) or an unsuccessful response
     * moves to the next apex; when every apex fails, [emptyPayloadError]
     * (all returned 200 without a usable payload) or the last transport
     * error is thrown.
     */
    private suspend fun <T> fetchFlightAcrossApexes(
        path: String,
        emptyPayloadError: String,
        parse: (flight: String) -> T?,
    ): T {
        var lastError: IOException? = null
        for (apex in readerApexes) {
            try {
                val response = client.newCall(GET("$apex$path", headers)).execute()
                response.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code} on $path")
                    parse(RscFlight.extract(it.body.string()))
                        ?.let { parsed -> return parsed }
                    lastError = IOException(emptyPayloadError)
                }
            } catch (e: IOException) {
                lastError = e
            }
            // The edge sometimes answers the document route with a data-less
            // app shell (its flight middleware routes some clients to it,
            // 200 + x-prochan-shell, no payload at all). The
            // router's OWN flight form of the same route. The RSC header
            // plus the _rsc param. Still returns the raw payload stream.
            // NOTE: the raw stream carries no self.__next_f.push markers,
            // so the body is parsed as-is (no RscFlight.extract pass).
            try {
                val request = GET(rscFlightUrl(apex, path), headers).newBuilder()
                    .header("RSC", "1")
                    .build()
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code} on $path (flight form)")
                    parse(it.body.string())
                        ?.let { parsed -> return parsed }
                    lastError = IOException(emptyPayloadError)
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("ProComic: reader unreachable for $path")
    }

    /** The router's flight form of a reader path: the URL the site's own
     * client-side router requests (the `_rsc` param; the RSC request
     * header is added by the caller). Pure so the harness can pin it. */
    internal fun rscFlightUrl(apex: String, path: String): String = "${apex.trimEnd('/')}$path?_rsc=1"

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
        thumbnail_url = resolveCoverUrl(
            coverImageApp = null,
            coverImage = coverImage,
            thumbnail = null,
            cdnPath = cdn_path,
        )?.let(::coverProxyUrl)
        genre = typeGenreKeywords(type).joinToString(", ").ifBlank { null }
        status = this@toSManga.status.toProComicStatus()
        initialized = true
    }

    private fun coverProxyUrl(resolved: String): String = runCatching { imageServer.imageProxyUrl(resolved, ProComicImageServer.COVER_PROXY_MAX_EDGE) }.getOrDefault(resolved)

    private fun isPlannedLoopbackUrl(url: String): Boolean = url.contains("/${ProComicImageServer.MAP_PAGE_PATH_PREFIX}/") ||
        url.contains("/${ProComicImageServer.COMPOSED_PATH_PREFIX}/")

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
        // Novel rows are dropped: text chapters, client-rendered pages. 
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
        thumbnail_url = resolveCoverUrl(
            coverImageApp = this@toSManga.coverImageApp,
            coverImage = this@toSManga.coverImage,
            thumbnail = this@toSManga.thumbnail,
            cdnPath = cdn_path,
        )?.let(::coverProxyUrl)
        genre = buildList {
            addAll(typeGenreKeywords(this@toSManga.type))
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

    override fun getMangaUrl(manga: SManga): String = "${readerApexes.first()}/ar/series/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        // Both apexes (and their www forms). The site migrated .pro → .net
        // and older library entries still carry .pro links.
        if (url.host !in setOf(
                "procomic.net",
                "www.procomic.net",
                "procomic.pro",
                "www.procomic.pro",
            )
        ) {
            return null
        }
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "ar") return null

        val mangaUrl = when {
            segments.size >= 3 && segments[1] == "chapter" -> {
                // /ar/chapter/{seriesSlug}-{number}-{id}
                resolveSeriesUrlFromChapterPage(segments[2]) ?: return null
            }

            segments.size >= 3 && segments[1] == "series" -> {
                // /ar/series/{seriesSlug}-{id}. The canonical series path
                // since the .net migration (the bare /ar/{slug}-{id} only
                // 308s there now).
                segments[2]
            }

            else -> segments[1]
        }

        val tmpManga = SManga.create().apply { this.url = mangaUrl }
        return fetchMangaUpdate(tmpManga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    /** The reader page flight carries `seriesUrl:"/ar/{slug}-{id}"`. */
    private suspend fun resolveSeriesUrlFromChapterPage(chapterSlug: String): String? = runCatching {
        fetchFlightAcrossApexes(
            path = "/ar/chapter/$chapterSlug",
            emptyPayloadError = "ProComic: seriesUrl missing on the reader page",
        ) { flight ->
            val key = "\"seriesUrl\":"
            val index = flight.indexOf(key)
            if (index < 0) return@fetchFlightAcrossApexes null
            val start = flight.indexOf('"', index + key.length)
            val end = flight.indexOf('"', start + 1)
            if (start < 0 || end < 0) return@fetchFlightAcrossApexes null
            flight.substring(start + 1, end).substringAfterLast('/')
        }
    }.getOrNull()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (fetchDetails && fetchChapters) {
            // One series-page fetch feeds both details and the chapter
            // list. Fetching them independently would issue the same
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
                    cdn_path = row.cdn_path,
                    coverImage = row.coverImage,
                    coverImageApp = row.coverImageApp,
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
            thumbnail_url = resolveCoverUrl(
                coverImageApp = series.coverImageApp,
                coverImage = series.coverImage,
                thumbnail = series.thumbnail,
                cdnPath = series.cdn_path,
            )?.let(::coverProxyUrl)
            author = meta?.authorText()
            artist = meta?.artistText()
            genre = buildList {
                addAll(typeGenreKeywords(series.type))
                addAll(meta?.genres.orEmpty())
                addAll(pageData.categories?.genreNames().orEmpty())
                if (showTags) {
                    meta?.tags.orEmpty().forEach { add(it.trim()) }
                    pageData.categories?.tagNames().orEmpty().forEach { add(it) }
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

    internal fun typeGenreKeywords(type: String?): List<String> {
        val trimmed = type?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        val capitalized = trimmed.replaceFirstChar { c -> c.uppercase() }
        return when (trimmed.lowercase()) {
            "manhwa", "manhua", "webtoon", "long strip" -> listOf(capitalized, "Long Strip", "Webtoon").distinct()
            else -> listOf(capitalized)
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

    internal class SeriesPageData(
        val series: SeriesDetailsDto,
        val recentChapters: List<ChapterRowDto>,
        val statusText: String? = null,
        val categories: SeriesCategoriesDto? = null,
    )

    private suspend fun fetchSeriesPage(mangaUrl: String): SeriesPageData = fetchFlightAcrossApexes(
        path = "/ar/series/$mangaUrl",
        emptyPayloadError = "ProComic: the series page carried no payload on either apex",
        parse = ::parseSeriesFlight,
    )

    internal fun parseSeriesFlight(flight: String): SeriesPageData? {
        val wrapperKey = "{\"series\":{"
        val wrapperStart = flight.indexOf(wrapperKey)
        if (wrapperStart < 0) return null
        val wrapperJson = RscFlight.balancedObject(flight, wrapperStart) ?: return null
        val wrapper = runCatching { json.decodeFromString(SeriesFlightWrapperDto.serializer(), wrapperJson) }.getOrNull() ?: return null
        return SeriesPageData(wrapper.series, parseChapterRows(flight), categories = wrapper.categories)
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
        // anchor that request, and their reader route is client-side too. 
        // for those the public chapters API is the full-TOC source.
        var toc: List<TocChapterDto> = newestChapter?.let { chapter ->
            val number = chapter.chapter_number ?: ""
            runCatching {
                fetchReaderToc("/ar/chapter/$seriesSlug-$number-${chapter.id}")
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
     * one entry per non-zero chapter number survives. The first team in
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

    private suspend fun fetchReaderToc(readerPath: String): List<TocChapterDto> = fetchFlightAcrossApexes(
        path = readerPath,
        emptyPayloadError =
        "ProComic: chapter table of contents missing \u2014 the chapter is locked " +
            "or unavailable on the site",
    ) { flight ->
        val markerIndex = flight.indexOf("\"chapterLabel\":")
        val arrayIndex = flight.indexOf("\"chapters\":[", if (markerIndex >= 0) markerIndex else 0)
        if (arrayIndex < 0) return@fetchFlightAcrossApexes null
        val arrayStart = arrayIndex + "\"chapters\":".length
        val arrayJson = RscFlight.balancedArray(flight, arrayStart)
            ?: return@fetchFlightAcrossApexes null
        json.parseToJsonElement(arrayJson).jsonArray.mapNotNull { element ->
            runCatching {
                json.decodeFromString(TocChapterDto.serializer(), element.jsonObject.toString())
            }.getOrNull()
        }.ifEmpty { null }
    }

    /**
     * GET /api/public/chapters?contentId={seriesId}&page={n}&limit=50. 
     * `{chapters:[...], hasMore}`. Newest first, dates included. The server
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
        val ref = runCatching { json.decodeFromString(ChapterRef.serializer(), chapter.url) }.getOrNull()
        return if (ref != null) chapterPageUrl(ref) else "$baseUrl/ar"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = runCatching { pageListInternal(chapter) }
        .onFailure { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "page-list fetch survived: ${t.javaClass.simpleName}: ${t.message?.take(180)}")
        }
        .getOrDefault(emptyList())

    private suspend fun pageListInternal(chapter: SChapter): List<Page> {
        val ref = runCatching { json.decodeFromString(ChapterRef.serializer(), chapter.url) }.getOrNull()
        if (ref == null) {
            Log.e(TAG, "chapter url decode failed: ${chapter.url.take(80)}")
            return emptyList()
        }
        val readerPath = "/ar/chapter/${ref.seriesSlug}-${ref.chapterNumber}-${ref.chapterId}"
        val payload = runCatching {
            fetchFlightAcrossApexes(
                path = readerPath,
                emptyPayloadError =
                "ProComic: no pages found \u2014 the chapter is locked or unavailable on the site " +
                    "(unlock with coins or the shortlink there first)",
            ) { flight ->
                val images = parseReaderImages(flight)
                val appImages = parseAppImages(flight)
                if (images.isEmpty() && appImages.isEmpty()) {
                    null
                } else {
                    // Prefer the app variant per page (200 for
                    // plain clients, the signature rides in the file name); the
                    // raw CDN URL is the fallback (the proxy's session retry is
                    // its last chance, the edge 403s bare TLS on those hosts).
                    PagesPayload(
                        upstreams = List(maxOf(images.size, appImages.size)) { index ->
                            appImages.getOrNull(index) ?: images[index]
                        },
                        deferred = parseDeferredMedia(flight),
                        imagePass = parseImagePass(flight),
                        publicCount = parsePublicImageCount(flight),
                        cdnPath = parseCdnPath(flight),
                    )
                }
            }
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.i(TAG, "no WebView-free payload (${t.message?.take(120)}) — the reader session is the source")
            runCatching {
                imageServer.recordDeferredTrail(ref.chapterId, listOf("flight carried no page list — reader session is the source"))
            }
            PagesPayload(emptyList(), null, null, null)
        }

        var upstreams = payload.upstreams
        val deferred = payload.deferred
        latestImagePass = payload.imagePass
        var walkTrail: List<String> = emptyList()
        var deferredWalkComplete = false
        var plannedMaps: List<PlannedMap> = emptyList()
        if (deferred?.token != null && upstreams.isNotEmpty()) {
            val walk = fetchDeferredPages(
                chapterId = ref.chapterId,
                deferred = deferred,
                imagePass = payload.imagePass,
                referer = chapterPageUrl(ref),
                cdnPath = payload.cdnPath,
            )
            walkTrail = walk.trail
            deferredWalkComplete = walk.complete
            latestImagePass = latestImagePass ?: walk.imagePass
            val extra = walk.images
            if (extra.isNotEmpty()) {
                val seen = upstreams.toHashSet()
                upstreams = upstreams + extra.filter { it !in seen }
                Log.i(TAG, "deferred: +${extra.size} pages (total ${upstreams.size})")
            }
            val walkMaps = walk.maps.takeIf { walk.images.isEmpty() }.orEmpty()
            if (walk.images.isNotEmpty() && walk.maps.isNotEmpty()) {
                Log.i(TAG, "deferred: the images tier answered — dropping ${walk.maps.size} redundant map tokens")
            }
            if (walkMaps.isNotEmpty()) {
                // The maps are the chapter's protected pages IN ORDER after
                // the public embed (the site's own reader renders them at
                // absoluteIndex = publicBase + ordinal. The exact pageIndex
                // its plan POST sends). The public count from the flight is
                // the base; the walk's own image batches (when any) extend it.
                val mapBase = (payload.publicCount ?: upstreams.size).coerceAtLeast(upstreams.size)
                plannedMaps = walkMaps.mapIndexedNotNull { ordinal, entry ->
                    val token = entry.token?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    runCatching {
                        PlannedMap(
                            chapterId = ref.chapterId,
                            pageIndex = mapBase + ordinal,
                            token = token,
                            method = entry.method,
                            cdnPath = payload.cdnPath,
                            referer = chapterPageUrl(ref),
                        )
                    }.getOrNull()
                }
                Log.i(TAG, "deferred: +${plannedMaps.size} planned map pages (base $mapBase)")
            }
            if (extra.isEmpty() && plannedMaps.isEmpty()) {
                walk.trail.forEach { Log.i(TAG, "deferred: $it") }
            }
        } else if (deferred == null && upstreams.isNotEmpty()) {
            deferredWalkComplete = true
        }
        payload.publicCount?.takeIf { it > upstreams.size }?.let { cap ->
            Log.i(TAG, "public embed capped at $cap pages by the site")
        }

        // The site-gated CDN era: pages that ride only the raw cdn{1-4}
        // hosts cannot be fetched WebView-free at all (the origin 403s
        // every non-browser client, and their lawful signed route needs a
        // browser session). Listing them renders permanent error cards. 
        // the reader gets the loadable pages only, with the drop named
        // in logcat and /.diag. A raw-ONLY flight still ships everything
        // (the legacy guard inside [partitionLoadablePages]).
        val (loadable, gated) = partitionLoadablePages(upstreams)
        if (gated.isNotEmpty()) {
            Log.i(
                TAG,
                "site serves ${gated.size} pages of this chapter only through a browser-signed " +
                    "CDN route — listing the ${loadable.size} loadable pages",
            )
            runCatching {
                imageServer.recordDeferredTrail(
                    ref.chapterId,
                    walkTrail + "shipped ${loadable.size} loadable pages; " +
                        "${gated.size} browser-gated pages not listed (they cannot render WebView-free)",
                )
            }
        }
        var finalUrls: List<String> = loadable

        val plannedDistinct = plannedMaps
            .groupBy { it.pageIndex }
            .map { (_, sameIndex) -> sameIndex.first() }
        if (plannedDistinct.size != plannedMaps.size) {
            Log.i(TAG, "dedup: ${plannedMaps.size} -> ${plannedDistinct.size} planned map pages")
        }
        if (plannedDistinct.isNotEmpty()) {
            val plannedUrls = plannedDistinct.mapIndexedNotNull { ordinal, planned ->
                runCatching {
                    imageServer.registerMapPage(
                        chapterId = planned.chapterId,
                        pageIndex = planned.pageIndex,
                        token = planned.token,
                        method = planned.method,
                        cdnPath = planned.cdnPath,
                        referer = planned.referer,
                    )
                }.onFailure { t ->
                    Log.w(TAG, "map page ${planned.pageIndex} not registered: ${t.javaClass.simpleName}")
                }.getOrNull()
            }
            if (plannedUrls.isNotEmpty()) {
                finalUrls = finalUrls + plannedUrls
                Log.i(TAG, "planned ${plannedUrls.size} protected map pages (total ${finalUrls.size})")
                runCatching {
                    imageServer.recordDeferredTrail(
                        ref.chapterId,
                        walkTrail + "planned ${plannedUrls.size} protected pages from the exchange's reconstruction maps" +
                            "(composed on request; AVIF pieces decode via the platform on Android 12+, via the reader session's capture below)",
                    )
                }
            } else {
                runCatching {
                    imageServer.recordDeferredTrail(
                        ref.chapterId,
                        walkTrail + "${plannedDistinct.size} reconstruction-map tokens arrived but none could be registered",
                    )
                }
            }
        }

        val avifRenderable = ProComicImageServer.avifDecodable(android.os.Build.VERSION.SDK_INT)
        val needsSessionRendering = false
        val needsUrlHarvest = deferredWalkComplete && plannedDistinct.isNotEmpty() && !avifRenderable
        if (readerSessionEnabled && (!deferredWalkComplete || needsUrlHarvest)) {
            if (System.currentTimeMillis() < sessionPausedUntil) {
                Log.i(TAG, "reader session cooling down after fruitless sessions — keeping the WebView-free list")
                runCatching {
                    imageServer.recordDeferredTrail(
                        ref.chapterId,
                        walkTrail + "reader session cooling down — shipping the WebView-free list",
                    )
                }
            } else {
                val harvest = runCatching {
                    ProComicReaderSession.harvest(
                        readerApexes,
                        readerPath,
                        ProComicImageServer.BROWSER_UA,
                        timeoutSeconds = if (deferredWalkComplete) 120L else 240L,
                    )
                }
                    .onFailure { t ->
                        Log.e(TAG, "reader session survived: ${t.javaClass.simpleName}: ${t.message?.take(140)}")
                    }
                    .getOrNull()
                val harvestedRows = harvest?.rows.orEmpty()
                harvest?.notes?.forEach { Log.i(TAG, it) }
                runCatching { imageServer.recordDeferredTrail(ref.chapterId, harvest?.notes.orEmpty()) }
                var capturedRowsCount = 0
                val sessionEntries: List<Pair<ProComicReaderSession.HarvestRow, String?>> = if (harvestedRows.isEmpty()) {
                    emptyList()
                } else {
                    var capturedCount = 0
                    val entries = harvestedRows.mapIndexed { index, row ->
                        val url = runCatching {
                            if (row.isPieces) {
                                val captured = harvest?.captured?.get(row.index)
                                when {
                                    captured != null -> {
                                        capturedCount++
                                        imageServer.registerCapturedRow(ref.chapterId, row.index, captured)
                                    }
                                    else -> imageServer.composedProxyUrl(row)
                                }
                            } else {
                                if (row.total > 1) null else row.u?.let { imageServer.imageProxyUrl(it) }
                            }
                        }.onFailure { t ->
                            Log.w(TAG, "row $index skipped: ${t.javaClass.simpleName}")
                            null
                        }.getOrNull()
                        row to url
                    }
                    capturedRowsCount = capturedCount
                    if (capturedCount > 0) {
                        Log.i(TAG, "reader session: $capturedCount protected pages captured as rendered pixels (no on-device AVIF decode)")
                        runCatching {
                            imageServer.recordDeferredTrail(
                                ref.chapterId,
                                listOf(
                                    "reader session: $capturedCount protected pages captured as rendered pixels " +
                                        "(in-page or band draw — the session's Chromium decoded the site's AVIF)",
                                ),
                            )
                        }
                    }
                    entries
                }
                if (sessionEntries.isNotEmpty()) {
                    val merged = finalUrls.toMutableList()
                    var replacedCaptured = 0
                    var replacedPlain = 0
                    var appended = 0
                    for ((row, url) in sessionEntries) {
                        if (url == null) continue
                        val capturedRender = row.isPieces &&
                            harvest?.captured?.containsKey(row.index) == true
                        val plainRender = !row.isPieces && row.total <= 1
                        when {
                            capturedRender && row.index in merged.indices -> {
                                if (merged[row.index] != url) {
                                    merged[row.index] = url
                                    replacedCaptured++
                                }
                            }
                            plainRender && row.index in merged.indices &&
                                isPlannedLoopbackUrl(merged[row.index]) -> {
                                merged[row.index] = url
                                replacedPlain++
                            }
                            row.index >= merged.size -> {
                                merged.add(url)
                                appended++
                            }
                            else -> Unit // keep the walk's slot — the list never shrinks
                        }
                    }
                    if (replacedCaptured + replacedPlain + appended > 0) {
                        Log.i(
                            TAG,
                            "reader session overlay: $replacedCaptured captured, $replacedPlain plain, " +
                                "$appended appended (${finalUrls.size} -> ${merged.size} pages, $capturedRowsCount captured)",
                        )
                        runCatching {
                            imageServer.recordDeferredTrail(
                                ref.chapterId,
                                listOf(
                                    "captured-overlay merge: $replacedCaptured captured + $replacedPlain plain " +
                                        "upgraded in place, $appended appended (${finalUrls.size} -> ${merged.size})",
                                ),
                            )
                        }
                        finalUrls = merged
                        sessionFailures = 0
                    }
                }
                if (finalUrls.size <= loadable.size) {
                    sessionFailures++
                    if (sessionFailures >= SESSION_FAILURE_LIMIT && sessionPausedUntil == 0L) {
                        sessionPausedUntil = System.currentTimeMillis() + SESSION_COOLDOWN_MS
                        Log.i(TAG, "${SESSION_FAILURE_LIMIT} fruitless reader sessions — cooling down for ${SESSION_COOLDOWN_MS / 60000} min")
                    }
                }
            }
        }
        upstreams = finalUrls

        val pages = upstreams.mapIndexed { index, upstream ->
            val imageUrl = if (upstream.startsWith("http://127.0.0.1:")) {
                upstream
            } else {
                runCatching { imageServer.imageProxyUrl(upstream) }.getOrDefault(upstream)
            }
            Page(
                index = index,
                url = "$baseUrl$readerPath",
                imageUrl = imageUrl,
            )
        }

        imageServer.preloadImages(ref.chapterId.toString(), upstreams.filterNot { it.startsWith("http://127.0.0.1:") })
        return pages
    }

    /** The page list embedded in the reader flight: `"images":[…]` arrays
     * (the exact props the site's reader component consumes) with any
     * https URL on a site-owned host kept. Since the .net migration these
     * are `cdn{1..4}.procomic.net/{series}/{chapter}/{file}.avif` raw
     * files. 403 to non-browser clients, so they only feed the appImages
     * fallback below. */
    private fun parseReaderImages(flight: String): List<String> {
        val urls = mutableListOf<String>()
        val key = "\"images\":["
        var index = 0
        while (true) {
            index = flight.indexOf(key, index)
            if (index < 0) break
            val arrayStart = index + key.length - 1
            val arrayJson = RscFlight.balancedArray(flight, arrayStart) ?: run {
                index += key.length
                continue
            }
            runCatching {
                json.parseToJsonElement(arrayJson).jsonArray.forEach { element ->
                    when (element) {
                        // Flat page list (the current .net shape): strings.
                        is JsonPrimitive ->
                            if (isReaderImageUrl(element.content)) urls.add(element.content)

                        // Legacy nested per-page shape: an array of piece
                        // URLs per page. Flatten, order preserved.
                        is JsonArray -> element.forEach { inner ->
                            val imageUrl = (inner as? JsonPrimitive)?.content ?: return@forEach
                            if (isReaderImageUrl(imageUrl)) urls.add(imageUrl)
                        }

                        else -> Unit
                    }
                }
            }
            index = arrayStart + arrayJson.length
        }
        return urls.distinct()
    }

    /** The app-host image class: signed-filename files on `app.<apex>`
     * (app.procomic.net, app.procomic.pro, app.prochan.net. The parent
     * brand serves a share of the catalog) that fetch directly from any
     * client, the signature riding in the file name (200
     * for plain TLS on every probed chapter). These are the only page
     * hosts the WebView-free chain can always serve. */
    internal fun isAppHostImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && httpUrl.host.startsWith("app.") && isReaderImageUrl(url)
    }.getOrDefault(false)

    internal fun isSignedProxyImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps &&
            httpUrl.encodedPath == "/api/cdn-image" &&
            !httpUrl.queryParameter("url").isNullOrBlank() &&
            !httpUrl.queryParameter("token").isNullOrBlank() &&
            isReaderImageUrl(url)
    }.getOrDefault(false)

    internal fun partitionLoadablePages(upstreams: List<String>): Pair<List<String>, List<String>> {
        val loadable = upstreams.filter { isAppHostImageUrl(it) || isSignedProxyImageUrl(it) }
        if (loadable.isEmpty() || loadable.size == upstreams.size) return upstreams to emptyList()
        val kept = loadable.toHashSet()
        return loadable to upstreams.filterNot { it in kept }
    }

    private fun isReaderImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (
            httpUrl.host == "procomic.net" ||
                httpUrl.host == "www.procomic.net" ||
                httpUrl.host == "procomic.pro" ||
                httpUrl.host == "www.procomic.pro" ||
                httpUrl.host == "prochan.net" ||
                httpUrl.host == "www.prochan.net" ||
                httpUrl.host == "procomic.im" ||
                httpUrl.host == "www.procomic.im" ||
                httpUrl.host.endsWith(".procomic.net") ||
                httpUrl.host.endsWith(".procomic.pro") ||
                httpUrl.host.endsWith(".prochan.net") ||
                httpUrl.host.endsWith(".procomic.im")
            )
    }.getOrDefault(false)

    /** The reader's own `<img>` sources: `"appImages":[{mobile,desktop}]`. 
     * signed-filename AVIF URLs on `app.<host>` that fetch directly from
     * any client (200 for plain okhttp/curl TLS on every
     * probed chapter, the signature carried in the file name; mobile and
     * desktop are identical). desktop wins, mobile is the fallback. */
    private fun parseAppImages(flight: String): List<String> {
        val key = "\"appImages\":["
        val index = flight.indexOf(key)
        if (index < 0) return emptyList()
        // arrayStart points AT the '[' (see parseReaderImages).
        val arrayStart = index + key.length - 1
        val arrayJson = RscFlight.balancedArray(flight, arrayStart) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(arrayJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                ((obj["desktop"] ?: obj["mobile"]) as? JsonPrimitive)
                    ?.content
                    ?.takeIf { it.startsWith("https://") && isReaderImageUrl(it) }
            }
        }.getOrDefault(emptyList())
    }


    /** Everything getPageList extracts from one reader flight. */
    private class PagesPayload(
        val upstreams: List<String>,
        val deferred: DeferredMediaDto?,
        val imagePass: String?,
        val publicCount: Int?,
        val cdnPath: String? = null,
    )

    internal class PlannedMap(
        val chapterId: Int,
        /** The page's ABSOLUTE index in the chapter (public base + ordinal). 
         * exactly the `pageIndex` the site's own plan POST sends. */
        val pageIndex: Int,
        val token: String,
        val method: String?,
        val cdnPath: String?,
        /** The chapter route. The Referer the piece edge requires
         * (pieces 403 without it, 200 with it). */
        val referer: String,
    )

    internal val readerSignalPrimary: String get() = READER_SIGNAL_PRIMARY
    internal val readerSignalFallback: String get() = READER_SIGNAL_FALLBACK

    /** The flight's `"deferredMedia":{...}` object, when the chapter ships
     * one (unlocked chapters above the public-image cap). */
    internal fun parseDeferredMedia(flight: String): DeferredMediaDto? {
        val key = "\"deferredMedia\":"
        val index = flight.indexOf(key)
        if (index < 0) return null
        var start = index + key.length
        while (start < flight.length && flight[start].isWhitespace()) start++
        if (start >= flight.length || flight[start] != '{') return null
        val objJson = RscFlight.balancedObject(flight, start) ?: return null
        return runCatching { json.decodeFromString(DeferredMediaDto.serializer(), objJson) }.getOrNull()
    }

    /** The flight's `"imagePass":"p1.…"` JWT. The pages-scope credential
     * the reader attaches as `x-chapter-pass` on protected media fetches. */
    internal fun parseImagePass(flight: String): String? {
        val key = "\"imagePass\":\""
        val index = flight.indexOf(key)
        if (index < 0) return null
        val end = flight.indexOf('"', index + key.length)
        if (end < 0) return null
        return flight.substring(index + key.length, end).takeIf { it.startsWith("p1.") }
    }

    /** The flight's `"publicImageCount":N`. How many pages the site
     * considers public (the SSR embed cap; observed live: 3). */
    internal fun parsePublicImageCount(flight: String): Int? {
        val key = "\"publicImageCount\":"
        val index = flight.indexOf(key)
        if (index < 0) return null
        var start = index + key.length
        var end = start
        while (end < flight.length && flight[end].isDigit()) end++
        if (end == start) return null
        return flight.substring(start, end).toIntOrNull()
    }

    internal fun parseCdnPath(flight: String): String? {
        val key = "\"cdnPath\":\""
        val index = flight.indexOf(key)
        if (index < 0) return null
        val end = flight.indexOf('"', index + key.length)
        if (end < 0) return null
        val value = flight.substring(index + key.length, end).trim()
        return value.takeIf { Regex("cdn[1-9]").matches(it) }
    }

    private suspend fun fetchDeferredPages(
        chapterId: Int,
        deferred: DeferredMediaDto,
        imagePass: String?,
        referer: String,
        cdnPath: String? = null,
    ): DeferredWalk {
        val (images, trail, complete, maps, pass) = fetchDeferredPagesInner(chapterId, deferred, imagePass, referer, cdnPath)
        runCatching { imageServer.recordDeferredTrail(chapterId, trail) }
        return DeferredWalk(images, trail, complete, maps, pass)
    }

    private class DeferredWalk(
        val images: List<String>,
        val trail: List<String>,
        val complete: Boolean,
        val maps: List<ChapterMapTokenDto> = emptyList(),
        val imagePass: String? = null,
    )

    private suspend fun fetchDeferredPagesInner(
        chapterId: Int,
        deferred: DeferredMediaDto,
        imagePass: String?,
        referer: String,
        cdnPath: String? = null,
    ): WalkResult {
        val trail = mutableListOf<String>()
        val initialToken = deferred.token
        if (initialToken.isNullOrBlank()) {
            trail += "deferredMedia present without a token"
            return WalkResult(emptyList(), trail, false)
        }
        trail += "deferredMedia split=${deferred.splitIndex} turnstile=${deferred.requireTurnstile}"

        var token: String = initialToken
        var pass = imagePass
        var tokenRenewals = 0
        var passRenewals = 0
        var mints = 0
        var exchanges = 0
        var turnstileToken: String? = null
        var turnstileWidget: String? = null
        val collected = mutableListOf<String>()
        val seen = HashSet<String>()
        val collectedMaps = mutableListOf<ChapterMapTokenDto>()
        val mapSeen = HashSet<String>()
        var readerSignal: String? = READER_SIGNAL_PRIMARY
        var fallbackSignalTried = false
        var replayedAfterRenewal = false
        var lastMapsCount = 0

        fun renewForNextSlice(): String? {
            if (tokenRenewals >= MAX_TOKEN_RENEWALS) {
                trail += "deferred renewals exhausted — walking with the current token"
                return null
            }
            tokenRenewals++
            val renewed = renewDeferredToken(chapterId, token, referer)
            if (renewed == null) {
                trail += "refresh POST failed — continuing with the current token"
                return null
            }
            trail += "refresh POST minted the next-batch token"
            return renewed
        }

        val walkDeadline = System.currentTimeMillis() + MAX_WALK_WALL_MS

        fun noteUpgradeVerdict(where: String) {
            if (turnstileToken != null && collected.isEmpty()) {
                mintFruitless++
                if (mintFruitless >= MINT_FRUITLESS_LIMIT && mintPausedUntil == 0L) {
                    mintPausedUntil = System.currentTimeMillis() + MINT_COOLDOWN_MS
                    trail += "$MINT_FRUITLESS_LIMIT upgrade mints bought no images tier — solver cooling down for ${MINT_COOLDOWN_MS / 60000} min"
                } else {
                    trail += "$where: the upgrade token still drew no images tier"
                }
            }
        }
        while (exchanges < MAX_DEFERRED_EXCHANGES &&
            collected.size + collectedMaps.size < MAX_DEFERRED_PAGES
        ) {
            exchanges++
            if (System.currentTimeMillis() > walkDeadline) {
                trail += "deferred walk wall (${MAX_WALK_WALL_MS / 1000} s) reached — shipping ${collected.size + collectedMaps.size} deferred pages"
                return WalkResult(collected, trail, false, collectedMaps, pass)
            }
            val (images, outcome) = deferredAttempt(
                chapterId = chapterId,
                token = token,
                pass = pass,
                referer = referer,
                split = deferred.splitIndex,
                turnstileToken = turnstileToken,
                turnstileWidget = turnstileWidget,
                readerSignal = readerSignal,
            )
            when (outcome.kind) {
                DeferredOutcome.Kind.OK -> {
                    trail += outcome.note
                    outcome.pass?.takeIf { it.isNotBlank() && it != pass }?.let {
                        pass = it
                        latestImagePass = it
                        trail += "the answer carried a fresh pages pass (x-chapter-pass renewed)"
                    }
                    val freshImages = images
                        .filter { seen.add(it) }
                        .map { url ->
                            when {
                                isAppHostImageUrl(url) || isSignedProxyImageUrl(url) -> url
                                else -> {
                                    val signed = runCatching { resignDeferredImageUrl(url) }.getOrNull()
                                    if (signed != null) {
                                        trail += "signed a raw deferred page for the bare cdn-image route"
                                        signed
                                    } else {
                                        trail += "the sign endpoint refused a raw deferred page — it stays unlisted"
                                        url
                                    }
                                }
                            }
                        }
                    val freshMaps = outcome.maps.filter { entry ->
                        val entryToken = entry.token
                        entryToken != null && entryToken.isNotBlank() && mapSeen.add(entryToken)
                    }
                    if (freshImages.isEmpty() && freshMaps.isEmpty()) {
                        noteUpgradeVerdict("terminal answer")
                        if (replayedAfterRenewal) {
                            trail += "the renewed token replayed the same slice — the deferred list is complete " +
                                "(${collected.size + collectedMaps.size} pages beyond the public embed)"
                            return WalkResult(collected, trail, true, collectedMaps, pass)
                        }
                        // Same slice again: advance the cursor the reader's
                        // way. Renew the token, then exchange once more.
                        val renewed = renewForNextSlice()
                        if (renewed == null) {
                            trail += "the deferred list is complete (${collected.size + collectedMaps.size} pages beyond the public embed)"
                            return WalkResult(collected, trail, true, collectedMaps, pass)
                        }
                        token = renewed
                        replayedAfterRenewal = true
                        continue
                    }
                    if (freshMaps.isNotEmpty()) lastMapsCount = freshMaps.size
                    if (freshImages.isEmpty() && freshMaps.isNotEmpty() &&
                        turnstileToken == null && mints == 0 &&
                        System.currentTimeMillis() <= walkDeadline &&
                        System.currentTimeMillis() >= mintPausedUntil &&
                        readerSessionEnabled
                    ) {
                        trail += "the exchange degraded to the maps tier — minting one widget token for the images tier (the reader's own shape)"
                        val (siteKey, siteKeyNote) = fetchTurnstileGateConfig(referer)
                        trail += siteKeyNote
                        val solved = runCatching {
                            TurnstileSolver.solve(
                                applicationContext,
                                siteKey,
                                null,
                                ProComicImageServer.BROWSER_UA,
                            )
                        }
                            .onFailure { trail += "turnstile solver crashed safely: ${it.javaClass.simpleName}" }
                            .getOrNull()
                        if (solved == null) {
                            mintFruitless++
                            if (mintFruitless >= MINT_FRUITLESS_LIMIT && mintPausedUntil == 0L) {
                                mintPausedUntil = System.currentTimeMillis() + MINT_COOLDOWN_MS
                                trail += "$MINT_FRUITLESS_LIMIT fruitless mint ladders — solver cooling down for ${MINT_COOLDOWN_MS / 60000} min"
                            }
                            trail += "the upgrade mint produced no token — keeping the maps tier"
                        } else {
                            mintFruitless = 0
                            mints++
                            turnstileToken = solved.first
                            // Reader parity: the visible-mode widget is
                            // rendered from the login sitekey, so the
                            // header is "login". The gate branch's exact
                            // pairing.
                            turnstileWidget = "login"
                            trail += "upgrade token minted offscreen — re-exchanging with the images tier requested"
                            continue
                        }
                    }
                    if (freshImages.isEmpty() && freshMaps.isNotEmpty() &&
                        freshMaps.size == lastMapsCount
                    ) {
                        noteUpgradeVerdict("re-mint terminal")
                        trail += "the exchange re-minted the same ${freshMaps.size} map pages with fresh tokens — " +
                            "the deferred list is complete (${collected.size + collectedMaps.size} pages beyond the public embed)"
                        return WalkResult(collected, trail, true, collectedMaps, pass)
                    }
                    collected += freshImages
                    collectedMaps += freshMaps
                    replayedAfterRenewal = false
                    trail += "deferred batch: +${freshImages.size} pages, +${freshMaps.size} map pages " +
                        "(running total ${collected.size + collectedMaps.size})"
                    // The site-gated CDN era: a batch whose IMAGE pages all
                    // ride the raw cdn hosts can never be displayed (their
                    // signed route needs a browser session the WebView-free
                    // chain cannot mint). The WebView mint that bought it
                    // was fruitless. Map pages are NOT subject to this: a
                    // reconstruction map composes from the site's own
                    // freshly-signed piece urls, no raw-host fetch at all.
                    // The page-list partition drops raw pages downstream.
                    if (freshImages.isNotEmpty() && freshMaps.isEmpty() &&
                        freshImages.none { isAppHostImageUrl(it) }
                    ) {
                        mintFruitless++
                        if (mintFruitless >= MINT_FRUITLESS_LIMIT && mintPausedUntil == 0L) {
                            mintPausedUntil = System.currentTimeMillis() + MINT_COOLDOWN_MS
                            trail += "$MINT_FRUITLESS_LIMIT minted batches carried only browser-gated pages — solver cooling down for ${MINT_COOLDOWN_MS / 60000} min"
                        }
                        trail += "the batch's pages all ride the browser-only signed CDN route — ending the walk"
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    // Refresh between slices so the server's cursor
                    // advances (the reader renews its token before every
                    // load). A failed renewal is not fatal. One more
                    // exchange with the current token may still advance a
                    // session-keyed cursor.
                    renewForNextSlice()?.let { token = it }
                }

                DeferredOutcome.Kind.DONE -> {
                    trail += outcome.note
                    trail += "the deferred list is complete (${collected.size + collectedMaps.size} pages beyond the public embed)"
                    return WalkResult(collected, trail, true, collectedMaps, pass)
                }

                DeferredOutcome.Kind.TURNSTILE -> {
                    trail += outcome.note
                    if (readerSignal != null && !fallbackSignalTried) {
                        fallbackSignalTried = true
                        readerSignal = READER_SIGNAL_FALLBACK
                        trail += "the primary reader signal was rejected — retrying the exchange with the fallback signal"
                        continue
                    }
                    readerSignal = null
                    if (!readerSessionEnabled) {
                        trail += if (collected.isEmpty() && collectedMaps.isEmpty()) {
                            "reader-gate solving is opt-in (Browser page walk) — keeping the public pages (the maps tier stays on the cover road)"
                        } else {
                            "reader-gate solving is opt-in (Browser page walk) — shipping ${collected.size + collectedMaps.size} deferred pages"
                        }
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    if (mints >= MAX_TURNSTILE_MINTS) {
                        trail += if (collected.isEmpty() && collectedMaps.isEmpty()) {
                            "a minted turnstile token did not clear the gate — keeping the public pages"
                        } else {
                            "the gate re-armed after ${collected.size + collectedMaps.size} deferred pages (the widget token is single-use per exchange) — shipping what was fetched"
                        }
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    if (System.currentTimeMillis() > walkDeadline) {
                        trail += "deferred walk wall reached before the mint — shipping ${collected.size + collectedMaps.size} deferred pages"
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    mints++
                    if (System.currentTimeMillis() < mintPausedUntil) {
                        trail += "turnstile solver cooling down after fruitless mints — shipping ${collected.size + collectedMaps.size} deferred pages"
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    trail += "minting the widget token offscreen (mint $mints/${MAX_TURNSTILE_MINTS}, mode=${outcome.mode ?: "hidden"})…"
                    val (siteKey, siteKeyNote) = fetchTurnstileGateConfig(referer)
                    trail += siteKeyNote
                    val solved = runCatching {
                        TurnstileSolver.solve(
                            applicationContext,
                            siteKey,
                            outcome.mode,
                            ProComicImageServer.BROWSER_UA,
                        )
                    }
                        .onFailure { trail += "turnstile solver crashed safely: ${it.javaClass.simpleName}" }
                        .getOrNull()
                    if (solved == null) {
                        mintFruitless++
                        if (mintFruitless >= MINT_FRUITLESS_LIMIT && mintPausedUntil == 0L) {
                            mintPausedUntil = System.currentTimeMillis() + MINT_COOLDOWN_MS
                            trail += "${MINT_FRUITLESS_LIMIT} fruitless mint ladders — solver cooling down for ${MINT_COOLDOWN_MS / 60000} min"
                        }
                        trail += "turnstile solver returned no token — keeping the public pages"
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    mintFruitless = 0
                    turnstileToken = solved.first
                    // Reader parity: the visible-mode widget is rendered
                    // from the login sitekey, so the header is "login".
                    turnstileWidget = "login"
                    trail += "turnstile token minted offscreen (mode=${outcome.mode ?: "hidden"}) — retrying the deferred exchange"
                }

                DeferredOutcome.Kind.TOKEN_EXPIRED -> {
                    if (tokenRenewals >= MAX_TOKEN_RENEWALS) {
                        trail += outcome.note
                        trail += "deferred renewals exhausted — shipping ${collected.size + collectedMaps.size} deferred pages"
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    tokenRenewals++
                    val renewed = renewDeferredToken(chapterId, token, referer)
                    if (renewed == null) {
                        trail += "refresh POST failed — keeping the public pages"
                        return WalkResult(collected, trail, false, collectedMaps, pass)
                    }
                    trail += "refresh POST minted a new deferred token"
                    token = renewed
                }

                DeferredOutcome.Kind.PASS_EXPIRED -> {
                    if (passRenewals >= MAX_PASS_RENEWALS) {
                        trail += outcome.note
                        pass = null
                        latestImagePass = null
                        continue
                    }
                    passRenewals++
                    val renewed = pass?.let { renewImagePass(chapterId, it, referer) }
                    if (renewed == null) {
                        trail += "image-pass POST failed — retrying without the header"
                        pass = null
                        latestImagePass = null
                    } else {
                        trail += "image-pass POST minted a new pass"
                        pass = renewed
                        latestImagePass = renewed
                    }
                }

                DeferredOutcome.Kind.UNREACHABLE -> {
                    trail += outcome.note
                    if (collected.isEmpty() && collectedMaps.isEmpty()) {
                        return WalkResult(emptyList(), trail, false)
                    }
                    trail += "deferred exchanges stopped — shipping ${collected.size + collectedMaps.size} deferred pages"
                    return WalkResult(collected, trail, false, collectedMaps, pass)
                }
            }
        }
        trail += "deferred page bound reached — shipping ${collected.size + collectedMaps.size} deferred pages"
        return WalkResult(collected, trail, false, collectedMaps, pass)
    }

    private data class WalkResult(
        val images: List<String>,
        val trail: List<String>,
        val complete: Boolean,
        val maps: List<ChapterMapTokenDto> = emptyList(),
        val imagePass: String? = null,
    )

    private class DeferredOutcome(
        val kind: Kind,
        val note: String,
        val mode: String? = null,
        val maps: List<ChapterMapTokenDto> = emptyList(),
        val pass: String? = null,
        val mapDelivery: String? = null,
        val pieceRenderMode: String? = null,
    ) {
        enum class Kind { OK, DONE, TURNSTILE, TOKEN_EXPIRED, PASS_EXPIRED, UNREACHABLE }
    }

    private fun deferredAttempt(
        chapterId: Int,
        token: String,
        pass: String?,
        referer: String,
        split: Int?,
        turnstileToken: String?,
        turnstileWidget: String?,
        readerSignal: String?,
    ): Pair<List<String>, DeferredOutcome> {
        var lastNote = "no apex answered"
        for (apex in readerApexes) {
            val host = apex.substringAfter("//")
            try {
                val url = "$apex/chapter-deferred-media/$chapterId"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("token", token)
                    .apply {
                        readerSignal?.let { addQueryParameter("readerSignal", it) }
                        split?.let { addQueryParameter("split", it.toString()) }
                    }
                    .build()
                    .toString()
                val request = GET(url, headers).newBuilder()
                    .header("Accept", "application/json")
                    .header("Referer", referer)
                    .apply {
                        pass?.let { header("x-chapter-pass", it) }
                        turnstileToken?.let { header("x-turnstile-token", it) }
                        turnstileWidget?.let { header("x-turnstile-widget", it) }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastNote = "HTTP ${response.code} from $host: ${errorNote(bodyText)}"
                        when {
                            response.code == 403 && bodyText.contains("chapter_pass_expired", ignoreCase = true) ->
                                return emptyList<String>() to
                                    DeferredOutcome(DeferredOutcome.Kind.PASS_EXPIRED, lastNote)

                            response.code == 403 && bodyText.contains("Turnstile", ignoreCase = true) ->
                                return emptyList<String>() to DeferredOutcome(
                                    DeferredOutcome.Kind.TURNSTILE,
                                    "the gate answers '${errorNote(bodyText)}' — the reader-signal ladder walks " +
                                        "next (fallback signal, then the mint the site's own reader no longer runs) ($host)",
                                    parseDeferredTurnstileMode(bodyText),
                                )

                            response.code == 403 && bodyText.contains("invalid token", ignoreCase = true) ->
                                return emptyList<String>() to
                                    DeferredOutcome(DeferredOutcome.Kind.TOKEN_EXPIRED, lastNote)
                        }
                        return@use
                    }
                    val answer = parseDeferredAnswer(bodyText)
                    if (answer != null) {
                        val images = answer.images
                            .map { it.trim() }
                            .filter { it.startsWith("https://") && isReaderImageUrl(it) }
                            .distinct()
                        val maps = answer.maps.filter { entry ->
                            !entry.token.isNullOrBlank() &&
                                (
                                    entry.method == null ||
                                        entry.method == "browser" ||
                                        entry.method == "browser_session"
                                    )
                        }
                        if (images.isEmpty() && maps.isEmpty()) {
                            return emptyList<String>() to DeferredOutcome(
                                DeferredOutcome.Kind.DONE,
                                "HTTP 200 from $host with empty images[] and maps[] — the deferred list is complete",
                            )
                        }
                        val delivery = answer.mapDelivery
                        return images to DeferredOutcome(
                            DeferredOutcome.Kind.OK,
                            buildString {
                                append("deferred media answered via $host: ${images.size} additional pages")
                                if (maps.isNotEmpty()) {
                                    append(" + ${maps.size} reconstruction maps")
                                    if (delivery != null) append(" (mapDelivery=$delivery)")
                                }
                            },
                            maps = maps,
                            pass = answer.pass?.takeIf { it.startsWith("p1.") },
                            mapDelivery = delivery,
                            pieceRenderMode = answer.pieceRenderMode?.takeIf { it == "img" },
                        )
                    }
                    if (hasDeferredImagesArray(bodyText)) {
                        return emptyList<String>() to DeferredOutcome(
                            DeferredOutcome.Kind.DONE,
                            "HTTP 200 from $host with an empty images[] — the deferred list is complete",
                        )
                    }
                    lastNote = "HTTP 200 from $host without a usable payload"
                    return@use
                }
            } catch (e: IOException) {
                lastNote = "$host: ${e.message ?: "connection failed"}"
            } catch (t: Throwable) {
                lastNote = "$host: ${t.javaClass.simpleName}"
            }
        }
        return emptyList<String>() to DeferredOutcome(DeferredOutcome.Kind.UNREACHABLE, lastNote)
    }

    internal fun parseDeferredAnswer(bodyText: String): DeferredAnswerDto? {
        if (bodyText.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(bodyText).jsonObject
            val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
            json.decodeFromString(DeferredAnswerDto.serializer(), data.toString())
        }.getOrNull()
    }

    /** `{data:{images:[…]}}` (or the same shape top-level). The reader
     * appends these after the public pages. Strict about what may ride
     * further: absolute https URLs on the site's own image hosts. */
    internal fun parseDeferredImages(bodyText: String): List<String> {
        if (bodyText.isBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(bodyText).jsonObject
            val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
            val array = data["images"] as? JsonArray ?: return@runCatching emptyList()
            array.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                .filter { it.startsWith("https://") && isReaderImageUrl(it) }
                .distinct()
        }.getOrDefault(emptyList())
    }

    internal fun hasDeferredImagesArray(bodyText: String): Boolean = runCatching {
        val root = json.parseToJsonElement(bodyText).jsonObject
        val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
        data["images"] is JsonArray
    }.getOrDefault(false)

    internal fun parseDeferredTurnstileMode(bodyText: String): String? = runCatching {
        val mode = json.parseToJsonElement(bodyText)
            .jsonObject["turnstileMode"]?.jsonPrimitive?.content?.trim()?.lowercase()
        mode?.takeIf { it == "hidden" || it == "visible" }
    }.getOrNull()

    /** The live Turnstile gate config from GET /api/auth/public-settings:
     * `{"loginTurnstile":{"enabled":true,"siteKey":"0x…"}}`. The reader
     * renders its widget from this key. The
     * challenge URL sitekey equals this field, and it differs from the
     * key baked into older page chunks). Returns (siteKey|null, note).
     * siteKey null means every apex failed. The solver falls back to
     * its baked-in reader key. */
    internal fun parseTurnstileSiteKey(bodyText: String): String? = runCatching {
        val root = json.parseToJsonElement(bodyText).jsonObject
        val login = root["loginTurnstile"] as? kotlinx.serialization.json.JsonObject
            ?: return@runCatching null
        (login["siteKey"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrDefault(null)

    private fun fetchTurnstileGateConfig(referer: String): Pair<String?, String> {
        for (apex in readerApexes) {
            val host = apex.substringAfter("//")
            try {
                val request = GET("$apex/api/auth/public-settings", headers).newBuilder()
                    .header("Accept", "application/json")
                    .header("Referer", referer)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val key = parseTurnstileSiteKey(response.body?.string().orEmpty())
                    if (key != null) {
                        return key to "turnstile sitekey fetched live from $host (loginTurnstile.siteKey, ${key.take(14)}…)"
                    }
                }
            } catch (_: IOException) {
            } catch (_: Throwable) {
            }
        }
        return null to "no public-settings sitekey answered — the solver falls back to the baked-in reader key"
    }

    /** A legible one-line summary of an error body for the diag trail. */
    private fun errorNote(bodyText: String): String {
        val json = runCatching {
            json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        if (!json.isNullOrBlank()) return json
        val compact = bodyText.replace('\n', ' ').trim()
        return if (compact.length > 80) compact.take(77) + "…" else compact.ifEmpty { "(empty body)" }
    }

    /** POST /chapter-deferred-media/{id}/refresh. The renewal endpoint
     * the site's reader uses when the deferred JWT expires mid-read
     * (200 {token} with just the session cookies). */
    private fun renewDeferredToken(chapterId: Int, token: String, referer: String): String? {
        for (apex in readerApexes) {
            val answer = postJson(
                url = "$apex/chapter-deferred-media/$chapterId/refresh",
                jsonBody = """{"token":"$token"}""",
                referer = referer,
            ) ?: continue
            if (answer.first != 200) continue
            val renewed = runCatching {
                json.parseToJsonElement(answer.second).jsonObject["token"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!renewed.isNullOrBlank()) return renewed
        }
        return null
    }

    /** POST /chapter-deferred-media/{id}/image-pass. Renews the
     * x-chapter-pass credential the same way. */
    private fun renewImagePass(chapterId: Int, pass: String, referer: String): String? {
        for (apex in readerApexes) {
            val answer = postJson(
                url = "$apex/chapter-deferred-media/$chapterId/image-pass",
                jsonBody = """{"pass":"$pass"}""",
                referer = referer,
            ) ?: continue
            if (answer.first != 200) continue
            val renewed = runCatching {
                json.parseToJsonElement(answer.second).jsonObject["pass"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!renewed.isNullOrBlank()) return renewed
        }
        return null
    }

    private fun postJson(url: String, jsonBody: String, referer: String, passHeader: String? = null): Pair<Int, String>? = runCatching {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("Referer", referer)
            .apply { passHeader?.let { header("x-chapter-pass", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }.getOrNull()

    internal fun planChapterMap(request: ProComicImageServer.MapPageRequest): ProComicImageServer.MapPagePlanResult? {
        val pass = latestImagePass
        val body = buildString {
            append("{\"token\":\"").append(request.token.replace("\\", "\\\\").replace("\"", "\\\\\""))
            append("\",\"method\":\"").append(request.method ?: "browser_session")
            if (!request.cdnPath.isNullOrBlank()) append("\",\"cdnPath\":\"").append(request.cdnPath)
            append("\",\"pageIndex\":").append(request.pageIndex)
            append("}")
        }
        for (apex in readerApexes) {
            val answer = postJson(
                url = "$apex/chapter-map-proxy-plan/${request.chapterId}",
                jsonBody = body,
                referer = request.referer,
                passHeader = pass,
            ) ?: continue
            if (answer.first != 200) continue
            val map = runCatching {
                val root = json.parseToJsonElement(answer.second).jsonObject
                val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
                val mapJson = (data["map"] as? kotlinx.serialization.json.JsonObject) ?: return@runCatching null
                json.decodeFromString(ChapterMapPlanDto.serializer(), mapJson.toString())
            }.getOrNull() ?: continue
            val width = (map.dim.getOrNull(0) ?: 0.0).toInt()
            val height = (map.dim.getOrNull(1) ?: 0.0).toInt()
            if (width <= 0 || height <= 0) continue
            if (map.pieces.isEmpty()) continue
            val rects = map.effectiveRects(width, height)
            if (rects.size < map.pieces.size) continue
            // The server draws slot i at rects[i]; the site's own renderer
            // draws pieces[order[i]] there. Hand it the DRAW-ORDERED
            // pieces (identity in the observed live shape).
            val drawPieces = map.drawOrder().map { map.pieces[it] }
            return ProComicImageServer.MapPagePlanResult(
                width = width,
                height = height,
                pieces = drawPieces,
                rects = rects.take(drawPieces.size),
            )
        }
        return null
    }

    private fun resignDeferredImageUrl(rejectedUrl: String): String? {
        val pass = latestImagePass ?: return null
        val raw = runCatching { rejectedUrl.toHttpUrl().queryParameter("url") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: rejectedUrl
        for (apex in readerApexes) {
            val answer = postJson(
                url = "$apex/api/cdn-image/sign",
                jsonBody = """{"url":"$raw"}""",
                referer = "$baseUrl/",
                passHeader = pass,
            ) ?: continue
            if (answer.first != 200) continue
            val obj = runCatching { json.parseToJsonElement(answer.second).jsonObject }.getOrNull() ?: continue
            val token = obj["token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: continue
            val expires = obj["expires"]?.jsonPrimitive?.content
            val es = obj["es"]?.jsonPrimitive?.content
            val host = obj["host"]?.jsonPrimitive?.content
            val signed = runCatching {
                (if (!host.isNullOrBlank()) "https://$host" else apex).toHttpUrl()
                    .newBuilder()
                    .addPathSegment("api")
                    .addPathSegment("cdn-image")
                    .addQueryParameter("url", raw)
                    .addQueryParameter("token", token)
                    .apply {
                        expires?.let { addQueryParameter("expires", it) }
                        es?.let { addQueryParameter("es", it) }
                    }
                    .build()
                    .toString()
            }.getOrNull() ?: continue
            return signed
        }
        return null
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_READER_SESSION
            title = "Browser page walk (opt-in)"
            summary = "OFF by default — chapter pages ride the cover pipeline " +
                "(server-side JPEG transcode; on Android 12+ the platform decoder " +
                "that already renders the covers). No browser ever opens. Turn ON " +
                "to allow the bounded offscreen browser road: the Turnstile token " +
                "mints plus one bounded page-walk session at chapter open (it " +
                "collects the pages the site's own reader renders), with a " +
                "breaker that pauses it after fruitless runs. Only flip this if " +
                "pages beyond the public embed stay error cards on the cover road."
            setDefaultValue(false)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SAFE_FETCH
            title = "Safe flight fetch (no challenge solver on listings)"
            summary = "Recommended. The flight fetch never spawns the library challenge " +
                "solver — a challenged answer fails legibly instead. (The reader-gate " +
                "solve above uses its own hardened, separately-switched surface.)"
            setDefaultValue(true)
        }.let(screen::addPreference)

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

        private const val READER_SIGNAL_PRIMARY = "reader-slot-script-v1"
        private const val READER_SIGNAL_FALLBACK = "zoom-placeholder-v2"

        const val MAX_DEFERRED_EXCHANGES = 14
        const val MAX_DEFERRED_PAGES = 200
        const val MAX_TOKEN_RENEWALS = 14
        const val MAX_PASS_RENEWALS = 2
        const val MAX_TURNSTILE_MINTS = 1

        const val MAX_WALK_WALL_MS = 90_000L

        /** A single letter keeps the search endpoint in its "trusted" mode
         * (no Turnstile) while matching nearly every English-titled series. */
        const val CATALOG_QUERY = "e"

        const val PREF_SHOW_TAGS = "pref_show_tags"
        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        const val PREF_PREFERRED_SCANLATORS = "pref_preferred_scanlators"

        const val PREF_SAFE_FETCH = "pref_safe_fetch"

        const val PREF_READER_SESSION = "pref_reader_session_optin_v142"

        /** The reader-session failure breaker: N consecutive sessions that
         * yielded nothing pause the session for the cooldown window. 
         * chapter opens stay fast and browser-free until it lapses. */
        const val SESSION_FAILURE_LIMIT = 3
        const val SESSION_COOLDOWN_MS = 30L * 60_000L

        const val MINT_FRUITLESS_LIMIT = 2
        const val MINT_COOLDOWN_MS = 30L * 60_000L

        val GENRE_FALLBACK = listOf(
            "Action", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi",
            "Fantasy", "Gender Bender", "Harem", "Historical", "Horror",
            "Josei", "Martial Arts", "Mecha", "Mystery", "Psychological",
            "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo",
            "Shounen", "Slice of Life", "Sports", "Supernatural", "Tragedy",
        )
    }
}

private class ProComicChallengeInterceptor(
    private val cookieHosts: Set<String>,
    private val safeMode: () -> Boolean,
) : Interceptor {

    /** The library solver only loads when the user opts out of safe
     * mode. Lazy keeps the safe path free of any WebView dependency. */
    private val webviewSolver: Interceptor by lazy { CloudflareSolverInterceptor(cookieHosts) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host !in cookieHosts) return chain.proceed(request)

        // Opt-out: the library behavior wholesale (proceed + detect +
        // solve + retry), unchanged. The user accepted the WebView risk.
        if (!safeMode()) return webviewSolver.intercept(chain)

        val response = chain.proceed(request)
        if (!isChallenge(response)) return response
        response.close()
        throw IOException(
            "ProComic: Cloudflare challenge on ${request.url.host} — " +
                "safe fetch keeps the page-count fetch WebView-free " +
                "(turn OFF 'Safe chapter fetch' in settings to solve it)",
        )
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503 && response.code != 429) return false
        if (response.header("cf-mitigated") == "challenge") return true
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isNotEmpty() && !contentType.contains("text/html", ignoreCase = true)) return false
        return runCatching {
            val body = response.peekBody(MAX_PEEK_BYTES).string()
            CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private companion object {
        const val MAX_PEEK_BYTES = 256L * 1024L

        val CHALLENGE_MARKERS = listOf(
            "Just a moment",
            "Attention Required",
            "cf_chl_opt",
            "cf-challenge",
            "challenge-platform",
            "challenge-error-text",
        )
    }
}
