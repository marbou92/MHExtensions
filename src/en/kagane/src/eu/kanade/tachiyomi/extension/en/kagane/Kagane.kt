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
                // NOTE: `statuses`/`formats` carry the API's own enum values
                // (e.g. "Abandoned", not the display label "Cancelled"), and
                // genres carry the site's genre UUIDs — display names make the
                // search endpoint answer HTTP 400.
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
        scorePosition = preferences.scorePosition(),
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

    internal class GenreTriState(val label: String, val id: String) : Filter.TriState(label)

    /**
     * Genre filter with include/exclude states. The API does NOT accept genre
     * names — `genres.values`/`exclude` must carry the site's genre UUIDs
     * (sending display names is exactly what caused the HTTP 400 on filtered
     * searches). Pairs mirror the site's own browse filters.
     */
    internal class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it.first, it.second) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.id }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.id }

        private companion object {
            val GENRES = listOf(
                "4-koma" to "019bef86-56a6-71a1-af75-c1e43f48fba4", "AI" to "019c3a02-5288-777a-b5d8-1bf9f79163ae",
                "Action" to "019c1fe4-fabf-7242-a0a8-7866f98f705e", "Adventure" to "019c1b6f-b682-7fee-9a9b-86e648b77e9e",
                "Award Winning" to "019c1fea-1b14-72d5-b2f8-c3194c4e1efe", "Comedy" to "019c1fe5-51fe-7f26-aee3-f057ac50f81d",
                "Coming of Age" to "019c3a02-b00d-731d-884c-41460acc81e4", "Cooking" to "019c2043-f726-7f78-9c58-7caf4662bf1f",
                "Crime" to "019c2043-d238-744b-8d5a-21bbc5166bd7", "Demons" to "019c1fe8-cad1-7842-82a7-db5a87fc0a5a",
                "Doujinshi" to "019c1fea-2ddc-7e74-9935-a8b1b7782dd2", "Drama" to "019c1b6f-3d3b-7528-a3e2-a791cfdcc4f7",
                "Ecchi" to "019c200d-6238-7364-9422-c50bd75e4e0d", "Fan Colored" to "019c1fe9-da2f-7938-a795-2b862b221cb3",
                "Fantasy" to "019c1b6f-51ed-7991-a796-a8ca26e86666", "Full Color" to "019c1fe9-9527-7f47-bf28-36ab2d6d4acd",
                "Gender Bender" to "019c1fe7-5189-7619-9094-6e4b85c139ec", "Gore" to "019c1fe6-6717-77fa-8205-85d8e8a8755c",
                "Harem" to "019c1fe8-74eb-73e6-840e-7916f145012e", "Hentai" to "019c3a01-d241-7d44-836d-095b98d4dc1e",
                "Historical" to "019c1fe6-7ea3-7741-9191-5ba94f6e9552", "Horror" to "019c1b6f-c2e3-7909-93cf-0a48a2f2278f",
                "Isekai" to "019c1fe6-3ba5-7e76-aa0d-8756c314e2fa", "Josei" to "019c1b70-101d-7c9c-a28c-cc2439104443",
                "LGBTQIA+" to "019c1feb-8388-7c89-8c2d-ddfb530065e6", "Magic" to "019c1fe8-2221-7d69-9cd2-b67d64924994",
                "Magical Girls" to "019c1fe7-13fa-7019-a5a6-e7bbceb200f2", "Martial Arts" to "019c1fe8-4c93-7f12-a6f0-e805fe4ade21",
                "Mecha" to "019c2044-1fd9-7e84-b9c9-cbf20945a664", "Medical" to "019c1fe7-2c1e-730c-af33-ca3bef3a161d",
                "Military" to "019c1fe8-e822-7938-a04c-dee0a24f022c", "Monsters" to "019c1fe8-3261-7c3d-9103-428f9a54eb41",
                "Music" to "019c200f-1825-738c-b7c1-67266b9520b1", "Mystery" to "019c1b6f-7991-7edb-8c7f-df6876f09a94",
                "Office Workers" to "019c1fe8-a048-7453-a06d-212678bf777a", "Official Colored" to "019c1fe9-f0a1-71c6-bbe0-6afc3822647a",
                "Omegaverse" to "019c22cd-bb85-7541-9db6-7e6ea3394d0b", "Oneshot" to "019c1fe9-ae8d-7294-bcc8-9bd4269b94d7",
                "Philosophical" to "019c1fe7-380c-72a5-8668-930c5e90ec44", "Police" to "019c1fe9-4fe3-776a-b11c-a14d76a52e7d",
                "Post-Apocalyptic" to "019c1fe9-0e7c-7782-8265-c29ee6c19e79", "Psychological" to "019c1ee5-dfa9-7b69-86a8-16831c1d81fe",
                "Reincarnation" to "019c1fe8-5a6d-73bd-835d-16ae059bce6e", "Reverse Harem" to "019c1fe8-8837-78de-a911-f54754c2e713",
                "Romance" to "019beb9a-a36a-718c-b551-d917a0818cc7", "School Life" to "019c1fe7-db44-77a1-acc2-28c2bbb49ae1",
                "Sci-Fi" to "019c1fe5-b704-72a6-a676-a8ad87ebcdd8", "Seinen" to "019c1fea-983f-7279-944e-8fb6e2cccdbc",
                "Shoujo" to "019c1b6f-fe1c-7eed-b93b-e1bf6dff63fd", "Shoujo Ai" to "019c1ee5-626f-7284-b5e8-8f3df5c5f76b",
                "Shounen" to "019c1fea-85c7-76a7-996a-b41ce0cb05f8", "Shounen Ai" to "019c1ee5-4be9-766d-b545-5b87d7cdff96",
                "Slice of Life" to "019c1fe5-84d9-7a67-9240-1f303dc231bc", "Smut" to "019c2042-2a6f-7bcb-9c29-6098b8426aee",
                "Sports" to "019c1fe6-bc40-7aa4-bac6-29a6cc29b559", "Supernatural" to "019c1b6f-9c7b-77a0-8b54-e62925422b91",
                "Survival" to "019c1fe8-b2ab-7e15-a55b-9fb28901cc9d", "Thriller" to "019c1fe6-0228-7052-98a2-fe6df7994d93",
                "Time Travel" to "019c1fe8-f932-7a19-a502-4ad6a2adfae4", "Tragedy" to "019c1fe6-211d-7e73-927d-3c54b66a9055",
                "Vampires" to "019c1fe9-78cc-7d2e-be56-cc3f8442ccf0", "Video Games" to "019c1fe9-21a7-7bbd-9763-10a5855a8e5c",
                "Villainess" to "019c1fe9-3609-73d2-b4bf-8420cc15c5b2", "Wuxia" to "019c1fe7-0344-755b-b4df-c019f4cdbc21",
                "Yaoi" to "019c1ee5-38f9-7324-9f7d-07a01dbf88a6", "Yuri" to "019c1ee5-7e79-7fd2-8fcc-de739858c851",
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
                // Display label -> API enum value. The API's cancelled value is
                // "Abandoned"; sending "Cancelled" causes HTTP 400.
                RatingCheckBox("Ongoing", "Ongoing"),
                RatingCheckBox("Completed", "Completed"),
                RatingCheckBox("Hiatus", "Hiatus"),
                RatingCheckBox("Cancelled", "Abandoned"),
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

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the site rating"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("top")
        }.let(screen::addPreference)

        // Button-like row (tap to clear). Implemented as a switch that snaps
        // back off because the compile-time stub of androidx.preference does
        // not expose the plain Preference(Context) constructor.
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_CLEAR_CF_COOKIES
            title = "Reset Cloudflare clearance"
            summary = "Clears the cached Cloudflare cookies for kagane.to. Use this when requests keep failing after the site updated its protection - then open the source in WebView once to solve the new challenge."
            isChecked = false
            setOnPreferenceClickListener {
                clearCloudflareCookies()
                isChecked = false
                true
            }
        }.let(screen::addPreference)
    }

    /**
     * Deletes every cookie kagane.to stored in the WebView so the next
     * WebView visit performs a fresh Cloudflare solve.
     */
    private fun clearCloudflareCookies() {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val url = "$baseUrl/"
        val names = cookieManager.getCookie(url)
            ?.split(";")
            ?.mapNotNull { it.trim().substringBefore("=").takeIf(String::isNotEmpty) }
            .orEmpty()

        names.forEach { name ->
            cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
            cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$domain")
        }
        cookieManager.flush()
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
        private const val PREF_SCORE_POSITION = "pref_score_position"
        private const val PREF_CLEAR_CF_COOKIES = "pref_clear_cf_cookies"
    }
}
