package eu.kanade.tachiyomi.extension.all.swarm

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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Swarm (swarm.ws) — Next.js front-end over a MongoDB-backed aggregator API
 * (web APIs on swarm.ws/api, backend on api.swarm.ws — NOT behind Cloudflare).
 * Content is multi-source (MangaPark-style ids, Comick ULIDs, numeric ids), so
 * chapters carry a per-chapter `lang` and group/quality ranks.
 *
 * Endpoints (all live-verified 2026-09, no Cloudflare challenges):
 *  - Popular    GET /api/analytics/popular?window=all&limit=50&offset=N
 *  - Latest     GET /api/manga/filtered?order=last_updated&limit=50&offset=N
 *  - Search     GET /api/comic/search?title=&limit=50&offset=&nsfw=&suggestive=
 *               (+genres=&types=&yearStartMin/Max=&minChapters= — exact case)
 *  - Details    SSR page /comic/{slug} → Next.js flight payload {manga, chapters}
 *               (fallback: light backend search → api.swarm.ws/api/manga/{id})
 *  - Chapters   api.swarm.ws/api/chapter?manga={id}&limit=9999 as PLAIN JSON
 *               (slug → id resolved via the light backend search and cached;
 *               fixed sort: number desc → rank asc; hard cap ~10k rows).
 *               SSR flight payload stays as the last-resort fallback.
 *  - Pages      POST /api/sp  body {"payload":"<hex iv>:<hex ct>"} — AES-256-CBC
 *               over {"chapterId":…}, key = SHA-256(SECRET) (see SwarmCrypto);
 *               answer {baseUrl, chapter:{data:[urls]}} → MangaDex@Home shape.
 *               Fallback: the SSR reader page /comic/{slug}/chapter/{number}
 *               embeds {"chapterId","baseUrl","paths":[...]} directly.
 *
 * Chapter urls carry "id|number|slug" so the page-list fallback can rebuild
 * the reader page without extra state.
 *
 * Caveats inherited from the recon: `order=created` 500s server-side (never
 * used here); listing `total` is flaky under filters (hasNext uses the row
 * count); covers on cum.swarm.ws 404 directly and are rewritten to
 * swarm.ws/covers/. Images on cash.swarm.ws need no hotlink headers.
 */
@Source
abstract class Swarm :
    HttpSource(),
    ConfigurableSource {

    override val name = "Swarm"

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Web APIs live on the main domain. */
    private val webApi = "$baseUrl/api"

    /** Dedicated backend (no Cloudflare in front of it). */
    private val backendApi = "https://api.swarm.ws/api"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", CHROME_UA)
        .set("Referer", "$baseUrl/")

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ========================================================================
    // Browse / search
    // ========================================================================

    override fun popularMangaRequest(page: Int): Request = GET(
        "$webApi/analytics/popular?window=all&limit=50&offset=${(page - 1) * 50}",
        headers,
    )

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$webApi/manga/filtered?order=last_updated&limit=50&offset=${(page - 1) * 50}" +
            "&nsfw=${preferences.showNsfw()}",
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage = cardListParse(response)

    override fun latestUpdatesParse(response: Response): MangasPage = cardListParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort: String? = null
        val types = mutableListOf<String>()
        val includedGenres = mutableListOf<String>()
        val excludedGenres = mutableListOf<String>()
        var yearMin: String? = null
        var yearMax: String? = null
        var minChapters: String? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = sortValues[filter.state]
                is TypeFilter -> types += filter.selected
                is GenreFilter -> {
                    includedGenres += filter.selected
                }
                is ExcludedGenreFilter -> excludedGenres += filter.selected
                is YearMinFilter -> yearMin = filter.state.trim().takeIf { it.isNotBlank() }
                is YearMaxFilter -> yearMax = filter.state.trim().takeIf { it.isNotBlank() }
                is MinChaptersFilter -> minChapters = filter.state.trim().takeIf { it.isNotBlank() }
                else -> {}
            }
        }

        val url = "$webApi/comic/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("title", query)
            addQueryParameter("limit", "50")
            addQueryParameter("offset", ((page - 1) * 50).toString())
            val nsfw = preferences.showNsfw()
            addQueryParameter("nsfw", nsfw.toString())
            addQueryParameter("suggestive", nsfw.toString())
            sort?.takeIf { it.isNotBlank() }?.let { addQueryParameter("order", it) }
            if (types.isNotEmpty()) addQueryParameter("types", types.joinToString(","))
            if (includedGenres.isNotEmpty()) addQueryParameter("genres", includedGenres.joinToString(","))
            if (excludedGenres.isNotEmpty()) addQueryParameter("genresExclude", excludedGenres.joinToString(","))
            yearMin?.let { addQueryParameter("yearStartMin", it) }
            yearMax?.let { addQueryParameter("yearStartMax", it) }
            minChapters?.let { addQueryParameter("minChapters", it) }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = cardListParse(response)

    private fun cardListParse(response: Response): MangasPage {
        val body = response.body.string()
        val cards = runCatching { body.parseAs<SwarmCardListDto>(json).data }
            .getOrElse {
                runCatching {
                    json.decodeFromString(JsonArray.serializer(), body)
                        .map { json.decodeFromJsonElement(SwarmCardDto.serializer(), it) }
                }.getOrDefault(emptyList())
            }

        // The aggregator indexes the same series once per upstream source, so
        // listings (and therefore the app's search suggestions) can repeat a
        // title many times under different ids. Keep the first occurrence of
        // each distinct title — listings are relevance-ordered, so the first
        // row is the most complete record.
        val deduped = cards.distinctBy { it.title?.trim()?.lowercase() ?: it.url ?: it.id }

        return MangasPage(deduped.map { it.toSManga() }, cards.size >= 50)
    }

    private fun SwarmCardDto.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.url.orEmpty().ifBlank { id.orEmpty() }
        title = this@toSManga.title.orEmpty().ifBlank { this@toSManga.url.orEmpty() }
        thumbnail_url = rewriteCover(cover)
        status = statusStringToSManga(this@toSManga.status)
    }

    /** cum.swarm.ws covers 404 directly — the site itself serves /covers/. */
    private fun rewriteCover(cover: String?): String? {
        val value = cover?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.contains("cum.swarm.ws") ->
                value.replaceFirst(Regex("""https://cum\.swarm\.ws/?"""), "$baseUrl/covers/")
            value.startsWith("http") -> value
            value.startsWith("/") -> "$baseUrl$value"
            else -> "$baseUrl/covers/$value"
        }
    }

    // ========================================================================
    // Details
    // ========================================================================

    /** The SSR series page embeds the full model in its flight payload. */
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/comic/${manga.url}", headers)

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = client.newCall(mangaDetailsRequest(manga)).asObservableSuccess().map { response ->
        mangaDetailsParse(response, manga)
    }

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response, null)

    private fun mangaDetailsParse(response: Response, original: SManga?): SManga {
        val document = response.asJsoup()
        val slug = original?.url
            ?: response.request.url.pathSegments.filter { it.isNotBlank() }.lastOrNull().orEmpty()

        // Primary: the flight payload row that carries BOTH the manga object
        // and the chapters array (explicit predicate — the DTO fields are all
        // optional, so predicate inference is not possible).
        val series = document.extractNextJs<SwarmRscSeriesDto> { element: JsonElement ->
            element is JsonObject && element.containsKey("manga") && element.containsKey("chapters")
        }

        // Free id for the chapter fast path — saves the resolver round-trip.
        series?.manga?.id?.takeIf { it.isNotBlank() }?.let { id ->
            if (mangaIdCache.size > 30) mangaIdCache.clear()
            mangaIdCache[slug] = id
        }

        val mangaDto = series?.manga ?: run {
            // Fallback: resolve the slug through the light backend search and
            // fetch the backend detail record.
            resolveMangaId(slug)?.let { id ->
                runCatching {
                    client.newCall(GET("$backendApi/manga/$id", headers)).execute().use { detail ->
                        detail.body.string().parseAs<SwarmMangaEnvelopeDto>(json).data
                    }
                }.getOrNull()
            }
        } ?: throw IOException("Swarm: couldn't read the series page (layout change?).")

        return mangaDto.toSManga(slug)
    }

    /** Resolves a slug to the backend id via the LIGHT backend title search
     * (exact url match preferred). The old resolver hit the 600 KB+ web search
     * on every fallback; this one is a ~5 KB JSON call.
     */
    private fun resolveMangaId(slug: String): String? {
        if (slug.isBlank()) return null
        mangaIdCache[slug]?.let { return it }

        val keyword = slug.replace('-', ' ').replace('_', ' ')
        val searchUrl = "$backendApi/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", keyword)
            .addQueryParameter("limit", "10")
            .build()

        val id = runCatching {
            client.newCall(GET(searchUrl, headers)).execute().use { response ->
                response.body.string().parseAs<SwarmCardListDto>(json).data
            }.let { cards ->
                cards.firstOrNull { it.url == slug }?.id
                    ?: cards.singleOrNull()?.id
            }
        }.getOrNull()

        if (id != null) {
            if (mangaIdCache.size > 30) mangaIdCache.clear()
            mangaIdCache[slug] = id
        }
        return id
    }

    private fun SwarmMangaFullDto.toSManga(fallbackSlug: String): SManga {
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()

        val synopsis = description?.trim().orEmpty()

        val infoLine = if (showExtraInfo) {
            buildString {
                if (!status.isNullOrBlank()) {
                    append("**Status:** ").append(formatStatus(this@toSManga.status))
                }
                val yearValue = year.asIntOrNull()
                if (yearValue != null && yearValue > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Year:** ").append(yearValue)
                }
                val count = chapter_count.asIntOrNull()
                if (count != null && count > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Chapters:** ").append(count)
                }
                if (!type.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Type:** ").append(this@toSManga.type)
                }
                val langs = available_langs.orEmpty()
                if (langs.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Languages:** ").append(langs.joinToString(", "))
                }
            }.ifBlank { null }
        } else {
            null
        }

        val genreList = buildList {
            addAll(genres.orEmpty())
            if (preferences.showTagsInGenres()) addAll(tags.orEmpty())
        }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val desc = buildString {
            if (infoLine != null) {
                append(infoLine)
                append("\n\n")
            }
            append(synopsis)
            if (showAltNames && !alt_titles.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(alt_titles.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n") { "• $it" })
            }
        }.trim()

        return SManga.create().apply {
            url = this@toSManga.url.orEmpty().ifBlank { fallbackSlug }
            title = this@toSManga.title.orEmpty().ifBlank { fallbackSlug }
            author = this@toSManga.author.joinedNames() ?: this@toSManga.authors.joinedNames()
            artist = this@toSManga.artist.joinedNames() ?: this@toSManga.artists.joinedNames()
            genre = genreList.joinToString(", ").ifBlank { null }
            description = desc.ifBlank { synopsis.ifBlank { null } }
            thumbnail_url = rewriteCover(this@toSManga.cover ?: covers?.firstOrNull())
            status = statusStringToSManga(this@toSManga.status)
            initialized = true
        }
    }

    private fun formatStatus(status: String?): String = when (status?.lowercase()) {
        "ongoing", "releasing", "updating" -> "Ongoing"
        "completed", "finished", "end" -> "Completed"
        "on_hold", "on hold", "hiatus" -> "On hiatus"
        "cancelled", "canceled", "dropped", "discontinued" -> "Cancelled"
        else -> status?.replaceFirstChar { it.uppercase(Locale.ROOT) } ?: "Unknown"
    }

    private fun statusStringToSManga(status: String?): Int = when (status?.lowercase()) {
        "ongoing", "releasing", "updating" -> SManga.ONGOING
        "completed", "finished", "end" -> SManga.COMPLETED
        "on_hold", "on hold", "hiatus" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped", "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ========================================================================
    // Chapters
    // ========================================================================

    /**
     * The slug → backend-id cache, filled by [mangaDetailsParse] (the RSC
     * payload carries the id) and by [resolveMangaId]. Makes the chapter list
     * a single straight JSON call instead of "download the 2 MB series page
     * again and re-parse the whole flight payload".
     */
    private val mangaIdCache = ConcurrentHashMap<String, String>()

    override fun chapterListRequest(manga: SManga): Request {
        // Fast path: the backend chapter API as plain JSON. Slower path (SSR
        // series page + flight-payload parse) only when the id can't be
        // resolved at all.
        val id = resolveMangaId(manga.url)
        return if (id != null) {
            GET("$backendApi/chapter?manga=$id&limit=9999", headers)
        } else {
            mangaDetailsRequest(manga)
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = client.newCall(chapterListRequest(manga)).asObservableSuccess().map { response ->
        chapterListParse(response, manga)
    }

    override fun chapterListParse(response: Response): List<SChapter> = chapterListParse(response, SManga.create().apply { url = response.request.url.pathSegments.lastOrNull().orEmpty() })

    /**
     * Accepts both transports: the plain JSON chapter API (fast path) and the
     * SSR series page whose flight payload embeds the chapter rows (fallback).
     */
    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> {
        val body = response.body.string()

        val rows: List<SwarmChapterDto> = if (body.trimStart().startsWith("{")) {
            runCatching {
                body.parseAs<SwarmChapterListDto>(json).data
            }.getOrElse { emptyList<SwarmChapterDto>() }
        } else {
            val series = Jsoup.parse(body).extractNextJs<SwarmRscSeriesDto> { element: JsonElement ->
                element is JsonObject && element.containsKey("manga") && element.containsKey("chapters")
            }
            series?.chapters.orEmpty().ifEmpty {
                resolveMangaId(manga.url)?.let { id ->
                    runCatching {
                        client.newCall(GET("$backendApi/chapter?manga=$id&limit=9999", headers)).execute().use { chResponse ->
                            chResponse.body.string().parseAs<SwarmChapterListDto>(json).data
                        }
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }

        if (rows.isEmpty()) {
            throw IOException("Swarm: no chapters found for this series.")
        }

        val preferredLang = preferences.chapterLanguage().lowercase()
        val dedupe = preferences.deduplicateChapters()

        val preferredRows = rows.filter { it.lang?.lowercase() == preferredLang }
        val candidates = preferredRows.ifEmpty { rows }
        val fellBack = preferredRows.isEmpty()

        val chosen: List<SwarmChapterDto> = if (dedupe) {
            candidates
                .groupBy { it.number.asFloatOrNull() ?: 0f }
                .map { (_, uploads) ->
                    uploads.maxWithOrNull(
                        compareBy(
                            { it.is_official == true },
                            { RANK_ORDER.indexOf(it.rank?.lowercase() ?: "z") },
                            { it.priority?.lowercase() == "a" },
                        ),
                    ) ?: uploads.first()
                }
        } else {
            candidates.toList()
        }

        val showLangTags = !dedupe && candidates.any { it.lang?.lowercase() != preferredLang }

        return chosen
            .sortedByDescending { it.number.asFloatOrNull() ?: 0f }
            .map { it.toSChapter(manga.url, preferredLang, showLangTags, fellBack) }
    }

    private fun SwarmChapterDto.toSChapter(
        mangaSlug: String,
        preferredLang: String,
        showLangTag: Boolean,
        fellBack: Boolean,
    ): SChapter {
        val num = number.asFloatOrNull() ?: -1f
        val lang = lang.orEmpty().lowercase()
        val rawTitle = title.orEmpty().trim()

        return SChapter.create().apply {
            // Keep everything the page-list fallback needs: id, number, slug.
            url = listOf(
                id.orEmpty(),
                formatChapterNumber(num),
                mangaSlug,
            ).joinToString("|")
            name = buildString {
                if (num > 0) {
                    append("Ch. ")
                    append(formatChapterNumber(num))
                }
                if (rawTitle.isNotBlank() && !rawTitle.equals("chapter", ignoreCase = true)) {
                    if (isNotEmpty()) append(" - ")
                    append(rawTitle)
                }
                if (isEmpty()) append(if (num <= 0) "Oneshot" else "Chapter ${formatChapterNumber(num)}")
                if ((showLangTag || fellBack) && lang.isNotBlank() && lang != preferredLang) {
                    append(" [")
                    append(lang)
                    append("]")
                }
            }
            chapter_number = num
            date_upload = parseDate(published_date)
            scanlator = scan_group?.takeIf { it.isNotBlank() } ?: source?.takeIf { it.isNotBlank() }
        }
    }

    // ========================================================================
    // Pages
    // ========================================================================

    /**
     * Encrypted reader endpoint first; SSR reader page as the fallback (both
     * documented in the class KDoc).
     */
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = client.newCall(pageListRequest(chapter)).asObservableSuccess().map { response ->
        pageListParse(response, chapter)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val payload = SwarmCrypto.encryptPayload(
            """{"chapterId":"${chapter.url.substringBefore('|')}","nocache":false,"clean":false,"force":false}""",
        )
        val body = json.encodeToString(SwarmPagesRequestDto.serializer(), SwarmPagesRequestDto(payload))
            .toRequestBody(jsonMedia)
        return POST("$webApi/sp", headers, body)
    }

    override fun pageListParse(response: Response): List<Page> = pageListParse(response, null)

    private fun pageListParse(response: Response, chapter: SChapter?): List<Page> {
        val body = response.body.string()

        val sp = runCatching { body.parseAs<SwarmPagesDto>(json) }.getOrNull()
        val pageBase = sp?.baseUrl
        var urls = sp?.chapter?.data.orEmpty().map { resolvePageUrl(it, pageBase) }

        if (urls.isEmpty() && chapter != null) {
            urls = pageListViaReaderPage(chapter)
        }

        if (urls.isEmpty()) {
            throw IOException("Swarm: no pages returned for this chapter.")
        }

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    /** Fallback: scrape the reader SSR page for {baseUrl, paths}. */
    private fun pageListViaReaderPage(chapter: SChapter): List<String> {
        val parts = chapter.url.split('|')
        val num = parts.getOrElse(1) { "" }
        val slug = parts.getOrElse(2) { "" }
        if (slug.isBlank() || num.isBlank()) return emptyList()

        return runCatching {
            client.newCall(GET("$baseUrl/comic/$slug/chapter/$num", headers)).execute().use { response ->
                val html = response.body.string()
                val match = READER_PATHS_REGEX.find(html) ?: return emptyList()
                val jsonText = """{"baseUrl":"${match.groupValues[1]}","paths":${match.groupValues[2]}}"""
                    .replace("\\\"", "\"")
                val reader = json.decodeFromString(SwarmRscReaderDto.serializer(), jsonText)
                reader.paths.map { resolvePageUrl(it, reader.baseUrl) }
            }
        }.getOrDefault(emptyList())
    }

    private fun resolvePageUrl(path: String, base: String?): String = when {
        path.startsWith("http") -> path
        base != null && base.startsWith("http") -> base.trimEnd('/') + "/" + path.trimStart('/')
        else -> "$baseUrl/${path.trimStart('/')}"
    }

    override fun imageRequest(page: Page) = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================================================================
    // Filters
    // ========================================================================

    private val sortValues = arrayOf("last_updated", "views", "title")

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Latest update", "Most viewed", "Title A-Z"),
        )

    private class TypeFilter :
        Filter.Group<TypeFilter.CheckBox>(
            "Type",
            TYPE_VALUES.map { CheckBox(it) },
        ) {
        class CheckBox(name: String) : Filter.CheckBox(name, false)

        val selected: List<String> get() = state.filter { it.state }.map { it.name }
    }

    private class GenreFilter :
        Filter.Group<GenreFilter.CheckBox>(
            "Genres",
            GENRE_VALUES.map { CheckBox(it) },
        ) {
        class CheckBox(name: String) : Filter.CheckBox(name, false)

        val selected: List<String> get() = state.filter { it.state }.map { it.name }
    }

    private class ExcludedGenreFilter :
        Filter.Group<ExcludedGenreFilter.CheckBox>(
            "Excluded genres",
            GENRE_VALUES.map { CheckBox(it) },
        ) {
        class CheckBox(name: String) : Filter.CheckBox(name, false)

        val selected: List<String> get() = state.filter { it.state }.map { it.name }
    }

    private class YearMinFilter : Filter.Text("From year", "")
    private class YearMaxFilter : Filter.Text("To year", "")
    private class MinChaptersFilter : Filter.Text("Minimum chapters", "")

    override fun getFilterList() = FilterList(
        Filter.Header("Swarm filters"),
        Filter.Separator(),
        SortFilter(),
        TypeFilter(),
        Filter.Separator(),
        Filter.Header("Genres (exact names from the site)"),
        GenreFilter(),
        ExcludedGenreFilter(),
        Filter.Separator(),
        YearMinFilter(),
        YearMaxFilter(),
        MinChaptersFilter(),
    )

    // ========================================================================
    // Settings
    // ========================================================================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_NSFW
            title = "Show NSFW content"
            summary = "Off = only safe titles (site-side filter)"
            setDefaultValue(false)
        }.let(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_CHAPTER_LANGUAGE
            title = "Chapter language"
            summary = "Preferred chapter language (%s)"
            setDefaultValue("en")
            entries = CHAPTER_LANGUAGES.map { it.second }.toTypedArray()
            entryValues = CHAPTER_LANGUAGES.map { it.first }.toTypedArray()
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Preferred chapter language ($newValue)"
                true
            }
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "Keep one chapter per number — prefers official uploads, then the " +
                "site's quality rank"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genres"
            summary = "Include the tag list next to the genres in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display status, year, chapters, type and languages above the description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.showNsfw(): Boolean = getBoolean(PREF_SHOW_NSFW, false)

    private fun android.content.SharedPreferences.chapterLanguage(): String = getString(PREF_CHAPTER_LANGUAGE, "en") ?: "en"

    private fun android.content.SharedPreferences.deduplicateChapters(): Boolean = getBoolean(PREF_DEDUPLICATE_CHAPTERS, true)

    private fun android.content.SharedPreferences.showTagsInGenres(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun formatChapterNumber(num: Float): String = if (num % 1f == 0f) num.toInt().toString() else num.toString()

    private val dateFormats = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    ).map { pattern ->
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val value = raw.trim()
        for (format in dateFormats) {
            try {
                @Suppress("DEPRECATION")
                val date = format.parse(value) ?: continue
                return date.time
            } catch (_: ParseException) {
            } catch (_: Exception) {
            }
        }
        return runCatching {
            java.time.Instant.parse(value).toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            }.getOrElse {
                runCatching {
                    java.time.LocalDateTime.parse(value)
                        .atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
                }.getOrDefault(0L)
            }
        }
    }

    companion object {
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** Matches the {"baseUrl","paths":[...]} blob embedded in the reader SSR page. */
        private val READER_PATHS_REGEX = Regex(
            """"baseUrl"\s*:\s*"([^"]*)"\s*,\s*"paths"\s*:\s*(\[[^\]]*\])""",
        )

        private const val PREF_SHOW_NSFW = "pref_show_nsfw"
        private const val PREF_CHAPTER_LANGUAGE = "pref_chapter_language"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"

        private val CHAPTER_LANGUAGES = listOf(
            "en" to "English",
            "fr" to "French",
            "ar" to "Arabic",
            "es" to "Spanish",
            "es-la" to "Spanish (LatAm)",
            "pt-br" to "Portuguese (Brazil)",
            "id" to "Indonesian",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "tr" to "Turkish",
            "ru" to "Russian",
            "de" to "German",
            "it" to "Italian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
        )

        private val RANK_ORDER = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")

        private val TYPE_VALUES = listOf(
            "Manga", "Manhwa", "Manhua", "Comic",
            "French", "Spanish", "Indonesian", "Thai", "Vietnamese",
        )

        private val GENRE_VALUES = listOf(
            "Action", "Adventure", "Comedy", "Cooking", "Crime", "Demons", "Drama",
            "Fantasy", "Historical", "Horror", "Isekai", "Martial Arts", "Mecha",
            "Medical", "Music", "Mystery", "Psychological", "Romance", "School Life",
            "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports",
            "Superhero", "Supernatural", "Thriller", "Tragedy", "Yaoi", "Yuri",
        )
    }
}
