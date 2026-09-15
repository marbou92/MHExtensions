package eu.kanade.tachiyomi.extension.en.mkissa

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Source
abstract class MKissa :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val apiHost = "api.mkissa.net"
    private val apiUrl = "https://$apiHost/api"

    // Persisted GraphQL operation hashes (Apollo persisted queries).
    // These are stable identifiers of server-side queries.
    private val hashPopular = "e5d9d7742fa20e19e517b075cf67703cfcd152279e4552432ac1e9e51c62b15c"
    private val hashQueryManga = "6597aaed54521e7a29a2f8567c7adc9f59ab573b9f8c514c49dd9fd215645458"
    private val hashMangaById = "1b17565a2ecb1213402c861c6e644105f28534b25685219900eb0167200b97e7"

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

    // ------------------------------------------------------------------
    // GraphQL helpers
    // ------------------------------------------------------------------

    private fun graphqlRequest(variables: JsonObject, hash: String): Request {
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", hash)
            }
        }

        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()

        return GET(url.toString(), headers)
    }

    private val allowAdult: Boolean
        get() = preferences.defaultContentRatings().contains("pornographic")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = graphqlRequest(
        variables = buildJsonObject {
            put("type", "manga")
            put("size", PAGE_SIZE)
            put("dateRange", 1)
            put("page", page)
            put("allowAdult", allowAdult)
            put("allowUnknown", false)
        },
        hash = hashPopular,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val popular = response.parseAs<PopularResponse>().data?.queryPopular
        val cards = popular?.recommendations?.mapNotNull { it.anyCard }.orEmpty()

        val mangas = cards.map { card ->
            SManga.create().apply {
                url = card._id
                title = card.title()
                thumbnail_url = card.coverUrl()
            }
        }

        val total = popular?.total ?: 0
        val hasNext = cards.size >= PAGE_SIZE && page * PAGE_SIZE < total
        return MangasPage(mangas, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        mangaListRequest(page, "Trending", "")

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sortBy = "Top"
        filters.forEach { filter ->
            if (filter is SortFilter && filter.state?.index != null) {
                sortBy = SORT_OPTIONS[filter.state!!.index]
            }
        }
        return mangaListRequest(page, sortBy, query)
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    private fun mangaListRequest(page: Int, sortBy: String, query: String): Request =
        graphqlRequest(
            variables = buildJsonObject {
                putJsonObject("search") {
                    put("sortBy", sortBy)
                    put("isManga", true)
                    if (query.isNotBlank()) put("query", query)
                    put("listProfile", "browse")
                    put("allowAdult", allowAdult)
                    put("allowUnknown", false)
                    put("denyEcchi", false)
                    put("lite", false)
                }
                put("limit", PAGE_SIZE)
                put("page", page)
                put("translationType", "sub")
            },
            hash = hashQueryManga,
        )

    private fun mangaListParse(response: Response): MangasPage {
        val mangas = response.parseAs<MangaListResponse>()
            .data?.mangas?.edges
            .orEmpty()
            .map { it.toCard() }
            .map { card ->
                SManga.create().apply {
                    url = card._id
                    title = card.title()
                    thumbnail_url = card.coverUrl()
                }
            }
        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = graphqlRequest(
        variables = buildJsonObject {
            put("_id", manga.url)
            putJsonObject("search") {
                put("allowAdult", allowAdult)
                put("allowUnknown", false)
                put("denyEcchi", false)
                put("lite", false)
                put("forMe", false)
            }
        },
        hash = hashMangaById,
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = response.parseAs<MangaDetailResponse>().data?.manga
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
        val detail = response.parseAs<MangaDetailResponse>().data?.manga
            ?: throw IOException("Manga not found")
        return detail.toChapterList()
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/", headers)

    override fun pageListParse(response: Response): List<Page> = throw IOException(
        "MKissa protects its chapter images with a rotating anti-bot token that is " +
            "generated by their own JavaScript and cannot be reproduced by this " +
            "extension. Open ${chapter.url.let { "$baseUrl$it" }} in your browser to " +
            "read this chapter on the site.",
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf("Top", "Trending", "Popular", "Random"),
            Filter.Sort.Selection(0, false),
        )

    private companion object {
        val SORT_OPTIONS = arrayOf("Top", "Trending", "Popular", "Random")
        const val PAGE_SIZE = 24
    }

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
            summary = "Display year, type, status and rating"
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
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.blockedGenres(): Set<String> =
        getString(PREF_BLOCKED_GENRES, "")
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    private fun android.content.SharedPreferences.showAltNames(): Boolean =
        getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean =
        getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean =
        getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.scorePosition(): String =
        getString(PREF_SCORE_POSITION, "end") ?: "end"

    private fun android.content.SharedPreferences.defaultContentRatings(): Set<String> =
        getStringSet(PREF_CONTENT_RATING, setOf("safe", "suggestive")) ?: setOf("safe", "suggestive")

    companion object {
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}
