package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * MangaBall (mangaball.com — the old mangaball.net Laravel site is gone and
 * 301-redirects here; the front-end was rebuilt on Next.js with a dedicated
 * JSON API at api.mangaball.com/api/v1).
 *
 * THE CSRF ERA IS OVER: the new stack has no CSRF tokens, no XSRF cookies and
 * no meta tags. v25's "couldn't read the site CSRF token" failures came from
 * bootstrapping a token system that no longer exists, and from the API calls
 * hitting mangaball.net whose 301 redirect silently converted every POST into
 * a GET. Everything below talks to api.mangaball.com directly with the exact
 * calls the site's own client makes — all verified live (2026-09), anonymous,
 * cookie-free and challenge-free:
 *
 *  1. Browse/search  GET /title/search-advanced?sort=&page=&limit=24&adult_mode=
 *                    &keyword=&status=&demographic=&original_language=
 *                    &publication_year=&included_tags=&tag_mode=&excluded_tags=
 *                    (Popular = sort=views_desc, Latest = updated_chapters_desc)
 *  2. Details        POST /title/detail            {"title_id": "..."}
 *  3. Chapters       POST /chapter/chapter-listing-by-title-id
 *                    {"title_id": "...", "user_id": "demo_user"} → FLAT rows
 *  4. Pages          GET  /chapter-detail?chapter_id=…&user_id=demo_user
 *                    → data.chapter.pages[] = absolute image URLs
 *  5. Filter tags    GET  /tag/get-grouped → {format, genre, theme, content}
 *
 * "demo_user" is exactly what the site's own client sends for logged-out
 * visitors. The NSFW toggle maps to the server's adult_mode filter (all /
 * no_18) instead of client-side hiding. Manga URLs are the numeric title ids
 * the site now links with (/title-detail/{id}); old v24 slug urls resolve via
 * the same /title/search lookup the site's own code uses as its fallback.
 */
@Source
abstract class MangaBall :
    KeiSource(),
    ConfigurableSource {

    override val name = "MangaBall"

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val domain = baseUrl.removePrefix("https://")

    /** The site's dedicated JSON API host. */
    private val apiBase = "https://api.mangaball.com/api/v1"

    /** Cover/page CDN (the site's BASE_DOMAIN_STORAGE_COVER constant). */
    private val coverCdn = "https://bulbasaur.poke-black-and-white.net/covers/"

    /** Lenient JSON — shapes verified live but some fields are unions. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

        // Insurance: Cloudflare still fronts both hosts (a per-endpoint WAF
        // rule used to challenge /tag/search and friends; the solver heals
        // any challenge that appears without a manual WebView visit).
        addInterceptor(CloudflareSolverInterceptor(setOf(domain, "api.mangaball.com")))

        // KeiSource stamps "Origin" on everything; browsers never send it on
        // document GETs. Drop it there (same reason as Kagane's sanitizer).
        addInterceptor(::originSanitizerInterceptor)
    }

    private fun originSanitizerInterceptor(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "GET" && request.header("Origin") != null) {
            return chain.proceed(request.newBuilder().removeHeader("Origin").build())
        }
        return chain.proceed(request)
    }

    // ------------------------------------------------------------------
    // Headers
    // ------------------------------------------------------------------

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Origin", "https://mangaball.com")
            .set("Referer", "https://mangaball.com/")
            .build()
    }

    private val jsonBody = "application/json; charset=utf-8".toMediaType()

    private fun jsonRequestBody(json: String) = json.toRequestBody(jsonBody)

    // ------------------------------------------------------------------
    // Browse + search
    // ------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage = mangaList(page, sort = SORT_VIEWS_DESC)

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaList(page, sort = SORT_UPDATED_DESC)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sort: String = SORT_VIEWS_DESC
        var status: String? = null
        var demographic: String? = null
        var originalLanguage: String? = null
        var year: String? = null
        val includedTags = mutableListOf<String>()
        val excludedTags = mutableListOf<String>()
        var tagMatchAll = false

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = sortValues[filter.state]
                is StatusFilter -> status = statusValues[filter.state].takeIf { it.isNotBlank() }
                is DemographicFilter -> demographic = demographicValues[filter.state].takeIf { it.isNotBlank() }
                is OriginalLanguageFilter -> originalLanguage = languageValues[filter.state].takeIf { it.isNotBlank() }
                is YearFilter -> year = filter.state.trim().takeIf { it.isNotBlank() }
                is GenreMatchModeFilter -> tagMatchAll = filter.state == 1
                // Format tags live in the same tag taxonomy — they filter via
                // the exact same included_tags parameter.
                is FormatFilter -> includedTags += filter.selectedIds()
                is GenreFilter -> {
                    includedTags += filter.includedIds()
                    excludedTags += filter.excludedIds()
                }
                else -> {}
            }
        }

        return mangaList(
            page = page,
            sort = sort,
            keyword = query,
            status = status,
            demographic = demographic,
            originalLanguage = originalLanguage,
            year = year,
            includedTags = includedTags,
            excludedTags = excludedTags,
            tagMatchAll = tagMatchAll,
        )
    }

    private suspend fun mangaList(
        page: Int,
        sort: String,
        keyword: String = "",
        status: String? = null,
        demographic: String? = null,
        originalLanguage: String? = null,
        year: String? = null,
        includedTags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        tagMatchAll: Boolean = false,
    ): MangasPage {
        val url = "$apiBase/title/search-advanced".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")
            // Server-side NSFW filter — the exact vocabulary the site uses.
            addQueryParameter("adult_mode", if (preferences.showNsfw()) "all" else "no_18")
            if (keyword.isNotBlank()) addQueryParameter("keyword", keyword)
            status?.let { addQueryParameter("status", it) }
            demographic?.let { addQueryParameter("demographic", it) }
            originalLanguage?.let { addQueryParameter("original_language", it) }
            year?.let { addQueryParameter("publication_year", it) }
            if (includedTags.isNotEmpty()) {
                addQueryParameter("included_tags", includedTags.joinToString(","))
                addQueryParameter("tag_mode", if (tagMatchAll) "and" else "any")
            }
            if (excludedTags.isNotEmpty()) addQueryParameter("excluded_tags", excludedTags.joinToString(","))
        }.build()

        val response = client.get(url, apiHeaders)
        val result = response.parseAs<MbSearchResponse>(json)
        return MangasPage(result.data.map { it.toSManga() }, result.hasNextPage())
    }

    private fun MbTitleDto.toSManga(): SManga = SManga.create().apply {
        val id = idOrNull().orEmpty()
        url = id.ifBlank { slug.orEmpty() }
        title = name.orEmpty().ifBlank { slug.orEmpty() }
        thumbnail_url = coverUrl()
        status = statusStringToSManga(this@toSManga.status)
    }

    /** coverUrl from the shared image object (search rows + detail DTOs). */
    private fun coverUrlOf(image: MbImageDto?): String? {
        val path = image?.cover?.path
        if (!path.isNullOrBlank()) {
            return if (path.startsWith("http")) path else coverCdn + path
        }
        image?.file?.let { file ->
            val asString = JsonElements.asStringOrNull(file)
            if (!asString.isNullOrBlank()) return asString
        }
        return image?.cdn_mangadex?.takeIf { it.isNotBlank() }
    }

    private fun MbTitleDto.coverUrl(): String? = coverUrlOf(image)

    // ------------------------------------------------------------------
    // Details
    // ------------------------------------------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith(domain) && !url.host.endsWith("mangaball.net")) return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        if (segments.first() !in TITLE_PATHS) return null

        val key = segments.last()
        return fetchMangaDetailsByKey(key)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title-detail/${manga.url}/"

    private suspend fun fetchMangaDetailsByKey(key: String): SManga {
        val titleId = resolveTitleId(key)
        val response = client.post(
            "$apiBase/title/detail",
            apiHeaders,
            jsonRequestBody(buildJsonObject { put("title_id", titleId) }.toString()),
        )
        val detail = response.parseAs<MbDetailResponse>(json).data
            ?: throw IOException("MangaBall: no details returned for this title")

        return detail.toSManga().apply { url = titleId }
    }

    /**
     * Accepts both url shapes:
     *  - current site links: the numeric title id (/title-detail/{24-hex id})
     *  - v24-era slugs (/title-detail/{slug}): resolved with the same
     *    /title/search lookup the site's own code uses as its fallback,
     *    exact-slug match preferred.
     */
    private suspend fun resolveTitleId(key: String): String {
        val trimmed = key.trim().trim('/')
        if (trimmed.matches(TITLE_ID_REGEX)) return trimmed

        val candidates = try {
            client.post(
                "$apiBase/title/search/",
                apiHeaders,
                jsonRequestBody(
                    buildJsonObject {
                        put("keyword", trimmed)
                        put("limit", 20)
                    }.toString(),
                ),
            ).parseAs<MbSearchResponse>(json).data
        } catch (_: Exception) {
            emptyList()
        }

        candidates.firstOrNull { it.slug == trimmed || it.slug.orEmpty() == trimmed }?.idOrNull()
            ?.let { return it }
        candidates.firstOrNull()?.idOrNull()?.let { return it }

        throw IOException(
            "MangaBall: couldn't find this title on the new site (it may have been renamed " +
                "or removed). Search for it by name instead.",
        )
    }

    private fun MbDetailDto.toSManga(): SManga {
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()

        val synopsis = description
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n\n")

        // Info line (Comix-style, bold labels) — status, original language,
        // publication year, chapter/views/likes counters.
        val infoLine = if (showExtraInfo) {
            buildString {
                if (!status.isNullOrBlank()) {
                    append("**Status:** ").append(formatStatus(status))
                }
                if (!originalLanguage.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Original:** ").append(formatOriginalLanguage(originalLanguage))
                }
                val year = JsonElements.asStringOrNull(date_published)?.takeIf { it.isNotBlank() }
                if (year != null) {
                    if (isNotEmpty()) append(" · ")
                    append("**Year:** ").append(year)
                }
                val chapterCount = JsonElements.asLongOrNull(chapters_count)
                if (chapterCount != null && chapterCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Chapters:** ").append(chapterCount)
                }
                val views = JsonElements.asLongOrNull(views_count)
                if (views != null && views > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Views:** ").append(views)
                }
                val likes = JsonElements.asLongOrNull(likes_count)
                if (likes != null && likes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Likes:** ").append(likes)
                }
            }.ifBlank { null }
        } else {
            null
        }

        val desc = buildString {
            if (infoLine != null) {
                append(infoLine)
                append("\n\n")
            }
            append(synopsis)
            if (showAltNames && alternateName.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(alternateName.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n") { "• $it" })
            }
        }.trim()

        return SManga.create().apply {
            url = idOrNull().orEmpty()
            title = name.orEmpty()
            author = (authors.ifEmpty { this@toSManga.author }).mapNotNull { it.name }.distinct().joinToString(", ").ifBlank { null }
            genre = tags.mapNotNull { it.name }.distinct().joinToString(", ").ifBlank { null }
            description = desc.ifBlank { synopsis.ifBlank { null } }
            thumbnail_url = coverUrlOf(image)
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

    private fun formatOriginalLanguage(lang: String?): String = when (lang?.lowercase()) {
        "en" -> "English"
        "ja", "jp" -> "Japanese"
        "ko", "kr" -> "Korean"
        "cn", "zh" -> "Chinese"
        else -> lang ?: "Unknown"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val titleId = resolveTitleId(manga.url)

        val updatedManga = if (fetchDetails) {
            fetchMangaDetailsByKey(titleId)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            chapterList(titleId)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun statusStringToSManga(status: String?): Int = when (status?.lowercase()) {
        "ongoing", "releasing", "updating" -> SManga.ONGOING
        "completed", "finished", "end" -> SManga.COMPLETED
        "on_hold", "on hold", "hiatus" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped", "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ------------------------------------------------------------------
    // Chapter list
    // ------------------------------------------------------------------

    private suspend fun chapterList(titleId: String): List<SChapter> {
        val body = jsonRequestBody(
            buildJsonObject {
                put("title_id", titleId)
                put("user_id", USER_ID_DEMO)
            }.toString(),
        )

        val response = client.post("$apiBase/chapter/chapter-listing-by-title-id", apiHeaders, body)
        val listing = response.parseAs<MbChapterListingResponse>(json)

        val preferredLang = preferences.preferredLanguage().lowercase()
        val fallback = preferences.fallbackLanguage()
        val dedupe = preferences.deduplicateUploads()
        val showLangTags = preferences.showAllUploads()

        val rows = listing.data
            .filter { it.idOrNull() != null }
            .sortedByDescending { it.chapterNumber() }

        var candidates = rows.filter { it.lang?.lowercase() == preferredLang }
        if (candidates.isEmpty() && fallback) candidates = rows
        if (candidates.isEmpty()) {
            throw IOException(
                "MangaBall returned no chapters in \"$preferredLang\" for this title. " +
                    "Enable \"Fallback to any language\" in MangaBall settings to see the " +
                    "other translations.",
            )
        }

        // One row per (chapter number, volume) when deduplicating: the site
        // itself aggregates multiple group uploads per chapter; keep the most
        // viewed, then the freshest upload.
        val chosen: List<MbChapterDto> = if (dedupe) {
            candidates
                .groupBy { Pair(it.volumeNumber() ?: 0.0, it.chapterNumber()) }
                .map { (_, uploadsForNumber) ->
                    uploadsForNumber.maxWithOrNull(
                        compareBy({ it.viewsCount() }, { parseDate(it.created_at) }),
                    ) ?: uploadsForNumber.first()
                }
                .sortedByDescending { it.chapterNumber() }
        } else {
            candidates
        }

        return chosen.map { it.toSChapter(preferredLang, showLangTags) }
    }

    private fun MbChapterDto.toSChapter(preferredLang: String, showLangTag: Boolean): SChapter {
        val id = idOrNull().orEmpty()
        val num = chapterNumber()
        val vol = volumeNumber()
        val lang = lang.orEmpty().lowercase()
        val rawTitle = name.orEmpty().trim().replace(CHAPTER_PREFIX_REGEX, "")

        return SChapter.create().apply {
            url = id
            name = buildString {
                if (vol != null && vol > 0) {
                    append("Vol. ")
                    append(formatNumber(vol))
                    append(" ")
                }
                if (num > 0) {
                    append("Ch. ")
                    append(formatNumber(num))
                }
                if (rawTitle.isNotBlank() && !rawTitle.equals("chapter", ignoreCase = true)) {
                    if (isNotEmpty()) append(" - ")
                    append(rawTitle)
                }
                if (isEmpty()) append(if (num <= 0) "Oneshot" else "Chapter ${formatNumber(num)}")
                if (showLangTag && lang.isNotBlank() && lang != preferredLang) {
                    append(" [")
                    append(lang)
                    append("]")
                }
            }
            chapter_number = num.toFloat().takeIf { num > 0 } ?: -1f
            date_upload = parseDate(created_at ?: updated_at)
            scanlator = groupName().ifBlank { null }
        }
    }

    private fun formatNumber(value: Double): String {
        val trimmed = value.toString().removeSuffix(".0")
        return if (trimmed.endsWith(".0")) trimmed.removeSuffix(".0") else trimmed
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.trim().trim('/').substringAfterLast('/')
        val url = "$apiBase/chapter-detail".toHttpUrl().newBuilder()
            .addQueryParameter("chapter_id", chapterId)
            .addQueryParameter("user_id", USER_ID_DEMO)
            .build()

        val response = client.get(url, apiHeaders)
        val pages = response.parseAs<MbChapterDetailResponse>(json).data?.chapter?.pages.orEmpty()

        if (pages.isEmpty()) {
            throw IOException(
                "MangaBall: no pages returned for this chapter. Refresh the chapter list " +
                    "and try again (the chapter may have been removed from the site).",
            )
        }

        return pages.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val id = chapter.url.trim('/').substringAfterLast('/')
        val num = chapter.chapter_number
        val query = if (num > 0f) "?chapter=${formatNumber(num.toDouble())}" else ""
        return "$baseUrl/chapter-detail/$id$query"
    }

    // Page images live on the site's own CDN and reject requests without a
    // browser-like Referer — send one (the site's own <img> requests do).
    override fun imageRequest(page: Page) = GET(
        page.imageUrl!!,
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build(),
    )

    // ------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------

    private val dateFormats = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    ).map { pattern ->
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }

    /** Flexible date parser: ISO / SQL / "2d ago" relative formats → epoch ms. */
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

        // ISO-8601 with offset (java.time handles the rest)
        return runCatching {
            java.time.Instant.parse(value).toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            }.getOrElse {
                runCatching {
                    java.time.LocalDateTime.parse(value)
                        .atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
                }.getOrElse { parseRelativeDate(value) }
            }
        }
    }

    /** Parses relative dates like "4d ago", "1mo ago", "39s ago". */
    private fun parseRelativeDate(relative: String): Long {
        val match = RELATIVE_DATE_REGEX.find(relative) ?: return 0L
        val num = match.groupValues[1].toLongOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase()
        val millis = when (unit) {
            "s", "sec" -> num * 1000L
            "m", "min" -> num * 60_000L
            "h", "hr" -> num * 3_600_000L
            "d", "day" -> num * 86_400_000L
            "w", "wk" -> num * 604_800_000L
            "mo", "mos" -> num * 2_592_000_000L
            "y", "yr" -> num * 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - millis
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    /**
     * Live taxonomy from GET /tag/get-grouped — four groups (format / genre /
     * theme / content), each an array of {id, name} rows used verbatim as the
     * search-advanced tag ids. The response is wrapped in a {"data": …}
     * envelope, so the inner object is what gets cached for getFilterList.
     */
    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): kotlinx.serialization.json.JsonElement {
        val response = client.get("$apiBase/tag/get-grouped", apiHeaders)
        val root = json.decodeFromString(
            kotlinx.serialization.json.JsonElement.serializer(),
            response.body.string(),
        )
        return (root as? kotlinx.serialization.json.JsonObject)?.get("data") ?: root
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        buildList {
            add(Filter.Header("MangaBall filters"))
            add(Filter.Separator())
            add(SortFilter())
            add(StatusFilter())
            add(DemographicFilter())
            add(OriginalLanguageFilter())
            add(YearFilter())
            add(Filter.Separator())

            val groups = runCatching { data?.parseAs<MbTagGroupsDto>(json) }.getOrNull()
            val genres = buildList {
                addAll(groups?.genre.orEmpty())
                addAll(groups?.theme.orEmpty())
            }.filter { it.idOrNull() != null && !it.name.isNullOrBlank() }
                .distinctBy { it.idOrNull() ?: it.slug }
            val formats = groups?.format.orEmpty()
                .filter { it.idOrNull() != null && !it.name.isNullOrBlank() }
                .distinctBy { it.idOrNull() ?: it.slug }

            if (genres.isNotEmpty()) {
                add(Filter.Header("Genres (live list from the site)"))
                add(GenreMatchModeFilter())
                add(GenreFilter("Genres", genres, excludeMode = false))
                add(GenreFilter("Exclude genres", genres, excludeMode = true))
            } else {
                add(Filter.Header("Genres are loading — press 'Reset' to retry"))
            }
            if (formats.isNotEmpty()) {
                add(Filter.Header("Format"))
                add(FormatFilter(formats))
            }
        },
    )

    private val sortValues = arrayOf(
        SORT_VIEWS_DESC,
        SORT_VIEWS_ASC,
        SORT_UPDATED_DESC,
        SORT_UPDATED_ASC,
        SORT_CREATED_DESC,
        SORT_CREATED_ASC,
        SORT_NAME_ASC,
        SORT_NAME_DESC,
    )

    private val statusValues = arrayOf("", "ongoing", "completed", "on_hold", "cancelled", "hiatus")
    private val demographicValues = arrayOf("", "shounen", "shoujo", "seinen", "josei", "yuri", "yaoi")
    private val languageValues = arrayOf("", "en", "jp", "kr", "cn", "zh", "manga", "comics", "manhua", "manhwa")

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf(
                "Most viewed",
                "Least viewed",
                "Latest update",
                "Oldest update",
                "Newest added",
                "Oldest added",
                "Title A-Z",
                "Title Z-A",
            ),
        )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Any", "Ongoing", "Completed", "On hold", "Cancelled", "Hiatus"),
        )

    private class DemographicFilter :
        Filter.Select<String>(
            "Demographic",
            arrayOf("Any", "Shounen", "Shoujo", "Seinen", "Josei", "Yuri", "Yaoi"),
        )

    private class OriginalLanguageFilter :
        Filter.Select<String>(
            "Original language",
            arrayOf("Any", "English (Comics)", "Japanese (Manga)", "Korean (Manhwa)", "Chinese (Manhua)"),
        )

    private class YearFilter : Filter.Text("Publication year", "")

    private class GenreMatchModeFilter :
        Filter.Select<String>(
            "Genre match mode",
            arrayOf("Any selected genre", "All selected genres"),
        )

    /** Checkbox genre filter carrying the site's tag ids as values. */
    private class GenreFilter(
        title: String,
        tags: List<MbFilterTag>,
        private val excludeMode: Boolean,
    ) : Filter.Group<GenreFilter.CheckBox>(
        title,
        tags.map { CheckBox(it.name.orEmpty(), it.idOrNull().orEmpty()) },
    ) {
        class CheckBox(name: String, val id: String) : Filter.CheckBox(name, false)

        fun selectedIds(): List<String> = state.filter { it.state }.map { it.id }

        fun includedIds(): List<String> = if (excludeMode) emptyList() else selectedIds()

        fun excludedIds(): List<String> = if (excludeMode) selectedIds() else emptyList()
    }

    /** Checkbox filter over the site's "format" tag group (Long Strip etc.). */
    private class FormatFilter(
        tags: List<MbFilterTag>,
    ) : Filter.Group<FormatFilter.CheckBox>(
        "Format",
        tags.map { CheckBox(it.name.orEmpty(), it.idOrNull().orEmpty()) },
    ) {
        class CheckBox(name: String, val id: String) : Filter.CheckBox(name, false)

        fun selectedIds(): List<String> = state.filter { it.state }.map { it.id }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_NSFW
            title = "Show NSFW content"
            summary = "Off = only safe titles (filtered server-side, exact site behavior)"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_CHAPTER_LANGUAGE
            title = "Chapter language"
            summary = "Preferred translation language for chapters (%s)"
            setDefaultValue("en")
            entries = CHAPTER_LANGUAGES.map { it.second }.toTypedArray()
            entryValues = CHAPTER_LANGUAGES.map { it.first }.toTypedArray()
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Preferred translation language for chapters ($newValue)"
                true
            }
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FALLBACK_LANGUAGE
            title = "Fallback to any language"
            summary = "Show chapters even when no translation in the preferred language exists"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_UPLOADS
            title = "Deduplicate chapter uploads"
            summary = "Keep only one upload per chapter (useful when multiple groups upload the " +
                "same chapter) — prefers the most viewed, then the freshest upload"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALL_UPLOADS
            title = "Show upload language tags"
            summary = "Append the translation language to chapter names when it differs from " +
                "the preferred language"
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
            summary = "Display status, original language, year, chapters, views and likes " +
                "above the description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.preferredLanguage(): String = getString(PREF_CHAPTER_LANGUAGE, "en") ?: "en"

    private fun android.content.SharedPreferences.fallbackLanguage(): Boolean = getBoolean(PREF_FALLBACK_LANGUAGE, true)

    private fun android.content.SharedPreferences.deduplicateUploads(): Boolean = getBoolean(PREF_DEDUPLICATE_UPLOADS, true)

    private fun android.content.SharedPreferences.showAllUploads(): Boolean = getBoolean(PREF_SHOW_ALL_UPLOADS, true)

    private fun android.content.SharedPreferences.showNsfw(): Boolean = getBoolean(PREF_SHOW_NSFW, true)

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    companion object {
        private val TITLE_PATHS = setOf("manga", "title-detail")

        /** The site's own title-id shape: 24 hex chars (Mongo ObjectId). */
        private val TITLE_ID_REGEX = Regex("""^[0-9a-f]{24}$""", RegexOption.IGNORE_CASE)

        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s*(s|sec|m|min|h|hr|d|day|w|wk|mo|mos|y|yr)s?\s*ago""")

        /** Strips the site's own "Ch. N" / "Chapter N" prefix from chapter titles. */
        private val CHAPTER_PREFIX_REGEX = Regex(
            """^\s*ch(apter)?\.?\s*[\d.]+\s*[-–—:]?\s*""",
            RegexOption.IGNORE_CASE,
        )

        // search-advanced string sorts (verified live against the new API).
        private const val SORT_VIEWS_DESC = "views_desc"
        private const val SORT_VIEWS_ASC = "views_asc"
        private const val SORT_UPDATED_DESC = "updated_chapters_desc"
        private const val SORT_UPDATED_ASC = "updated_chapters_asc"
        private const val SORT_CREATED_DESC = "created_at_desc"
        private const val SORT_CREATED_ASC = "created_at_asc"
        private const val SORT_NAME_ASC = "name_asc"
        private const val SORT_NAME_DESC = "name_desc"

        /** What the site's own client sends as user_id for logged-out visitors. */
        private const val USER_ID_DEMO = "demo_user"

        private const val PREF_CHAPTER_LANGUAGE = "pref_chapter_language"
        private const val PREF_FALLBACK_LANGUAGE = "pref_fallback_language"
        private const val PREF_DEDUPLICATE_UPLOADS = "pref_deduplicate_uploads"
        private const val PREF_SHOW_ALL_UPLOADS = "pref_show_all_uploads"
        private const val PREF_SHOW_NSFW = "pref_show_nsfw"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"

        private val CHAPTER_LANGUAGES = listOf(
            "en" to "English",
            "es" to "Spanish",
            "es-419" to "Spanish (LatAm)",
            "pt-BR" to "Portuguese (Brazil)",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "ru" to "Russian",
            "tr" to "Turkish",
            "ar" to "Arabic",
            "id" to "Indonesian",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "hi" to "Hindi",
            "tl" to "Filipino",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "zh-Hant" to "Chinese (Traditional)",
            "pl" to "Polish",
            "nl" to "Dutch",
            "uk" to "Ukrainian",
        )
    }
}
