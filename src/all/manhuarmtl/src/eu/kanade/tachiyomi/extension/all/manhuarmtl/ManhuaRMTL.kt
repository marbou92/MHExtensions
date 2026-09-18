package eu.kanade.tachiyomi.extension.all.manhuarmtl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Source
abstract class ManhuaRMTL :
    Madara(),
    ConfigurableSource {

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val mangaSubString = "manga"

    /**
     * Client for minting a fresh clearance against the site root when an
     * image/XHR request gets challenged. Hardened identically but without
     * priming of its own (no recursion).
     */
    private val primeClient: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .apply {
            CloudflareBypass(setOf(baseUrl.removePrefix("https://"), "cdn.manhuarmtl.com")).install(this)
        }
        .build()

    // Custom client: Cloudflare hardening + OCR text-overlay interceptor
    override val client: OkHttpClient = network.client.newBuilder()
        .apply {
            CloudflareBypass(
                cookieHosts = setOf(baseUrl.removePrefix("https://"), "cdn.manhuarmtl.com"),
                primeUrl = "$baseUrl/",
                primeClient = primeClient,
            ).install(this)
        }
        .addNetworkInterceptor(::ocrImageInterceptor)
        .build()

    /**
     * Short-timeout client for the auxiliary OCR/translation calls — a hung
     * gate used to stall the whole chapter open path for up to a minute.
     */
    private val auxClient: OkHttpClient = network.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    // Thread-safe storage for OCR text boxes, keyed by full image URL
    private val ocrData = ConcurrentHashMap<String, List<OcrTextBox>>()

    // Translation cache: "<lang>|<text>" -> translated text
    private val translationCache = ConcurrentHashMap<String, String>()

    // Background pool that pre-translates chapter text while images download
    private val translateExecutor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "ManhuaRMTL-Translate").apply { isDaemon = true }
    }

    // Site excludes adult content by default; override to show everything unless the user opts out
    override val adultContentFilterOptions: Map<String, String> = mapOf(
        "Show all (incl. adult)" to "",
        "Hide adult" to "0",
        "Adult only" to "1",
    )

    // Verified against the site's own browse dropdown (#mrm-arch-sort).
    // NOTE: the values change when the site updates its theme — v1.4.63 used
    // views_all/recent and those silently fell back to the default (A-Z)
    // ordering, which made Popular and Latest show the same list.
    override val orderByFilterOptions: Map<String, String> = mapOf(
        "Latest update" to "latest",
        "Least recently updated" to "latest_asc",
        "Trending" to "trending",
        "Newest added" to "new",
        "Oldest added" to "new_asc",
        "Title A-Z" to "az",
        "Title Z-A" to "za",
        "Most chapters" to "chapters",
        "Fewest chapters" to "chapters_asc",
        "Top rated" to "rating",
    )

    private val preferences = getPreferences()

    // ============================== Popular / Latest ==============================

    // Custom MRM card layout instead of standard Madara
    override fun popularMangaSelector() = "li.mrm-r-item"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        url = element.selectFirst("a.mrm-r-item__link")?.attr("href")?.substringAfter(baseUrl) ?: ""
        title = element.selectFirst("a.mrm-r-item__link")?.attr("title") ?: ""
        thumbnail_url = element.selectFirst("span.mrm-r-item__art img")?.attr("abs:src")?.trim()
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // ------------------------------------------------------------------
    // Browse (Popular / Latest)
    //
    // IMPORTANT: the site only honours the "sort" values rendered in its
    // own dropdown (#mrm-arch-sort). Unknown values (like the old
    // "views_all") silently fall back to the DEFAULT ordering, which is
    // the same list "latest" shows — that is why Popular and Latest
    // used to lineup identically.
    // Verified live 2026-09:
    //   Popular → sort=trending (Trending, the site's popularity sort)
    //   Latest  → sort=latest   (Recently updated — the dropdown default)
    // ------------------------------------------------------------------
    private fun archiveUrl(sort: String, page: Int): String {
        val path = if (page > 1) "/manga/page/$page/" else "/manga/"
        val adult = if (preferences.hideNsfw()) "&adult=0" else ""
        return "$baseUrl$path?sort=$sort$adult"
    }

    override fun popularMangaRequest(page: Int): Request = GET(archiveUrl("trending", page), headers)

    override fun latestUpdatesRequest(page: Int): Request = GET(archiveUrl("latest", page), headers)

    override fun popularMangaNextPageSelector(): String? = "a.next.page-numbers, a.mrm-pager__btn[rel=next]"

    // ============================== Search ==============================
    // Search ALWAYS shows everything — the NSFW setting does NOT filter search.
    // The user can still find NSFW content via search even when "Hide NSFW" is ON.

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        // Check if user explicitly selected an Adult content filter
        val adultFilter = filters.filterIsInstance<AdultContentFilter>().firstOrNull()
        // Default to "show all" in search — the NSFW setting doesn't affect search
        val adultValue = adultFilter?.toUriPart() ?: ""

        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("post_type", "wp-manga")
            addQueryParameter("s", query)
            addQueryParameter("adult", adultValue)
            if (page > 1) addQueryParameter("pg", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is AuthorFilter -> if (filter.state.isNotBlank()) addQueryParameter("author", filter.state)
                    is ArtistFilter -> if (filter.state.isNotBlank()) addQueryParameter("artist", filter.state)
                    is YearFilter -> if (filter.state.isNotBlank()) addQueryParameter("release", filter.state)
                    is StatusFilter -> filter.state.forEach { if (it.state) addQueryParameter("status[]", it.id) }
                    is OrderByFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("sort", filter.toUriPart())
                    is GenreConditionFilter -> addQueryParameter("op", filter.toUriPart())
                    is GenreList -> filter.state.filter { it.state }.forEach { addQueryParameter("genre[]", it.id) }
                    is ExcludeGenreList -> filter.state.filter { it.state }.forEach { addQueryParameter("exclude_genre[]", it.id) }
                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaNextPageSelector(): String? = "a.next.page-numbers, a.mrm-pager__btn[rel=next]"

    // ============================== Genres ==============================
    //
    // The site sits behind an intermittent Cloudflare challenge; Madara's
    // built-in genre loader gives up FOREVER after one failed attempt
    // (genresFetched flag), which is why the genre filters came up empty.
    // We run our own loader with retries + backoff and two page sources:
    // the search page (filter chips) and the homepage (genre nav links).

    private fun loadGenresWithRetry() {
        val requests = listOf(
            genresRequest(),
            GET("$baseUrl/", headers),
        )

        repeat(GENRE_FETCH_ATTEMPTS) { attempt ->
            if (genresList.isNotEmpty()) return

            for (request in requests) {
                if (genresList.isNotEmpty()) return
                try {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val parsed = parseGenres(resp.asJsoup())
                        if (parsed.isNotEmpty()) genresList = parsed
                    }
                } catch (_: Exception) {
                    // Retry below
                }
            }

            if (genresList.isEmpty()) {
                try {
                    Thread.sleep(1500L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    // Override the genre request — the form lives on the search results page
    override fun genresRequest(): Request = GET("$baseUrl/?post_type=wp-manga&s=", headers)

    // Custom MRM chips layout — NOT the standard Madara checkbox-group.
    // Multiple selectors so minor theme tweaks don't empty the filter list.
    override fun parseGenres(document: Document): List<Genre> {
        // 1) Current MRM filter chips on the search page
        val chips = document.select("div.mrm-fgroup__chips label.mrm-gchip--in, label.mrm-gchip")
            .mapNotNull { label ->
                val name = label.selectFirst("span")?.text()?.takeIf { it.isNotBlank() }
                    ?: label.ownText().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val id = label.selectFirst("input[type=checkbox]")?.`val`()?.takeIf { it.isNotBlank() }
                    ?: name.slugify()
                Genre(name, id)
            }
            .distinctBy { it.id.lowercase() }
        if (chips.isNotEmpty()) return chips

        // 2) Standard Madara checkbox group (in case the theme reverts)
        val checkboxes = document.selectFirst("div.checkbox-group")
            ?.select("div.checkbox")
            ?.mapNotNull { li ->
                val name = li.selectFirst("label")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = li.selectFirst("input[type=checkbox]")?.`val`()?.takeIf { it.isNotBlank() }
                    ?: name.slugify()
                Genre(name, id)
            }
            .orEmpty()
            .distinctBy { it.id.lowercase() }
        if (checkboxes.isNotEmpty()) return checkboxes

        // 3) Genre nav/tag links (homepage menus, details pages) — the href
        //    slug is exactly what the search endpoint expects in genre[].
        return document.select("a[href*='/genre/'], a[href*=\"/genre/\"], div.mrm-genres__list a[rel=tag]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("/genre/").trimEnd('/').substringBefore('?').substringBefore('#')
                if (slug.isBlank()) return@mapNotNull null
                val name = a.text().takeIf { it.isNotBlank() }
                    ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
                Genre(name, slug)
            }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id.lowercase() }
    }

    private fun String.slugify(): String = trim()
        .lowercase()
        .replace("[^a-z0-9]+".toRegex(), "-")
        .trim('-')

    // ============================== Manga Details ==============================

    // Custom MRM "hero" layout selectors
    override val mangaDetailsSelectorTitle = "h1.mrm-hero__title"
    override val mangaDetailsSelectorThumbnail = "div.mrm-hero__cover img"
    override val mangaDetailsSelectorAuthor = ".post-content_item:contains(Author) .author-content a, .post-content_item:contains(Author) .summary-content a"
    override val mangaDetailsSelectorArtist = ".post-content_item:contains(Artist) .artist-content a, .post-content_item:contains(Artist) .summary-content a"
    override val mangaDetailsSelectorStatus = ".post-content_item:contains(Status) .summary-content"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5:contains(Summary) + div, div.mrm-panel div.summary__content"
    override val mangaDetailsSelectorGenre = "div.mrm-genres__list a[rel=tag]"
    override val mangaDetailsSelectorTag = ""
    override val seriesTypeSelector = ".post-content_item:contains(Type) .summary-content"

    // Alt names live in the MRM hero block, not the standard post-content row
    override val altNameSelector = "p.mrm-hero__alt"

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        with(document) {
            manga.title = selectFirst(mangaDetailsSelectorTitle)?.ownText() ?: ""
            select(mangaDetailsSelectorAuthor).map { it.text() }.filter { it.notUpdating() }.joinToString().takeIf { it.isNotBlank() }?.let { manga.author = it }
            select(mangaDetailsSelectorArtist).map { it.text() }.filter { it.notUpdating() }.joinToString().takeIf { it.isNotBlank() }?.let { manga.artist = it }

            // Raw synopsis
            val synopsis = selectFirst(mangaDetailsSelectorDescription)?.let {
                if (it.select("p").text().isNotEmpty()) {
                    it.select("p").joinToString(separator = "\n\n") { p -> p.text().replace("<br>", "\n") }
                } else {
                    it.text()
                }
            }

            selectFirst(mangaDetailsSelectorThumbnail)?.let { manga.thumbnail_url = imageFromElement(it) }

            selectFirst(mangaDetailsSelectorStatus)?.let {
                val statusText = it.text().filter { ch -> ch.isLetterOrDigit() || ch.isWhitespace() }.trim()
                manga.status = when {
                    completedStatusList.any { c -> c.equals(statusText, true) } -> SManga.COMPLETED
                    ongoingStatusList.any { c -> c.equals(statusText, true) } -> SManga.ONGOING
                    hiatusStatusList.any { c -> c.equals(statusText, true) } -> SManga.ON_HIATUS
                    canceledStatusList.any { c -> c.equals(statusText, true) } -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }

            // Extract type early — used for both genre chips and info line
            val type = selectFirst(seriesTypeSelector)?.ownText()?.takeIf { it.isNotBlank() && it.notUpdating() }

            // Genres (optionally include type: Manhwa/Manhua/Manga)
            val genreList = select(mangaDetailsSelectorGenre).mapTo(ArrayList()) { it.text() }
            if (preferences.showTypeInGenre() && type != null) {
                genreList.add(type)
            }
            manga.genre = genreList.distinctBy(String::lowercase).joinToString().ifBlank { null }

            // ===== Build comix-style description =====
            val showAltNames = preferences.showAltNames()
            val showExtraInfo = preferences.showExtraInfo()
            val scorePosition = preferences.getScorePosition()

            // Alt names
            val altNames = selectFirst(altNameSelector)?.ownText()?.takeIf { it.isNotBlank() && it.notUpdating() }

            // Rating / votes from MRM facts — site uses a 0-5 scale (NOT 0-10 like comix)
            val ratingText = selectFirst("li.mrm-facts__item--rating strong")?.text()
            val ratingScore = ratingText?.toFloatOrNull()
            val votesText = selectFirst("li.mrm-facts__item--rating .mrm-facts__sub")?.text()
            val votesCount = Regex("""(\d+)""").find(votesText ?: "")?.value?.toIntOrNull() ?: 0
            val hasScore = ratingScore != null && votesCount > 0

            val stars = if (hasScore) {
                val score = ratingScore!!
                // Site uses 0-5 scale: round to nearest int (5.0 → 5 stars, 4.4 → 4, 4.8 → 5)
                val fullStars = score.roundToInt().coerceIn(0, 5)
                "★".repeat(fullStars) + "☆".repeat(5 - fullStars) + " $score"
            } else {
                null
            }

            // Type / chapters / views / release year
            val chaptersText = selectFirst(".post-content_item:contains(Chapters) .summary-content")?.text()
            val chaptersNum = chaptersText?.filter { it.isDigit() }?.toIntOrNull()
            val releaseYear = selectFirst(".post-content_item:contains(Release) .summary-content a")?.text()
                ?: selectFirst(".post-content_item:contains(Release) .summary-content")?.ownText()
            val viewsText = selectFirst("li.mrm-facts__item:has(i.ion-md-eye)")?.text()
            val views = viewsText?.filter { it.isDigit() }

            val infoLine = if (showExtraInfo) {
                buildString {
                    if (type != null) append("**Type:** $type")
                    if (releaseYear != null) {
                        if (isNotEmpty()) append(" · ")
                        append("**Year:** $releaseYear")
                    }
                    if (chaptersNum != null && chaptersNum > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("**Chapters:** $chaptersNum")
                    }
                    if (views != null && views.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("**Views:** $views")
                    }
                    if (manga.status != SManga.UNKNOWN) {
                        if (isNotEmpty()) append(" · ")
                        append("**Status:** ${formatStatus(manga.status)}")
                    }
                    if (hasScore) {
                        if (isNotEmpty()) append(" · ")
                        append("**$votesCount ratings**")
                    }
                }.ifBlank { null }
            } else {
                null
            }

            val desc = buildString {
                if (scorePosition == "top" && stars != null) {
                    append(stars)
                    append("\n")
                    if (infoLine != null) {
                        append(infoLine)
                        append("\n\n")
                    }
                }

                synopsis?.let { append(it) }

                if (showAltNames && altNames != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative names:\n")
                    append("• $altNames")
                }

                if (scorePosition == "end" && stars != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(stars)
                    if (infoLine != null) {
                        append("\n")
                        append(infoLine)
                    }
                }

                if (scorePosition == "none" && infoLine != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(infoLine)
                }
            }.trim()

            manga.description = desc.ifBlank { synopsis }
            manga.initialized = true
        }

        return manga
    }

    private fun formatStatus(status: Int): String = when (status) {
        SManga.ONGOING -> "Ongoing"
        SManga.COMPLETED -> "Completed"
        SManga.CANCELLED -> "Cancelled"
        SManga.ON_HIATUS -> "On hiatus"
        else -> "Unknown"
    }

    // ============================== Chapters ==============================
    // Standard Madara selectors work — li.wp-manga-chapter is present in the detail HTML.
    // All chapters are in the initial page load (no AJAX needed).

    // ============================== Pages + OCR ==============================
    // The site serves RAW images. Translated text is a JS overlay fetched from
    // fetch-ocr.php. We parse the _0xvault credentials from the reading page,
    // fetch the text data, translate it to the user's language, and burn it
    // onto the images via a network interceptor.

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: ""
        val readingPageUrl = response.request.url.toString()
        val document = Jsoup.parse(html, readingPageUrl)

        val pages = pageListParse(document)

        val mode = chapterTextMode()
        if (mode != MODE_RAW && html.isNotBlank()) {
            // Clear previous chapter's OCR data
            ocrData.clear()

            try {
                val credentials = parseOcrCredentials(html)
                if (credentials != null) {
                    val ocrPages = fetchOcrData(credentials, readingPageUrl)
                    if (ocrPages != null && ocrPages.isNotEmpty()) {
                        // Build filename → text boxes map (try multiple key formats for robust matching)
                        val ocrByFilename = mutableMapOf<String, List<OcrTextBox>>()
                        val grouping = ocrTextGrouping()
                        for (ocrPage in ocrPages) {
                            val filename = ocrPage.image ?: continue
                            val rawBoxes = ocrPage.normalisedTexts()
                            if (rawBoxes.isEmpty()) continue
                            // Site-like rendering: merge consecutive line boxes
                            // into paragraph blocks (union box + joined text).
                            val textBoxes = when (grouping) {
                                GROUP_PARAGRAPH -> groupIntoParagraphs(
                                    rawBoxes.distinctBy { b ->
                                        "${b.text}|${b.box.map { (it * 10).toInt() }}"
                                    },
                                )
                                else -> rawBoxes.distinctBy { b ->
                                    "${b.text}|${b.box.map { (it * 10).toInt() }}"
                                }
                            }
                            if (textBoxes.isNotEmpty()) {
                                // Store under original name AND URL-decoded name
                                ocrByFilename[filename] = textBoxes
                                ocrByFilename[filename.replace("%20", " ")] = textBoxes
                                ocrByFilename[filename.replace(" ", "_")] = textBoxes
                            }
                        }

                        // Match OCR data to pages by filename (try multiple formats)
                        for (page in pages) {
                            val imageUrl = page.imageUrl ?: continue
                            // Strip leading spaces (site has src=" https://..."), get filename, strip query
                            val filename = imageUrl.trim().substringAfterLast("/").substringBefore("?")
                            // Also try URL-decoded version
                            val decodedFilename = java.net.URLDecoder.decode(filename, "UTF-8")

                            val textBoxes = ocrByFilename[filename]
                                ?: ocrByFilename[decodedFilename]
                                ?: ocrByFilename[decodedFilename.replace(" ", "_")]
                            if (textBoxes != null && textBoxes.isNotEmpty()) {
                                ocrData[imageUrl.trim()] = textBoxes
                            }
                        }

                        // Pre-translate all text boxes in the background so the
                        // overlay is ready by the time images arrive (non-English modes)
                        if (mode != MODE_EN) {
                            ocrData.values
                                .flatMap { boxes -> boxes.map { it.text } }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .forEach { text ->
                                    translateExecutor.execute {
                                        try {
                                            translateText(text, mode)
                                        } catch (_: Exception) {
                                            // Interceptor falls back to the English text
                                        }
                                    }
                                }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back to raw images silently
            }
        }

        return pages
    }

    /**
     * Parse OCR credentials from the reading page HTML.
     * The credentials are in a JS array: _0xvault = ["base64cid","hex64token",ts,"hex16nonce","url","hex32ref"]
     */
    private fun parseOcrCredentials(html: String): OcrCredentials? {
        // Find the _0xvault array — it contains exactly 6 elements
        // ["base64","hex64",number,"hex16","url","hex32"]
        val vaultRegex = Regex(
            """_0xvault\s*=\s*\[\s*"([A-Za-z0-9+/=]+)"\s*,\s*"([0-9a-f]{64})"\s*,\s*(\d+)\s*,\s*"([0-9a-f]{16})"\s*,\s*"(https?:\\?/\\?/[^"]+fetch-ocr\.php)"\s*,\s*"([0-9a-f]{32})"\s*\]""",
        )
        val match = vaultRegex.find(html) ?: return null

        // Unescape the URL (JS uses \/ for /)
        val gateUrl = match.groupValues[5].replace("\\/", "/")

        return OcrCredentials(
            cid = match.groupValues[1], // base64 — sent as-is, do NOT decode
            token = match.groupValues[2], // 64-hex
            timestamp = match.groupValues[3].toLongOrNull() ?: 0L,
            nonce = match.groupValues[4], // 16-hex
            gateUrl = gateUrl,
            ref = match.groupValues[6], // 32-hex
        )
    }

    /**
     * Fetch OCR text data from fetch-ocr.php.
     * Sends the exact same request as the site's JS:
     * - POST with JSON body {"cid":"<base64>","ref":"<hex>"}
     * - Headers: X-Gate-Token, X-Gate-Nonce, X-Gate-Timestamp, X-Requested-With, Cache-Control
     * - Origin and Referer headers are REQUIRED (site returns 403 without them)
     */
    private fun fetchOcrData(credentials: OcrCredentials, readingPageUrl: String): List<OcrPage>? {
        // Body: cid stays base64, ref is hex — both as-is from _0xvault
        val jsonBody = """{"cid":"${credentials.cid}","ref":"${credentials.ref}"}"""
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        // Use the source's default headers (includes User-Agent) as a base,
        // then set all required OCR headers
        val request = Request.Builder()
            .url(credentials.gateUrl)
            .post(requestBody)
            .headers(headers)
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cache-Control", "no-cache")
            .header("X-Gate-Token", credentials.token)
            .header("X-Gate-Nonce", credentials.nonce)
            .header("X-Gate-Timestamp", credentials.timestamp.toString())
            .header("Referer", readingPageUrl)
            .header("Origin", baseUrl)
            .build()

        return try {
            val response = auxClient.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (body.isNullOrBlank()) return null

            // Detect Cloudflare challenge page
            if (body.contains("Just a moment") || body.contains("cf-challenge") || body.contains("cf-mitigated")) {
                return null
            }

            // Try parsing as bare array first, then as envelope
            try {
                body.parseAs<List<OcrPage>>()
            } catch (_: Exception) {
                try {
                    body.parseAs<OcrResponse>().pages()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ============================== Translation ==============================
    // The OCR gate only carries English text. For the other overlay languages
    // we translate each text box (Google's public gtx endpoint) and cache the
    // result, so each string is translated at most once.

    private fun translateText(text: String, target: String): String {
        val key = "$target|$text"
        translationCache[key]?.let { return it }

        val translated = try {
            val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "en")
                .addQueryParameter("tl", target)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                .build()

            auxClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                parseGtxResponse(body)
            }
        } catch (_: Exception) {
            null
        }

        // Fall back to the English text when translation fails
        val result = translated?.takeIf { it.isNotBlank() } ?: text

        if (translationCache.size > MAX_TRANSLATION_CACHE) translationCache.clear()
        translationCache[key] = result
        return result
    }

    private fun parseGtxResponse(body: String): String? {
        val segments = Json.parseToJsonElement(body).jsonArray
            .firstOrNull()?.jsonArray ?: return null

        return buildString {
            segments.forEach { segment ->
                try {
                    append(segment.jsonArray[0].jsonPrimitive.content)
                } catch (_: Exception) {
                    // Skip malformed segments
                }
            }
        }.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Network interceptor that overlays translated text on raw chapter images.
     * Runs for every image served from the site/CDN while a text mode is active.
     */
    private fun ocrImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val mode = chapterTextMode()
        if (mode == MODE_RAW) return response
        if (!response.isSuccessful) return response

        val url = request.url.toString()

        // Only process images from the site hosts (covers cdn.manhuarmtl.com)
        if (!url.contains("manhuarmtl.com")) return response

        // Look up OCR text boxes for this image URL (try both raw and trimmed)
        val textBoxes = ocrData[url] ?: ocrData[url.trim()] ?: return response
        if (textBoxes.isEmpty()) return response

        // Read the image bytes
        val imageBytes = response.body?.bytes() ?: return response
        if (imageBytes.isEmpty()) return response

        // Overlay text on the image
        val modifiedBytes = overlayText(imageBytes, textBoxes, mode) ?: return response

        // Build new response with modified image
        val contentType = response.body?.contentType()
        val newBody = modifiedBytes.toResponseBody(contentType)

        return response.newBuilder()
            .body(newBody)
            .build()
    }

    /**
     * Burn text boxes onto a raw image bitmap.
     * Matches the site's rendering:
     * - Font size: min(sqrt(w*h)/sqrt(len), w/len, h/2), clamped [8, 64], then
     *   ×2 (the site's own 200% sizing) — an optional user scale is applied on
     *   top (default 1.0 = same size as the site).
     * - Text horizontally centered on the box center (allowed to overflow the
     *   image edge, exactly like the site's overlay divs).
     * - Text TOP-ALIGNED to the box top — the site anchors the first line at
     *   the top of the box; vertically centering it made labels sit visibly
     *   lower than on the website.
     * - Black text with a white outline.
     */
    private fun overlayText(imageBytes: ByteArray, textBoxes: List<OcrTextBox>, targetLang: String): ByteArray? {
        // inMutable avoids a second full-image copy (big win on the tall
        // webtoon strips this site serves).
        val options = BitmapFactory.Options().apply { inMutable = true }
        var decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        if (decoded == null) {
            // Retry once without options in case the format choked on inMutable
            decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        }
        val mutableBitmap = if (decoded.isMutable) {
            decoded
        } else {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            copy ?: return null
        }
        val canvas = Canvas(mutableBitmap)

        for (textBox in textBoxes) {
            val x = textBox.box.getOrElse(0) { 0f }
            val y = textBox.box.getOrElse(1) { 0f }
            val w = textBox.box.getOrElse(2) { 0f }
            val h = textBox.box.getOrElse(3) { 0f }

            if (w <= 0 || h <= 0) continue

            val text = when (targetLang) {
                MODE_EN -> textBox.text
                else -> translateText(textBox.text, targetLang)
            }
            if (text.isBlank()) continue

            // ===== Font size calculation (the site's own formula) =====
            // 1. baseFontSize = min(sqrt(w*h)/sqrt(len), w/len, h/2)
            // 2. Clamp to [8, 64]; 3. ×2 (site's 200% sizing); 4. Clamp to [8, 64]
            val textLength = text.length
            val boxArea = w * h
            val areaFactor = Math.sqrt(boxArea.toDouble()) / Math.sqrt(textLength.toDouble())
            val widthFactor = w.toDouble() / textLength
            val heightFactor = h.toDouble() / 2.0
            val rawBase = minOf(areaFactor, widthFactor, heightFactor)
            val clampedBase = rawBase.coerceIn(8.0, 64.0)
            val finalSize = clampedBase * 2.0
            val siteFontSize = finalSize.toFloat().coerceIn(8f, 64f)
            // Optional user scale (default 1.0 = site-exact size)
            val fontSize = (siteFontSize * overlayTextScale()).coerceIn(6f, 120f)

            // Outline width: max(0.75, fontSize * 0.08)
            val outlineWidth = maxOf(0.75f, fontSize * 0.08f)

            // maxWidth = w * 1.4 (text can extend beyond box)
            val maxWidth = (w * 1.4f).toInt()

            // Stroke paint (white outline — 4-corner shadow simulation)
            // Using "casual" font family for a more comic/manga look (closest to Anime Ace)
            val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = fontSize
                typeface = Typeface.create("casual", Typeface.BOLD)
                style = Paint.Style.STROKE
                strokeWidth = outlineWidth * 2
            }

            // Fill paint (black text)
            val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = fontSize
                typeface = Typeface.create("casual", Typeface.BOLD)
            }

            // Use StaticLayout for word-wrapping within maxWidth
            // (handles Arabic/RTL shaping and joining automatically)
            // Line spacing multiplier 1.2 to match site's line-height
            @Suppress("DEPRECATION")
            val strokeLayout = StaticLayout(text, strokePaint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1.2f, 0f, false)

            @Suppress("DEPRECATION")
            val fillLayout = StaticLayout(text, fillPaint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1.2f, 0f, false)

            // Position like the site: horizontally centered on the box center
            // (no clamping — the site's overlay divs simply overflow the image
            // edge and get clipped), vertically TOP-ALIGNED to the box top so
            // labels sit exactly where the website puts them.
            val textHeight = strokeLayout.height.toFloat()
            val boxCenterX = x + w / 2f

            val imgHeight = mutableBitmap.height.toFloat()
            val layoutWidth = maxWidth.toFloat()
            val translateX = boxCenterX - layoutWidth / 2f
            val translateY = (y + TEXT_TOP_PADDING).coerceIn(0f, (imgHeight - textHeight).coerceAtLeast(0f))

            canvas.save()
            canvas.translate(translateX, translateY)
            // Draw stroke (outline) first, then fill on top
            strokeLayout.draw(canvas)
            fillLayout.draw(canvas)
            canvas.restore()
        }

        val output = java.io.ByteArrayOutputStream()
        // WEBP_LOSSY encodes noticeably faster on modern Android versions
        val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        mutableBitmap.compress(compressFormat, OVERLAY_JPEG_QUALITY, output)

        mutableBitmap.recycle()

        return output.toByteArray()
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!.trim(), headers.newBuilder().set("Referer", baseUrl).build())

    // ============================== Filters ==============================

    private class ExcludeGenreList(title: String, genres: List<Genre>) : Filter.Group<GenreCheckBox>(title, genres.map { GenreCheckBox(it.name, it.id) })

    override fun getFilterList(): FilterList {
        if (genresList.isEmpty()) {
            launchIO { loadGenresWithRetry() }
        }

        val filters = mutableListOf<Filter<*>>(
            AuthorFilter("Author"),
            ArtistFilter("Artist"),
            YearFilter("Release year"),
            StatusFilter(
                title = "Status",
                status = statusFilterOptions.map { Tag(it.key, it.value) },
            ),
            OrderByFilter(
                title = "Sort by",
                options = orderByFilterOptions.toList(),
                state = 1, // Default: Latest
            ),
            AdultContentFilter(
                title = "Adult content",
                options = adultContentFilterOptions.toList(),
            ),
        )

        if (genresList.isNotEmpty()) {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Genres (include)"),
                GenreConditionFilter(
                    title = "Genre match mode",
                    options = genreConditionFilterOptions.toList(),
                ),
                GenreList(
                    title = "Genres",
                    genres = genresList,
                ),
                Filter.Separator(),
                Filter.Header("Genres (exclude)"),
                ExcludeGenreList(
                    title = "Exclude genres",
                    genres = genresList,
                ),
            )
        } else if (fetchGenres) {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Genres couldn't be loaded yet — press 'Reset' to retry"),
            )
        }

        return FilterList(filters)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Chapter text mode (translated overlay vs Raw)
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_CHAPTER_TEXT_MODE
            title = "Chapter text overlay"
            summary = "Burns translated text onto the raw images. English uses the site's own MTL data; other languages machine-translate it."
            entries = arrayOf(
                "English (site MTL)",
                "Arabic (العربية)",
                "Spanish (Español)",
                "French (Français)",
                "German (Deutsch)",
                "Portuguese (Português)",
                "Italian (Italiano)",
                "Russian (Русский)",
                "Turkish (Türkçe)",
                "Indonesian (Bahasa Indonesia)",
                "Filipino (Tagalog)",
                "Vietnamese (Tiếng Việt)",
                "Raw images only",
            )
            entryValues = arrayOf(
                MODE_EN,
                "ar",
                "es",
                "fr",
                "de",
                "pt",
                "it",
                "ru",
                "tr",
                "id",
                "tl",
                "vi",
                MODE_RAW,
            )
            setDefaultValue(MODE_EN)
        }.let(screen::addPreference)

        // Overlay text scale
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_OVERLAY_TEXT_SCALE
            title = "Overlay text size"
            summary = "Scale of the burned-in overlay text relative to the website (100% matches the site)"
            entries = arrayOf("75% (smaller)", "100% (same as site)", "135% (bigger)", "160% (biggest)")
            entryValues = arrayOf("0.75", "1.0", "1.35", "1.6")
            setDefaultValue("1.0")
        }.let(screen::addPreference)

        // OCR text grouping
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_OCR_GROUPING
            title = "OCR text grouping"
            summary = "Paragraphs merge the site's per-line OCR boxes into flowing text blocks like the website; Per line keeps every detected line as its own overlay"
            entries = arrayOf("Paragraphs (like the site)", "Per line (raw boxes)")
            entryValues = arrayOf(GROUP_PARAGRAPH, GROUP_LINE)
            setDefaultValue(GROUP_PARAGRAPH)
        }.let(screen::addPreference)

        // Hide NSFW content from browse/latest only (does NOT affect search)
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_NSFW
            title = "Hide NSFW in browse"
            summary = "Hide adult content from Popular and Latest lists. Search is unaffected — you can still find NSFW content via search."
            setDefaultValue(false)
        }.let(screen::addPreference)

        // Show alt names
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show extra info
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status, year, chapters, views, rating"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show type in genre chips
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TYPE_IN_GENRE
            title = "Show type in genre chips"
            summary = "Include Manhwa/Manhua/Manga in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Score display position
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)
    }

    private fun chapterTextMode(): String = preferences.getString(PREF_CHAPTER_TEXT_MODE, MODE_EN) ?: MODE_EN

    private fun overlayTextScale(): Float = when (preferences.getString(PREF_OVERLAY_TEXT_SCALE, "1.0")) {
        "0.75" -> 0.75f
        "1.35" -> 1.35f
        "1.6" -> 1.6f
        else -> 1.0f
    }

    private fun ocrTextGrouping(): String = preferences.getString(PREF_OCR_GROUPING, GROUP_PARAGRAPH) ?: GROUP_PARAGRAPH
    private fun android.content.SharedPreferences.hideNsfw(): Boolean = getBoolean(PREF_HIDE_NSFW, false)
    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)
    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)
    private fun android.content.SharedPreferences.showTypeInGenre(): Boolean = getBoolean(PREF_SHOW_TYPE_IN_GENRE, true)
    private fun android.content.SharedPreferences.getScorePosition(): String = getString(PREF_SCORE_POSITION, "end") ?: "end"

    companion object {
        private const val MODE_EN = "en"
        private const val MODE_RAW = "raw"
        private const val GROUP_PARAGRAPH = "paragraph"
        private const val GROUP_LINE = "line"
        private const val MAX_TRANSLATION_CACHE = 3000
        private const val TEXT_TOP_PADDING = 2f
        private const val OVERLAY_JPEG_QUALITY = 85
        private const val GENRE_FETCH_ATTEMPTS = 3
        private const val PREF_CHAPTER_TEXT_MODE = "pref_chapter_text_mode"
        private const val PREF_OVERLAY_TEXT_SCALE = "pref_overlay_text_scale"
        private const val PREF_OCR_GROUPING = "pref_ocr_grouping"
        private const val PREF_HIDE_NSFW = "pref_hide_nsfw"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TYPE_IN_GENRE = "pref_show_type_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}
