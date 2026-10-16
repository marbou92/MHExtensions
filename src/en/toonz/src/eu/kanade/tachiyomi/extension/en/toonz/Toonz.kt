package eu.kanade.tachiyomi.extension.en.toonz

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Toonz (toonz.to).
 *
 * Next.js App Router site:
 * - browse: server-rendered /manga|/manhwa|/adult/browse cards (?sort=&page=),
 * - search: GET /api/search?q= (JSON),
 * - details: series page with a rich JSON-LD payload (author, status,
 *   aggregateRating, country) plus the RSC flight data (comicId, catalog),
 * - chapters: GET /api/comics/{comicId}/chapters (full JSON list),
 * - pages: the reader chapter page server-renders <img> tags pointing at
 *   /uploads/chapters/... (plain HTTP, no WebView needed).
 *
 * Cloudflare (v72): the site now serves a managed challenge (403,
 * cf-mitigated: challenge) on EVERY endpoint - browse, /api/search, the
 * chapters API and even cover/reader images. The window-attached WebView
 * solver clears it once; cf_clearance then lasts ~a year, so the challenge
 * cost is a single one-time WebView pass per installation. toonx.to (the
 * adult catalog host the old entries still point at) 301s into toonz.to
 * but is kept in the solver's domain set so a challenge on the redirect
 * source itself can be solved too.
 */
@Source
abstract class Toonz :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(CloudflareSolverInterceptor(setOf("toonz.to", "toonx.to")))
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    /** Series page path of the entry currently being processed, used to
     * build chapter and deeplink URLs (the chapters API has no series slug). */
    private var currentSeriesPath: String = ""

    // ========================= Popular & Latest ==========================

    override fun popularMangaRequest(page: Int) = browseRequest(page, "popular")

    override fun popularMangaParse(response: Response) = browseParse(response)

    override fun latestUpdatesRequest(page: Int) = browseRequest(page, "latest")

    override fun latestUpdatesParse(response: Response) = browseParse(response)

    private fun browseRequest(page: Int, sort: String): Request = GET("$baseUrl/${catalogPref()}/browse?sort=$sort&page=$page", headers)

    private fun browseParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val section = response.request.url.pathSegments.firstOrNull() ?: "manhwa"
        val mangas = document.select("a.block[href^=/$section/]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (!SERIES_PATH.matches(href)) return@mapNotNull null
                SManga.create().apply {
                    url = href
                    title = a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                        .ifBlank { href.substringAfterLast('/').replace('-', ' ') }
                    thumbnail_url = a.selectFirst("img")?.attr("abs:src")
                        ?.takeIf { it.contains("/uploads/covers/") }
                }
            }
            .distinctBy { it.url }

        if (mangas.isEmpty()) {
            // The adult catalog now 301s to toonx.to, whose browse grid renders
            // client-side - no SSR cards. The RSC flight payload still carries
            // every card, so recover them from there.
            return toonxFlightParse(document)
        }
        return MangasPage(mangas, mangas.size >= 12)
    }

    /** Parses the toonx.to RSC flight data into browse cards.
     *
     * Card shape in the payload (after unescaping): each card starts with
     * `["$","div","<comicId>",{"className":"group relative"` and contains
     * `"href":"/comic/<slug>","className":"block"`, a cover at
     * `"src":"/uploads/covers/<slug>_thumb.webp"` and the title as the h3's
     * `"children":"<title>"`. */
    private fun toonxFlightParse(document: Document): MangasPage {
        val flight = document.select("script").joinToString("\n") { it.data() }
            .replace("\\\"", "\"")
        val anchor = Regex("""\["\$","div","(\d+)",\{"className":"group relative"""")
        val marks = anchor.findAll(flight).toList()

        val mangas = marks.mapIndexedNotNull { index, match ->
            val start = match.range.last + 1
            val end = marks.getOrNull(index + 1)?.range?.first ?: flight.length
            val card = flight.substring(start, end)

            val slug = Regex("\"href\":\"/comic/([a-z0-9-]+)\",\"className\":\"block\"")
                .find(card)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val title = Regex("\"h3\",null,[{]\"className\":\"mt-1\\.5[^\"]*\",\"children\":\"([^\"]+)\"")
                .find(card)?.groupValues?.get(1)
                ?: slug.replace('-', ' ')
            val cover = Regex("\"src\":\"/uploads/covers/([^\"]+)\"")
                .find(card)?.groupValues?.get(1)

            SManga.create().apply {
                // Absolute url: adult entries live on toonx.to under /adult/.
                url = "https://toonx.to/adult/$slug#${match.groupValues[1]}"
                this.title = htmlUnescape(title).trim()
                thumbnail_url = cover?.let { "https://toonx.to/uploads/$it" }
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, mangas.size >= 12)
    }

    private fun htmlUnescape(text: String): String = org.jsoup.parser.Parser.unescapeEntities(text, false)

    // ============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            return deeplinkHandler(query)
        }
        if (query.isNotBlank()) {
            return client.newCall(GET("$baseUrl/api/search?q=${query.trim()}", headers))
                .asObservableSuccess()
                .map { it.parseAs<SearchResponse>().toMangasPage() }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = browseRequest(page, filters.firstInstanceOrNull<SortSelect>()?.selected ?: "popular")

    override fun searchMangaParse(response: Response) = browseParse(response)

    private fun deeplinkHandler(query: String): Observable<MangasPage> {
        val path = query.toHttpUrl().encodedPath
        if (!SERIES_PATH.matches(path)) return Observable.just(MangasPage(emptyList(), false))
        return client.newCall(GET("$baseUrl$path", headers))
            .asObservableSuccess()
            .map { MangasPage(listOf(mangaDetailsParse(it)), false) }
    }

    // ============================== Details ==============================

    /** Series urls may be absolute (adult entries live on toonx.to). */
    private fun seriesUrl(seriesPath: String): String = if (seriesPath.startsWith("http")) seriesPath else "$baseUrl$seriesPath"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(seriesUrl(manga.url.substringBefore('#')), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val rawHtml = document.outerHtml()

        val ldJson = document.select("script[type=application/ld+json]")
            .map(Element::data)
            .firstOrNull { it.contains("aggregateRating") || it.contains("creativeWorkStatus") }
        var author: String? = null
        var statusText: String? = null
        var ratingValue: Double? = null
        var ratingCount: Int? = null
        var country: String? = null
        if (ldJson != null) {
            runCatching {
                val ld = ldJson.parseAs<BookSchema>()
                author = ld.author?.mapNotNull { it.name }?.joinToString(", ")?.ifBlank { null }
                statusText = ld.creativeWorkStatus
                ratingValue = ld.aggregateRating?.ratingValue
                ratingCount = ld.aggregateRating?.ratingCount
                country = ld.countryOfOrigin?.name
            }
        }

        // RSC flight data: comicId (needed by the chapters API). The site now
        // exposes it as data-comic-id on the page root; the flight payload is
        // kept as a fallback.
        val comicId = Regex("""data-comic-id="(\d+)"""").find(rawHtml)?.groupValues?.get(1)
            ?: Regex(""""comicId":(\d+),""").find(rawHtml)?.groupValues?.get(1)

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()

        // Genres from the genre tab links.
        val genres = document.select("a[href^=/genre/]").mapNotNull {
            it.text().trim().takeIf(String::isNotBlank)
        }.distinct()

        val synopsis = document.selectFirst("p.mt-2")?.text()?.trim().orEmpty()
            .ifBlank {
                document.select("p").mapNotNull { it.text() }
                    .firstOrNull { it.length > 120 }
                    .orEmpty()
            }

        val cover = document.selectFirst("img[src*=/uploads/covers/]")?.attr("abs:src")
            ?: document.selectFirst("link[rel=preload][imageSrcSet*=covers]")?.attr("href")
                ?.substringBefore(' ')

        val latestChapter = Regex(""""latest":\{"id":\d+,"chapterNumber":"([0-9.]+)"""")
            .find(rawHtml)?.groupValues?.get(1)
            ?.trimEnd('0')?.trimEnd('.')

        val stars = ratingValue?.let { toStars(it) }
        val infoLine = if (showExtraInfoPref()) {
            buildList {
                country?.let { add("**Origin:** $it") }
                latestChapter?.takeIf { it.isNotBlank() }?.let { add("**Chapters:** $it") }
                ratingCount?.takeIf { it > 0 }?.let { add("**$it ratings**") }
                statusText?.let {
                    add("**Status:** ${it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }}")
                }
            }.joinToString(" · ")
        } else {
            ""
        }

        val desc = buildString {
            val position = scorePositionPref()
            if (position == "top" && stars != null) {
                append(stars, "\n")
                if (infoLine.isNotEmpty()) append(infoLine, "\n\n")
            }
            if (position == "none" && infoLine.isNotEmpty()) append(infoLine, "\n\n")

            append(synopsis)

            if (showAltNamesPref()) {
                val alt = Regex(""""alternateName":"([^"]{2,120})"""").find(rawHtml)
                    ?.groupValues?.get(1)
                if (!alt.isNullOrBlank() && !alt.equals(title, ignoreCase = true)) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative names:\n")
                    alt.split(';').mapNotNull { it.trim().takeIf(String::isNotBlank) }
                        .forEach { append("• $it\n") }
                }
            }

            if (position == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
                if (infoLine.isNotEmpty()) append("\n", infoLine)
            }
        }.trim()

        val status = when (statusText?.lowercase(Locale.ROOT)) {
            "ongoing", "releasing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "canceled", "discontinued" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val path = response.request.url.encodedPath

        return SManga.create().apply {
            url = if (comicId != null) "$path#$comicId" else path
            this.title = title
            author = author
            artist = null
            genre = genres.joinToString(", ").ifBlank { null }
            this.status = status
            thumbnail_url = cover
            description = desc.ifBlank { null }
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String = seriesUrl(manga.url.substringBefore('#'))

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        currentSeriesPath = manga.url.substringBefore('#')
        // The chapters API needs the numeric comic id. NEVER depend on
        // mangaDetailsParse having run first: on the very first open from
        // Browse the url is a bare path (Mihon does not copy the details url
        // back onto the entry it fetches chapters with), which used to throw
        // "Open the manga details once to refresh this entry" - reported as
        // "chapter list not loading". Resolve the id from the series page
        // itself whenever the fragment is missing.
        val fragment = manga.url.substringAfter('#', "")
        val comicId = fragment.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: resolveComicId(currentSeriesPath)
            ?: throw Exception("Could not resolve the series id — open the manga details once and try again")
        // Both hosts share the same chapters API path.
        val apiHost = if (currentSeriesPath.startsWith("http")) {
            "https://" + currentSeriesPath.removePrefix("https://").substringBefore('/')
        } else {
            baseUrl
        }
        return GET("$apiHost/api/comics/$comicId/chapters", headers)
    }

    private fun resolveComicId(seriesPath: String): String? {
        val seriesResponse = client.newCall(GET(seriesUrl(seriesPath), headers)).execute()
        return seriesResponse.use { resp ->
            if (!resp.isSuccessful) return@use null
            val document = resp.asJsoup()
            document.selectFirst("[data-comic-id]")?.attr("data-comic-id")
                ?: Regex(""""comicId":(\d+),""").find(document.outerHtml())?.groupValues?.get(1)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesPath = currentSeriesPath
        if (seriesPath.isBlank()) {
            throw Exception("Open the manga details once to refresh this entry")
        }
        val data = response.parseAs<ChaptersResponse>()
        return data.chapters.map { dto ->
            SChapter.create().apply {
                // The slug IS the page segment ("chapter-12", "chapter-15.01") -
                // the reader page lives at {series}/{slug}. The old
                // "$seriesPath/chapter/{slug}" produced /chapter/chapter-12,
                // which 404s on the site and killed every page fetch.
                url = "$seriesPath/${dto.slug}"
                name = buildChapterName(dto)
                chapter_number = dto.chapterNumber.toFloatOrNull() ?: 0f
                date_upload = dto.date?.parseDate() ?: 0L
            }
        }
    }

    private fun buildChapterName(dto: ChapterDto): String = buildString {
        val number = dto.chapterNumber.trimEnd('0').trimEnd('.')
        append("Chapter ", number)
        dto.title?.takeIf(String::isNotBlank)?.let { append(": ", it) }
    }

    // =============================== Pages ===============================
    //
    // The reader server-renders only the first handful of <img> tags and
    // lazy-loads the rest, and the site's own pageCount metadata overshoots
    // (its value would produce dead links). So: take the image URL pattern
    // from the embedded tags, then binary-probe how far the file sequence
    // actually goes (~10 HEAD requests per chapter, not one per page).

    override fun pageListRequest(chapter: SChapter): Request = if (chapter.url.startsWith("http")) GET(chapter.url, headers) else GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val embedded = (
            document.select("img[src^=/uploads/chapters/]").map { it.attr("src") } +
                document.select("link[rel=preload][href^=/uploads/chapters/]").map { it.attr("href") }
            )
            .map { it.substringBefore(' ') }
            .distinct()
            .sorted()
        if (embedded.isEmpty()) throw Exception("No page images found — the chapter may be unavailable")

        val first = embedded.first()
        val prefix = first.substringBeforeLast('/') + '/'
        val ext = first.substringAfterLast('.', "webp")
        val firstIndex = first.substringAfterLast('/').substringBeforeLast('.').toIntOrNull() ?: 1
        val width = first.substringAfterLast('/').substringBeforeLast('.').length.coerceAtLeast(1)

        fun pageUrl(n: Int): String = "$prefix${n.toString().padStart(width, '0')}.$ext"

        fun exists(n: Int): Boolean = runCatching {
            // The network layer ships no HEAD helper - build the probe manually.
            val request = Request.Builder()
                .url(pageUrl(n))
                .headers(headers)
                .head()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        // Exponential probe for the first missing index, then binary descent.
        var hi = 1
        while (hi <= MAX_PAGES && exists(hi)) hi *= 2
        val last = if (hi > MAX_PAGES) {
            MAX_PAGES
        } else {
            var lo = hi / 2
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (exists(mid)) lo = mid else hi = mid
            }
            lo
        }

        if (last < firstIndex) {
            // Pattern probing found nothing beyond the embedded tags - use
            // those directly rather than showing an empty reader.
            return embedded.mapIndexed { index, url ->
                Page(index, imageUrl = if (url.startsWith("http")) url else "$baseUrl$url")
            }
        }

        val pageOrigin = response.request.url.let { "${it.scheme}://${it.host}" }
        return (1..last).mapIndexed { index, _ -> Page(index, imageUrl = "$pageOrigin${pageUrl(index + 1)}") }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================ Preferences ============================

    private fun catalogPref(): String = preferences.getString(PREF_CATALOG, "manhwa") ?: "manhwa"
    private fun showExtraInfoPref() = preferences.getBoolean(PREF_EXTRA_INFO, true)
    private fun showAltNamesPref() = preferences.getBoolean(PREF_ALT_NAMES, true)
    private fun scorePositionPref() = preferences.getString(PREF_SCORE_POSITION, "top") ?: "top"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_CATALOG
            title = "Browse section"
            summary = "%s"
            entries = arrayOf("Manhwa", "Manga", "Adult")
            entryValues = arrayOf("manhwa", "manga", "adult")
            setDefaultValue("manhwa")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Origin, chapter count and ratings below the score"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALT_NAMES
            title = "Show alternative names"
            summary = "Append alternative titles to the description when available"
            setDefaultValue(true)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score position"
            summary = "%s"
            entries = arrayOf("Top of description", "Bottom of description", "Hidden")
            entryValues = arrayOf("top", "end", "none")
            setDefaultValue("top")
        }.let(screen::addPreference)
    }

    // ============================= Helpers ===============================

    private fun toStars(rating: Double): String {
        val full = (rating / 2).toInt().coerceIn(0, 5)
        return "★".repeat(full) + "☆".repeat(5 - full) + " $rating"
    }

    private fun String?.parseDate(): Long = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.parse(this.orEmpty().substringBefore('.'))?.time ?: 0L
    }.getOrDefault(0L)

    companion object {
        /** Cap for the binary page-count probe (defensive: no chapter is 500 pages). */
        private const val MAX_PAGES = 500

        private val SERIES_PATH = Regex("""^/(manga|manhwa|adult)/[a-z0-9-]+$""")

        private const val PREF_CATALOG = "pref_catalog"
        private const val PREF_EXTRA_INFO = "pref_extra_info"
        private const val PREF_ALT_NAMES = "pref_alt_names"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}

// ================================ DTOs ==================================

@Serializable
internal class SearchResponse(
    val total: Int = 0,
    val results: List<SearchResult> = emptyList(),
) {
    fun toMangasPage() = MangasPage(
        mangas = results.map { r ->
            SManga.create().apply {
                url = "/${r.type}/${r.slug}"
                title = r.title
                thumbnail_url = r.coverThumb?.let { "https://toonz.to/uploads/$it" }
                author = r.authors?.joinToString(", ")?.ifBlank { null }
            }
        },
        hasNextPage = false,
    )
}

@Serializable
internal class SearchResult(
    val id: Long,
    val title: String,
    val slug: String,
    @kotlinx.serialization.SerialName("cover_thumb") val coverThumb: String? = null,
    val type: String = "manhwa",
    val authors: List<String>? = null,
)

@Serializable
internal class ChaptersResponse(val chapters: List<ChapterDto> = emptyList())

@Serializable
internal class ChapterDto(
    val id: Long,
    @kotlinx.serialization.SerialName("chapterNumber") val chapterNumber: String,
    val title: String? = null,
    val slug: String,
    @kotlinx.serialization.SerialName("pageCount") val pageCount: Int = 0,
    val date: String? = null,
)

@Serializable
internal class BookSchema(
    val author: List<SchemaPerson>? = null,
    @kotlinx.serialization.SerialName("creativeWorkStatus") val creativeWorkStatus: String? = null,
    @kotlinx.serialization.SerialName("countryOfOrigin") val countryOfOrigin: SchemaCountry? = null,
    @kotlinx.serialization.SerialName("aggregateRating") val aggregateRating: SchemaRating? = null,
)

@Serializable
internal class SchemaPerson(val name: String? = null)

@Serializable
internal class SchemaCountry(val name: String? = null)

@Serializable
internal class SchemaRating(
    @kotlinx.serialization.SerialName("ratingValue") val ratingValue: Double? = null,
    @kotlinx.serialization.SerialName("ratingCount") val ratingCount: Int? = null,
)

// =============================== Filters ================================

internal class SortSelect(state: Int = 0) :
    Filter.Select<String>(
        "Sort",
        arrayOf("Popular", "Latest", "Top rated", "Trending", "Most favorited"),
        state,
    ) {
    val selected: String
        get() = when (state) {
            1 -> "latest"
            2 -> "top-rated"
            3 -> "trending"
            4 -> "most-favorited"
            else -> "popular"
        }
}
