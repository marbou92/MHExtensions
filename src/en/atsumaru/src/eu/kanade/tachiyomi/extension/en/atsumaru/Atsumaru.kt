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
import java.text.NumberFormat
import java.util.Locale
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

    /**
     * Cover/poster paths returned by the API (`posters/xxx.jpg`) live under
     * `/static/` on the CDN — `cdn.atsu.moe/posters/...` answers 404, which is
     * why browse covers used to be broken.
     */
    private fun coverUrl(path: String?): String? {
        val clean = path?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            clean.startsWith("http") -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> "$cdnBase/static/${clean.removePrefix("/")}"
        }
    }

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
            .addQueryParameter("mediums", "Comic")
            .addQueryParameter("contentRatings", contentRatings.joinToString(","))
            .build()
        return GET(url.toString(), apiHeaders)
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = browseUrl(
        "popular",
        page,
        types = listOf("Manga", "Manwha", "Manhua", "OEL"),
        contentRatings = defaultContentRatings(),
    )

    override fun popularMangaParse(response: Response): MangasPage = browseParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = browseUrl(
        "recentlyUpdated",
        page,
        types = listOf("Manga", "Manwha", "Manhua", "OEL"),
        contentRatings = defaultContentRatings(),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = browseParse(response)

    private fun browseParse(response: Response): MangasPage {
        val dto = response.parseAs<BrowseDto>()
        val mangas = dto.items.map { item ->
            SManga.create().apply {
                url = item.id
                title = item.title
                thumbnail_url = coverUrl(item.image)
            }
        }
        return MangasPage(mangas, dto.items.size >= browsePageSize)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Typesense requires `q=*` to match everything; an empty `q` returns
        // 0 results ("no result found") even when filters would match.
        val url = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.ifBlank { "*" })
            .addQueryParameter("query_by", "title")
            .addQueryParameter("per_page", searchPageSize.toString())
            .addQueryParameter("page", page.toString())

        val filterParts = mutableListOf<String>()

        // Excluded hidden entries, novels and view-less stubs (mirrors the site).
        filterParts.add("hidden:!=true")
        filterParts.add("medium:!=[Novel]")
        filterParts.add("views:>0")

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> filter.state.filter { it.state }.let { checked ->
                    val values = checked.map { it.value }
                    if (values.isNotEmpty()) filterParts.add("type:=[" + values.joinToString(",") + "]")
                }
                is StatusFilter -> filter.state.filter { it.state }.let { checked ->
                    val values = checked.map { it.value }
                    if (values.isNotEmpty()) filterParts.add("status:=[${values.joinToString(",")}]")
                }
                is ContentRatingFilter -> filter.state.filter { it.state }.let { checked ->
                    val values = checked.map { it.value }
                    if (values.isNotEmpty()) {
                        filterParts.add("mbContentRating:=[${values.joinToString(",")}]")
                    } else {
                        // Nothing selected: allow documents with any rating or none.
                        filterParts.add("(mbContentRating:=[Safe,Suggestive,Erotica] || mbContentRating:!=*)")
                    }
                }
                is GenreFilter -> {
                    val included = filter.included()
                    val excluded = filter.excluded()
                    if (included.isNotEmpty()) {
                        filterParts.add(included.joinToString(" && ") { "genreIds:=[$it]" })
                    }
                    if (excluded.isNotEmpty()) {
                        filterParts.add("genreIds:!==[${excluded.joinToString(",")}]")
                    }
                }
                is YearFilter -> {
                    val year = filter.state.trim().toIntOrNull()
                    if (year != null) filterParts.add("releaseYear:=[$year]")
                }
                else -> {}
            }
        }

        filters.firstInstanceOrNull<SortFilter>()?.let { sort ->
            sort.toSortBy()?.let { url.addQueryParameter("sort_by", it) }
        }

        if (query.isNotBlank()) {
            url.addQueryParameter("query_by", "title,englishTitle,otherNames,authors")
            url.addQueryParameter("query_by_weights", "4,3,2,1")
            url.addQueryParameter("num_typos", "4,3,2,1")
        }

        url.addQueryParameter("filter_by", filterParts.joinToString(" && "))

        return GET(url.build().toString(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.hits.map { hit ->
            val doc = hit.document
            SManga.create().apply {
                url = doc.id
                title = doc.title
                thumbnail_url = coverUrl(doc.poster ?: doc.posterMedium)
            }
        }
        return MangasPage(mangas, dto.hasNextPage(searchPageSize))
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/manga/page?id=${manga.url}", apiHeaders)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaPage = response.parseAs<MangaPageDto>().mangaPage

        // Warm the scanlator-name cache used by the chapter list.
        if (mangaPage.scanlators.isNotEmpty()) {
            scanlatorCache[mangaPage.id] = mangaPage.scanlators.associate { it.id to it.name }
        }

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

        // Author/artist split from the typed authors list.
        val authors = mangaPage.authors
            .filter { it.type == null || it.type.equals("Author", true) || it.type.equals("Story", true) }
            .map { it.name }
            .distinct()
        val artists = mangaPage.authors
            .filter { it.type?.equals("Artist", true) == true || it.type?.equals("Art", true) == true }
            .map { it.name }
            .distinct()

        // Rating formatted to two decimals, e.g. "8.48/10" (was "8.47980").
        val ratingText = mangaPage.avgRating
            ?.takeIf { it > 0 }
            ?.let { String.format(Locale.ENGLISH, "%.2f/10", it) }
        val stars = ratingText?.let { text ->
            val full = (mangaPage.avgRating!! / 2).toInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " $text"
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                mangaPage.released?.takeIf { it > 0 }?.let { ts ->
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
                    append("**Views:** ${formatViews(it)}")
                }
            }.ifBlank { null }
        } else {
            null
        }

        val details = buildString {
            // The rating is rendered exactly once, in the configured position.
            if (scorePosition == "top" && stars != null) {
                append(stars)
                append("\n\n")
            }

            if (infoLine != null) {
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
            author = authors.joinToString(", ").ifBlank { null }
            artist = artists.joinToString(", ").ifBlank { null }
            genre = genreChips.ifBlank { null }
            description = details.ifBlank { mangaPage.synopsis }
            status = formatAtsuStatus(mangaPage.status)
            thumbnail_url = coverUrl(mangaPage.poster?.image)
            initialized = true
        }
    }

    private fun formatViews(views: String): String = views.toLongOrNull()?.let {
        NumberFormat.getIntegerInstance(Locale.ENGLISH).format(it)
    } ?: views

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/manga/allChapters?mangaId=${manga.url}", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.queryParameter("mangaId").orEmpty()
        val scanlatorNames = resolveScanlators(mangaId)
        val chapters = response.parseAs<AllChaptersDto>().chapters.map { ch ->
            ch.toSChapter(scanlatorNames[ch.scanlationMangaId])
                .apply { url = "$mangaId|${ch.id}" }
        }.sortedWith(
            compareByDescending<SChapter> { it.chapter_number }
                .thenByDescending { it.date_upload },
        )

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

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun splitChapterUrl(chapter: SChapter): Pair<String, String> {
        val parts = chapter.url.split("|")
        if (parts.size != 2) throw IOException("Outdated chapter URL. Refresh the chapter list.")
        return parts[0] to parts[1]
    }

    // ----------------------------------------------------------------------
    // Scanlator names — the allChapters payload only carries the group's id
    // (`scanlationMangaId`); the display names live in the details response
    // (`scanlators[]`). Details parse fills the cache; the chapter list
    // fetches details once when the cache is cold.
    // ----------------------------------------------------------------------

    private val scanlatorCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    private fun resolveScanlators(mangaId: String): Map<String, String> {
        scanlatorCache[mangaId]?.let { return it }

        return try {
            client.newCall(GET("$apiUrl/manga/page?id=$mangaId", apiHeaders))
                .execute()
                .use { resp ->
                    val page = resp.parseAs<MangaPageDto>().mangaPage
                    val map = page.scanlators.associate { it.id to it.name }
                    if (map.isNotEmpty()) scanlatorCache[mangaId] = map
                    map
                }
        } catch (_: Exception) {
            // Scanlator names are cosmetic — never fail the chapter list over them.
            emptyMap()
        }
    }

    private inline fun <reified T : Filter<*>> FilterList.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        ContentRatingFilter(),
        YearFilter(),
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf(
                "Relevance",
                "Most viewed",
                "Trending",
                "Newest added",
                "Release date",
                "Highest rated",
                "Title",
            ),
            Filter.Sort.Selection(0, false),
        ) {
        fun toSortBy(): String? = when (state?.index) {
            1 -> if (state?.ascending == true) "views:asc" else "views:desc"
            2 -> if (state?.ascending == true) "trending:asc" else "trending:desc"
            3 -> if (state?.ascending == true) "dateAdded:asc" else "dateAdded:desc"
            4 -> if (state?.ascending == true) "released:asc" else "released:desc"
            5 -> if (state?.ascending == true) "mbRating:asc" else "mbRating:desc"
            6 -> if (state?.ascending == true) "title:asc" else "title:desc"
            else -> null
        }
    }

    private class GenreTriState(name: String, val id: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it.first, it.second) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.id }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.id }

        private companion object {
            // Live values from /api/explore/availableFilters (2026-09).
            val GENRES = listOf(
                "Action" to "39",
                "Adult" to "46",
                "Adventure" to "37",
                "Boys Love" to "180",
                "Comedy" to "6",
                "Drama" to "31",
                "Fantasy" to "36",
                "Girls Love" to "4",
                "Hentai" to "10",
                "Historical" to "45",
                "Horror" to "44",
                "Martial Arts" to "29",
                "Mystery" to "32",
                "Psychological" to "18",
                "Romance" to "9",
                "Sci-Fi" to "1",
                "Slice of Life" to "7",
                "Smut" to "41",
                "Supernatural" to "22",
                "Thriller" to "19",
                "Tragedy" to "5",
            )
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
                TypeCheckBox("Canceled", "Canceled"),
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

    private class YearFilter : Filter.Text("Year (e.g. 2024)")

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
            summary = "Display year, type, status and views"
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
            summary = "Where to display the manga rating"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("top")
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

    private fun android.content.SharedPreferences.scorePosition(): String = getString(PREF_SCORE_POSITION, "top") ?: "top"

    private fun defaultContentRatings(): List<String> = preferences.getStringSet(PREF_CONTENT_RATING, setOf("Safe", "Suggestive"))?.toList()
        ?: listOf("Safe", "Suggestive")

    companion object {
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}
