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
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import kotlin.time.Duration.Companion.minutes
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
     * (Browsing/details/chapters are not captcha-gated; the captcha+AA-crypto
     * gates only apply to `chapterPages`, which is now harvested from the
     * reader WebView — see getPageList.)
     */
    private suspend fun graphql(
        query: String,
        variables: JsonObject,
    ): Response = client.post(
        apiUrl,
        buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toJsonRequestBody(),
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
    // The reader is opened in an off-screen WebView and the page list is
    // captured from the site's OWN pipeline. Why (verified against the live
    // site, 2026-09):
    //
    // - The `chapterPages` GraphQL query answers NEED_CAPTCHA without a
    //   Turnstile token — and even WITH a token the server enforces an
    //   additional anti-abuse crypto proof ("aaReq" / x-aa-boot / x-build-id,
    //   WebCrypto over epoch/lane/build material) built by deliberately
    //   obfuscated code, answering AA_CRYPTO_MISSING to requests without it.
    // - The site's READER page runs that whole pipeline itself — Turnstile,
    //   the AA crypto proof and the GraphQL call — inside a real WebView.
    //
    // How the list is captured (three signals, strongest first):
    // 1. **Fetch/XHR hook** — injected at page start; wraps window.fetch and
    //    XMLHttpRequest and forwards every API response that contains the
    //    chapterPages payload through a jsBridge. One response carries the
    //    FULL page list (edges[].pictureUrls + pictureUrlHead), so this
    //    resolves immediately when the reader's API call lands — no waiting
    //    for images, no virtualisation gaps. (This is what the v13 build was
    //    missing: it scraped <img> tags, but the reader virtualises/recycles
    //    its page nodes, so the collection never stabilised and the chapter
    //    appeared to "load endlessly".)
    // 2. **Request interception** — every image the reader downloads is
    //    logged (survives virtualisation, catches URLs without extensions).
    // 3. **DOM scrape** on each poll — catches images served from cache.
    //
    // A stuck challenge (title still "Just a moment…" after 45s with nothing
    // captured) fails FAST with an actionable message instead of spinning.
    // Successful results are cached in the source prefs — chapter pages are
    // immutable, so re-opening a chapter (or retrying after a WebView solve)
    // is instant.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // url format: "/manga/<mangaId>/chapter-<chapterString>-<translation>"
        // (blank segments filtered so leading/trailing slashes don't matter)
        val parts = chapter.url.split("/").filter(String::isNotBlank)
        if (parts.size < 3) throw IOException("Outdated chapter URL. Refresh the chapter list.")

        val readerUrl = "$baseUrl${chapter.url}"

        // Chapter pages are immutable — serve the cached list instantly when
        // we already captured it once (covers re-opens and retries).
        val cached = readPageCache(chapter.url)
        if (cached != null) return cached.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }

        val imageUrls = try {
            collectReaderPages(readerUrl)
        } catch (e: WebViewTimeoutException) {
            throw IOException(
                "MKissa reader didn't finish loading. Open the chapter once in WebView " +
                    "(Browse → Sources → MKissa → ⋮ → Open in WebView), then try again.",
                e,
            )
        }

        if (imageUrls.isEmpty()) {
            throw IOException(
                "MKissa reader didn't expose any pages. Open the chapter once in WebView " +
                    "(Browse → Sources → MKissa → ⋮ → Open in WebView), then try again.",
            )
        }

        writePageCache(chapter.url, imageUrls)
        return imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // ----------------------------- page cache -----------------------------

    private fun readPageCache(chapterUrl: String): List<String>? {
        val raw = preferences.getString("$PAGE_CACHE_PREFIX$chapterUrl", null) ?: return null
        return raw.split('\n').filter(String::isNotBlank).takeIf { it.isNotEmpty() }
    }

    private fun writePageCache(chapterUrl: String, urls: List<String>) {
        // Keep the cache bounded — drop the oldest entries beyond the cap.
        val keys = preferences.all.keys
            .filter { it.startsWith(PAGE_CACHE_PREFIX) }
            .toMutableSet()
        if (keys.size >= PAGE_CACHE_MAX_ENTRIES) {
            keys.take(keys.size - PAGE_CACHE_MAX_ENTRIES + 1).forEach { key ->
                preferences.edit().remove(key).apply()
            }
        }
        preferences.edit()
            .putString("$PAGE_CACHE_PREFIX$chapterUrl", urls.joinToString("\n"))
            .apply()
    }

    // --------------------------- WebView capture ---------------------------

    /**
     * Loads the site's own reader page in an off-screen WebView and captures
     * the chapter's page URLs. The fetch/XHR hook delivers the chapterPages
     * GraphQL response through the jsBridge; request interception and DOM
     * scraping run as fallbacks until then.
     */
    private suspend fun collectReaderPages(readerUrl: String): List<String> = runWebView(timeout = 2.minutes) {
        val collected = Collections.synchronizedSet(LinkedHashSet<String>())
        var lastCount = 0
        var stablePolls = 0
        var finished = false
        var challengePolls = 0

        fun consider(url: String) {
            val cleaned = url.trim().takeIf { it.startsWith("http") } ?: return
            if (isPageImageUrl(cleaned)) collected.add(cleaned)
        }

        // Receives raw API response bodies from the injected fetch/XHR hook.
        jsBridge("mhbridge") { message ->
            runCatching {
                val pages = extractPageUrls(message, readerUrl)
                if (pages.isNotEmpty()) resolve(pages)
            }
        }

        interceptRequest { request ->
            consider(request.url.toString())
            // WebView "Accept: image/..." header is a solid image signal even
            // when the CDN path has no file extension.
            val accept = request.requestHeaders?.get("Accept")
            if (accept?.contains("image/") == true) {
                collected.add(request.url.toString())
            }
            null
        }

        onPageStarted { _ ->
            evaluateJs(FETCH_HOOK_JS)
        }

        onPageFinished { _ -> finished = true }

        poll(1.seconds) {
            // Re-install the hook if the page re-navigated before it ran.
            evaluateJs("window.__mhHookInstalled===true||(${FETCH_HOOK_JS})")

            // Nudge lazy loaders — jump to the bottom of the strip.
            evaluateJs(
                "try{window.scrollTo(0,(document.scrollingElement||document.body).scrollHeight)}catch(e){}",
            )

            evaluateJs(DOM_IMAGES_JS) { value ->
                runCatching {
                    val element = Json.parseToJsonElement(value)
                    val inner = (element as? JsonPrimitive)?.content ?: value
                    (Json.parseToJsonElement(inner) as? JsonArray)?.forEach { item ->
                        (item as? JsonPrimitive)?.content?.let(::consider)
                    }
                }
            }

            val count = collected.size

            // Fail fast when the reader is stuck on a Cloudflare check —
            // sitting for 45s with zero progress means the challenge needs a
            // real browser (or interaction); spinning for minutes won't fix it.
            evaluateJs(DOM_TITLE_JS) { value ->
                val title = value
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    .orEmpty()
                if (title.isEmpty() || CHALLENGE_TITLE_REGEX.containsMatchIn(title)) {
                    challengePolls++
                } else {
                    challengePolls = 0
                }
                if (challengePolls >= 45 && count == 0) {
                    reject(
                        IOException(
                            "MKissa reader is stuck on a Cloudflare check. Open the site once in " +
                                "WebView (Browse → Sources → MKissa → ⋮ → Open in WebView), solve it, then try again.",
                        ),
                    )
                }
            }

            // Fallback resolution: image harvesting stable after page finish.
            if (finished && count > 0 && count == lastCount) {
                stablePolls++
                if (stablePolls >= 4) resolve(collected.toList())
            } else {
                stablePolls = 0
                lastCount = count
            }
        }

        loadUrl(readerUrl)
    }

    /**
     * Pulls the page list out of a captured chapterPages GraphQL response:
     * picks the highest-priority edge that has pictureUrls and normalises
     * every entry the way the site's own reader does (absolute URLs pass
     * through, "//"-prefixed get https:, relative paths join the edge's
     * pictureUrlHead, falling back to the reader page origin).
     */
    private fun extractPageUrls(rawBody: String, readerUrl: String): List<String> {
        if (!rawBody.contains("chapterPages") || !rawBody.contains("pictureUrls")) return emptyList()

        val root = runCatching { Json.parseToJsonElement(rawBody) }.getOrNull() ?: return emptyList()
        val edges = root.jsonObjectOrNull("data")
            ?.jsonObjectOrNull("chapterPages")
            ?.jsonArrayOrNull("edges")
            ?: return emptyList()

        val best = edges
            .mapNotNull { it as? JsonObject }
            .filter { edge -> (edge["pictureUrls"] as? JsonArray)?.isNotEmpty() == true }
            .maxByOrNull { edge -> (edge["priority"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f }
            ?: return emptyList()

        val head = (best["pictureUrlHead"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val pageOrigin = readerUrl.toHttpUrl()

        return (best["pictureUrls"] as? JsonArray)
            ?.mapNotNull { item -> (item as? JsonPrimitive)?.content?.trim() }
            .orEmpty()
            .mapNotNull { url ->
                when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("blob:") -> null // can't be fetched outside the page
                    head.isNotBlank() -> head.trimEnd('/') + "/" + url.trimStart('/')
                    else -> pageOrigin.resolve(url)?.toString()
                }
            }
            .filter { it.startsWith("http") }
            .distinct()
    }

    private fun JsonElement?.jsonObjectOrNull(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun JsonElement?.jsonArrayOrNull(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    /** True for URLs that look like page images — never site UI, API or captcha traffic. */
    private fun isPageImageUrl(url: String): Boolean {
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return false
        if (host == "challenges.cloudflare.com" || host == apiHost) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.substringAfterLast('.').substringBefore('%') in PAGE_IMAGE_EXTENSIONS
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

        // (The chapter-pages GraphQL document was removed: the server now
        // answers AA_CRYPTO_MISSING to any request lacking the site's own
        // obfuscated anti-abuse crypto proof, so pages are collected from the
        // real reader in an off-screen WebView instead — see getPageList.)

        private const val PAGE_CACHE_PREFIX = "mkissa_pages_"
        private const val PAGE_CACHE_MAX_ENTRIES = 60

        /** Challenge-page title markers used by the stuck-detection poll. */
        private val CHALLENGE_TITLE_REGEX = Regex(
            "just a moment|attention required|security verification|verify you are human|checking your browser",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Injected at page start (and re-installed by the poll loop): wraps
         * window.fetch and XMLHttpRequest so every response from the API host
         * whose body carries the chapterPages payload is forwarded to the
         * jsBridge. Our .then handler is attached before the promise is
         * handed back to the site, so the response body is teed before the
         * site consumes it.
         */
        private val FETCH_HOOK_JS = """
            (function(){
              if(window.__mhHookInstalled) return;
              window.__mhHookInstalled=true;
              function send(t){
                try{
                  t=String(t||'');
                  if(t.indexOf('chapterPages')!==-1 && t.indexOf('pictureUrls')!==-1){
                    window.mhbridge.post(t.slice(0,2000000));
                  }
                }catch(e){}
              }
              var of=window.fetch;
              if(of){
                window.fetch=function(){
                  var p=of.apply(this,arguments);
                  try{
                    var a0=arguments[0];
                    var url=typeof a0==='string'?a0:(a0&&a0.url)||'';
                    if(url.indexOf('api.mkissa.net')!==-1){
                      p.then(function(r){
                        try{ r.clone().text().then(send).catch(function(){}); }catch(e){}
                      }).catch(function(){});
                    }
                  }catch(e){}
                  return p;
                };
              }
              var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open=function(m,u){
                this.__mhUrl=u; return oo.apply(this,arguments);
              };
              XMLHttpRequest.prototype.send=function(){
                var x=this;
                try{
                  x.addEventListener('load',function(){
                    try{
                      var u=String(x.__mhUrl||'');
                      if(u.indexOf('api.mkissa.net')!==-1) send(x.responseText);
                    }catch(e){}
                  });
                }catch(e){}
                return os.apply(this,arguments);
              };
            })();
        """.trimIndent()

        /** Collects every page-image URL currently in the DOM. */
        private val DOM_IMAGES_JS = """
            (function () {
              try {
                var out = [];
                var imgs = document.images;
                for (var i = 0; i < imgs.length; i++) {
                  var im = imgs[i];
                  var s = im.currentSrc || im.src || im.getAttribute('data-src') || '';
                  if (s) out.push(String(s));
                }
                return JSON.stringify(out);
              } catch (e) { return '[]'; }
            })();
        """.trimIndent()

        /** Current page title — used to detect a stuck Cloudflare challenge. */
        private const val DOM_TITLE_JS = "document.title"

        /** File extensions the chapter-page image harvester accepts. */
        private val PAGE_IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "avif", "gif", "jfif")
    }
}
