package eu.kanade.tachiyomi.extension.en.atsumaru

import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Source
abstract class Atsumaru :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val cdnBase = "https://cdn.atsu.moe"
    private val apiUrl = "$baseUrl/api"

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            CloudflareBypass(setOf(baseUrl.removePrefix("https://"), "cdn.atsu.moe")).install(this)
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val apiHeaders by lazy {
        headersBuilder().set("Accept", "application/json").build()
    }

    private val browsePageSize = 24
    private val searchPageSize = 24

    private fun browseUrl(
        endpoint: String,
        page: Int,
        types: List<String>,
        contentRatings: List<String>,
    ): Request {
        val offset = (page - 1) * browsePageSize
        val url = "$apiUrl/home2/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", browsePageSize.toString())
            .addQueryParameter("types", types.joinToString(","))
            .addQueryParameter("mediums", "Comic,Novel")
            .addQueryParameter("contentRatings", contentRatings.joinToString(","))
            .build()
        return GET(url.toString(), apiHeaders)
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = browseUrl(
        "popular",
        page,
        types = defaultTypes(),
        contentRatings = defaultContentRatings(),
    )

    override fun popularMangaParse(response: Response): MangasPage = browseParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = browseUrl(
        "recentlyUpdated",
        page,
        types = defaultTypes(),
        contentRatings = defaultContentRatings(),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = browseParse(response)

    private fun browseParse(response: Response): MangasPage {
        val dto = response.parseAs<BrowseDto>()
        val mangas = dto.items.map { item ->
            SManga.create().apply {
                url = item.id
                title = item.title
                thumbnail_url = item.image?.let { "$cdnBase/$it" }
            }
        }
        return MangasPage(mangas, dto.items.size >= browsePageSize)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("query_by", "title")
            .addQueryParameter("per_page", searchPageSize.toString())
            .addQueryParameter("page", page.toString())

        val filterParts = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> filter.state.filter { it.state }.let { checked ->
                    val values = checked.map { it.value }
                    if (values.isNotEmpty()) filterParts.add("type:=" + values.joinToString("|"))
                }
                is StatusFilter -> filter.state.filter { it.state }.let { checked ->
                    val values = checked.map { it.value }
                    if (values.isNotEmpty()) filterParts.add("status:=${values.joinToString("|")}")
                }
                is ContentRatingFilter -> filter.state.filter { it.state }.let { checked ->
                    val values = checked.map { it.value }
                    if (values.isNotEmpty()) filterParts.add("mbContentRating:=${values.joinToString("|")}")
                }
                is GenreFilter -> filter.state.filter { it.state }.let { checked ->
                    val ids = checked.map { it.id }
                    if (ids.isNotEmpty()) filterParts.add("genreIds:=[${ids.joinToString(",")}]")
                }
                else -> {}
            }
        }

        filters.firstInstanceOrNull<SortFilter>()?.let { sort ->
            sort.toSortBy()?.let { url.addQueryParameter("sort_by", it) }
        }

        if (filterParts.isNotEmpty()) {
            url.addQueryParameter("filter_by", filterParts.joinToString(" && "))
        }

        return GET(url.build().toString(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.hits.map { hit ->
            val doc = hit.document
            SManga.create().apply {
                url = doc.id
                title = doc.title
                thumbnail_url = (doc.poster ?: doc.posterMedium)?.let { "$cdnBase$it" }
            }
        }
        return MangasPage(mangas, dto.hasNextPage(searchPageSize))
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/manga/page?id=${manga.url}", apiHeaders)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaPage = response.parseAs<MangaPageDto>().mangaPage
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()
        val showTagsInGenre = preferences.showTagsInGenre()
        val blockedGenres = preferences.blockedGenres()
        val scorePosition = preferences.scorePosition()

        val genreChips = buildList {
            addAll(mangaPage.genres.map { it.name })
            if (showTagsInGenre) addAll(mangaPage.tags.map { it.name })
        }.distinct()
            .filterNot { it.lowercase() in blockedGenres }
            .joinToString(", ")

        // Comix-style score stars (rating is out of 10 → 5 stars)
        val stars = mangaPage.avgRating?.takeIf { it > 0 }?.let { score ->
            val full = (score / 2).toInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " $score"
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                mangaPage.released?.let { ts ->
                    val year = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneOffset.UTC).year
                    append("**Year:** $year")
                }
                if (!mangaPage.type.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Type:** ${mangaPage.type}")
                }
                if (!mangaPage.status.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Status:** ${mangaPage.status}")
                }
                mangaPage.views?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**Views:** $it")
                }
                if (stars != null && mangaPage.avgRating != null) {
                    if (isNotEmpty()) append(" · ")
                    append("**$stars**")
                }
            }.ifBlank { null }
        } else {
            null
        }

        val details = buildString {
            if (scorePosition == "top" && stars != null) {
                append(stars)
                append("\n")
                if (infoLine != null) {
                    append(infoLine)
                    append("\n\n")
                }
            } else if (infoLine != null) {
                append(infoLine)
                append("\n\n")
            }

            mangaPage.synopsis?.let { append(it.trim()) }

            if (showAltNames && mangaPage.otherNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(mangaPage.otherNames.joinToString("\n") { "• $it" })
            }

            if (scorePosition == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
            }
        }.trim()

        return SManga.create().apply {
            url = mangaPage.id
            title = mangaPage.title
            author = mangaPage.authors.joinToString(", ") { it.name }.ifBlank { null }
            genre = genreChips.ifBlank { null }
            description = details.ifBlank { mangaPage.synopsis }
            status = formatAtsuStatus(mangaPage.status)
            thumbnail_url = mangaPage.poster?.image?.let { "$cdnBase/$it" }
            initialized = true
        }
    }

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/manga/allChapters?mangaId=${manga.url}", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.queryParameter("mangaId").orEmpty()
        val chapters = response.parseAs<AllChaptersDto>().chapters.map { ch ->
            ch.toSChapter().apply { url = "$mangaId|${ch.id}" }
        }

        if (!preferences.deduplicateChapters()) return chapters

        val seen = mutableSetOf<Float>()
        return chapters.filter { ch ->
            val key = ch.chapter_number
            if (key <= 0f) return@filter true
            seen.add(key)
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val (mangaId, chapterId) = splitChapterUrl(chapter)
        return GET("$apiUrl/read/chapter?mangaId=$mangaId&chapterId=$chapterId", apiHeaders)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (mangaId, _) = splitChapterUrl(chapter)
        return "$baseUrl/read/$mangaId/${chapter.url.substringAfter('|')}"
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<ReadChapterDto>()
        if (dto.readChapter.pages.isEmpty()) {
            throw IOException("No pages found for this chapter")
        }
        return dto.readChapter.pages.toPageList(cdnBase)
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun splitChapterUrl(chapter: SChapter): Pair<String, String> {
        val parts = chapter.url.split("|")
        if (parts.size != 2) throw IOException("Outdated chapter URL. Refresh the chapter list.")
        return parts[0] to parts[1]
    }

    private inline fun <reified T : Filter<*>> FilterList.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        ContentRatingFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf(
                "Relevance",
                "Highest rated",
                "Newest added",
                "Title",
            ),
            Filter.Sort.Selection(0, false),
        ) {
        fun toSortBy(): String? = when (state?.index) {
            1 -> if (state?.ascending == true) "mbRating:asc" else "mbRating:desc"
            2 -> if (state?.ascending == true) "dateAdded:asc" else "dateAdded:desc"
            3 -> "title:asc"
            else -> null
        }
    }

    private class TypeCheckBox(name: String, val value: String, state: Boolean = false) : Filter.CheckBox(name, state)

    private class TypeFilter :
        Filter.Group<TypeCheckBox>(
            "Type",
            listOf(
                TypeCheckBox("Manga", "Manga"),
                TypeCheckBox("Manhwa", "Manwha"),
                TypeCheckBox("Manhua", "Manhua"),
                TypeCheckBox("OEL", "OEL"),
                TypeCheckBox("Other", "Other"),
            ),
        )

    private class StatusFilter :
        Filter.Group<TypeCheckBox>(
            "Status",
            listOf(
                TypeCheckBox("Ongoing", "Ongoing"),
                TypeCheckBox("Completed", "Completed"),
                TypeCheckBox("Hiatus", "Hiatus"),
            ),
        )

    private class ContentRatingFilter :
        Filter.Group<TypeCheckBox>(
            "Content rating",
            listOf(
                TypeCheckBox("Safe", "Safe", true),
                TypeCheckBox("Suggestive", "Suggestive", true),
                TypeCheckBox("Erotica", "Erotica"),
            ),
        )

    private class GenreCheckBox(name: String, val id: String, state: Boolean = false) : Filter.CheckBox(name, state)

    private class GenreFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox("Action", "39"),
                GenreCheckBox("Adult", "46"),
                GenreCheckBox("Adventure", "37"),
                GenreCheckBox("Boys Love", "180"),
                GenreCheckBox("Comedy", "6"),
                GenreCheckBox("Drama", "31"),
                GenreCheckBox("Fantasy", "36"),
                GenreCheckBox("Girls Love", "4"),
                GenreCheckBox("Hentai", "10"),
                GenreCheckBox("Historical", "45"),
                GenreCheckBox("Horror", "44"),
                GenreCheckBox("Martial Arts", "29"),
                GenreCheckBox("Mystery", "32"),
                GenreCheckBox("Psychological", "18"),
                GenreCheckBox("Romance", "9"),
                GenreCheckBox("Sci-Fi", "1"),
                GenreCheckBox("Slice of Life", "7"),
                GenreCheckBox("Smut", "41"),
                GenreCheckBox("Supernatural", "22"),
                GenreCheckBox("Thriller", "21"),
                GenreCheckBox("Tragedy", "5"),
            ),
        )

    // ========================================================================
    // Settings (Comix-style)
    // ========================================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        MultiSelectListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Default content rating"
            summary = "Content ratings to show by default in browse/search"
            entries = arrayOf("Safe", "Suggestive", "Erotica")
            entryValues = arrayOf("Safe", "Suggestive", "Erotica")
            setDefaultValue(setOf("Safe", "Suggestive"))
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_TYPES
            title = "Default type filter"
            summary = "Manga types to show by default (empty = all)"
            entries = arrayOf("Manga", "Manhwa", "Manhua", "OEL", "Other")
            entryValues = arrayOf("Manga", "Manwha", "Manhua", "OEL", "Other")
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "Keep only one chapter per number (useful when multiple scanlators upload the same chapter)"
            setDefaultValue(false)
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
            summary = "Display year, type, status, views and rating"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genre chips"
            summary = "Include tags in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.blockedGenres(): Set<String> = getString(PREF_BLOCKED_GENRES, "")
        ?.split(",")
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

    private fun android.content.SharedPreferences.deduplicateChapters(): Boolean = getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.scorePosition(): String = getString(PREF_SCORE_POSITION, "end") ?: "end"

    private fun defaultContentRatings(): List<String> = preferences.getStringSet(PREF_CONTENT_RATING, setOf("Safe", "Suggestive"))?.toList()
        ?: listOf("Safe", "Suggestive")

    private fun defaultTypes(): List<String> = preferences.getStringSet(PREF_DEFAULT_TYPES, emptySet())?.toList()
        ?: listOf("Manga", "Manwha", "Manhua", "OEL", "Other")

    companion object {
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_DEFAULT_TYPES = "pref_default_types"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}
