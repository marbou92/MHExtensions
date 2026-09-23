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
import eu.kanade.tachiyomi.multisrc.madara.GenreRoute
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ManhuaRMTL :
    Madara(),
    ConfigurableSource {

    override val mangaSubString = "manga"

    /**
     * Short-timeout client for the auxiliary OCR/translation calls — a hung
     * gate used to stall the whole chapter open path for up to a minute.
     */
    private val auxClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Keiyoushi-manhuarm-style Cloudflare warm-up — see the interceptor. */
    private val warmupInterceptor = CloudflareWarmupInterceptor(baseUrl, headers)

    /** Ensures the one-shot session warm-up below only ever runs once. */
    private val primed = AtomicBoolean(false)

    /**
     * Proactive, one-shot Cloudflare warm-up: BEFORE the session's first
     * request, do a plain GET to the base URL and only then run the real
     * request. When the clearance cookie is missing or stale, the app's
     * CloudflareInterceptor (which KeiSource keeps right after the source
     * interceptors) solves the challenge on that homepage GET — a URL a
     * WebView can actually navigate. The upstream warmup interceptor only
     * reacts AFTER a request has already failed, so every session paid
     * "fail -> warm-up -> retry" first; priming shaves that whole cycle
     * off the first open and keeps the POST-free GET solve path.
     */
    private fun primingInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!primed.compareAndSet(false, true)) return chain.proceed(request)

        try {
            if (request.url.host != baseUrl.toHttpUrl().host) {
                chain.proceed(GET(baseUrl, headers)).close()
            }
        } catch (_: Exception) {
            // Priming is best-effort; the warmup interceptor + the app's
            // CloudflareInterceptor still cover the failure case.
        }

        return chain.proceed(request)
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        // One-shot session warm-up (priming) + keiyoushi-manhuarm-style
        // warm-up on the first failed request, so the app-level
        // CloudflareInterceptor mints cf_clearance on a URL a WebView can
        // actually navigate. No extension-level WebView solving.
        //
        // What made THIS extension slower than the rest (audited in our own
        // code, 2026-09): KeiSource stamps "Origin: <baseUrl>" onto EVERY
        // request — but real browsers never send Origin on document
        // navigations or <img> loads (only on CORS fetch/XHR/POST). That
        // inconsistent Origin is exactly the kind of header Cloudflare's bot
        // scoring flags, which kept challenging otherwise-clean traffic and
        // made every browse pay a WebView solve. OriginSanitizer strips it
        // from GETs (documents, images — the whole browsing path) and keeps
        // it on POSTs (madara AJAX, fetch-ocr.php) where the browser does
        // send it.
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        writeTimeout(15, TimeUnit.SECONDS)

        addInterceptor(::originSanitizerInterceptor)
        addInterceptor(::primingInterceptor)
        addInterceptor(warmupInterceptor)

        // Burn translated OCR text onto raw chapter images.
        addNetworkInterceptor(::ocrImageInterceptor)

        rateLimit(2, 1.seconds)
    }

    /**
     * Strips the "Origin" header from GET requests. Browsers only send
     * Origin on CORS fetch/XHR and POSTs — never on top-level document GETs
     * or <img> loads — so sending it there is a bot signal that keeps
     * Cloudflare challenge-prone (the reported "bypass is slow").
     */
    private fun originSanitizerInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "GET" && request.header("Origin") != null) {
            return chain.proceed(request.newBuilder().removeHeader("Origin").build())
        }
        return chain.proceed(request)
    }

    /** Browser-like image headers — keiyoushi manhuarm's exact set. */
    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .set("Referer", "$baseUrl/")
            .set("Connection", "keep-alive")
            .set("Accept-Language", "en-US,en-US;q=0.9,en;q=0.8")
            .set("Accept-Encoding", "gzip, deflate, br, zstd")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .set("Sec-Fetch-Storage-Access", "none")
            .set("Priority", "u=5, i")
            .set("TE", "trailers")
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    // Thread-safe storage for OCR text boxes, keyed by full image URL
    private val ocrData = ConcurrentHashMap<String, List<OcrTextBox>>()

    // Translation cache: "<lang>|<text>" -> translated text
    private val translationCache = ConcurrentHashMap<String, String>()

    // Background pool that pre-translates chapter text while images download
    private val translateExecutor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "ManhuaRMTL-Translate").apply { isDaemon = true }
    }

    // Raw HTML of the chapter page last fetched via fetchChapterDocument —
    // carries the OCR gate credentials for the overlay.
    @Volatile
    private var lastChapterHtml: String? = null

    private val preferences = getPreferences()

    // ============================== Popular / Latest ==============================
    //
    // The site does not answer the standard madara_load_more AJAX endpoint;
    // its archives are plain HTML pages ordered by ?sort=, rendered with a
    // custom MRM card layout (li.mrm-r-item).
    //
    // IMPORTANT: the site only honours the "sort" values rendered in its
    // own dropdown (#mrm-arch-sort). Unknown values (like the old
    // "views_all") silently fall back to the DEFAULT ordering, which is
    // the same list "latest" shows — that is why Popular and Latest
    // used to line up identically. Verified live 2026-09:
    //   Popular → sort=trending (Trending, the site's popularity sort)
    //   Latest  → sort=latest   (Recently updated — the dropdown default)

    private fun archiveUrl(sort: String, page: Int): String {
        val path = if (page > 1) "/manga/page/$page/" else "/manga/"
        val adult = if (preferences.hideNsfw()) "&adult=0" else ""
        return "$baseUrl$path?sort=$sort$adult"
    }

    override suspend fun getPopularManga(page: Int): MangasPage = browseArchive("trending", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = browseArchive("latest", page)

    private suspend fun browseArchive(sort: String, page: Int): MangasPage {
        val document = client.get(archiveUrl(sort, page)).asJsoup()
        val mangas = document.select("li.mrm-r-item").mapNotNull(::mrmCardToSManga)
        return MangasPage(mangas, hasNextPageSelector(document))
    }

    private fun hasNextPageSelector(document: Document): Boolean = document.selectFirst("a.next.page-numbers, a.mrm-pager__btn[rel=next]") != null

    /** Custom MRM card layout — used by archives AND the search page. */
    private fun mrmCardToSManga(element: Element): SManga? {
        val link = element.selectFirst("a.mrm-r-item__link") ?: return null
        val href = link.attr("abs:href").takeIf(String::isNotBlank) ?: return null
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: return null

        return SManga.create().apply {
            url = path
            title = link.attr("title").ifBlank { link.ownText() }
            thumbnail_url = element.selectFirst("span.mrm-r-item__art img")?.let { imageFromElement(it) }
            memo = mangaMemo(path, emptyList())
        }
    }

    // ============================== Search ==============================
    // Search ALWAYS shows everything — the NSFW setting does NOT filter search.
    // The user can still find NSFW content via search even when "Hide NSFW" is ON.

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Default to "show all" in search — the NSFW setting doesn't affect search
        val adultValue = filters.filterIsInstance<AdultContentFilter>().firstOrNull()?.toUriPart() ?: ""

        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("post_type", "wp-manga")
            addQueryParameter("s", query)
            addQueryParameter("adult", adultValue)
            if (page > 1) addQueryParameter("pg", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is TextParamFilter -> if (filter.state.isNotBlank()) addQueryParameter(filter.param, filter.state)
                    is StatusParamFilter -> filter.state.forEach { if (it.state) addQueryParameter("status[]", it.value) }
                    is SortParamFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("sort", filter.toUriPart())
                    is GenreConditionParamFilter -> addQueryParameter("op", filter.toUriPart())
                    is GenreParamFilter -> filter.state.filter { it.state }.forEach { addQueryParameter("genre[]", it.value) }
                    is ExcludeGenreParamFilter -> filter.state.filter { it.state }.forEach { addQueryParameter("exclude_genre[]", it.value) }
                    is TagParamFilter -> filter.state.filter { it.state }.forEach { addQueryParameter("tag[]", it.value) }
                    else -> {}
                }
            }
        }.build()

        val document = client.get(url.toString()).asJsoup()
        val mangas = document.select("li.mrm-r-item").mapNotNull(::mrmCardToSManga)
        return MangasPage(mangas, hasNextPageSelector(document))
    }

    // ============================== Genres ==============================
    //
    // The site sits behind an intermittent Cloudflare challenge; genre chips
    // live on the search page (custom MRM markup), with a fallback to the
    // homepage genre nav links. KeiSource's filter-fetch mechanism handles
    // caching and retries (up to 3 attempts, then "press Reset").

    override suspend fun fetchFilterData(): JsonElement {
        // Three fallback sources, most specific first. All are real HTML
        // pages, so the app's own Cloudflare WebView solve can clear them
        // directly when a challenge shows up.
        val requests = listOf(
            "$baseUrl/?post_type=wp-manga&s=",
            "$baseUrl/manga/",
            "$baseUrl/",
        )

        for (request in requests) {
            runCatching {
                val (genres, tags) = parseTaxonomy(client.get(request).asJsoup())
                if (genres.isNotEmpty() || tags.isNotEmpty()) {
                    return FilterTaxonomyDto(
                        genres = genres.map { GenreRoute(it.first, it.second, "/genre/${it.second}/") },
                        tags = tags.map { GenreRoute(it.first, it.second, "/tag/${it.second}/") },
                    ).toJsonElement()
                }
            }
        }

        // Every fetch failed (challenge the app couldn't clear, hard 429s…).
        // Ship the built-in genre list so the Genres filter still works —
        // "press Reset" replaces it with the live list once the site answers.
        return FilterTaxonomyDto(
            genres = fallbackGenres.map { GenreRoute(it.first, it.second, "/genre/${it.second}/") },
        ).toJsonElement()
    }

    /**
     * Genre and tag options in (name, slug) pairs, from several possible site
     * layouts. Genres and tags are kept SEPARATE: they used to be merged into
     * one giant filter group, which made the Genres dialog enormous (both
     * taxonomies rendered twice — include + exclude) and laggy to open.
     */
    private fun parseTaxonomy(document: Document): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val genres = linkedMapOf<String, Pair<String, String>>()
        val tags = linkedMapOf<String, Pair<String, String>>()

        fun put(bucket: MutableMap<String, Pair<String, String>>, name: String, slug: String) {
            if (name.isNotBlank() && slug.isNotBlank()) bucket.putIfAbsent(slug.lowercase(), name to slug)
        }

        // 1) Current MRM filter groups on the search page. Each group has a
        //    title, which lets us split GENRE chips from TAG chips (both use
        //    the same mrm-gchip markup — this is how tags used to end up
        //    inside the Genres filter).
        val groups = document.select("div.mrm-fgroup")
        for (group in groups) {
            val title = group.selectFirst(
                "[class*='fgroup__title'], [class*=\"fgroup__title\"], h3, h4, h5, legend",
            )?.text()?.lowercase().orEmpty()
            val isTagGroup = title.contains("tag")

            for (label in group.select("label.mrm-gchip--in, label.mrm-gchip")) {
                val name = label.selectFirst("span")?.text()?.takeIf { it.isNotBlank() }
                    ?: label.ownText().takeIf { it.isNotBlank() }
                    ?: continue
                val id = label.selectFirst("input[type=checkbox]")?.`val`()?.takeIf { it.isNotBlank() }
                    ?: name.slugify()
                put(if (isTagGroup) tags else genres, name, id)
            }
        }

        // 1b) Chips without group titles (or groups the selector missed):
        //     treat every chip as a GENRE (the historical behaviour) — never
        //     guess tags from unlabelled chips.
        if (groups.isEmpty()) {
            document.select("div.mrm-fgroup__chips label.mrm-gchip--in, label.mrm-gchip")
                .forEach { label ->
                    val name = label.selectFirst("span")?.text()?.takeIf { it.isNotBlank() }
                        ?: label.ownText().takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val id = label.selectFirst("input[type=checkbox]")?.`val`()?.takeIf { it.isNotBlank() }
                        ?: name.slugify()
                    put(genres, name, id)
                }
        }

        if (genres.isNotEmpty() || tags.isNotEmpty()) {
            return genres.values.toList() to tags.values.toList()
        }

        // 2) Standard Madara checkbox group (in case the theme reverts)
        document.selectFirst("div.checkbox-group")
            ?.select("div.checkbox")
            ?.forEach { li ->
                val name = li.selectFirst("label")?.text()?.takeIf { it.isNotBlank() } ?: return@forEach
                val id = li.selectFirst("input[type=checkbox]")?.`val`()?.takeIf { it.isNotBlank() }
                    ?: name.slugify()
                put(genres, name, id)
            }
        if (genres.isNotEmpty()) return genres.values.toList() to tags.values.toList()

        // 3) Taxonomy nav links (homepage menus, details pages) — the href
        //    slug is exactly what the search endpoint expects. Genre links
        //    carry /genre/<slug>/, tag links /tag/<slug>/; rel=tag is generic
        //    WordPress taxonomy markup and appears on BOTH, so the href wins.
        document.select("a[href*='/genre/'], a[href*=\"/genre/\"], div.mrm-genres__list a")
            .forEach { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("/genre/").trimEnd('/').substringBefore('?').substringBefore('#')
                if (slug.isBlank()) return@forEach
                val name = a.text().takeIf { it.isNotBlank() }
                    ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
                put(genres, name, slug)
            }
        document.select("a[href*='/tag/'], a[href*=\"/tag/\"]")
            .forEach { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("/tag/").trimEnd('/').substringBefore('?').substringBefore('#')
                if (slug.isBlank()) return@forEach
                val name = a.text().takeIf { it.isNotBlank() }
                    ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
                put(tags, name, slug)
            }

        return genres.values.toList() to tags.values.toList()
    }

    private fun String.slugify(): String = trim()
        .lowercase()
        .replace("[^a-z0-9]+".toRegex(), "-")
        .trim('-')

    /**
     * Last-resort genre list for when the site blocks every filter-data
     * request (a challenge the app cannot clear, repeated 429s, …). Slugs are
     * the site's /genre/<slug>/ paths collected from its own navigation; the
     * built-in list guarantees the Genres filter is never empty — the site's
     * live list replaces it as soon as a fetch succeeds.
     */
    private val fallbackGenres: List<Pair<String, String>> = listOf(
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Cooking" to "cooking",
        "Drama" to "drama",
        "Fantasy" to "fantasy",
        "Historical" to "historical",
        "Horror" to "horror",
        "Isekai" to "isekai",
        "Josei" to "josei",
        "Martial Arts" to "martial-arts",
        "Mature" to "mature",
        "Mecha" to "mecha",
        "Mystery" to "mystery",
        "Psychological" to "psychological",
        "Romance" to "romance",
        "School Life" to "school-life",
        "Sci-Fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shounen" to "shounen",
        "Slice of Life" to "slice-of-life",
        "Sports" to "sports",
        "Supernatural" to "supernatural",
        "Tragedy" to "tragedy",
        "Webtoons" to "webtoons",
    )

    // ============================== Manga Details ==============================

    // Custom MRM "hero" layout selectors
    override val mangaDetailsSelectorTitle = "h1.mrm-hero__title"
    override val mangaDetailsSelectorThumbnail = "div.mrm-hero__cover img"

    // Author/Artist extraction does NOT use these selectors directly; see staffValue().
    // Kept for compatibility with theme internals that may reference them.
    override val mangaDetailsSelectorAuthor = "div.author-content > a, div.manga-authors > a"
    override val mangaDetailsSelectorArtist = "div.artist-content > a, div.manga-artists > a"
    override val mangaDetailsSelectorStatus = ".post-content_item:contains(Status) .summary-content"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5:contains(Summary) + div, div.mrm-panel div.summary__content"
    override val mangaDetailsSelectorGenre = "div.mrm-genres__list a[rel=tag]"
    override val mangaDetailsSelectorTag = ""
    override val seriesTypeSelector = ".post-content_item:contains(Type) .summary-content"

    // Alt names live in the MRM hero block, not the standard post-content row
    override val altNameSelector = "p.mrm-hero__alt"

    /**
     * Extracts staff names (Author/Artist) robustly across MRM's mixed markup:
     *
     * 1. Standard madara meta rows — a `.post-content_item` row is only used
     *    when its own heading text equals the field ("Author"/"Artist"), so a
     *    combined "Author & Artist" row is never mistaken for either.
     * 2. MRM custom facts list — `li.mrm-facts__item` labelled by its <strong>.
     * 3. Generic madara author/artist content blocks as a final fallback.
     *
     * Values are read as block text (not per-link texts), so names rendered as
     * separate links ("John" + "Doe") join as "John Doe" instead of "John, Doe",
     * and repeated selectors can no longer duplicate a name.
     */
    private fun staffValue(document: Document, heading: String): String? {
        fun String?.clean(): String? = this
            ?.replace(Regex("^\\s*$heading\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUpdating(it) }

        // 1) Madara meta rows — the row's own heading must match the field exactly
        val row = document.select("div.post-content_item, li.post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading, h5")?.ownText().equals(heading, ignoreCase = true)
        }
        row?.selectFirst(".author-content, .artist-content, .summary-content")?.text()?.clean()?.let { return it }

        // 2) MRM facts items — <li class="mrm-facts__item"><strong>Label</strong>…</li>
        val fact = document.select("li.mrm-facts__item").firstOrNull { item ->
            item.selectFirst("strong")?.ownText().equals(heading, ignoreCase = true)
        }
        if (fact != null) {
            fact.select("a").eachText().filter(String::isNotBlank).distinct().joinToString().clean()?.let { return it }
            fact.text().clean()?.let { return it }
        }

        // 3) Generic madara author/artist blocks
        val genericSelector = if (heading == "Author") "div.author-content, div.manga-authors" else "div.artist-content, div.manga-artists"
        document.selectFirst(genericSelector)?.text()?.clean()?.let { return it }

        return null
    }

    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga {
        val manga = SManga.create()

        val path = runCatching { document.location().toHttpUrl().encodedPath }.getOrDefault("/$mangaSubString/")
        val genres = document.select(mangaDetailsSelectorGenre).mapNotNull { element ->
            val href = element.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val genrePath = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: return@mapNotNull null
            val slug = genrePath.trimEnd('/').substringAfterLast('/').takeIf(String::isNotEmpty) ?: return@mapNotNull null
            GenreRoute(element.text(), slug, genrePath)
        }

        manga.url = preserveUrl?.takeIf { !it.all(Char::isDigit) } ?: id
        manga.title = document.selectFirst(mangaDetailsSelectorTitle)?.ownText() ?: ""
        staffValue(document, "Author")?.let { manga.author = it }
        staffValue(document, "Artist")?.let { manga.artist = it }

        // Raw synopsis
        val synopsis = document.selectFirst(mangaDetailsSelectorDescription)?.let {
            if (it.select("p").text().isNotEmpty()) {
                it.select("p").joinToString(separator = "\n\n") { p -> p.text().replace("<br>", "\n") }
            } else {
                it.text()
            }
        }

        document.selectFirst(mangaDetailsSelectorThumbnail)?.let { manga.thumbnail_url = imageFromElement(it) }

        document.selectFirst(mangaDetailsSelectorStatus)?.let {
            val statusText = it.text().filter { ch -> ch.isLetterOrDigit() || ch.isWhitespace() }.trim()
            manga.status = statusText.toStatus()
        }

        // Extract type early — used for both genre chips and info line
        val type = document.selectFirst(seriesTypeSelector)?.ownText()?.takeIf { it.isNotBlank() && !isUpdating(it) }

        // Genres (optionally include type: Manhwa/Manhua/Manga)
        val genreList = genres.map(GenreRoute::name).toMutableList()
        if (preferences.showTypeInGenre() && type != null) {
            genreList.add(type)
        }
        manga.genre = genreList.distinctBy(String::lowercase).joinToString().ifBlank { null }

        // ===== Build comix-style description =====
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()
        val scorePosition = preferences.getScorePosition()

        // Alt names
        val altNames = document.selectFirst(altNameSelector)?.ownText()?.takeIf { it.isNotBlank() && !isUpdating(it) }

        // Rating / votes from MRM facts — site uses a 0-5 scale (NOT 0-10 like comix)
        val ratingText = document.selectFirst("li.mrm-facts__item--rating strong")?.text()
        val ratingScore = ratingText?.toFloatOrNull()
        val votesText = document.selectFirst("li.mrm-facts__item--rating .mrm-facts__sub")?.text()
        val votesCount = Regex("""(\d+)""").find(votesText ?: "")?.value?.toIntOrNull() ?: 0
        val hasScore = ratingScore != null && votesCount > 0

        val stars = if (hasScore) {
            val score = ratingScore
            // Site uses 0-5 scale: round to nearest int (5.0 → 5 stars, 4.4 → 4, 4.8 → 5)
            val fullStars = score.roundToInt().coerceIn(0, 5)
            "★".repeat(fullStars) + "☆".repeat(5 - fullStars) + " $score"
        } else {
            null
        }

        // Type / chapters / views / release year
        val chaptersText = document.selectFirst(".post-content_item:contains(Chapters) .summary-content")?.text()
        val chaptersNum = chaptersText?.filter { it.isDigit() }?.toIntOrNull()
        val releaseYear = document.selectFirst(".post-content_item:contains(Release) .summary-content a")?.text()
            ?: document.selectFirst(".post-content_item:contains(Release) .summary-content")?.ownText()
        val viewsText = document.selectFirst("li.mrm-facts__item:has(i.ion-md-eye)")?.text()
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
        manga.memo = mangaMemo(path, genres, legacyId = id.takeIf { preserveUrl?.all(Char::isDigit) == false })

        return manga
    }

    private fun formatStatus(status: Int): String = when (status) {
        SManga.ONGOING -> "Ongoing"
        SManga.COMPLETED -> "Completed"
        SManga.CANCELLED -> "Cancelled"
        SManga.ON_HIATUS -> "On hiatus"
        else -> "Unknown"
    }

    // ============================== Pages + OCR ==============================
    // The site serves RAW images. Translated text is a JS overlay fetched from
    // fetch-ocr.php. We parse the _0xvault credentials from the reading page,
    // fetch the text data, translate it to the user's language, and burn it
    // onto the images via a network interceptor.

    override suspend fun fetchChapterDocument(chapterUrl: String): Document {
        val response = client.get(chapterUrl)
        val html = response.use { it.body.string() }
        lastChapterHtml = html
        return html.asJsoup(chapterUrl)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = super.getPageList(chapter)

        val html = lastChapterHtml
        lastChapterHtml = null

        val mode = chapterTextMode()
        if (mode != MODE_RAW && !html.isNullOrBlank()) {
            // Clear previous chapter's OCR data
            ocrData.clear()

            try {
                val credentials = parseOcrCredentials(html)
                if (credentials != null) {
                    val ocrPages = fetchOcrData(credentials, getChapterUrl(chapter))
                    if (ocrPages != null && ocrPages.isNotEmpty()) {
                        // Build filename → text boxes map (try multiple key formats for robust matching)
                        val ocrByFilename = mutableMapOf<String, List<OcrTextBox>>()
                        for (ocrPage in ocrPages) {
                            val filename = ocrPage.image ?: continue
                            val rawBoxes = ocrPage.normalisedTexts()
                            if (rawBoxes.isEmpty()) continue
                            // Rendering modes: the SITE renders each OCR entry as
                            // its own overlay box (it cuts text into many small
                            // blocks); paragraph merging is an optional mode.
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
                        // overlay is ready by the time images arrive (non-English modes).
                        // The futures are shared: the image interceptor awaits the SAME
                        // request instead of issuing its own serial network calls.
                        if (mode != MODE_EN) {
                            ocrData.values
                                .flatMap { boxes -> boxes.map { it.text } }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .forEach { text -> prefetchTranslation(text, mode) }
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
            val body = response.body.string()
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

    // In-flight translation requests, deduplicated so the image interceptor
    // can piggyback on the background prefetch instead of doing its own
    // serial network calls (which used to hold every page response hostage
    // for one RTT PER TEXT BOX — the real "chapter loading is slow").
    private val translationsInFlight = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String>>()

    /**
     * Submits a background translation and registers the shared future so
     * [getTranslation] can await it (bounded) instead of re-requesting.
     */
    private fun prefetchTranslation(text: String, target: String) {
        val key = "$target|$text"
        if (translationCache.containsKey(key)) return
        if (translationsInFlight.containsKey(key)) return

        val future = java.util.concurrent.CompletableFuture.supplyAsync({
            try {
                translateText(text, target)
            } catch (_: Exception) {
                text
            }
        }, translateExecutor)
        translationsInFlight.putIfAbsent(key, future)
    }

    /**
     * Cache-only-with-shared-wait lookup used by the IMAGE interceptor.
     * NEVER performs its own network call: the image response must not be
     * held hostage for translation round-trips. Order of preference:
     * 1. translated value already cached,
     * 2. a translation that is already in flight (awaited up to 3s),
     * 3. the original English text (drawn as-is).
     */
    private fun getTranslation(text: String, target: String): String {
        val key = "$target|$text"
        translationCache[key]?.let { return it }

        val future = translationsInFlight[key]
        if (future != null) {
            try {
                return future.get(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Timeout/cancel/interrupt — fall through to English text
            }
        }

        return text
    }

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
                val body = resp.body.string()
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
        translationsInFlight.remove(key)
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
        val imageBytes = response.body.bytes()
        if (imageBytes.isEmpty()) return response

        // Overlay text on the image
        val modifiedBytes = overlayText(imageBytes, textBoxes, mode) ?: return response

        // Build new response with modified image
        val contentType = response.body.contentType()
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
        val imgWidth = mutableBitmap.width.toFloat()

        for (textBox in textBoxes) {
            val x = textBox.box.getOrElse(0) { 0f }
            val y = textBox.box.getOrElse(1) { 0f }
            val w = textBox.box.getOrElse(2) { 0f }
            val h = textBox.box.getOrElse(3) { 0f }

            if (w <= 0 || h <= 0) continue

            val text = when (targetLang) {
                MODE_EN -> textBox.text
                else -> getTranslation(textBox.text, targetLang)
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

            // maxWidth = w * 1.4 (text may extend beyond its box), but never
            // wider than the image itself — at 135% scale, edge boxes used to
            // lay their text out past the bitmap border where it got clipped
            // ("text goes out of the screen").
            val maxWidth = min(w * 1.4f, imgWidth).toInt().coerceAtLeast(8)

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

            // Position like the site: horizontally centered on the box center,
            // then CLAMPED so the whole layout stays inside the image — text
            // hanging off the left/right edge was cut off entirely. Vertical
            // position stays top-aligned to the box top (site behaviour),
            // clamped so tall layouts don't spill past the bottom.
            val textHeight = strokeLayout.height.toFloat()
            val boxCenterX = x + w / 2f

            val imgHeight = mutableBitmap.height.toFloat()
            val layoutWidth = maxWidth.toFloat()
            val translateX = (boxCenterX - layoutWidth / 2f).coerceIn(0f, (imgWidth - layoutWidth).coerceAtLeast(0f))
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

    // ============================== Filters ==============================

    private class TextParamFilter(title: String, val param: String) : Filter.Text(title)

    private class StatusTag(name: String, val value: String) : Filter.CheckBox(name)

    private class StatusParamFilter(title: String, values: List<Pair<String, String>>) : Filter.Group<StatusTag>(title, values.map { StatusTag(it.first, it.second) })

    private class SortParamFilter(title: String, private val sortOptions: List<Pair<String, String>>) : Filter.Select<String>(title, sortOptions.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = sortOptions[state].second
    }

    private class AdultContentFilter(title: String, private val adultOptions: List<Pair<String, String>>) : Filter.Select<String>(title, adultOptions.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = adultOptions[state].second
    }

    private class GenreConditionParamFilter(title: String, private val conditionOptions: List<Pair<String, String>>) : Filter.Select<String>(title, conditionOptions.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = conditionOptions[state].second
    }

    private class GenreTag(name: String, val value: String) : Filter.CheckBox(name)

    private class GenreParamFilter(title: String, genres: List<GenreRoute>) : Filter.Group<GenreTag>(title, genres.map { GenreTag(it.name, it.slug) })

    private class ExcludeGenreParamFilter(title: String, genres: List<GenreRoute>) : Filter.Group<GenreTag>(title, genres.map { GenreTag(it.name, it.slug) })

    private class TagParamFilter(title: String, tags: List<GenreRoute>) : Filter.Group<GenreTag>(title, tags.map { GenreTag(it.name, it.slug) })

    override fun getFilterList(data: JsonElement?): FilterList {
        val taxonomy = runCatching { data?.parseAs<FilterTaxonomyDto>() }.getOrNull()
        val genres = taxonomy?.genres.orEmpty()
        val tags = taxonomy?.tags.orEmpty()

        return FilterList(
            buildList {
                add(TextParamFilter(intl["author_filter_title"], "author"))
                add(TextParamFilter(intl["artist_filter_title"], "artist"))
                add(TextParamFilter(intl["year_filter_title"], "release"))
                add(StatusParamFilter(intl["status_filter_title"], statusFilterOptions))
                add(SortParamFilter("Sort by", orderByFilterOptions))
                add(AdultContentFilter(intl["adult_content_filter_title"], adultFilterOptions))
                if (genres.isNotEmpty()) {
                    add(Filter.Separator())
                    add(Filter.Header(intl["genre_filter_header"]))
                    add(GenreConditionParamFilter(intl["genre_condition_filter_title"], genreConditionFilterOptions))
                    add(GenreParamFilter(intl["genre_filter_title"], genres))
                    add(Filter.Header("Genres (exclude)"))
                    add(ExcludeGenreParamFilter("Exclude genres", genres))
                } else {
                    add(Filter.Separator())
                    add(Filter.Header("Genres are loading — press 'Reset' to retry"))
                }
                if (tags.isNotEmpty()) {
                    add(Filter.Separator())
                    add(Filter.Header("Tags (separate from genres — e.g. Full color)"))
                    add(TagParamFilter("Tags", tags))
                }
            },
        )
    }

    // Site-specific sort values — verified against the site's own dropdown.
    override val orderByFilterOptions = listOf(
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

    // Site excludes adult content by default; override to show everything unless the user opts out
    override val adultFilterOptions = listOf(
        "Show all (incl. adult)" to "",
        "Hide adult" to "0",
        "Adult only" to "1",
    )

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
            // NOTE: ListPreference summaries are printf format strings — write
            // literal percent signs as %% or the settings screen crashes.
            summary = "Scale of the burned-in overlay text relative to the website (135%% is the default)"
            entries = arrayOf("75% (smaller)", "100% (same as site)", "135% (default)", "160% (biggest)")
            entryValues = arrayOf("0.75", "1.0", "1.35", "1.6")
            setDefaultValue("1.35")
        }.let(screen::addPreference)

        // OCR text grouping — the site keeps every OCR block separate (it cuts
        // text into many small boxes); merging is offered as an extra option.
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_OCR_GROUPING
            title = "OCR text grouping"
            summary = "Same as the site renders every OCR box on its own; Merged paragraphs stitches consecutive lines into flowing blocks instead"
            entries = arrayOf("Same as the site (one box per OCR entry)", "Merged paragraphs")
            entryValues = arrayOf(GROUP_LINE, GROUP_PARAGRAPH)
            setDefaultValue(GROUP_LINE)
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

    private val grouping: String
        get() = preferences.getString(PREF_OCR_GROUPING, GROUP_LINE) ?: GROUP_LINE

    private fun chapterTextMode(): String = preferences.getString(PREF_CHAPTER_TEXT_MODE, MODE_EN) ?: MODE_EN

    private fun overlayTextScale(): Float = when (preferences.getString(PREF_OVERLAY_TEXT_SCALE, "1.35")) {
        "0.75" -> 0.75f
        "1.0" -> 1.0f
        "1.6" -> 1.6f
        else -> 1.35f
    }

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
