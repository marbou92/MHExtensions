package eu.kanade.tachiyomi.extension.en.mkissa

import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA = "application/json".toMediaType()

@Source
abstract class MKissa :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val apiHost = "api.mkissa.net"
    private val apiUrl = "https://$apiHost/api"

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            // MKissa's API sits on a separate host: sync WebView cookies for both
            // the site and the API host and fingerprint all requests.
            CloudflareBypass(setOf(baseUrl.removePrefix("https://"), apiHost)).install(this)
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json")

    private val apiHeaders by lazy {
        headersBuilder().set("Content-Type", "application/json").build()
    }

    /**
     * Executes a plain GraphQL query against the API. The API supports
     * introspection and full queries, so we no longer depend on fragile
     * persisted-query hashes.
     */
    private fun graphqlRequest(query: String, variables: JsonObject): Request = POST(
        apiUrl,
        apiHeaders,
        buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(JSON_MEDIA),
    )

    private val allowAdult: Boolean
        get() = preferences.defaultContentRatings().contains("Pornographic") ||
            preferences.defaultContentRatings().contains("pornographic")

    private fun baseSearchVariables(
        page: Int,
        sortBy: String,
        ascending: Boolean,
        query: String,
        genres: List<String>,
        genresExcluded: List<String>,
        year: Int?,
    ): JsonObject = buildJsonObject {
        putJsonObject("search") {
            put("sortBy", sortBy)
            put("sortDirection", if (ascending) "ASC" else "DSC")
            put("isManga", true)
            if (query.isNotBlank()) put("query", query)
            if (genres.isNotEmpty()) putJsonArray("genres") { genres.forEach { add(it) } }
            if (genresExcluded.isNotEmpty()) putJsonArray("excludeGenres") { genresExcluded.forEach { add(it) } }
            if (year != null) put("year", year)
            put("listProfile", "browse")
            put("allowAdult", allowAdult)
            put("allowUnknown", false)
            put("denyEcchi", false)
            put("lite", false)
        }
        put("page", page)
        put("limit", PAGE_SIZE)
        put("translationType", "sub")
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = graphqlRequest(
        query = QUERY_POPULAR,
        variables = buildJsonObject {
            put("type", "manga")
            put("size", PAGE_SIZE)
            put("page", page)
            put("dateRange", 1)
            put("allowAdult", allowAdult)
            put("allowUnknown", false)
            put("denyEcchi", false)
        },
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val popular = response.parseAs<PopularDto>().data?.queryPopular
        val cards = popular?.recommendations?.mapNotNull { it.anyCard }.orEmpty()

        val mangas = cards.map { card ->
            SManga.create().apply {
                url = card._id
                title = card.title()
                thumbnail_url = card.coverUrl()
            }
        }

        return MangasPage(mangas, cards.size >= PAGE_SIZE)
    }

    // =============================== Latest ===============================

    // "Latest_Update" is the API's own sort value for recently-updated manga.
    // The old build used "Trending" here, which surfaced almost the same
    // entries as the Popular tab.
    override fun latestUpdatesRequest(page: Int): Request = graphqlRequest(
        query = QUERY_MANGA_LIST,
        variables = baseSearchVariables(
            page = page,
            sortBy = "Latest_Update",
            ascending = false,
            query = "",
            genres = emptyList(),
            genresExcluded = emptyList(),
            year = null,
        ),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sortBy = "Top"
        var ascending = false
        val genres = mutableListOf<String>()
        val genresExcluded = mutableListOf<String>()
        var year: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    sortBy = SORT_OPTIONS[filter.state?.index ?: 0]
                    ascending = filter.state?.ascending == true
                }
                is GenreFilter -> {
                    genres.addAll(filter.included())
                    genresExcluded.addAll(filter.excluded())
                }
                is YearFilter -> year = filter.state.trim().toIntOrNull()
                else -> {}
            }
        }

        return graphqlRequest(
            query = QUERY_MANGA_LIST,
            variables = baseSearchVariables(
                page = page,
                sortBy = sortBy,
                ascending = ascending,
                query = query,
                genres = genres,
                genresExcluded = genresExcluded,
                year = year,
            ),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    private fun mangaListParse(response: Response): MangasPage {
        val mangas = response.parseAs<MangaListDto>()
            .data?.mangas?.edges
            .orEmpty()
            .map { edge ->
                SManga.create().apply {
                    url = edge._id
                    title = edge.title()
                    thumbnail_url = edge.coverUrl()
                }
            }
        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = graphqlRequest(
        query = QUERY_MANGA_DETAILS,
        variables = buildJsonObject {
            put("_id", manga.url)
        },
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = response.parseAs<MangaDetailDto>().data?.manga
            ?: throw IOException("Manga not found")

        return detail.toSManga(
            showAltNames = preferences.showAltNames(),
            showExtraInfo = preferences.showExtraInfo(),
            showTagsInGenre = preferences.showTagsInGenre(),
            blockedGenres = preferences.blockedGenres(),
            scorePosition = preferences.scorePosition(),
        )
    }

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val detail = response.parseAs<MangaDetailDto>().data?.manga
            ?: throw IOException("Manga not found")
        return detail.toChapterList()
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/", headers)

    override fun pageListParse(response: Response): List<Page> = throw IOException(
        "MKissa gates chapter pages behind a captcha that this extension cannot " +
            "solve. Open $baseUrl in your browser to read this chapter on the site.",
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
        YearFilter(),
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf(
                "Top",
                "Trending",
                "Popular",
                "Latest update",
                "Recently added",
                "Release year",
                "Name",
                "Random",
            ),
            Filter.Sort.Selection(0, false),
        )

    private class GenreTriState(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it, it) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

        private companion object {
            // Site genre/tag names (AniList-style spelling, verified live).
            val GENRES = listOf(
                "Action", "Adventure", "Comedy", "Crossdressing", "Demons", "Drama",
                "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical", "Horror",
                "Isekai", "Josei", "Magic", "Martial Arts", "Mecha", "Military",
                "Music", "Mystery", "One Shot", "Police", "Psychological", "Romance",
                "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
                "Space", "Sports", "Super Power", "Supernatural", "Thriller",
                "Tragedy", "Vampires", "Webtoons", "Manhua", "Manhwa", "Doujinshi",
            )
        }
    }

    private class YearFilter : Filter.Text("Year (e.g. 2024)")

    // ========================================================================
    // Settings (Comix-style)
    // ========================================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        MultiSelectListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Default content rating"
            summary = "Ratings to show by default (selecting Pornographic enables adult content)"
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf("safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(setOf("safe", "suggestive"))
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
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
            summary = "Include tags (theme, format) in the genre field"
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

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.scorePosition(): String = getString(PREF_SCORE_POSITION, "top") ?: "top"

    private fun android.content.SharedPreferences.defaultContentRatings(): Set<String> = getStringSet(PREF_CONTENT_RATING, setOf("safe", "suggestive")) ?: setOf("safe", "suggestive")

    companion object {
        private const val PAGE_SIZE = 24
        private val SORT_OPTIONS = arrayOf(
            "Top",
            "Trending",
            "Popular",
            "Latest_Update",
            "Recent",
            "Release_Year",
            "Name_ASC",
            "Random",
        )
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"

        // GraphQL documents ------------------------------------------------

        private const val QUERY_POPULAR = """
            query(${'$'}type: VaildPopularTypeEnumType!, ${'$'}size: Int!, ${'$'}page: Int, ${'$'}dateRange: Int, ${'$'}allowAdult: Boolean, ${'$'}allowUnknown: Boolean, ${'$'}denyEcchi: Boolean) {
              queryPopular(type: ${'$'}type, size: ${'$'}size, page: ${'$'}page, dateRange: ${'$'}dateRange, allowAdult: ${'$'}allowAdult, allowUnknown: ${'$'}allowUnknown, denyEcchi: ${'$'}denyEcchi) {
                recommendations {
                  anyCard {
                    _id
                    name
                    englishName
                    nativeName
                    thumbnail
                    tbObj { u }
                  }
                }
              }
            }
        """

        private const val QUERY_MANGA_LIST = """
            query(${'$'}search: SearchInput, ${'$'}page: Int, ${'$'}limit: Int) {
              mangas(search: ${'$'}search, page: ${'$'}page, limit: ${'$'}limit, translationType: sub) {
                edges {
                  _id
                  name
                  englishName
                  nativeName
                  thumbnail
                  tbObj { u }
                }
              }
            }
        """

        // IMPORTANT: `airedStart` and `availableChaptersDetail` are opaque
        // "Object" scalar fields — they must NOT carry a sub-selection.
        // Selecting subfields on them is a GraphQL validation error and the
        // whole details query answers HTTP 400 (details/chapters never loaded).
        private const val QUERY_MANGA_DETAILS = """
            query(${'$'}_id: String!) {
              manga(_id: ${'$'}_id) {
                _id
                name
                englishName
                nativeName
                altNames
                authors
                description
                status
                type
                genres
                tags
                thumbnail
                tbObj { u }
                airedStart
                score
                averageScore
                pageStatus { userScoreAverValue }
                availableChaptersDetail
              }
            }
        """
    }
}
