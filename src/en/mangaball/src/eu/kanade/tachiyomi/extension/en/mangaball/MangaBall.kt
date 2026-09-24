package eu.kanade.tachiyomi.extension.en.mangaball

import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * MangaBall (mangaball.net) — Laravel app with a JSON API under /api/v1.
 *
 * Flow (mirrors the site's own front-end and the Yui007/mangaball-downloader
 * reference client):
 *  1. GET the homepage once and read the Laravel CSRF token from
 *     `meta[name="csrf-token"]` (also establishes the PHPSESSID session).
 *  2. Browse/search = POST /api/v1/title/search-advanced/ (form-encoded,
 *     `X-Requested-With: XMLHttpRequest`, `X-CSRF-Token: ...`).
 *  3. Chapter list = POST /api/v1/chapter/chapter-listing-by-title-id/
 *     with `title_id` (the trailing number of the title slug).
 *  4. Chapter pages = GET /chapter-detail/{translationId}/ and read the
 *     inline `const chapterImages = JSON.parse(\`...\`)` array.
 *
 * Cloudflare is handled Comix-style: WebView cookie sync + coherent browser
 * fingerprint + smart retry (see CloudflareBypass), with real challenge
 * solving left to the app-level CloudflareInterceptor.
 */
@Source
abstract class MangaBall :
    KeiSource(),
    ConfigurableSource {

    override val name = "MangaBall"

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val domain = baseUrl.removePrefix("https://")

    /** Lenient JSON — the API shape is verified but types of some fields are not. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Client for the CSRF bootstrap homepage (no csrf logic → no recursion). */
    private val csrfClient: OkHttpClient = network.client.newBuilder()
        .apply { CloudflareBypass(setOf(domain)).install(this) }
        .build()

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

        // KeiSource stamps "Origin" on everything; browsers never send it on
        // document GETs. Drop it there (same reason as Kagane's sanitizer).
        addInterceptor(::originSanitizerInterceptor)

        // Comix-style CF handling: cookie sync + fingerprint + smart retry.
        CloudflareBypass(setOf(domain)).install(this)
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

    /** Headers for document page GETs (homepage, title-detail, chapter-detail). */
    private val htmlHeaders by lazy {
        headers.newBuilder()
            .set(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .build()
    }

    /** Headers for the JSON API (XHR). */
    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // ------------------------------------------------------------------
    // CSRF token lifecycle
    // ------------------------------------------------------------------

    @Volatile
    private var csrfToken: String? = null

    private val csrfMutex = Mutex()

    private suspend fun ensureCsrf(force: Boolean = false): String? {
        if (!force) csrfToken?.let { return it }
        return csrfMutex.withLock {
            if (!force) csrfToken?.let { return it }

            val response = csrfClient.get("$baseUrl/", htmlHeaders)
            val body = response.body.string()
            val document = Jsoup.parse(body, baseUrl)

            val token = document.selectFirst("meta[name=csrf-token]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            if (token == null) {
                // Site layout change or a challenge page leaked through.
                throw IOException(
                    "MangaBall: couldn't read the site's CSRF token. " +
                        "Open the site in WebView once, then retry.",
                )
            }

            csrfToken = token
            token
        }
    }

    private fun invalidateCsrf() {
        csrfToken = null
    }

    /**
     * POSTs a form to an /api/v1 endpoint with CSRF + XHR headers, refreshing
     * the CSRF token once on Laravel's 419 (Page Expired) or a non-CF 403.
     * (CF blocks never reach here: CloudflareBypass retries and throws first.)
     */
    private suspend fun apiPost(url: String, form: FormBody): Response {
        val token = ensureCsrf()
        val headers = apiHeaders.newBuilder()
            .apply { token?.let { set("X-CSRF-Token", it) } }
            .build()

        val response = client.post(url, headers, form)
        if (response.code != 419 && response.code != 403) return response

        // Possibly expired session/token: re-bootstrap and retry once.
        response.close()
        invalidateCsrf()
        val fresh = ensureCsrf(force = true)
        val retryHeaders = apiHeaders.newBuilder()
            .apply { fresh?.let { set("X-CSRF-Token", it) } }
            .build()
        return client.post(url, retryHeaders, form)
    }

    // ------------------------------------------------------------------
    // Browse + search
    // ------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage = mangaList(page, searchType = "popular", query = "")

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaList(page, searchType = "latest", query = "")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var status: String? = null
        var demographic: String? = null
        var originalLanguage: String? = null
        var year: String? = null

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> status = statusValues[filter.state].takeIf { it.isNotBlank() }
                is DemographicFilter -> demographic = demographicValues[filter.state].takeIf { it.isNotBlank() }
                is OriginalLanguageFilter -> originalLanguage = languageValues[filter.state].takeIf { it.isNotBlank() }
                is YearFilter -> year = filter.state.trim().takeIf { it.isNotBlank() }
                else -> {}
            }
        }

        return mangaList(
            page = page,
            searchType = "latest",
            query = query,
            status = status,
            demographic = demographic,
            originalLanguage = originalLanguage,
            year = year,
        )
    }

    private suspend fun mangaList(
        page: Int,
        searchType: String,
        query: String,
        status: String? = null,
        demographic: String? = null,
        originalLanguage: String? = null,
        year: String? = null,
    ): MangasPage {
        val form = FormBody.Builder().apply {
            if (query.isNotBlank()) {
                // Verified working minimal search (Yui007/mangaball-downloader):
                // search_input + numeric sort + page.
                add("search_input", query)
                add("filters[sort]", "6")
            } else {
                // Home listings: the site's own listings use search_type.
                add("search_type", searchType)
            }
            add("filters[page]", page.toString())

            status?.let { add("filters[publicationStatus]", it) }
            demographic?.let { add("filters[demographic]", it) }
            originalLanguage?.let { add("filters[originalLanguages]", it) }
            year?.let { add("filters[publicationYear]", it) }
        }.build()

        val response = apiPost("$baseUrl/api/v1/title/search-advanced/", form)
        val result = response.parseAs<MbSearchResponse>(json)

        val mangas = result.data.map { it.toSManga() }
        return MangasPage(mangas, result.hasNextPage())
    }

    private fun MbTitleDto.toSManga(): SManga = SManga.create().apply {
        val slug = titleSlug()
        url = slug
        title = name.orEmpty().ifBlank { slug }
        thumbnail_url = coverUrl()?.toAbsoluteUrl()
        status = statusStringToSManga(this@toSManga.status)
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        isNotBlank() -> "$baseUrl/$this"
        else -> this
    }

    // ------------------------------------------------------------------
    // Details + chapters
    // ------------------------------------------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith(domain)) return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val slug = segments.last()
        return if (segments.first() in TITLE_PATHS) fetchMangaDetailsBySlug(slug) else null
    }

    private suspend fun fetchMangaDetailsBySlug(slug: String): SManga {
        val response = client.get("$baseUrl/title-detail/$slug/", htmlHeaders)
        return parseMangaDetails(response).apply { url = slug }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title-detail/${manga.url}/"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url

        val updatedManga = if (fetchDetails) {
            fetchMangaDetailsBySlug(slug)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            chapterList(slug)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(response: Response): SManga {
        val body = response.body.string()
        val document = Jsoup.parse(body, baseUrl)

        val rawTitle = document.selectFirst("#comicDetail h6")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        val title = rawTitle
            ?.replace(Regex("""\s*\|\s*MangaBall\s*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            .orEmpty()

        val description = document.selectFirst("#descriptionContent")?.text()?.trim()

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("#comicDetail img[src*=\"/storage/\"]")?.attr("src")

        val statusText = document.selectFirst("#comicDetail .badge-status")?.text()?.trim()

        val genres = buildList {
            addAll(document.select(".keywords-container [data-keyword-id]").eachText())
            addAll(document.select("#comicDetail [data-tag-id]").eachText())
            addAll(document.select("#comicDetail [data-person-id]").eachText())
        }.map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Free", ignoreCase = true) }
            .distinct()

        return SManga.create().apply {
            this.title = title
            this.description = description?.takeIf { it.isNotBlank() }
            this.thumbnail_url = cover?.toAbsoluteUrl()?.takeIf { it.isNotBlank() }
            this.status = statusTextToSManga(statusText)
            this.genre = genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
            this.initialized = true
        }
    }

    private fun statusStringToSManga(status: String?): Int = when (status?.lowercase()) {
        "ongoing", "releasing", "updating" -> SManga.ONGOING
        "completed", "finished", "end" -> SManga.COMPLETED
        "on_hold", "on hold", "hiatus" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped", "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun statusTextToSManga(text: String?): Int = when (text?.lowercase()) {
        "ongoing", "releasing", "updating" -> SManga.ONGOING
        "completed", "finished", "end" -> SManga.COMPLETED
        "on hold", "hiatus" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped", "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ------------------------------------------------------------------
    // Chapter list
    // ------------------------------------------------------------------

    private suspend fun chapterList(slug: String): List<SChapter> {
        val titleId = slug.substringAfterLast('-')

        val form = FormBody.Builder()
            .add("title_id", titleId)
            .build()

        val response = apiPost("$baseUrl/api/v1/chapter/chapter-listing-by-title-id/", form)
        val listing = response.parseAs<MbChapterListingResponse>(json)

        val preferredLang = preferences.preferredLanguage().lowercase()
        val fallback = preferences.fallbackLanguage()
        val dedupe = preferences.deduplicateUploads()
        val showUploads = preferences.showAllUploads()

        val groups = listing.allChapters
            .sortedByDescending { it.chapterNumber() }

        val chapters = mutableListOf<SChapter>()

        for (group in groups) {
            val uploads = group.translations
            if (uploads.isEmpty()) continue

            var candidates = uploads.filter {
                it.language?.lowercase() == preferredLang
            }
            if (candidates.isEmpty() && fallback) candidates = uploads
            if (candidates.isEmpty()) continue

            val chosen = if (dedupe) {
                listOf(bestUpload(candidates))
            } else {
                candidates
            }

            for (tr in chosen) {
                val id = tr.idOrNull() ?: continue
                val lang = tr.language?.lowercase().orEmpty()
                chapters += tr.toSChapter(group, id, preferredLang, lang, showUploads)
            }
        }

        if (chapters.isEmpty()) {
            throw IOException(
                "MangaBall returned no chapters for this title. " +
                    "If the title opens on the website, refresh this screen; " +
                    "if it keeps failing, the chapter language settings may exclude every upload " +
                    "(Settings → MangaBall → Fallback to any language).",
            )
        }

        return chapters
    }

    /** Picks the "best" upload: most likes → most views → newest date. */
    private fun bestUpload(candidates: List<MbChapterTranslationDto>): MbChapterTranslationDto = candidates.maxWithOrNull(
        compareBy(
            { it.likesOrNull() },
            { it.viewsOrNull() },
            { parseDate(it.date) },
        ),
    ) ?: candidates.first()

    private fun MbChapterTranslationDto.toSChapter(
        group: MbChapterGroupDto,
        id: String,
        preferredLang: String,
        lang: String,
        showLangTag: Boolean,
    ): SChapter = SChapter.create().apply {
        url = id
        val num = group.chapterNumber()
        val rawTitle = group.title?.trim().orEmpty()
        val vol = volumeOrNull()

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
            if (rawTitle.isNotBlank()) {
                if (isNotEmpty()) append(" - ")
                append(rawTitle)
            }
            if (isEmpty()) append("Chapter $id")
            if (showLangTag && lang.isNotBlank() && lang != preferredLang) {
                append(" [")
                append(lang)
                append("]")
            }
        }

        chapter_number = num.toFloat().takeIf { num > 0 } ?: -1f
        date_upload = parseDate(date)
        scanlator = groupNameOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: language?.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    private fun formatNumber(value: Double): String {
        val trimmed = value.toString().removeSuffix(".0")
        return if (trimmed.endsWith(".0")) trimmed.removeSuffix(".0") else trimmed
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.trim().trim('/').substringAfterLast('/')
        val response = client.get("$baseUrl/chapter-detail/$id/", htmlHeaders)
        val body = response.body.string()

        if (response.code == 403 || body.contains("Just a moment", ignoreCase = true)) {
            throw IOException(
                "MangaBall's Cloudflare check didn't clear. Open the chapter in WebView once, " +
                    "then retry.",
            )
        }

        val pageUrls = extractChapterImages(body)
            .ifEmpty { extractChapterImagesFallback(body) }

        if (pageUrls.isEmpty()) {
            throw IOException(
                "MangaBall: couldn't read this chapter's pages (site layout changed or " +
                    "Cloudflare blocked the request). Refresh the chapter list and try again, " +
                    "or open the chapter in WebView.",
            )
        }

        return pageUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url.toAbsoluteUrl())
        }
    }

    /**
     * The chapter-detail page embeds the page list as
     * `const chapterImages = JSON.parse(\`[...]\`)` — the same inline JS the
     * site's own reader consumes.
     */
    private fun extractChapterImages(body: String): List<String> {
        val match = CHAPTER_IMAGES_REGEX.find(body) ?: return emptyList()
        val rawJson = match.groupValues[1].trim()
        if (rawJson.isEmpty()) return emptyList()

        return runCatching {
            val element = json.decodeFromString(
                kotlinx.serialization.json.JsonElement.serializer(),
                rawJson,
            )
            when (element) {
                is kotlinx.serialization.json.JsonArray ->
                    element.mapNotNull { it.asStringOrNull() }
                is kotlinx.serialization.json.JsonObject ->
                    element["images"]?.let { el ->
                        (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.asStringOrNull() }
                    } ?: emptyList()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Fallbacks for layout variations of the inline page list. */
    private fun extractChapterImagesFallback(body: String): List<String> {
        for (regex in FALLBACK_IMAGE_REGEXES) {
            val match = regex.find(body) ?: continue
            val rawJson = match.groupValues[1].trim()
            val urls = runCatching {
                val element = json.decodeFromString(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    rawJson,
                )
                (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.asStringOrNull() }
            }.getOrNull() ?: emptyList()
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter-detail/${chapter.url.trim('/').substringAfterLast('/')}/"

    override fun imageRequest(page: Page) = GET(page.imageUrl!!, headers)

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

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        Filter.Header("MangaBall filters"),
        Filter.Separator(),
        StatusFilter(),
        DemographicFilter(),
        OriginalLanguageFilter(),
        YearFilter(),
    )

    private val statusValues = arrayOf("", "ongoing", "completed", "on_hold", "cancelled", "hiatus")
    private val demographicValues = arrayOf("", "shounen", "shoujo", "seinen", "josei", "yuri", "yaoi")
    private val languageValues = arrayOf("", "en", "jp", "kr", "zh")

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
            arrayOf("Any", "English", "Japanese", "Korean", "Chinese"),
        )

    private class YearFilter : Filter.Text("Publication year", "")

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FALLBACK_LANGUAGE
            title = "Fallback to any language"
            summary = "Show chapters even when no translation in the preferred language exists"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_UPLOADS
            title = "Deduplicate chapter uploads"
            summary = "MangaBall often carries several uploads per chapter — keep the best one " +
                "(most liked, then most viewed, then newest)"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALL_UPLOADS
            title = "Show upload language tags"
            summary = "Append the translation language to chapter names when it differs from " +
                "the preferred language"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.preferredLanguage(): String = getString(PREF_CHAPTER_LANGUAGE, "en") ?: "en"

    private fun android.content.SharedPreferences.fallbackLanguage(): Boolean = getBoolean(PREF_FALLBACK_LANGUAGE, true)

    private fun android.content.SharedPreferences.deduplicateUploads(): Boolean = getBoolean(PREF_DEDUPLICATE_UPLOADS, true)

    private fun android.content.SharedPreferences.showAllUploads(): Boolean = getBoolean(PREF_SHOW_ALL_UPLOADS, true)

    companion object {
        private val TITLE_PATHS = setOf("manga", "title-detail")

        private val CHAPTER_IMAGES_REGEX = Regex(
            """const\s+chapterImages\s*=\s*JSON\.parse\(`([^`]+)`\)""",
        )

        private val FALLBACK_IMAGE_REGEXES = arrayOf(
            Regex("""chapterImages\s*=\s*(\[[\s\S]*?\])\s*;"""),
            Regex("""chapterImages\s*=\s*JSON\.parse\('([^']+)'\)"""),
            Regex("""chapterImages\s*=\s*JSON\.parse\("([^"]+)"\)"""),
        )

        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s*(s|sec|m|min|h|hr|d|day|w|wk|mo|mos|y|yr)s?\s*ago""")

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

        private const val PREF_CHAPTER_LANGUAGE = "pref_chapter_language"
        private const val PREF_FALLBACK_LANGUAGE = "pref_fallback_language"
        private const val PREF_DEDUPLICATE_UPLOADS = "pref_deduplicate_uploads"
        private const val PREF_SHOW_ALL_UPLOADS = "pref_show_all_uploads"
    }
}
