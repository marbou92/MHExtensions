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
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

        return MangasPage(mangas, mangas.size >= 12)
    }

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

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

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

        // RSC flight data: comicId (needed by the chapters API).
        val comicId = Regex(""""comicId":(\d+),""").find(rawHtml)?.groupValues?.get(1)
            ?: Regex("""data-comic-id="(\d+)"""").find(rawHtml)?.groupValues?.get(1)

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

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url.substringBefore('#')}"

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        currentSeriesPath = manga.url.substringBefore('#')
        val comicId = manga.url.substringAfter('#', "")
        if (comicId.isBlank() || !comicId.all(Char::isDigit)) {
            throw Exception("Open the manga details once to refresh this entry")
        }
        return GET("$baseUrl/api/comics/$comicId/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesPath = currentSeriesPath
        if (seriesPath.isBlank()) {
            throw Exception("Open the manga details once to refresh this entry")
        }
        val data = response.parseAs<ChaptersResponse>()
        return data.chapters.map { dto ->
            SChapter.create().apply {
                url = "$seriesPath/chapter/${dto.slug}"
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

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select("img[src^=/uploads/chapters/]")
        if (images.isEmpty()) throw Exception("No page images found — the chapter may be unavailable")
        return images.mapIndexed { index, img ->
            Page(index, imageUrl = img.attr("abs:src"))
        }
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
