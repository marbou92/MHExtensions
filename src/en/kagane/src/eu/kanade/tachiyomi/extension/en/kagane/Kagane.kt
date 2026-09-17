package eu.kanade.tachiyomi.extension.en.kagane

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA = "application/json".toMediaType()

private fun jsonBody(json: JsonObject) = json.toString().toRequestBody(JSON_MEDIA)

@Source
abstract class Kagane :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val domain get() = baseUrl.removePrefix("https://")
    private val apiUrl get() = "https://$domain/api/v2"

    // ------------------------------------------------------------------
    // Modern Cloudflare bypass (browser fingerprint + cookie sync + retry)
    // ------------------------------------------------------------------

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            CloudflareBypass(setOf(domain)).install(this)
        }
        .addInterceptor(::imageTokenInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val apiHeaders by lazy {
        headersBuilder().set("Accept", "application/json").build()
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = searchRequest(
        page,
        "",
        SortFilter(Filter.Sort.Selection(1, false)),
        defaultContentRatings(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
    )

    override fun popularMangaParse(response: Response): MangasPage = searchParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = searchRequest(
        page,
        "",
        SortFilter(Filter.Sort.Selection(6, false)),
        defaultContentRatings(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = searchParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sortFilter = SortFilter()
        val contentRatings = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val formats = mutableListOf<String>()
        val genresIncluded = mutableListOf<String>()
        val genresExcluded = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortFilter = filter
                is ContentRatingFilter -> contentRatings.addAll(
                    filter.state.filter { it.state }.map { it.value },
                )
                is StatusFilter -> statuses.addAll(
                    filter.state.filter { it.state }.map { it.value },
                )
                is FormatFilter -> formats.addAll(
                    filter.state.filter { it.state }.map { it.value },
                )
                is GenreFilter -> {
                    genresIncluded.addAll(filter.included())
                    genresExcluded.addAll(filter.excluded())
                }
                else -> {}
            }
        }

        return searchRequest(
            page,
            query,
            sortFilter,
            contentRatings.ifEmpty { defaultContentRatings() },
            statuses,
            formats,
            genresIncluded,
            genresExcluded,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = searchParse(response)

    private fun searchRequest(
        page: Int,
        query: String,
        sortFilter: SortFilter,
        contentRatings: List<String>,
        statuses: List<String>,
        formats: List<String>,
        genresIncluded: List<String>,
        genresExcluded: List<String>,
    ): Request {
        val sortParam = sortFilter.toUriPart()

        val body = buildJsonObject {
            if (query.isNotBlank()) put("title", query)
            putJsonArray("source_type") {
                add("Official")
                add("Unofficial")
                add("Mixed")
            }
            putJsonArray("content_rating") {
                // The API requires the capitalized enum values ("Safe",
                // "Suggestive", ...). Lowercase values make it answer HTTP 400
                // even with a valid Cloudflare clearance.
                contentRatings.forEach { add(normalizeRating(it)) }
            }
            putJsonArray("content_lang") {
                add("en")
            }
            if (statuses.isNotEmpty()) {
                putJsonArray("upload_status") { statuses.forEach { add(it) } }
            }
            if (formats.isNotEmpty()) {
                putJsonArray("format") { formats.forEach { add(it) } }
            }
            if (genresIncluded.isNotEmpty() || genresExcluded.isNotEmpty()) {
                putJsonObject("genres") {
                    putJsonArray("values") { genresIncluded.forEach { add(it) } }
                    put("match_all", true)
                    if (genresExcluded.isNotEmpty()) {
                        putJsonArray("exclude") { genresExcluded.forEach { add(it) } }
                    }
                }
            }
        }

        val url = "$apiUrl/search/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("size", PAGE_SIZE.toString())
            .apply {
                if (sortParam.isNotEmpty()) addQueryParameter("sort", sortParam)
            }
            .build()

        return POST(url.toString(), apiHeaders, jsonBody(body))
    }

    private fun searchParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.content.map { book ->
            SManga.create().apply {
                url = book.id
                title = book.title.trim()
                thumbnail_url = book.coverImage?.let { "$apiUrl/image/$it" }
            }
        }
        return MangasPage(mangas, dto.hasNextPage())
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/${manga.url}", apiHeaders)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<DetailsDto>().toSManga(
        apiUrl = apiUrl,
        seriesId = response.request.url.encodedPath
            .substringAfter("/series/")
            .substringBefore("/"),
        showAltNames = preferences.showAltNames(),
        showExtraInfo = preferences.showExtraInfo(),
        showTagsInGenre = preferences.showTagsInGenre(),
        blockedGenres = preferences.blockedGenres(),
    )

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/series/${manga.url}", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesId = response.request.url.encodedPath
            .substringAfter("/series/")
            .substringBefore("/")
        val details = response.parseAs<DetailsDto>()

        val chapters = details.seriesBooks.map {
            it.toSChapter(seriesId)
        }

        // API returns oldest → newest; Mihon expects newest first.
        val ordered = chapters.reversed()

        if (!preferences.deduplicateChapters()) return ordered

        // Keep only the first chapter per number (they are newest-first).
        val seen = mutableSetOf<Float>()
        return ordered.filter { ch ->
            val key = ch.chapter_number
            if (key <= 0f) return@filter true
            seen.add(key)
        }
    }

    // =============================== Pages ===============================

    /**
     * The book manifest needs a short-lived integrity token (~5 min TTL). Mint
     * (or reuse) it here and return the book-token request itself, so the app
     * executes it with the `x-integrity-token` header already attached. Blocking
     * inside request builders is the established pattern in this codebase.
     */
    override fun pageListRequest(chapter: SChapter): Request {
        val bookId = chapter.url.substringAfterLast("/reader/")
        val token = getIntegrityToken()

        val challengeUrl = "$apiUrl/books/$bookId".toHttpUrl().newBuilder()
            .addQueryParameter("is_datasaver", dataSaver.toString())
            .build()

        return POST(
            challengeUrl.toString(),
            headers.newBuilder().add("x-integrity-token", token).build(),
            "{}".toRequestBody(JSON_MEDIA),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val bookId = response.request.url.encodedPath
            .substringAfter("/books/")
            .substringBefore("?")
        val challenge = response.parseAs<ChallengeDto>()
        accessToken = challenge.accessToken

        val cacheUrl = challenge.cacheUrl.trimEnd('/')
        val pages = challenge.manifest?.pages ?: emptyList()

        return pages.map { page ->
            val pageUrl = "$cacheUrl/api/v2/books/page".toHttpUrl().newBuilder()
                .apply {
                    if (dataSaver) addPathSegment("datasaver")
                    addPathSegment(bookId)
                    addPathSegment("${page.pageId}.${page.ext ?: "jxl"}")
                    addQueryParameter("token", challenge.accessToken)
                }
                .build()
                .toString()
            Page(page.pageNumber, imageUrl = pageUrl)
        }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // --------------------------------------------------------------
    // Integrity token (short-lived, ~5 minutes TTL) + access token
    // --------------------------------------------------------------

    @Volatile
    private var accessToken: String = ""

    @Volatile
    private var integrityToken: String = ""

    @Volatile
    private var integrityExp: Long = 0L

    private val dataSaver: Boolean
        get() = preferences.getBoolean(PREF_DATA_SAVER, false)

    private fun getIntegrityToken(): String {
        if (integrityExp > System.currentTimeMillis()) return integrityToken

        // Warm up the Cloudflare session before minting a token.
        client.newCall(GET("$baseUrl/", headers)).execute().close()

        val res = client.newCall(
            POST("$baseUrl/api/integrity", headers, "".toRequestBody(null)),
        ).execute().use { it.parseAs<IntegrityDto>() }

        integrityToken = res.token
        // `exp` is unix seconds; refresh a minute early to be safe.
        integrityExp = if (res.exp > 0) {
            (res.exp - 60) * 1000
        } else {
            System.currentTimeMillis() + 4 * 60 * 1000
        }

        return integrityToken
    }

    private fun buildChallenge(bookId: String): ChallengeDto {
        val token = getIntegrityToken()
        val challengeUrl = "$apiUrl/books/$bookId".toHttpUrl().newBuilder()
            .addQueryParameter("is_datasaver", dataSaver.toString())
            .build()

        val challenge = client.newCall(
            POST(
                challengeUrl.toString(),
                headers.newBuilder().add("x-integrity-token", token).build(),
                "{}".toRequestBody(JSON_MEDIA),
            ),
        ).execute().use { it.parseAs<ChallengeDto>() }

        accessToken = challenge.accessToken
        return challenge
    }

    /**
     * Page image requests carry a short-lived `?token=`. When it expires the CDN
     * answers 401/403/507 — re-mint and retry transparently.
     */
    private fun imageTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.queryParameterNames.contains("token")) {
            return chain.proceed(request)
        }

        val bookId = if (url.pathSegments.getOrNull(4) == "datasaver") {
            url.pathSegments.getOrNull(5).orEmpty()
        } else {
            url.pathSegments.getOrNull(4).orEmpty()
        }

        var response = chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                .build(),
        )

        if (response.code == 401 || response.code == 403 || response.code == 507) {
            response.close()
            val challenge = runCatching { buildChallenge(bookId) }
                .getOrElse { throw IOException("Failed to refresh the page token. Try again.") }

            accessToken = challenge.accessToken
            response = chain.proceed(
                request.newBuilder()
                    .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                    .build(),
            )
        }

        return response
    }

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
        FormatFilter(),
        StatusFilter(),
        ContentRatingFilter(),
    )

    internal class SortFilter(
        selection: Filter.Sort.Selection = Filter.Sort.Selection(0, false),
    ) : Filter.Sort(
        "Sort by",
        arrayOf(
            "Relevance",
            "Popular (total views)",
            "Popular (average views)",
            "Popular (today)",
            "Popular (week)",
            "Popular (month)",
            "Latest update",
            "Title",
            "Book count",
            "Recently added",
        ),
        selection,
    ) {
        fun toUriPart(): String {
            val base = SORT_OPTIONS.getOrNull(state?.index ?: 0).orEmpty()
            return if (base.isEmpty()) "" else base + if (state?.ascending == true) "" else ",desc"
        }

        private companion object {
            val SORT_OPTIONS = listOf(
                "",
                "total_views",
                "avg_views",
                "avg_views_today",
                "avg_views_week",
                "avg_views_month",
                "updated_at",
                "series_name",
                "books_count",
                "created_at",
            )
        }
    }

    internal class GenreTriState(name: String, val value: String) : Filter.TriState(name)

    /**
     * Genre filter with include/exclude states. Values are the site's own
     * genre names (verified against the website's browse filters).
     */
    internal class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it, it) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

        private companion object {
            val GENRES = listOf(
                "4-koma", "AI", "Action", "Adventure", "Award Winning", "Comedy",
                "Coming of Age", "Cooking", "Crime", "Demons", "Doujinshi", "Drama",
                "Ecchi", "Fan Colored", "Fantasy", "Full Color", "Gender Bender",
                "Gore", "Harem", "Hentai", "Historical", "Horror", "Isekai", "Josei",
                "LGBTQIA+", "Magic", "Magical Girls", "Martial Arts", "Mecha",
                "Medical", "Military", "Monsters", "Music", "Mystery",
                "Office Workers", "Official Colored", "Omegaverse", "Oneshot",
                "Philosophical", "Police", "Post-Apocalyptic", "Psychological",
                "Reincarnation", "Reverse Harem", "Romance", "School Life", "Sci-Fi",
                "Seinen", "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai",
                "Slice of Life", "Smut", "Sports", "Supernatural", "Survival",
                "Thriller", "Time Travel", "Tragedy", "Vampires", "Video Games",
                "Villainess", "Wuxia", "Yaoi", "Yuri",
            )
        }
    }

    private class RatingCheckBox(
        name: String,
        val value: String,
        state: Boolean = false,
    ) : Filter.CheckBox(name, state)

    private class FormatFilter :
        Filter.Group<RatingCheckBox>(
            "Format",
            listOf(
                RatingCheckBox("Manga", "Manga"),
                RatingCheckBox("Manhwa", "Manhwa"),
                RatingCheckBox("Manhua", "Manhua"),
                RatingCheckBox("Comic", "Comic"),
                RatingCheckBox("Other", "Other"),
            ),
        )

    private class StatusFilter :
        Filter.Group<RatingCheckBox>(
            "Status",
            listOf(
                RatingCheckBox("Ongoing", "Ongoing"),
                RatingCheckBox("Completed", "Completed"),
                RatingCheckBox("Hiatus", "Hiatus"),
                RatingCheckBox("Cancelled", "Cancelled"),
            ),
        )

    private class ContentRatingFilter :
        Filter.Group<RatingCheckBox>(
            "Content rating",
            listOf(
                // Capitalized values are required by the API (lowercase ones
                // cause HTTP 400 responses even after a WebView clearance).
                RatingCheckBox("Safe", "Safe", true),
                RatingCheckBox("Suggestive", "Suggestive", true),
                RatingCheckBox("Erotica", "Erotica"),
                RatingCheckBox("Pornographic", "Pornographic"),
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
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
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
            summary = "Keep only one chapter per number (useful when multiple groups upload the same chapter)"
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
            summary = "Display year, format, status and content rating"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genre chips"
            summary = "Include tags in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DATA_SAVER
            title = "Data saver"
            summary = "Download smaller recompressed images"
            setDefaultValue(false)
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

    /**
     * The API only accepts the capitalized enum values. Older builds saved
     * lowercase values in the preferences, so normalize whatever comes out.
     */
    private fun normalizeRating(value: String): String = value.lowercase().replaceFirstChar { it.uppercase() }

    private fun defaultContentRatings(): List<String> = preferences.getStringSet(PREF_CONTENT_RATING, setOf("Safe", "Suggestive"))
        ?.map(::normalizeRating)
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("Safe", "Suggestive")

    companion object {
        private const val PAGE_SIZE = 35

        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_DATA_SAVER = "pref_data_saver"
    }
}
