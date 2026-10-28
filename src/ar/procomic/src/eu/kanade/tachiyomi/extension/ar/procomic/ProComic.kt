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
import okhttp3.OkHttpClient
import okhttp3.Request
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
 *   - pages (v101): the site moved to procomic.net and reworked the reader
 *     payload — every page now ships as a plain signed-filename AVIF in
 *     `appImages` (app.<host>), fetchable directly, and the old map-token
 *     protection (deferredMedia maps, /chapter-map-proxy-plan, session
 *     keys, the stitch server) is gone from the payload (`maps:[]`,
 *     `protectionV2:null`) and dead server-side (the .pro signing stack
 *     answers 403 forbidden — the device-side HTTP 502 wave). Pages are
 *     served through the loopback image server's /.image-proxy route
 *     (browser UA + AVIF→JPEG transcode) because host apps cannot be
 *     relied on to send browser headers or decode AVIF below Android 12.
 *   - pages (v105): the site is DUAL-HOMED — most series migrated to
 *     procomic.net, but a share of the catalog still lives on procomic.pro
 *     (their .net reader route 308s to the series page with no payload;
 *     the .pro route carries it, with appImages on app.procomic.pro). The
 *     reader fetch falls back across both apexes.
 *   - covers (v105): the search API switched a wave of series to RELATIVE
 *     thumbnail paths (`/224/image_series/...avif`) that only resolve on
 *     the row's cdn{N} host, and every cover is AVIF on a widening host
 *     pool (cdn{1-4}.procomic.net, app.procomic.net, app.procomic.pro,
 *     app.prochan.net). Covers now resolve through resolveCoverUrl() and
 *     ride the same loopback proxy as pages: browser UA (the raw cdn hosts
 *     403 app clients) + AVIF→JPEG transcode (Android 10/11 cannot decode
 *     AVIF) + the result cache. The proxy binds a FIXED preferred port so
 *     persisted thumbnail_url values survive process restarts.
 *   - covers/pages v106: the bundled software decoder (libavif/dav1d) is
 *     CRASH-LOOP-GATED — a native crash inside the JNI takes the whole host
 *     app down (1.6.19 crashed at chapter load, 1.6.20 at cover load, both
 *     on Android 10 arm64), so every native decode writes an attempt marker
 *     first and a stale marker at process start disables the tier (this
 *     session, permanently after two). Native output is ARGB_8888 only
 *     (the RGBA_F16 10-bit path was the crash surface) and images over ~25
 *     MP are refused before allocation. Covers additionally request a
 *     downscaled variant (?e=1280) so an "originals" cover never becomes a
 *     ~90 MB bitmap, and the result cache is byte-budgeted (32 MB).
 *   - pages/covers v107: the loader root cause is fixed — v104–v106 handed
 *     the server the HOST app's apk (there is no per-extension Context),
 *     so the bundled libavif_android.so was never actually found on any
 *     device and Android 10/11 stayed on the error page. The path is now
 *     the extension's own installed apk from the package manager, the
 *     loader scans every candidate (pm path + classloader dex paths) for
 *     the .so entry before loading, and the diag trail names each scanned
 *     zip with its per-file outcome.
 *   - pages/covers v108: the MediaCodec "video/av1" framework tier — and
 *     the field verdict: the host app CRASHED on every cover and page
 *     load. Android 10's brand-new c2.av1 software codec, configured at a
 *     still image's full size (pages 800x5000, "originals" covers to
 *     4000x6000), is a native-crash/OOM-kill surface no Java guard can
 *     catch; the marker breaker only gates after two process deaths.
 *   - pages/covers v109: the MediaCodec tier is REMOVED from the ladder
 *     and replaced with a WebView decode tier — the host's Chromium
 *     engine (AVIF since Chrome 85; Play auto-updates the WebView package
 *     even on Android 10) decodes the AVIF offscreen (data: URL on the
 *     main looper, JS size probe, draw into a software bitmap) and every
 *     failure mode is survivable: caught Java exceptions, bounded
 *     timeouts, or a sandboxed-renderer death that leaves the app alive.
 *     Ladder: BitmapFactory (12+) -> libavif (loader) -> WebView (24+)
 *     -> ImageDecoder (28+).
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

    // lazy: the server reads baseUrl, which the @Source-generated subclass
    // supplies via computed getters (safe at any phase) — lazy keeps the
    // construction order a non-issue either way. internal: the JVM harness
    // drives the verification probes on it; harmless in the shipped build.
    internal val imageServer by lazy {
        ProComicImageServer(
            baseUrl = baseUrl,
            // The source's own client as the retry tier: when the server's
            // bare inner client cannot get an image through (region-
            // dependent edge behavior), the fetch is retried with the
            // session the reader page established — cookies, CF clearance,
            // the forced browser UA.
            sessionClient = client,
            // v111: a writable cache dir for the crash-loop breaker's
            // best-effort marker files. The decode ladder no longer needs
            // an APK path (the bundled .so is gone) or a context (the
            // WebView tier is gone) — the AVIF transcode happens SERVER-
            // SIDE through the weserv image proxy, so on Android 10/11
            // NO AVIF decoder ever runs in this process. Every tier that
            // could kill the host app (libavif JNI, MediaCodec, the
            // offscreen WebView) is removed; the worst case is the
            // legible error card, never a crash.
            nativeCacheDir = applicationContext.cacheDir,
        )
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

    private fun seriesPageUrl(mangaUrl: String): String = "$baseUrl/ar/series/$mangaUrl"

    private fun chapterPageUrl(ref: ChapterRef): String = "$baseUrl/ar/chapter/${ref.seriesSlug}-${ref.chapterNumber}-${ref.chapterId}"

    /**
     * The reader/series routes exist on BOTH deployments (v105): the site
     * is dual-homed — most series migrated to procomic.net, but a share of
     * the catalog (series 224 "On a Deserted Island..." among them) still
     * lives on procomic.pro, where the .net reader route 308s to the series
     * page instead of carrying the flight payload. Derived from baseUrl so
     * it tracks whichever apex the build targets.
     */
    private val readerApexes: List<String> by lazy {
        val base = baseUrl.trimEnd('/')
        val host = base.substringAfter("//")
        val alt = when {
            host.startsWith("www.") -> null
            host.endsWith(".net") -> host.removeSuffix(".net") + ".pro"
            host.endsWith(".pro") -> host.removeSuffix(".pro") + ".net"
            else -> null
        }
        listOfNotNull(base, alt?.let { "https://$it" }).distinct()
    }

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
        }
        throw lastError ?: IOException("ProComic: reader unreachable for $path")
    }

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
        genre = type?.let { it.replaceFirstChar { c -> c.uppercase() } }
        status = this@toSManga.status.toProComicStatus()
        initialized = true
    }

    /**
     * Covers ride the same loopback transcoder as pages (v105): every cover
     * the site ships is AVIF — undecodable below Android 12 — and the raw
     * cdn{N} hosts 403 non-browser clients. The proxy fetches with the
     * browser UA and serves plain JPEG, so covers render (fast) on every
     * device and in every host app. Falls back to the raw URL if the local
     * server cannot start; the server's fixed preferred port keeps the
     * persisted thumbnail_url values valid across app restarts.
     */
    private fun coverProxyUrl(resolved: String): String = runCatching { imageServer.imageProxyUrl(resolved, ProComicImageServer.COVER_PROXY_MAX_EDGE) }.getOrDefault(resolved)

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
        thumbnail_url = resolveCoverUrl(
            coverImageApp = this@toSManga.coverImageApp,
            coverImage = this@toSManga.coverImage,
            thumbnail = this@toSManga.thumbnail,
            cdnPath = cdn_path,
        )?.let(::coverProxyUrl)
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

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/ar/series/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        // Both apexes (and their www forms) — the site migrated .pro → .net
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
                // /ar/series/{seriesSlug}-{id} — the canonical series path
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
     * Either apex may carry the chapter (dual-homed site, v105).
     */
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
        val readerPath = "/ar/chapter/${ref.seriesSlug}-${ref.chapterNumber}-${ref.chapterId}"
        // images[] is the site's own page list (cdn.<host> raw files);
        // appImages[] mirrors it with the signed-filename app.<host>
        // variants the reader actually loads — the only URLs the edge
        // serves to non-browser clients since the .net migration. Either
        // apex may carry the chapter: the payload comes from whichever
        // deployment hosts this series (the other 308s to the series page).
        val upstreams = fetchFlightAcrossApexes(
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
                // Prefer the app variant per page (verified live: 200 for
                // plain clients, the signature rides in the file name); the
                // raw CDN URL is the fallback (the proxy's session retry is
                // its last chance, the edge 403s bare TLS on those hosts).
                List(maxOf(images.size, appImages.size)) { index ->
                    appImages.getOrNull(index) ?: images[index]
                }
            }
        }

        val pages = upstreams.mapIndexed { index, upstream ->
            Page(
                index = index,
                url = "$baseUrl$readerPath",
                // v106: if the loopback server cannot start at all, fall
                // back to the raw upstream URL rather than failing the
                // chapter — the app hosts serve plain clients fine.
                imageUrl = runCatching { imageServer.imageProxyUrl(upstream) }.getOrDefault(upstream),
            )
        }

        // v101: warm the transcode cache in the background so the
        // reader's own fetches land on finished JPEGs (the old
        // protected-page preloader, re-targeted at direct app images).
        imageServer.preloadImages(ref.chapterId.toString(), upstreams)
        return pages
    }

    /** The page list embedded in the reader flight: `"images":[…]` arrays
     * (the exact props the site's reader component consumes) with any
     * https URL on a site-owned host kept. Since the .net migration these
     * are `cdn{1..4}.procomic.net/{series}/{chapter}/{file}.avif` raw
     * files — 403 to non-browser clients, so they only feed the appImages
     * fallback below. */
    private fun parseReaderImages(flight: String): List<String> {
        val urls = mutableListOf<String>()
        val key = "\"images\":["
        var index = 0
        while (true) {
            index = flight.indexOf(key, index)
            if (index < 0) break
            // arrayStart must point AT the '[' — balancedArray refuses to
            // scan otherwise. (The pre-v101 code pointed one past it, which
            // only ever matched the old nested per-page shape and silently
            // dropped the flat page list the .net flight ships.)
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
                        // URLs per page — flatten, order preserved.
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

    /** Any https URL on a site-owned image host. The app-CDN pool grew in
     * v105: pages/covers ride app.procomic.net, app.procomic.pro AND
     * app.prochan.net (the parent brand serves a share of the catalog's
     * images), with raw files on cdn{1..4}.procomic.net. */
    private fun isReaderImageUrl(url: String): Boolean = runCatching {
        val httpUrl = url.toHttpUrl()
        httpUrl.isHttps && (
            httpUrl.host == "procomic.net" ||
                httpUrl.host == "www.procomic.net" ||
                httpUrl.host == "procomic.pro" ||
                httpUrl.host == "www.procomic.pro" ||
                httpUrl.host == "prochan.net" ||
                httpUrl.host == "www.prochan.net" ||
                httpUrl.host.endsWith(".procomic.net") ||
                httpUrl.host.endsWith(".procomic.pro") ||
                httpUrl.host.endsWith(".prochan.net")
            )
    }.getOrDefault(false)

    /** The reader's own `<img>` sources: `"appImages":[{mobile,desktop}]` —
     * signed-filename AVIF URLs on `app.<host>` that fetch directly from
     * any client (verified live: 200 for plain okhttp/curl TLS on every
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
