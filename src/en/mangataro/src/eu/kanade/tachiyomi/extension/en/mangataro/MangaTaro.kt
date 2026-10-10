package eu.kanade.tachiyomi.extension.en.mangataro

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import rx.Observable
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * MangaTaro (mangataro.org).
 *
 * WordPress site running the "mangapeak" theme (MangaTaro theme family):
 * - browse/filter list: POST /wp-json/manga/v1/load with JSON string-encoded arrays
 *   ({"genres":"[...]"} — the theme runs json_decode on each field again),
 * - quick search: POST /auth/search {"query":..., "limit":25},
 * - details: the manga page (rich JSON-LD: status, type, author, rating,
 *   episode count) + /wp-json/wp/v2/manga/{id}?_embed (full synopsis,
 *   term genres, cover),
 * - chapters: /auth/manga-chapters with an MD5 window token
 *   (_t = md5("{ts}mng_ch_{yyyyMMddHH}")[0..16]),
 * - pages: /auth/chapter-content?chapter_id= (plain image list).
 */
@Source
abstract class MangaTaro :
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
        .set("Origin", baseUrl)

    // ========================= Popular & Latest ==========================

    override fun popularMangaRequest(page: Int) = loadRequest(page, "", SortFilter.POPULAR)

    override fun popularMangaParse(response: Response) = loadParse(response)

    override fun latestUpdatesRequest(page: Int) = loadRequest(page, "", SortFilter.LATEST)

    override fun latestUpdatesParse(response: Response) = loadParse(response)

    // ============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            return deeplinkHandler(query)
        }

        // The quick-search endpoint ignores filters and returns everything at
        // once; browsing with filters uses the load endpoint even with a term.
        val searchWithFilters = filters.firstInstanceOrNull<SearchWithFilters>()
        return if (query.isNotBlank() && searchWithFilters?.state == false) {
            querySearch(query)
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    private fun querySearch(query: String): Observable<MangasPage> = client.newCall(querySearchRequest(query))
        .asObservableSuccess()
        .map { it.parseAs<SearchResponse>().toMangasPage() }

    private fun querySearchRequest(query: String): Request {
        val body = SearchPayload(query = query.trim(), limit = 25)
            .toJsonString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return POST("$baseUrl/auth/search", headers, body)
    }

    private fun deeplinkHandler(query: String): Observable<MangasPage> {
        val slug = query.toHttpUrl().pathSegments.filter(String::isNotBlank)
            .takeIf { it.size >= 2 && it[0] == "manga" }
            ?.get(1)
            ?: return Observable.just(MangasPage(emptyList(), false))

        return client.newCall(GET("$baseUrl/manga/$slug", headers))
            .asObservableSuccess()
            .map { response ->
                val id = response.asJsoup().body().attr("data-manga-id")
                if (id.isBlank()) throw Exception("Could not resolve manga id from the page")
                mangaDetailsParse(response, id)
            }
            .map { MangasPage(listOf(it), false) }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = loadRequest(
        page = page,
        query = query,
        sort = filters.firstInstance<SortFilter>().selected,
        types = filters.firstInstanceOrNull<TypeFilter>()?.selected.orEmpty(),
        statuses = filters.firstInstanceOrNull<StatusFilter>()?.selected.orEmpty(),
        genres = filters.firstInstanceOrNull<TagFilter>()?.checked.orEmpty(),
        matchAll = filters.firstInstanceOrNull<TagFilterMatch>()?.state ?: false,
    )

    override fun searchMangaParse(response: Response) = loadParse(response)

    // ========================= Browse (load API) =========================

    private fun loadRequest(
        page: Int,
        query: String,
        sort: String,
        types: List<String> = emptyList(),
        statuses: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        matchAll: Boolean = false,
    ): Request {
        // The theme double-decodes these fields: each array arrives as a
        // JSON-encoded STRING inside the JSON body.
        val body = LoadPayload(
            page = page,
            search = query.trim(),
            years = "[]",
            genres = genres.toJsonString(),
            types = types.toJsonString(),
            statuses = statuses.toJsonString(),
            sort = sort,
            genreMatchMode = if (matchAll) "all" else "any",
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)

        return POST("$baseUrl/wp-json/manga/v1/load", headers, body)
    }

    private fun loadParse(response: Response): MangasPage {
        val data = response.parseAs<List<BrowseManga>>()
        val mangas = data.filter { it.type != "Novel" && it.url.isNotBlank() }
            .map { it.toSManga() }
        return MangasPage(mangas, data.size >= 24)
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val dto = manga.url.parseAs<MangaUrl>()
        return GET("$baseUrl/manga/${dto.slug}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val id = response.asJsoup().body().attr("data-manga-id")
            .takeIf(String::isNotBlank)
            ?: throw Exception("Manga id missing from the page — refresh the entry URL")
        return mangaDetailsParse(response, id)
    }

    private fun mangaDetailsParse(response: Response, id: String): SManga {
        val document = response.asJsoup()

        // --- JSON-LD block: status, type, author, episode count, dates ----
        val ldJson = document.select("script[type=application/ld+json]")
            .map(Element::data)
            .firstOrNull { it.contains("\"@type\":\"Manga\"") || it.contains("numberOfEpisodes") }
        var ldAuthor: String? = null
        var ldGenre: String? = null
        var ldStatus: String? = null
        var ldEpisodes: Int? = null
        var ldYear: Int? = null
        if (ldJson != null) {
            runCatching {
                val ld = ldJson.parseAs<SchemaOrgManga>()
                ldAuthor = ld.author?.name?.takeIf(String::isNotBlank)
                ldGenre = ld.genre?.takeIf(String::isNotBlank)
                ldStatus = ld.status?.takeIf(String::isNotBlank)
                ldEpisodes = ld.numberOfEpisodes
                ldYear = ld.datePublished?.take(4)?.toIntOrNull()
            }
        }

        // --- /wp-json/wp/v2/manga/{id}: full synopsis, term genres, cover --
        val wp = runCatching {
            client.newCall(GET("$baseUrl/wp-json/wp/v2/manga/$id?_embed", headers))
                .execute()
                .use { it.parseAs<WpManga>() }
        }.getOrNull()

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { wp?.title?.rendered?.htmlUnescape().orEmpty() }

        val rating = document.selectFirst("span.rating-badge + span, span.rating-badge span.font-semibold")?.text()
            ?.trim()
            ?.toDoubleOrNull()
            ?: document.select("span.font-semibold").firstOrNull { it.text().matches(NUMBER_ONLY) }
                ?.text()?.trim()?.toDoubleOrNull()

        val altNames = document.selectFirst("h1 + p, div.flex-1 h1 + p")?.text()?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(title, true) }
            ?.split(" / ")
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .orEmpty()
            .ifEmpty {
                document.select("script[type=application/ld+json]")
                    .map(Element::data)
                    .firstOrNull { it.contains("\"alternateName\"") }
                    ?.let { data ->
                        runCatching { data.parseAs<SchemaOrgAlt>().alternateName }
                            .getOrNull()
                            ?.split(";", ",")
                            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                            .orEmpty()
                    }
                    .orEmpty()
            }

        val synopsisText = wp?.content?.rendered
            ?.let { Jsoup.parseBodyFragment(it).wholeText().trim() }
            ?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()

        val genres = buildList {
            wp?.embedded?.terms?.firstOrNull()?.mapNotNull { it.name?.trim() }
                ?.takeIf(List<Any>::isNotEmpty)
                ?.let(::addAll)
            if (isNotEmpty() && ldGenre != null && none { it.equals(ldGenre, true) }) {
                add(ldGenre!!)
            }
        }

        val author = wp?.embedded?.terms?.getOrNull(1)?.mapNotNull { it.name?.trim() }
            ?.filter(String::isNotBlank)
            ?.joinToString(", ")
            ?.ifBlank { null }
            ?: ldAuthor

        val statusText = (ldStatus ?: "unknown").lowercase(Locale.ROOT)
        val status = when {
            "ongoing" in statusText || "releasing" in statusText -> SManga.ONGOING
            "complete" in statusText || "finished" in statusText -> SManga.COMPLETED
            "hiatus" in statusText -> SManga.ON_HIATUS
            "cancel" in statusText || "dropped" in statusText -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val thumbnail = wp?.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?: document.selectFirst("img[src*=/content/media/]")?.attr("abs:src")

        val extraInfo = buildList {
            if (showExtraInfoPref()) {
                ldYear?.let { add("**Year:** $it") }
                ldEpisodes?.takeIf { it > 0 }?.let { add("**Chapters:** $it") }
                ldGenre?.let { add("**Type:** $it") }
                if (rating != null) {
                    val votes = document.select("a[href*=/home/manga/status/] span, a[href*=/home/manga/status/]").text()
                        .extractVotes()
                    if (votes > 0) add("**$votes ratings**")
                }
            }
        }.joinToString(" · ")

        val desc = buildString {
            val scorePosition = scorePositionPref()
            val stars = rating?.let { toStars(it) }
            if (scorePosition == "top" && stars != null) {
                append(stars, "\n")
                if (extraInfo.isNotEmpty()) append(extraInfo, "\n\n")
            }
            if (scorePosition == "none" && extraInfo.isNotEmpty()) {
                append(extraInfo, "\n\n")
            }

            append(synopsisText)

            if (showAltNamesPref() && altNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(altNames.joinToString("\n") { "• $it" })
            }

            if (scorePosition == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
                if (extraInfo.isNotEmpty()) append("\n", extraInfo)
            }
        }.trim()

        val slug = wp?.slug
            ?: response.request.url.pathSegments.lastOrNull().orEmpty()

        return SManga.create().apply {
            url = MangaUrl(id, slug).toJsonString()
            this.title = title
            this.author = author
            artist = null
            genre = buildList {
                addAll(genres)
            }.distinct().joinToString(", ").ifBlank { null }
            this.status = status
            this.thumbnail_url = thumbnail
            this.description = desc.ifBlank { null }
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.parseAs<MangaUrl>().slug
        return "$baseUrl/manga/$slug"
    }

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val timestamp = System.currentTimeMillis() / 1000
        val token = md5("${timestamp}mng_ch_${isoDateFormatter.format(Date())}").substring(0, 16)
        val id = manga.url.parseAs<MangaUrl>().id

        val url = "$baseUrl/auth/manga-chapters".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", id)
            .addQueryParameter("offset", "0")
            .addQueryParameter("limit", "9999")
            .addQueryParameter("order", "DESC")
            .addQueryParameter("_t", token)
            .addQueryParameter("_ts", timestamp.toString())
            .build()

        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ChapterListResponse>()
        if (!data.success) throw Exception("The site refused the chapter request — try again later")

        val placeholders = listOf(null, "", "N/A", "—")
        var hasScanlator = false

        val chapters = data.chapters
            .filter { it.language.equals(lang, ignoreCase = true) }
            .map { dto ->
                SChapter.create().apply {
                    setUrlWithoutDomain(dto.url.removeSuffix("/"))
                    name = buildString {
                        append("Chapter ")
                        append(dto.chapter)
                        if (dto.title != null && dto.title !in placeholders) {
                            append(": ", dto.title!!.htmlUnescape())
                        }
                    }
                    dto.groupName?.takeIf { it !in placeholders }?.let {
                        scanlator = it
                        hasScanlator = true
                    }
                    date_upload = dto.date?.parseRelativeDate() ?: 0L
                }
            }

        if (hasScanlator) {
            chapters.forEach { it.scanlator = it.scanlator ?: "​" }
        }

        return chapters
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("-").trimEnd('/')
        val url = "$baseUrl/auth/chapter-content".toHttpUrl().newBuilder()
            .addQueryParameter("chapter_id", chapterId)
            .build()
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PagesResponse>()
        if (!data.success) throw Exception("The site refused the page request — try again later")
        return data.images.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList() = FilterList(
        SearchWithFilters(),
        Filter.Header("If unchecked, search uses the quick search"),
        Filter.Header("and filters are ignored (more relevant results)"),
        Filter.Separator(),
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        TagFilter(),
        TagFilterMatch(),
    )

    // ============================ Preferences ============================

    private fun showTagsInGenrePref() = preferences.getBoolean(PREF_TAGS_IN_GENRE, false)
    private fun showExtraInfoPref() = preferences.getBoolean(PREF_EXTRA_INFO, true)
    private fun showAltNamesPref() = preferences.getBoolean(PREF_ALT_NAMES, true)
    private fun scorePositionPref() = preferences.getString(PREF_SCORE_POSITION, "top") ?: "top"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Year, chapter count, type and ratings below the score"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALT_NAMES
            title = "Show alternative names"
            summary = "Append the site's alternative titles to the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TAGS_IN_GENRE
            title = "Include type in the genre list"
            summary = "Adds the entry type (e.g. Manhwa) to the genres"
            setDefaultValue(false)
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

    private fun BrowseManga.toSManga() = SManga.create().apply {
        url = MangaUrl(id = id, slug = url.substringAfterLast('/')).toJsonString()
        title = title.htmlUnescape()
        thumbnail_url = cover
        description = description?.htmlUnescape()
        status = when (this@toSManga.status) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun String.extractVotes(): Int = Regex("(\\d+)\\s*(?:people )?voted", RegexOption.IGNORE_CASE).find(this)
        ?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun toStars(rating: Double): String {
        val full = (rating / 2).toInt().coerceIn(0, 5)
        return "★".repeat(full) + "☆".repeat(5 - full) + " ${rating.toString().removeSuffix(".0")}"
    }

    private fun md5(input: String): String = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun String.parseRelativeDate(): Long {
        val relative = relativeDateRegex.matchEntire(this.trim()) ?: return 0L
        val (amount, unit) = relative.destructured
        val calendar = Calendar.getInstance()
        when (unit.removeSuffix("s")) {
            "second" -> calendar.add(Calendar.SECOND, -amount.toInt())
            "minute" -> calendar.add(Calendar.MINUTE, -amount.toInt())
            "hour" -> calendar.add(Calendar.HOUR, -amount.toInt())
            "day" -> calendar.add(Calendar.DAY_OF_YEAR, -amount.toInt())
            "week" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount.toInt())
            "month" -> calendar.add(Calendar.MONTH, -amount.toInt())
            "year" -> calendar.add(Calendar.YEAR, -amount.toInt())
            else -> return 0L
        }
        return calendar.timeInMillis
    }

    private fun String.htmlUnescape(): String = org.jsoup.parser.Parser.unescapeEntities(this, false)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val NUMBER_ONLY = Regex("\\d+(?:\\.\\d+)?")
        private val relativeDateRegex = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")

        private val isoDateFormatter = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private const val PREF_EXTRA_INFO = "pref_extra_info"
        private const val PREF_ALT_NAMES = "pref_alt_names"
        private const val PREF_TAGS_IN_GENRE = "pref_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}

// ================================ DTOs ==================================

internal fun String.htmlUnescape(): String = org.jsoup.parser.Parser.unescapeEntities(this, false)

@Serializable
internal class MangaUrl(val id: String, val slug: String)

@Serializable
internal class SearchPayload(val query: String, val limit: Int)

@Serializable
internal class LoadPayload(
    val page: Int,
    val search: String,
    val years: String,
    val genres: String,
    val types: String,
    val statuses: String,
    val sort: String,
    val genreMatchMode: String,
)

@Serializable
internal class BrowseManga(
    val id: String,
    val title: String,
    val url: String,
    val cover: String? = null,
    val status: String? = null,
    val type: String? = null,
    val year: String? = null,
    val description: String? = null,
)

@Serializable
internal class SearchResponse(
    val success: Boolean = false,
    val results: List<SearchResult> = emptyList(),
) {
    fun toMangasPage() = MangasPage(
        mangas = results
            .filter { it.type != "Novel" }
            .map {
                SManga.create().apply {
                    url = MangaUrl(id = it.id, slug = it.slug).toJsonString()
                    title = it.title.htmlUnescape()
                    thumbnail_url = it.thumbnail
                    description = it.description?.htmlUnescape()
                    status = when (it.status) {
                        "Ongoing" -> SManga.ONGOING
                        "Completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            },
        hasNextPage = false,
    )
}

@Serializable
internal class SearchResult(
    val id: String,
    val title: String,
    val slug: String,
    val thumbnail: String? = null,
    val description: String? = null,
    val status: String? = null,
    val type: String? = null,
)

@Serializable
internal class SchemaOrgManga(
    val author: SchemaOrgPerson? = null,
    val genre: String? = null,
    val status: String? = null,
    val numberOfEpisodes: Int? = null,
    val datePublished: String? = null,
)

@Serializable
internal class SchemaOrgPerson(val name: String? = null)

@Serializable
internal class SchemaOrgAlt(val alternateName: String? = null)

@Serializable
internal class WpManga(
    val id: Long,
    val slug: String,
    val title: WpTitle,
    val content: WpContent,
    val embedded: WpEmbedded? = null,
)

@Serializable
internal class WpTitle(val rendered: String)

@Serializable
internal class WpContent(val rendered: String)

@Serializable
internal class WpEmbedded(
    @kotlinx.serialization.SerialName("wp:term")
    val terms: List<List<WpTerm>> = emptyList(),
    @kotlinx.serialization.SerialName("wp:featuredmedia")
    val featuredMedia: List<WpMedia> = emptyList(),
)

@Serializable
internal class WpTerm(val id: Long? = null, val name: String? = null)

@Serializable
internal class WpMedia(
    @kotlinx.serialization.SerialName("source_url")
    val sourceUrl: String? = null,
)

@Serializable
internal class ChapterListResponse(
    val success: Boolean = false,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
internal class ChapterDto(
    val id: String,
    val chapter: String,
    val title: String? = null,
    val date: String? = null,
    val language: String? = null,
    @kotlinx.serialization.SerialName("group_name")
    val groupName: String? = null,
    val url: String,
)

@Serializable
internal class PagesResponse(
    val success: Boolean = false,
    val images: List<String> = emptyList(),
)

// =============================== Filters ================================

internal class SearchWithFilters : Filter.CheckBox("Search with filters", false)

internal class SortFilter(state: Int = 0) :
    Filter.Select<String>(
        "Sort",
        arrayOf(
            "Latest update",
            "Popular",
            "Release date",
            "Title (A-Z)",
            "Title (Z-A)",
            "Oldest",
        ),
        state,
    ) {
    val selected: String
        get() = when (state) {
            1 -> "popular_desc"
            2 -> "release_desc"
            3 -> "title_asc"
            4 -> "title_desc"
            5 -> "post_asc"
            else -> "post_desc"
        }

    companion object {
        const val POPULAR = "popular_desc"
        const val LATEST = "post_desc"
    }
}

internal class CheckBoxOption(name: String, val value: String) : Filter.CheckBox(name)

internal class TypeFilter :
    Filter.Group<CheckBoxOption>(
        "Type",
        listOf("Manga", "Manhua", "Manhwa", "Novel").map { CheckBoxOption(it, it) },
    ) {
    val selected: List<String>
        get() = state.filter { it.state }.map { it.value }
}

internal class StatusFilter :
    Filter.Group<CheckBoxOption>(
        "Status",
        listOf("Ongoing", "Completed").map { CheckBoxOption(it, it) },
    ) {
    val selected: List<String>
        get() = state.filter { it.state }.map { it.value }
}

internal class GenreTriState(name: String, val value: String) : Filter.TriState(name)

internal class TagFilter :
    Filter.Group<GenreTriState>(
        "Genres",
        GENRES.map { GenreTriState(it.second, it.first) },
    ) {
    val checked: List<String>
        get() = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

    companion object {
        // Curated from the site's own genre buttons (id -> name). The site
        // exposes hundreds of user-made tags; the canonical set below covers
        // the entries with real catalog coverage.
        private val GENRES = listOf(
            2 to "Fantasy",
            3 to "Isekai",
            4 to "Martial Arts",
            5 to "Reincarnation",
            6 to "Manhwa",
            7 to "Action",
            8 to "Drama",
            9 to "Supernatural",
            10 to "School",
            12 to "Adventure",
            13 to "Shounen",
            14 to "Manga",
            15 to "Sci-Fi",
            16 to "Adult Cast",
            17 to "Award Winning",
            18 to "Historical",
            19 to "Seinen",
            20 to "Comedy",
            21 to "Parody",
            22 to "Super Power",
            23 to "Gore",
            24 to "Mystery",
            25 to "Sports",
            26 to "Psychological",
            27 to "Team Sports",
            28 to "Romantic Subtext",
            29 to "Romance",
            30 to "Military",
            31 to "Video Game",
            32 to "Time Travel",
            33 to "Manhua",
            34 to "Full Color",
            35 to "Magic",
            36 to "Game",
            37 to "Monsters",
            38 to "Murim",
            39 to "Webtoon",
            40 to "Ecchi",
            41 to "Love Polygon",
            42 to "Shoujo",
            43 to "Josei",
            44 to "Horror",
            45 to "Vampire",
            46 to "Gourmet",
            47 to "Slice of Life",
            48 to "One-shot",
            49 to "Girls Love",
            50 to "Organized Crime",
            51 to "Childcare",
            52 to "Delinquents",
            53 to "Mythology",
            54 to "Showbiz",
            55 to "Light Novel",
            56 to "Survival",
            57 to "Visual Arts",
            58 to "Detective",
            59 to "Gag Humor",
            60 to "Samurai",
            61 to "Reverse Harem",
            62 to "Villainess",
            63 to "Philosophical",
            64 to "Harem",
            65 to "Tragedy",
            66 to "Educational",
            67 to "Medical",
            68 to "Magical Sex Shift",
            69 to "Workplace",
            70 to "Otaku Culture",
            71 to "Erotica",
            72 to "Performing Arts",
            73 to "School Life",
            74 to "Mecha",
            75 to "Suspense",
            76 to "High Stakes Game",
            77 to "Strategy Game",
            79 to "Doujinshi",
            80 to "Boys Love",
            81 to "Crossdressing",
            83 to "Music",
            84 to "Space",
            85 to "Smut",
            87 to "Mature",
            88 to "Adult",
            91 to "Iyashikei",
            93 to "Anthropomorphic",
            94 to "CGDCT",
            97 to "Mahou Shoujo",
            98 to "Avant Garde",
            99 to "Yuri",
            100 to "Love Status Quo",
            102 to "Racing",
            105 to "Pets",
            106 to "Gender Bender",
            107 to "Kids",
            110 to "Urban Fantasy",
            964 to "Alchemy",
            965 to "Aliens",
            966 to "Alternate World",
            968 to "Beast Companions",
            974 to "Cultivation",
            979 to "Game Elements",
            984 to "Male Protagonist",
            985 to "Modern Day",
            988 to "Overpowered Protagonist",
            995 to "Sword And Magic",
            997 to "Weak to Strong",
            999 to "Accelerated Growth",
            1000 to "Adapted to Anime",
            1011 to "Dungeons",
            1020 to "Level System",
            1031 to "Special Abilities",
            1037 to "Aristocracy",
            1040 to "Dragons",
            1047 to "Arranged Marriage",
            1050 to "Abandoned Children",
            1058 to "Adopted Children",
            1134 to "Ghosts",
            1135 to "Animals",
            1136 to "Police",
            1172 to "Long Strip",
            1173 to "Web Comic",
            1341 to "Thriller",
            1342 to "Zombies",
            1350 to "Wuxia",
            1351 to "Adaptation",
            1376 to "Demons",
            1414 to "Monster Girls",
            1557 to "Post-Apocalyptic",
            1753 to "Crime",
            2061 to "Mafia",
            2062 to "Office Workers",
            2093 to "Genderswap",
            2094 to "4-Koma",
            2096 to "Anthology",
            2102 to "Vampires",
            2118 to "Cooking",
            2386 to "Ability Steal",
            2391 to "Xianxia",
            2392 to "Cautious Protagonist",
            2397 to "Absent Parents",
            2398 to "Business Management",
            2861 to "Age Regression",
            2862 to "Ancient Times",
            2863 to "Assassins",
            2864 to "Betrayal",
            4004 to "Artifacts",
            4005 to "Family",
            4006 to "Kingdoms",
            4007 to "Love interest falls in love first",
            4008 to "Devoted love interests",
            4010 to "Elves",
            4011 to "Transported to another world",
            4012 to "Academy",
            4013 to "Master-disciple relationship",
            4014 to "Reincarnated in another world",
            4098 to "Superhero",
            4116 to "Sexual Violence",
            4485 to "Virtual Reality",
            4601 to "Ninja",
            4739 to "Gyaru",
            4795 to "Dungeon",
            4930 to "Dark",
            4931 to "Evolution",
            5069 to "Adapted to Manga",
            5279 to "Battle Academy",
            5608 to "Acting",
            5609 to "Antihero protagonist",
            5610 to "Power couple",
            5611 to "Transmigration",
            5615 to "Amnesia",
            6079 to "Childhood Friends",
            6100 to "Incest",
            6880 to "Sci-Fi Martial Arts",
            6886 to "Saint/Saintess",
            6888 to "Empire",
            6891 to "Tall Female Characters",
            6892 to "Goddess/es",
            6894 to "God/s",
            6895 to "Abuse of Power",
            6901 to "Family Feud",
            6903 to "Underestimated Protagonist",
            6904 to "Cheat Skill/s",
            6906 to "Short Chapter/s",
            6907 to "Regression",
            6909 to "Money",
            6913 to "Business",
            6914 to "Regressed Male Lead",
            6916 to "Smart Protagonist",
            6927 to "Girls' Love",
            6941 to "Suggestive",
            7067 to "Ability user",
            7068 to "Coming of age",
            7138 to "Boss-Subordinate Relationship",
            7139 to "Death",
            7196 to "Steam punk",
            7197 to "Journey",
            7250 to "Romantic Comedy",
            7251 to "Love Triangle",
            7253 to "Psychic Powers",
            7254 to "Teen Protagonists",
            7255 to "High School",
            7256 to "Slow-Burn Romance",
            7258 to "1980s",
            7300 to "Swordplay",
            7301 to "Battle",
            7302 to "Colored",
            7443 to "Streamer Life",
            7444 to "Streaming",
            7447 to "Revenge",
            7461 to "Leveling Up",
            7484 to "System",
            7485 to "Villain Protagonist",
            7487 to "Divine Weapons",
            7489 to "Overpowered MC",
            7519 to "Swordsman",
            7571 to "Violence",
            7574 to "Non-Sexual Nudity",
            7598 to "Killer",
            7599 to "Multiple Leads",
            7610 to "19+",
            7676 to "Female lead",
            7705 to "Mother",
            7741 to "Royal Chef",
            7770 to "Animal",
            7862 to "Beast Taming",
            7870 to "Assassin",
            7871 to "Modern Life",
            7874 to "Action Adventure",
            7878 to "Delinquent",
            7887 to "Ghost",
            7897 to "Elf",
            7904 to "Cultivation System",
            7905 to "Rebirth",
            7906 to "Martial World",
            8052 to "Heartwarming",
            8058 to "Another World",
            8060 to "Slow life",
            8071 to "God",
            8078 to "Female Protagonist",
        ).map { (id, name) -> id.toString() to name }
    }
}

internal class TagFilterMatch : Filter.CheckBox("Match all selected genres", false)
