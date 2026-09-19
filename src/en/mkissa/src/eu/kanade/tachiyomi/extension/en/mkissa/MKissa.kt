package eu.kanade.tachiyomi.extension.en.mkissa

import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MKissa :
    KeiSource(),
    ConfigurableSource {

    private val preferences = getPreferences()

    private val apiHost = "api.mkissa.net"
    private val apiUrl = "https://$apiHost/api"

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

        // MKissa's API sits on a separate host: sync WebView cookies for both
        // the site and the API host and fingerprint all requests.
        CloudflareBypass(setOf(baseUrl.removePrefix("https://"), apiHost)).install(this)

        rateLimit(2)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        add("Referer", "$baseUrl/")
        add("Origin", baseUrl)
        add("Accept", "application/json")
    }

    /**
     * Executes a plain GraphQL query against the API. The API supports
     * introspection and full queries, so we do not depend on fragile
     * persisted-query hashes.
     *
     * `captchaToken` (optional) is attached as `extensions.captcha` exactly
     * like the site's own client does after solving its Turnstile challenge:
     * `{"query":..., "variables":..., "extensions":{"captcha":{"token":..,"provider":..}}}`
     */
    private suspend fun graphql(
        query: String,
        variables: JsonObject,
        captchaToken: Pair<String, String>? = null,
    ): Response = client.post(
        apiUrl,
        buildJsonObject {
            put("query", query)
            put("variables", variables)
            if (captchaToken != null) {
                put(
                    "extensions",
                    buildJsonObject {
                        put(
                            "captcha",
                            buildJsonObject {
                                put("token", captchaToken.first)
                                put("provider", captchaToken.second)
                            },
                        )
                    },
                )
            }
        }.toJsonRequestBody(),
    )

    /** Runs [parse] and reports whether the GraphQL body contains NEED_CAPTCHA. */
    private inline fun <T> Response.useAndDetectCaptcha(parse: (Response) -> T): Pair<T?, Boolean> = use { response ->
        val body = response.body.string()
        val captcha = body.contains("NEED_CAPTCHA")
        if (captcha) {
            null to true
        } else {
            // Re-wrap the already-read body so parse() can treat it normally
            parse(
                response.newBuilder()
                    .body(body.toResponseBody(response.body.contentType()))
                    .build(),
            ) to false
        }
    }

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
        genresMatchAll: Boolean,
        tags: List<String>,
        tagsExcluded: List<String>,
        authors: List<String>,
        year: Int?,
        season: String?,
        minChapters: Int?,
    ): JsonObject = buildJsonObject {
        putJsonObject("search") {
            put("sortBy", sortBy)
            put("sortDirection", if (ascending) "ASC" else "DSC")
            put("isManga", true)
            if (query.isNotBlank()) put("query", query)
            if (genres.isNotEmpty()) putJsonArray("genres") { genres.forEach { add(it) } }
            if (genresExcluded.isNotEmpty()) putJsonArray("excludeGenres") { genresExcluded.forEach { add(it) } }
            // includeGenres=true (default) matches ALL selected genres,
            // false matches ANY of them. Verified live.
            if (genres.isNotEmpty()) put("includeGenres", genresMatchAll)
            if (tags.isNotEmpty()) putJsonArray("tags") { tags.forEach { add(it) } }
            if (tagsExcluded.isNotEmpty()) putJsonArray("excludeTags") { tagsExcluded.forEach { add(it) } }
            if (authors.isNotEmpty()) putJsonArray("authors") { authors.forEach { add(it) } }
            if (year != null) put("year", year)
            if (season != null) put("season", season)
            if (minChapters != null) put("epRangeStart", minChapters)
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

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = graphql(
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
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = graphql(
            query = QUERY_MANGA_LIST,
            variables = baseSearchVariables(
                page = page,
                sortBy = "Latest_Update",
                ascending = false,
                query = "",
                genres = emptyList(),
                genresExcluded = emptyList(),
                genresMatchAll = true,
                tags = emptyList(),
                tagsExcluded = emptyList(),
                authors = emptyList(),
                year = null,
                season = null,
                minChapters = null,
            ),
        )

        return mangaListParse(response)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sortBy = "Top"
        var ascending = false
        val genres = mutableListOf<String>()
        val genresExcluded = mutableListOf<String>()
        var genresMatchAll = true
        val tags = mutableListOf<String>()
        val tagsExcluded = mutableListOf<String>()
        val authors = mutableListOf<String>()
        var year: Int? = null
        var season: String? = null
        var minChapters: Int? = null

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
                is GenreMatchModeFilter -> genresMatchAll = filter.matchAll()
                is TagFilter -> {
                    tags.addAll(filter.included())
                    tagsExcluded.addAll(filter.excluded())
                }
                is AuthorFilter -> {
                    filter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .let { authors.addAll(it) }
                }
                is SeasonFilter -> season = filter.toValue()
                is YearFilter -> year = filter.state.trim().toIntOrNull()
                is MinChaptersFilter -> minChapters = filter.state.trim().toIntOrNull()
                else -> {}
            }
        }

        val response = graphql(
            query = QUERY_MANGA_LIST,
            variables = baseSearchVariables(
                page = page,
                sortBy = sortBy,
                ascending = ascending,
                query = query,
                genres = genres,
                genresExcluded = genresExcluded,
                genresMatchAll = genresMatchAll,
                tags = tags,
                tagsExcluded = tagsExcluded,
                authors = authors,
                year = year,
                season = season,
                minChapters = minChapters,
            ),
        )

        return mangaListParse(response)
    }

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

    // ====================== Manga Details + Chapters ======================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.removePrefix("https://")) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "manga") return null
        val mangaId = segments[1]

        return fetchMangaDetails(mangaId).apply { initialized = true }
    }

    private suspend fun fetchMangaDetails(mangaId: String): SManga {
        val response = graphql(
            query = QUERY_MANGA_DETAILS,
            variables = buildJsonObject { put("_id", mangaId) },
        )

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

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val response = graphql(
            query = QUERY_MANGA_DETAILS,
            variables = buildJsonObject { put("_id", manga.url) },
        )

        val detail = response.parseAs<MangaDetailDto>().data?.manga
            ?: throw IOException("Manga not found")

        val detailsDeferred = async {
            if (fetchDetails) {
                detail.toSManga(
                    showAltNames = preferences.showAltNames(),
                    showExtraInfo = preferences.showExtraInfo(),
                    showTagsInGenre = preferences.showTagsInGenre(),
                    blockedGenres = preferences.blockedGenres(),
                    scorePosition = preferences.scorePosition(),
                )
            } else {
                manga
            }
        }

        val chaptersDeferred = async {
            if (fetchChapters) {
                detail.toChapterList()
            } else {
                chapters
            }
        }

        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    // =============================== Pages ===============================
    //
    // Reverse engineered reader flow (verified against the site's JS, 2026-09):
    //
    // 1. The reader POSTs `chapterPages` GraphQL queries to api.mkissa.net.
    // 2. The server answers NEED_CAPTCHA until the request carries the site's
    //    Cloudflare Turnstile token inside `extensions.captcha`
    //    ({"token": <token>, "provider": "turnstile1"}) — verified live: the
    //    error changes from NEED_CAPTCHA to "Error Re-captcha!" once the
    //    extensions block exists, so this is the accepted envelope.
    // 3. The site solves Turnstile in a hidden iframe page
    //    (https://api.mkissa.net/captcha/turnstile, sitekey
    //    0x4AAAAAADXpHZ1lTeqKwhch) which posts {type:"sitea-captcha-ready",
    //    token, provider} to its parent window (class "LL" in the site bundle).
    // 4. The retry returns `chapterPages.edges[].pictureUrls` — relative paths
    //    resolved against `pictureUrlHead` (or absolute URLs used as-is).

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // url format: "/manga/<mangaId>/chapter-<chapterString>-<translation>"
        // (blank segments filtered so leading/trailing slashes don't matter)
        val parts = chapter.url.split("/").filter(String::isNotBlank)
        if (parts.size < 3) throw IOException("Outdated chapter URL. Refresh the chapter list.")
        val mangaId = parts[1]
        val chapterInfo = parts[2].removePrefix("chapter-")
        val chapterString = chapterInfo.substringBeforeLast("-")
        val translation = chapterInfo.substringAfterLast("-", "sub")

        var response = graphql(
            query = QUERY_CHAPTER_PAGES,
            variables = buildJsonObject {
                put("_id", mangaId)
                put("translationType", translation)
                put("chapterString", chapterString)
            },
        )

        var captchaDetected = false
        var parsed: ChapterPagesDto? = null
        response.useAndDetectCaptcha { r -> r.parseAs<ChapterPagesDto>() }.let { (result, captcha) ->
            parsed = result
            captchaDetected = captcha
        }

        if (captchaDetected) {
            val (token, provider) = solveCaptchaToken()
            response = graphql(
                query = QUERY_CHAPTER_PAGES,
                variables = buildJsonObject {
                    put("_id", mangaId)
                    put("translationType", translation)
                    put("chapterString", chapterString)
                },
                captchaToken = token to provider,
            )

            response.useAndDetectCaptcha { r -> r.parseAs<ChapterPagesDto>() }.let { (result, captcha) ->
                if (captcha) throw IOException("MKissa rejected the security check. Try again in a few moments.")
                parsed = result
            }
        }

        val result = parsed
        val edges = result?.data?.chapterPages?.edges.orEmpty()
        val edge = edges.firstOrNull { it.pictureUrls.isNotEmpty() }

        if (edge == null) {
            // Surface the API's own reason (e.g. "Error Re-captcha!", bad
            // chapter id) instead of the misleading "no pages" message.
            result?.firstErrorMessage()?.let { throw IOException("MKissa: $it") }
            throw IOException(
                "No pages found for chapter $chapterString. " +
                    "Refresh the chapter list; if it persists, the chapter may have no readable pages yet.",
            )
        }

        val head = edge.pictureUrlHead?.takeIf { it.isNotBlank() } ?: ""
        return edge.pictureUrls.mapIndexed { index, rawUrl ->
            val imageUrl = when {
                rawUrl.startsWith("http") -> rawUrl
                head.isBlank() -> rawUrl.trimStart('/')
                else -> head.trimEnd('/') + "/" + rawUrl.trimStart('/')
            }
            Page(index, imageUrl = imageUrl)
        }
    }

    /**
     * Solves MKissa's Turnstile challenge exactly like the site does:
     * a small host page (origin api.mkissa.net, the same origin the site's
     * own iframe page lives on) embeds the site's captcha iframe and forwards
     * its `sitea-captcha-ready` postMessage to a JS bridge.
     *
     * The Turnstile widget usually resolves by itself in the off-screen
     * WebView; a real token is bound to the device IP, which is exactly what
     * the subsequent GraphQL request needs.
     */
    private suspend fun solveCaptchaToken(): Pair<String, String> = runWebView(timeout = 90.seconds) {
        var done = false

        fun parseMessageField(message: String, field: String): String? = runCatching {
            (Json.parseToJsonElement(message) as? JsonObject)?.get(field)?.let { element ->
                (element as? JsonPrimitive)?.content
            }
        }.getOrNull()

        jsBridge("mkCaptcha") { message ->
            if (done) return@jsBridge
            val token = parseMessageField(message, "token")

            if (!token.isNullOrBlank()) {
                val provider = parseMessageField(message, "provider") ?: "turnstile1"
                done = true
                resolve(token to provider)
            } else {
                reject(IOException("MKissa security check failed. Please try again."))
            }
        }

        onPageFinished { _ ->
            // Nudge the iframe to (re)execute if it is still waiting after load.
            evaluateJs(
                """
                (function () {
                  try {
                    var f = document.getElementById('cap');
                    if (f && f.contentWindow && f.contentWindow.parent === window) {
                      f.contentWindow.postMessage({ type: 'sitea-captcha-execute' }, '*');
                    }
                  } catch (e) {}
                })();
                """,
            )
        }

        loadData(
            baseUrl = "https://$apiHost/",
            html = """
                <html>
                  <body style="margin:0;background:transparent">
                    <iframe id="cap" src="https://$apiHost/captcha/turnstile"
                            style="width:320px;height:80px;border:0" allow="cross-origin-isolated"></iframe>
                    <script>
                      window.addEventListener('message', function (e) {
                        try {
                          var d = e.data;
                          if (d && (d.type === 'sitea-captcha-ready')) {
                            window.mkCaptcha.post(JSON.stringify(d));
                          }
                        } catch (err) {}
                      });
                    </script>
                  </body>
                </html>
            """.trimIndent(),
        )
    }

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        SortFilter(),
        GenreMatchModeFilter(),
        GenreFilter(),
        Filter.Separator(),
        Filter.Header("Tags (site tags like \"theme:monsters\")"),
        TagFilter(),
        Filter.Separator(),
        Filter.Header("Filter by author name"),
        AuthorFilter("Author"),
        SeasonFilter(),
        YearFilter("Year (e.g. 2024)"),
        MinChaptersFilter("Minimum chapters (e.g. 50)"),
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

    /** ALL (default) = manga must have every selected genre; ANY = at least one. */
    private class GenreMatchModeFilter :
        Filter.Select<String>(
            "Genre match mode",
            arrayOf("All selected genres", "Any selected genre"),
        ) {
        fun matchAll(): Boolean = state == 0
    }

    private class GenreTriState(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it, it) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

        private companion object {
            // Site genre/tag names (verified live against the API, 2026-09).
            val GENRES = listOf(
                "Action", "Adult", "Adventure", "Comedy", "Cooking", "Crossdressing",
                "Demons", "Doujinshi", "Drama", "Ecchi", "Fantasy", "Gender Bender",
                "Harem", "Hentai", "Historical", "Horror", "Isekai", "Josei",
                "Magic", "Manhwa", "Martial Arts", "Mature", "Mecha", "Medical",
                "Military", "Music", "Mystery", "One Shot", "Parody", "Philosophical",
                "Police", "Psychological", "Reincarnation", "Romance", "Samurai",
                "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
                "Space", "Sports", "Super Power", "Supernatural", "Thriller",
                "Tragedy", "Webtoons",
            )
        }
    }

    private class TagTriState(name: String, val value: String) : Filter.TriState(name)

    /**
     * Tags carry the site's own values ("theme:monsters", "format:full_color"
     * plus plain AniList-style names) — verified live. Sending display names
     * that don't exist server-side simply matches nothing, never errors.
     */
    private class TagFilter :
        Filter.Group<TagTriState>(
            "Tags",
            TAGS.map { TagTriState(it.first, it.second) },
        ) {
        fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

        fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

        private companion object {
            val TAGS = listOf(
                "Full color" to "format:full_color",
                "Web comic" to "format:web_comic",
                "Long strip" to "format:long_strip",
                "Adaptation" to "format:adaptation",
                "Award winning" to "format:award_winning",
                "Episodic" to "format:episodic",
                "Fan colored" to "format:fan_colored",
                "Isekai" to "theme:isekai",
                "Reincarnation" to "theme:reincarnation",
                "Cultivation" to "theme:cultivation",
                "Monsters" to "theme:monsters",
                "Vampires" to "theme:vampires",
                "Ghosts" to "theme:ghost",
                "Witches" to "theme:witch",
                "Gods" to "theme:gods",
                "Demons" to "theme:demons",
                "Elves" to "theme:elf",
                "Mythology" to "theme:mythology",
                "Superhero" to "theme:superhero",
                "Time travel" to "theme:time_travel",
                "Time skip" to "theme:time_skip",
                "Alternate universe" to "theme:alternate_universe",
                "Post-apocalyptic" to "theme:post_apocalyptic",
                "Dystopian" to "theme:dystopian",
                "Survival" to "theme:survival",
                "War" to "theme:war",
                "Military" to "theme:military",
                "Politics" to "theme:politics",
                "Conspiracy" to "theme:conspiracy",
                "Revenge" to "theme:revenge",
                "Crime" to "theme:crime",
                "Detective" to "theme:detective",
                "Noir" to "theme:noir",
                "Assassins" to "theme:assassins",
                "Martial arts" to "theme:martial_arts",
                "Swordplay" to "theme:swordplay",
                "Boxing" to "theme:boxing",
                "Athletics" to "theme:athletics",
                "Video games" to "theme:video_games",
                "Game elements" to "theme:game_elements",
                "Virtual world" to "theme:virtual_world",
                "School life" to "theme:school_life",
                "School club" to "theme:school_club",
                "Delinquents" to "theme:delinquents",
                "Family life" to "theme:family_life",
                "Parenthood" to "theme:parenthood",
                "Childcare" to "theme:childcare",
                "Found family" to "theme:found_family",
                "Romance" to "theme:romance",
                "Love triangle" to "theme:love_triangle",
                "Yuri" to "theme:yuri",
                "Yaoi" to "theme:yaoi",
                "LGBTQIA themes" to "theme:lgbtq_themes",
                "Crossdressing" to "theme:crossdressing",
                "Gender bender" to "theme:gender_bender",
                "Harem" to "theme:harem",
                "Reverse harem" to "theme:reverse_harem",
                "Villainess" to "theme:villainess",
                "Royal affairs" to "theme:royal_affairs",
                "Kingdom management" to "theme:kingdom_management",
                "Ancient china" to "theme:ancient_china",
                "Historical" to "theme:historical",
                "Rural" to "theme:rural",
                "Urban" to "theme:urban",
                "Office workers" to "theme:office_workers",
                "Cooking" to "theme:cooking",
                "Medicine" to "theme:medicine",
                "Music" to "theme:music",
                "Meta" to "theme:meta",
                "4-koma" to "theme:4_koma",
                "Gore" to "theme:gore",
                "Body horror" to "theme:body_horror",
                "Tragedy" to "theme:tragedy",
                "Suicide" to "theme:suicide",
                "Female protagonist" to "theme:female_protagonist",
                "Male protagonist" to "theme:male_protagonist",
                "Anti-hero" to "theme:anti_hero",
                "Clever protagonist" to "theme:clever_protagonist",
                "Vampires (plain)" to "vampires",
            )
        }
    }

    private class AuthorFilter(title: String) : Filter.Text(title)

    private class SeasonFilter :
        Filter.Select<String>(
            "Season",
            arrayOf("Any", "Winter", "Spring", "Summer", "Fall"),
        ) {
        fun toValue(): String? = when (state) {
            1 -> "Winter"
            2 -> "Spring"
            3 -> "Summer"
            4 -> "Fall"
            else -> null
        }
    }

    private class YearFilter(title: String) : Filter.Text(title)

    private class MinChaptersFilter(title: String) : Filter.Text(title)

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
                lastChapterDate
                score
                averageScore
                pageStatus { userScoreAverValue }
                availableChaptersDetail
              }
            }
        """

        // Matches the site's reader document ($j in its bundle): pages live in
        // chapterPages.edges[].pictureUrls (paths resolved against
        // pictureUrlHead).
        private const val QUERY_CHAPTER_PAGES = """
            query(${'$'}_id: String!, ${'$'}translationType: VaildTranslationTypeMangaEnumType!, ${'$'}chapterString: String!) {
              chapterPages(mangaId: ${'$'}_id, translationType: ${'$'}translationType, chapterString: ${'$'}chapterString, limit: 400) {
                edges {
                  chapterString
                  pictureUrls
                  pictureUrlHead
                  sourceName
                }
              }
            }
        """
    }
}
