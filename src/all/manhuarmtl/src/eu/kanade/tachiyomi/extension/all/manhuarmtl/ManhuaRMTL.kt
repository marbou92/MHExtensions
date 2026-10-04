package eu.kanade.tachiyomi.extension.all.manhuarmtl

import android.content.Context
import android.content.SharedPreferences
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
import keiyoushi.cloudflare.CloudflareSolverDiagnostics
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.get
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

@Source
abstract class ManhuaRMTL :
    Madara(),
    ConfigurableSource {

    override val mangaSubString = "manga"

    /**
     * Short-timeout client for the auxiliary OCR/translation calls — a hung
     * gate used to stall the whole chapter open path for up to a minute.
     *
     * The Cloudflare interceptor is deliberately STRIPPED here: the OCR gate
     * answers heavy use with 403 block pages whose HTML is full of Cloudflare
     * markers, and a challenge interceptor that mistakes a block page for a
     * challenge WIPES the still-valid site cf_clearance and forces a WebView
     * re-solve of a session that was never actually expired — the reported
     * "I have to open the WebView every ~30 minutes". A challenged gate here
     * simply yields "no OCR for this chapter" — a patient in-line retry plus
     * the background self-heal (scheduleOcrHeal) take it from there; the main
     * client keeps its full solver.
     */
    private val auxClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .apply {
                // Name-prefix match (v33): some Mihon forks rename their
                // app-level interceptor (CloudflareInterceptorV2 etc.) — the
                // exact-name filter silently missed those and left the
                // cookie-wiping app interceptor in the chain.
                interceptors().removeAll { it.javaClass.simpleName.contains("Cloudflare", ignoreCase = true) }
            }
            .build()
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        // The Cloudflare method (v23): a challenge SOLVER in a window-attached
        // WebView (shared keiyoushi.cloudflare implementation — see the Kagane
        // notes for the full rationale). Header hardening can never pass a
        // managed challenge; the WebView must be ATTACHED to a window (an
        // unattached one reports document.visibilityState="hidden" and the
        // Turnstile widget silently never renders). Challenges now auto-solve
        // like a real browser, and the strict challenge detection stops legit
        // API 403s from wiping the (measured: one-year!) cf_clearance — the
        // old "manual WebView visits every hour" loop was false-positive
        // detection, not expiry.
        //
        // v32: the app-level CloudflareInterceptor is REMOVED from this
        // client (newBuilder() copies it in from the base network client).
        // When the keiyoushi solver hands back a challenge after its rounds,
        // that app-level interceptor used to wipe cookies and run yet another
        // WebView solve — no single-flight, no tap mode, no clean-load probe
        // — so heavy sessions still collapsed into the "WebView every 30
        // minutes" loop. The keiyoushi solver below is the SINGLE Cloudflare
        // authority for this source: strict detection, one solve at a time,
        // interactive-Turnstile tap mode, and (with the core round-1 no-wipe
        // change) it never destroys a valid clearance on a transient
        // challenge — a failed request just retries on the next load.
        //
        // v33: the app interceptor removal now matches ANY Cloudflare-named
        // interceptor (forks rename it), and the core solver got the real
        // fixes for the persistent "502 → open in WebView at startup and
        // every ~1 h" report: the pre-solve clearance wipe actually works
        // now (it was a silent no-op against CF's Domain cookies), the
        // solving WebView takes REAL view focus (an unfocused challenge
        // iframe stalls like a background tab — the manual WebView always
        // passed because it was focused), solves load the site root (the
        // exact URL the manual WebView opens), and the self-heal verify pass
        // re-mints at 45 min — UNDER the user-measured ~1 h clearance TTL,
        // so expiry is refreshed before it can interrupt a reading session.
        interceptors().removeAll { it.javaClass.simpleName.contains("Cloudflare", ignoreCase = true) }
        //
        // Kept from the 2026-09 audit: OriginSanitizer strips the "Origin:
        // <baseUrl>" header KeiSource stamps onto every request — real
        // browsers never send Origin on document navigations or <img>
        // loads, and that inconsistent Origin is exactly the kind of header
        // Cloudflare's bot scoring flags.
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        writeTimeout(15, TimeUnit.SECONDS)

        addInterceptor(CloudflareSolverInterceptor(setOf(baseUrl.toHttpUrl().host)))

        addInterceptor(::originSanitizerInterceptor)

        // Burn translated OCR text onto raw chapter images.
        addNetworkInterceptor(::ocrImageInterceptor)
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

    /**
     * Document-shaped fingerprint for every HTML page request. The CF solver
     * only fills in sec-fetch-* headers that are MISSING (and its defaults —
     * dest=empty / mode=cors — describe an XHR, not a navigation), so a page
     * fetch without these read as a script making CORS calls to document
     * URLs: a strong bot signal that kept the site challenge-prone.
     */
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/png,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Upgrade-Insecure-Requests", "1")

    // The only non-document request the base class issues is the per-chapter
    // view-count POST — document headers on an XHR POST read as a bot signal,
    // and it buys the reader nothing. Off.
    override val sendViewCount get() = false

    /** Browser-like image headers for the CDN (chapter pages + covers). */
    override fun imageRequest(page: Page): Request {
        val target = runCatching { page.imageUrl!!.toHttpUrl() }.getOrNull()
        val imageHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .set("Referer", "$baseUrl/")
            .set("Connection", "keep-alive")
            .set("Accept-Language", "en-US,en-US;q=0.9,en;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", target?.let { secFetchSite(it.host) } ?: "cross-site")
            .set("Sec-Fetch-Storage-Access", "none")
            .set("Priority", "u=5, i")
            .removeAll("Upgrade-Insecure-Requests")
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    /**
     * A real <img> tag is same-origin when the image lives on the site host
     * and same-site on its CDN subdomain — the blanket "cross-site" this used
     * to send is a bot score penalty on the busiest request class of all.
     */
    private fun secFetchSite(host: String): String {
        val base = baseUrl.toHttpUrl().host.removePrefix("www.")
        val target = host.removePrefix("www.")
        return when {
            target == base -> "same-origin"
            target.endsWith(".$base") -> "same-site"
            else -> "cross-site"
        }
    }

    /**
     * OCR text boxes keyed by the FULL page image URL, kept for several
     * chapters at once. The old single map wiped on every chapter open was
     * the "OCR stops showing after a few chapters" bug: reader preloads and
     * the download queue open the NEXT chapter while the CURRENT one's
     * images are still downloading, and the clear() erased the boxes those
     * in-flight images were waiting for (they rendered raw — empty bubbles).
     */
    private val ocrData = ConcurrentHashMap<String, OcrEntry>()

    private class OcrEntry(val boxes: List<OcrTextBox>, val savedAt: Long)

    /**
     * Chapter page HTML keyed by the chapter URL it belongs to. A single
     * volatile slot went stale the moment two chapters were processed in
     * parallel — the second fetch overwrote the first one's credentials and
     * the first chapter matched its filenames against the WRONG chapter's
     * OCR payload (again: no overlay).
     */
    private val pendingChapterHtml = ConcurrentHashMap<String, String>()

    /**
     * Persistent mirror of the OCR boxes, keyed by full page image URL.
     * Mihon restores re-opened chapters from its database WITHOUT calling
     * getPageList again; when a burned image was evicted from the disk
     * cache, the re-download used to hit an EMPTY in-memory map (fresh
     * process after "leave the app and come back") and rendered raw. Boxes
     * are tiny, so the last few chapters live in a private prefs file.
     */
    private val ocrStore by lazy {
        applicationContext.getSharedPreferences("manhuarmtl_ocr", Context.MODE_PRIVATE)
    }

    // Translation cache: "<lang>|<text>" -> translated text
    private val translationCache = ConcurrentHashMap<String, String>()

    // Background pool that pre-translates chapter text while images download
    private val translateExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "ManhuaRMTL-Translate").apply { isDaemon = true }
    }

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

    // ============================== Related manga ==============================
    //
    // The site does not answer the madara_load_more AJAX endpoint (the
    // multisrc's default related route) and its MRM skin has no
    // server-rendered related block — so the default recommendations always
    // came back EMPTY. Route related manga through the site's own search
    // page instead: the same ?post_type=wp-manga&genre[]=<slug>&sort=trending
    // parameters the search flow uses (verified live 2026-09), parsed with
    // the same li.mrm-r-item card parser.

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val genreSlugs = relatedGenres(manga).map { it.slug }
        if (genreSlugs.isEmpty()) return emptyList()

        return genreSlugs
            .flatMap { genre ->
                val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
                    addQueryParameter("post_type", "wp-manga")
                    addQueryParameter("s", "")
                    addQueryParameter("genre[]", genre)
                    addQueryParameter("sort", "trending")
                }.build()

                runCatching {
                    client.get(url.toString()).asJsoup()
                        .select("li.mrm-r-item")
                        .mapNotNull(::mrmCardToSManga)
                }.getOrDefault(emptyList())
            }
            .filter { it.url != manga.url }
            .distinctBy { it.url }
            .take(24)
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
        // Keyed by chapter URL — a single slot was overwritten by whichever
        // chapter fetched last whenever two loaded in parallel (reader
        // preload / download queue), and the loser matched its pages against
        // the WRONG chapter's OCR credentials → overlay silently missing.
        if (pendingChapterHtml.size > 12) pendingChapterHtml.clear()
        pendingChapterHtml[chapterUrl] = html
        return html.asJsoup(chapterUrl)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = super.getPageList(chapter)

        // Drain THIS chapter's HTML only — never borrow another chapter's
        // (that borrowing was the wrong-credentials bug).
        val chapterUrl = runCatching { getChapterUrl(chapter) }.getOrNull()
        val html = chapterUrl?.let { pendingChapterHtml.remove(it) }

        val mode = chapterTextMode()
        // v52: captured BEFORE the OCR acquisition block. The page-cache
        // fingerprint must describe the coverage the interceptor will see
        // during THIS open's downloads — not the coverage after the in-line
        // harvest lands. The v47 post-state token baked the freshly-landed
        // coverage into the first open's identity, so a chapter whose
        // harvest landed in-line locked its raw-served pages under an
        // identity that could never change again — "stored healthy, screen
        // raw", and re-opens never refetched because the token matched.
        val rawEpochBefore = preferences.getInt(PREF_RAW_EPOCH, 0)
        // Captured BEFORE the fingerprint fragments are appended — bare
        // URLs are exactly what storeOcrBoxes / the interceptor match on.
        val imageUrls = pages.mapNotNull { it.imageUrl }
        // v59: this chapter's page-list directories become the filename-index
        // serve scope (see activeChapterDirs / lookupFilenameKey).
        setActiveChapterDirs(imageUrls)
        val coveredBefore = coveredPageCount(imageUrls)

        if (mode != MODE_RAW && !html.isNullOrBlank() && chapterUrl != null) {
            try {
                when {
                    // v49 split by coverage: a chapter whose pages all have
                    // boxes renders instantly — no visit, no direct path.
                    imageUrls.isNotEmpty() && coveredBefore >= imageUrls.size -> {
                        OcrDiagnostics.record(
                            "ocr: open — stored coverage complete ($coveredBefore/${imageUrls.size} stored); re-opens render instantly",
                        )
                    }

                    // v49: a PARTIAL re-open renders its stored pages at once
                    // and only schedules background top-ups — the user's
                    // re-open is never blocked behind a harvest again.
                    coveredBefore > 0 -> {
                        OcrDiagnostics.record(
                            "ocr: re-open — rendering stored $coveredBefore/${imageUrls.size}, background top-up chases the rest",
                        )
                        scheduleOcrTopUp(chapterUrl, imageUrls, mode)
                    }

                    // First open (nothing stored): earn the data in-line.
                    else -> {
                        // v40 fast path: shape-scanned credentials → direct gate POST
                        // (unchanged behaviour while the site honours its own vault).
                        val credentials = parseOcrCredentials(html)
                        if (credentials == null && !directGateWarned) {
                            directGateWarned = true
                            OcrDiagnostics.record("direct: no credential-shaped array in document (static vault gone — harvest handles OCR)")
                        }
                        var ocrPages: List<OcrPage>? =
                            if (credentials != null) fetchOcrData(credentials, chapterUrl) else null

                        // v40 harvest fallback (MHRepo issue #4): the site owner keeps
                        // reshaping the vault/gate handshake. When the direct path
                        // finds nothing, load the chapter in a COMPLETELY UNMODIFIED
                        // WebView and read the site's own result instead.
                        //
                        // v48: the in-line harvest goes through harvestBlocking —
                        // if a background top-up holds the slot, the foreground
                        // visit PREEMPTS it (v49) instead of waiting 50s or
                        // skipping to a raw burn.
                        if (ocrPages == null) {
                            val harvest = harvestBlocking(chapterUrl)
                            if (harvest != null) {
                                ocrPages = resolveHarvestResult(harvest, chapterUrl)
                                    ?: harvest.pages.takeIf { it.isNotEmpty() }
                            }
                        }

                        when {
                            // Nothing captured anywhere: gate refused / challenge.
                            // Instead of letting the chapter render raw forever,
                            // schedule the background heal that re-fetches everything
                            // once the block window passes.
                            ocrPages == null -> scheduleOcrHeal(chapterUrl, pages, mode)

                            // Gate answered but the chapter simply has no OCR
                            // text (art-only pages) — nothing to store, nothing
                            // to heal.
                            ocrPages.isEmpty() -> {}

                            else -> {
                                val fresh = storeOcrBoxes(imageUrls, ocrPages)
                                if (fresh.isNotEmpty()) {
                                    noteOcrLanded()

                                    // Pre-translate THIS chapter's text boxes in
                                    // the background so the overlay is ready by the
                                    // time images arrive (non-English modes). Used
                                    // to walk EVERY stored chapter: strings whose
                                    // translation once failed are never cached, so
                                    // they re-queued on every single chapter open
                                    // and the queue grew into a 429 storm over a
                                    // long session.
                                    if (mode != MODE_EN) prefetchTranslations(fresh.values, mode)
                                }

                                // v48 background top-up: partial coverage gets
                                // two warm re-visits that chase the missing
                                // pages (v49: skipped once a round proved the
                                // site has nothing more).
                                val coveredNow = coveredPageCount(imageUrls)
                                if (imageUrls.isNotEmpty() && coveredNow < imageUrls.size) {
                                    OcrDiagnostics.record(
                                        "top-up: $coveredNow/${imageUrls.size} pages stored (${imageUrls.size - coveredNow} missing) — background re-visit scheduled",
                                    )
                                    scheduleOcrTopUp(chapterUrl, imageUrls, mode)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // v35: an unexpected throw used to skip BOTH the overlay and
                // every recovery path silently — schedule the heal so the
                // background ladder still gets its chance.
                scheduleOcrHeal(chapterUrl, pages, mode)
            }

            // Drop boxes of chapters nobody has touched in a while — bounds the
            // map without ever erasing in-flight chapters.
            pruneOcrMemory()
        }

        // OCR render fingerprint (v26): encode the overlay settings into the
        // page URL fragment. Mihon's page caches key on the URL string, so
        // CHANGING any overlay setting (text mode, size, grouping) changes the
        // identity of every page — previously loaded, stale-rendered chapters
        // are instantly refetched and re-rendered with the new setting. The
        // fragment never reaches the wire (okhttp strips it from requests)
        // and the interceptor's OCR lookup strips it before matching.
        //
        // The "4-" prefix re-bumps the cache identity (v32) so pages burned
        // with English fallbacks by the dead gtx backend are refetched, and
        // the "-e<epoch>" tail keeps bumping it whenever a render had to burn
        // fallback text while other translations succeeded (see
        // getTranslation / overlayEpoch) — previously such pages stayed
        // English in Mihon's disk cache FOREVER, because the fingerprint only
        // changed when a setting changed.
        //
        // The "5-" prefix and the "-r<epoch>" tail are v34: when a gate block
        // burned a chapter RAW (every fetch attempt gave up), the first later
        // OCR landing bumps the raw epoch (see noteOcrLanded / scheduleOcrHeal)
        // — the burned chapter is then refetched WITH its overlay on the next
        // open instead of showing raw pages forever. The "5-" prefix also
        // re-baselines every page once for this release.
        val epoch = overlayEpoch()
        // The "-c<covered>-<total>" tail is per-chapter (v47, REPAIRED v52):
        // it carries THIS chapter's PRE-open coverage (coveredBefore,
        // captured before the acquisition block) and the "-r" tail the
        // PRE-open raw epoch. The identity now changes exactly when the
        // store changes between opens — the moment raw-served pages must be
        // refetched and re-rendered with their boxes. v47's post-state token
        // did the opposite: the in-line harvest landed BEFORE the token was
        // computed, so the first open's raw-served pages were locked under
        // an identity that already claimed the new coverage and never
        // changed again.
        //
        // The "8-" prefix re-baselines every page once for this release:
        // identities minted by v47-v51's post-state token can be permanently
        // raw-poisoned (byte-identical URLs → no request → the overlay
        // interceptor never runs).
        //
        // The "11-" prefix is v55: one-shot re-baseline for the coverage-based
        // container prune — v54's exact-text prune let side-by-side wrapper
        // boxes through (manga right-to-left text order, fused inline spans,
        // skipped children). Pages burned with those combined boxes refetch
        // and re-render from the corrected store.
        //
        // The "14-" prefix is v59: re-baseline for the chapter-scoped serve
        // lookup. v58's wire-directory equality blanked pages whose wire
        // spelling differs from the page-list spelling (raw images cached
        // under the 13- identities refetch and burn their stored boxes).
        val fingerprint = "#ocrv=14-$mode-${overlayTextScale()}-$grouping-e$epoch-r$rawEpochBefore" +
            "-c$coveredBefore-${pages.size}"
        for (page in pages) {
            val url = page.imageUrl ?: continue
            if (!url.contains("#ocrv=")) page.imageUrl = url + fingerprint
        }

        return pages
    }

    /**
     * Parse OCR credentials from the reading page HTML.
     *
     * v39 — SHAPE-based, not schema-based. The credentials historically live
     * in a JS array: `_0xvault = ["base64cid","hex64token",ts,"hex16nonce",
     * "https://…/fetch-ocr.php","hex32ref"]` — but every obfuscation round
     * (MHRepo issue #4: the site owner re-breaks the readers after each
     * keiyoushi release) has been nudging element widths, charsets, escapes
     * and the endpoint spelling. Roles are therefore assigned by what each
     * element LOOKS LIKE, not by fixed position/charset:
     *
     *  - the element containing "http" + "ocr"  → gate URL
     *  - the all-digit element                  → timestamp (optional)
     *  - the longest remaining hex-ish string   → token
     *  - the two shorter hex-ish strings        → nonce (shorter) + ref (longer)
     *  - the first non-URL string               → cid (sent as-is, NOT decoded)
     *
     * v51: only {cid, ref} — the POST body — is REQUIRED anymore. The wire
     * capture proves the site's own POST ships WITHOUT X-Gate-Token, and the
     * runtime arrays no longer carry the gate URL element. Optional slots
     * mean optional headers; a missing gate URL is resolved against the
     * reading page's own host (the gate POST is same-origin).
     */
    private fun parseOcrCredentials(html: String): OcrCredentials? {
        // Any JS array literal of strings/numbers on one logical line —
        // candidates are filtered by role matching below, so over-matching
        // is harmless (worst case: an array without an OCR URL is skipped).
        val arrayRegex = Regex("""\[\s*"(?:[^"\\]|\\.)*"(?:\s*,\s*(?:"(?:[^"\\]|\\.)*"|\d+))+\s*\]""")

        // v51: arrays that still carry the gate URL are the real vault —
        // prefer them. URL-less arrays (the current runtime's shape) are a
        // fallback so a reshaped vault can't silently blank the direct path.
        var fallback: OcrCredentials? = null
        for (candidate in arrayRegex.findAll(html)) {
            val elements = ELEMENT_SPLIT.findAll(candidate.value)
                .map { it.value.trim().trim('"').replace("\\/", "/") }
                .filter { it.isNotEmpty() }
                .toList()
            val creds = elements.asOcrCredentialsOrNull() ?: continue
            if (creds.gateUrl.isNotEmpty()) return creds
            if (fallback == null) fallback = creds
        }
        return fallback
    }

    /**
     * v51: the current protocol no longer mints every slot the v45 vault
     * carried — the captured wire POST ships WITHOUT X-Gate-Token and the
     * runtime arrays no longer carry the gate URL. Roles stay shape-based;
     * only {cid, ref} is required now. Empty token/nonce means "header not
     * sent"; an empty gateUrl is resolved by [fetchOcrDataOnce] against the
     * reading page's own host. This is what un-deadened the payload
     * channel: the strict parser rejected every runtime array the v50 scan
     * collected (5 of them in the field log) and nothing was ever posted.
     */
    private fun List<String>.asOcrCredentialsOrNull(): OcrCredentials? {
        val hexish = Regex("""^[0-9a-fA-F]{8,}$""")

        val gateUrl = firstOrNull { it.startsWith("http") && it.contains("ocr", ignoreCase = true) } ?: ""
        var cid: String? = null
        var timestamp = 0L

        val hexes = mutableListOf<String>()
        for (el in this) {
            when {
                el.startsWith("http") -> {}
                el.all { it.isDigit() } && timestamp == 0L && el.length in 6..15 ->
                    timestamp = el.toLongOrNull() ?: 0L
                cid == null -> {
                    // first non-URL, non-timestamp string: the cid (kept as-is)
                    cid = el
                }
                else -> hexes += el
            }
        }
        // Longest hex-ish leftover is the token (when the old triple-slot
        // layout is intact); the two shorter ones are nonce/ref. With the
        // token slot gone from the protocol, two hexes are nonce+ref and a
        // single hex is the ref alone.
        val sortedHexes = hexes.filter { hexish.matches(it) }.sortedByDescending { it.length }
        val token: String
        val nonce: String
        val ref: String
        when {
            sortedHexes.size >= 3 -> {
                token = sortedHexes[0]
                nonce = minOf(sortedHexes[1], sortedHexes[2])
                ref = maxOf(sortedHexes[1], sortedHexes[2])
            }
            sortedHexes.size == 2 -> {
                token = ""
                nonce = minOf(sortedHexes[0], sortedHexes[1])
                ref = maxOf(sortedHexes[0], sortedHexes[1])
            }
            sortedHexes.size == 1 -> {
                token = ""
                nonce = ""
                ref = sortedHexes[0]
            }
            else -> return null
        }
        cid ?: return null
        return OcrCredentials(
            cid = cid,
            token = token,
            timestamp = timestamp,
            nonce = nonce,
            gateUrl = gateUrl,
            ref = ref,
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
        // v52: while the protocol-level cooldown runs, skip the ladder —
        // the harvest handles OCR and the resolver's per-harvest probes
        // double as recovery checks.
        if (System.currentTimeMillis() < gateCooldownUntil) {
            if (!gateCooldownLogged) {
                gateCooldownLogged = true
                OcrDiagnostics.record(
                    "gate: direct path cooling down — the gate rejects our POSTs; the harvest handles OCR",
                )
            }
            return null
        }
        var result = fetchOcrDataOnce(credentials, readingPageUrl)
        var creds = credentials
        for (gapMs in OCR_FETCH_RETRY_GAPS_MS) {
            if (result != null) return result

            // The gate rate-limits heavy use (several chapters read back to
            // back) with short 403/429 windows. The old single 900 ms retry
            // gave up while the window was still open — the WHOLE chapter
            // silently rendered raw ("after ~5 chapters the OCR disappears,
            // the next chapter is fine again"). Escalating gaps ride out the
            // short windows right at chapter open; a failure that survives
            // them hands over to the background self-heal (scheduleOcrHeal)
            // instead of burning raw pages forever.
            Thread.sleep(gapMs)

            // v35 (the "first chapter of a session has no OCR" report): every
            // retry rides FRESH credentials from a cache-busted re-read of the
            // chapter page. The gate binds its blocks to the presented
            // credential set, so replaying the SAME vault — what the v34
            // ladder did — can never recover from a per-credential block or a
            // stale page-cache vault; a fresh render mints a new vault. The
            // re-read goes through the MAIN client, so a challenge on it is
            // auto-solved by the core solver before the next gate attempt.
            refreshOcrCredentials(readingPageUrl)?.let { creds = it }
            result = fetchOcrDataOnce(creds, readingPageUrl)
        }
        // v52: a full ladder of 400-empty answers is a PROTOCOL rejection,
        // not a rate-limit window — re-running it on every chapter open
        // costs ~10 s each and has never landed. Cool the direct path down
        // for 30 minutes; the harvest (which works) takes over immediately.
        if (result == null && lastGateWas400Empty) {
            gateCooldownUntil = System.currentTimeMillis() + 30 * 60_000L
            OcrDiagnostics.record("gate: 400 on every attempt — direct path cooling down 30 min")
        }
        return result
    }

    /**
     * Re-reads the chapter page for a FRESH _0xvault credential set, with a
     * throwaway query parameter so neither Cloudflare's cache nor the site's
     * own page cache can answer with the (possibly blocked or expired) vault
     * from the original load. Returns null when the page can't be fetched or
     * parsed; callers keep their previous credentials in that case.
     */
    private fun refreshOcrCredentials(chapterUrl: String): OcrCredentials? = try {
        val busted = chapterUrl.toHttpUrl().newBuilder()
            .addQueryParameter("ocrts", System.currentTimeMillis().toString())
            .build()
        val html = client.newCall(GET(busted.toString(), headers)).execute().use { it.body.string() }
        parseOcrCredentials(html)
    } catch (_: Exception) {
        null
    }

    // v52 gate forensics: the direct POST path has answered 400/empty all
    // day in the field while the site's own POST succeeds — these make the
    // credential shape visible in the paste and stop paying the ~10 s retry
    // ladder on every chapter open for a protocol that rejects us.
    @Volatile
    private var gateShapeLogged = false

    @Volatile
    private var lastGateWas400Empty = false

    @Volatile
    private var gateCooldownUntil = 0L

    @Volatile
    private var gateCooldownLogged = false

    /** v53: one burn-geometry line per process (bitmap dims + first box). */
    private val burnGeometryLogged = AtomicBoolean(false)

    private fun fetchOcrDataOnce(credentials: OcrCredentials, readingPageUrl: String): List<OcrPage>? {
        // One shape line per process — enough to spot a misassigned slot
        // (the 400's most likely cause) without logging credential values.
        if (!gateShapeLogged) {
            gateShapeLogged = true
            OcrDiagnostics.record(
                "gate: creds — cid=${credentials.cid.length}ch, ref=${credentials.ref.length}ch, " +
                    "nonce=${if (credentials.nonce.isEmpty()) "-" else credentials.nonce.length.toString() + "ch"}, " +
                    "token=${if (credentials.token.isEmpty()) "-" else "sent"}, ts=fresh",
            )
        }
        // v51: the runtime arrays no longer carry the gate URL — the site
        // POSTs same-origin, so the reading page's own host IS the gate host.
        val gateUrl = credentials.gateUrl.ifEmpty {
            readingPageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/fetch-ocr.php" } ?: return null
        }
        // Body: cid stays base64, ref is hex — both as-is from _0xvault
        val jsonBody = """{"cid":"${credentials.cid}","ref":"${credentials.ref}"}"""
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        // Use the source's default headers (includes User-Agent) as a base,
        // then set all required OCR headers. The Accept/sec-fetch-* overrides
        // make this read as exactly what it is on the site: a same-origin
        // jQuery AJAX POST — the document-shaped defaults from
        // configureHeaders would be a bot signal on an XHR.
        // v51: X-Gate-Token/X-Gate-Nonce are sent only when the credential
        // set carries them — the site's own POST no longer includes a token
        // (wire capture: token=false) and echoing empty headers would be a
        // bot signal. A missing timestamp is minted fresh, exactly what the
        // site's JS does at call time.
        val builder = Request.Builder()
            .url(gateUrl)
            .post(requestBody)
            .headers(headers)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cache-Control", "no-cache")
        if (credentials.token.isNotEmpty()) builder.header("X-Gate-Token", credentials.token)
        if (credentials.nonce.isNotEmpty()) builder.header("X-Gate-Nonce", credentials.nonce)
        // v52: ALWAYS mint the timestamp fresh. The vault's ts slot is the
        // page-render time (minutes old through any page cache); the site's
        // own JS mints its timestamp at call time — a stale ts is a plausible
        // 400 trigger, and a fresh one costs nothing.
        val ts = System.currentTimeMillis() / 1000
        val request = builder
            .header("X-Gate-Timestamp", ts.toString())
            .header("Referer", readingPageUrl)
            .header("Origin", baseUrl)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", secFetchSite(gateUrl.toHttpUrl().host))
            .removeHeader("Upgrade-Insecure-Requests")
            .build()

        return try {
            val response = auxClient.newCall(request).execute()
            val body = response.body.string()
            val cfMitigated = response.header("cf-mitigated")
            val status = response.code
            response.close()

            lastGateWas400Empty = false
            if (body.isNullOrBlank()) {
                lastGateWas400Empty = status == 400
                OcrDiagnostics.record("gate: HTTP $status, empty body")
                return null
            }

            // Detect Cloudflare challenge / block pages. These are NOT
            // solvable here (and must never touch the cookie store — the aux
            // client ships without Cloudflare interceptors for exactly that
            // reason); report "no data" and let the retry ladder (fresh
            // credentials via the main client's solver) recover. v35: the
            // cf-mitigated response HEADER is checked too — it is Cloudflare's
            // documented reliable marker and is invisible to a body-only scan.
            if (
                cfMitigated?.contains("challenge", ignoreCase = true) == true ||
                body.contains("Just a moment") ||
                body.contains("cf-challenge") ||
                body.contains("cf-mitigated") ||
                body.contains("Attention Required", ignoreCase = true) ||
                body.contains("cf-error-details", ignoreCase = true) ||
                body.contains("cf-turnstile", ignoreCase = true)
            ) {
                OcrDiagnostics.record("gate: HTTP $status challenge/block page")
                return null
            }

            // v39: shape-based parse (bare array / envelope / drifting field
            // names) replacing the strict DTO ladder — the site owner keeps
            // nudging the payload shape between obfuscation rounds.
            val parsed = parseOcrPayload(body)
            if (parsed == null) {
                OcrDiagnostics.record(
                    "gate: HTTP $status, unparsed body: ${body.take(140).replace(Regex("\\s+"), " ")}",
                )
            } else {
                OcrDiagnostics.record("gate: HTTP $status → ${parsed.size} pages")
            }
            parsed
        } catch (t: Exception) {
            OcrDiagnostics.record("gate: threw ${t.javaClass.simpleName}")
            null
        }
    }

    // ============================== Harvest resolution (v45) ==============================
    //
    // The passive observatory returns everything the site's own runtime
    // exposed during an unmodified visit. This resolver pulls it into the
    // best coordinate space available, gate-space first:
    //
    //  1. site-memory OCR payload — the exact object the site's reader
    //     renders from; its boxes are the gate's own pixel coordinates;
    //  2. runtime-minted gate credentials → one direct gate POST of our own
    //     — again the gate's own pixel coordinates (single attempt per
    //     candidate: the site just used the same ones successfully, so
    //     retry ladders here would only stall chapter open when a
    //     credential happens to be single-use);
    //  3. the DOM scrape's fraction boxes — resolution-independent rendered
    //     geometry, the fallback when the page exposes neither payload nor
    //     credentials (kept inside OcrHarvest's result as `pages`).
    //
    // 1 and 2 restore the alignment the direct path always had; 3 fixes the
    // "works but misaligned" report by never depending on the WebView's
    // image resolution or element geometry again.

    private fun resolveHarvestResult(result: OcrHarvest.HarvestResult, readingPageUrl: String): List<OcrPage>? {
        for (candidate in result.payloadCandidates) {
            val pages = parseOcrPayload(candidate)
                ?.takeIf { it.isNotEmpty() }
                ?.bareFilenames()
            if (pages != null) {
                OcrDiagnostics.record("runtime: site-memory payload → ${pages.size} pages (gate coordinates)")
                return pages
            }
        }

        var attempts = 0
        var unusable = 0
        // v53: the resolver's direct POST attempts must honor the protocol
        // cooldown too — the v52 field log showed two "gate: HTTP 400" lines
        // AFTER the cooldown was declared, because the per-open ladder
        // respects gateCooldownUntil but these recovery probes did not.
        val gateCooling = System.currentTimeMillis() < gateCooldownUntil
        if (gateCooling) {
            OcrDiagnostics.record("runtime: direct POST probes skipped — gate cooling down")
        }
        for (candidate in result.credentialArrays) {
            if (attempts >= 2) break
            if (gateCooling) {
                unusable++
                continue
            }
            val creds = credentialArrayFromJson(candidate)
            if (creds == null) {
                unusable++
                continue
            }
            attempts++
            val pages = fetchOcrDataOnce(creds, readingPageUrl)
            if (!pages.isNullOrEmpty()) {
                OcrDiagnostics.record("runtime: direct POST with page-minted credentials → ${pages.size} pages (gate coordinates)")
                return pages
            }
        }
        // v51: silence is a bug — when the harvest handed us credential sets
        // and none produced a POST, the paste must say why.
        if (attempts == 0 && result.credentialArrays.isNotEmpty()) {
            OcrDiagnostics.record(
                "runtime: ${result.credentialArrays.size} credential set(s) unusable ($unusable shapeless) — no direct POST possible",
            )
        }
        return null
    }

    /** JSON string of a runtime array → our credential model, or null. */
    private fun credentialArrayFromJson(raw: String): OcrCredentials? = runCatching {
        val arr = Json.parseToJsonElement(raw).jsonArray
        arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrNull()?.asOcrCredentialsOrNull()

    /** Normalises payload image values to the bare filename the matcher keys on. */
    private fun List<OcrPage>.bareFilenames(): List<OcrPage> = map { page ->
        page.copy(image = page.image?.trim()?.substringAfterLast('/')?.substringBefore('?'))
    }

    // ============================== OCR self-heal ==============================
    //
    // When even the patient in-line schedule cannot get past a gate block,
    // the chapter is burned RAW into Mihon's page cache (the raw images are
    // already downloaded and cached under the current fingerprint). The heal
    // job keeps retrying in the background — re-reading the chapter page for
    // FRESH credentials each round, because the block can outlive the
    // credential set parsed at chapter open — and when it finally lands, the
    // boxes are stored and the page-cache fingerprint epoch bumps, so the
    // burned chapter is refetched WITH the overlay on its next open instead
    // of showing raw pages forever.

    private val healScheduler = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ManhuaRMTL-OcrHeal").apply { isDaemon = true }
    }

    // One heal job per chapter even when getPageList re-runs it (re-opens,
    // restarts) while an earlier job is still retrying.
    private val healingChapters = ConcurrentHashMap<String, Boolean>()

    private fun scheduleOcrHeal(chapterUrl: String, pages: List<Page>, mode: String) {
        preferences.edit().putBoolean(PREF_RAW_PENDING, true).apply()
        OcrDiagnostics.record("heal scheduled for …${chapterUrl.takeLast(28)}")
        if (healingChapters.putIfAbsent(chapterUrl, true) != null) return

        // Captured BEFORE the fingerprint fragments are appended (this runs
        // inside getPageList, ahead of the fragment pass) — bare URLs are
        // exactly what the image interceptor looks up after stripping "#ocrv=".
        val imageUrls = pages.mapNotNull { it.imageUrl }

        healScheduler.execute {
            try {
                var landed = false
                for (gapMs in OCR_HEAL_GAPS_MS) {
                    Thread.sleep(gapMs)
                    try {
                        // v35: fresh credentials EVERY round, from a
                        // cache-busted page re-read — the plain re-read used
                        // to hit the cached page and replay the same blocked
                        // vault until the ~4 min heal ran out, while the
                        // block window outlived it (the un-healed "first
                        // chapter of a session").
                        var ocrPages: List<OcrPage>? = refreshOcrCredentials(chapterUrl)
                            ?.let { fetchOcrDataOnce(it, chapterUrl) }

                        // v40: while the site owner keeps the vault/gate
                        // reshaped, the direct path stays dead no matter how
                        // fresh the credentials — the unmodified-WebView
                        // observatory is the heal round that still works then.
                        // v45: gate-space-first resolution inside the harvest
                        // result (site-memory payload → runtime credentials →
                        // DOM fractions), same as the in-line path.
                        if (ocrPages == null) {
                            val harvest = OcrHarvest.harvest(chapterUrl, headers["User-Agent"] ?: "")
                            if (harvest != null) {
                                ocrPages = resolveHarvestResult(harvest, chapterUrl)
                                    ?: harvest.pages.takeIf { it.isNotEmpty() }
                            }
                        }

                        if (ocrPages == null) continue // gate still blocking / nothing observed
                        if (ocrPages.isEmpty()) break // gate answered: chapter simply has no OCR text

                        val fresh = storeOcrBoxes(imageUrls, ocrPages)
                        if (fresh.isNotEmpty()) {
                            OcrDiagnostics.record("heal: stored ${fresh.size} pages — epoch will bump")
                            if (mode != MODE_EN) prefetchTranslations(fresh.values, mode)
                            noteOcrLanded()
                            landed = true
                        }
                        break
                    } catch (_: Exception) {
                        // Transient (gate still blocking, page fetch choked) —
                        // the next spaced attempt takes over.
                    }
                }
                if (!landed) OcrDiagnostics.record("heal: ladder exhausted for …${chapterUrl.takeLast(28)}")
            } finally {
                healingChapters.remove(chapterUrl)
            }
        }
    }

    // ============================== Background top-up (v48/v49) ==============================
    //
    // Field data from the 150-page mega chapter: the site renders its ENTIRE
    // overlay payload up-front (~1s into the visit), so a partial store means
    // the DOM gave everything it has — but earlier rounds could also have
    // been cut short by the soft budget. Two WARM background re-visits chase
    // the missing pages; the moment one round adds nothing, the chapter is
    // marked exhausted (in-memory) and re-opens stop scheduling visits at
    // all: the site's DOM provably holds no more OCR for it.

    /** Chapters whose top-up rounds proved the site has nothing more. */
    private val exhaustedChapters = ConcurrentHashMap<String, Boolean>()

    /** v46: the "static vault gone" log fires once per process, not per chapter. */
    @Volatile
    private var directGateWarned = false

    private fun scheduleOcrTopUp(chapterUrl: String, imageUrls: List<String>, mode: String) {
        if (imageUrls.isEmpty()) return
        if (exhaustedChapters.containsKey(chapterUrl)) {
            OcrDiagnostics.record("top-up: skipped (top-up exhausted: the site shows no more OCR in its DOM)")
            return
        }
        // One background job per chapter — shared with the heal ladder so a
        // heal and a top-up can never hold the WebView slot at the same time.
        if (healingChapters.putIfAbsent(chapterUrl, true) != null) return
        val ua = headers["User-Agent"] ?: ""

        healScheduler.execute {
            try {
                for (gapMs in OCR_TOPUP_GAPS_MS) {
                    Thread.sleep(gapMs)

                    val before = coveredPageCount(imageUrls)
                    if (before >= imageUrls.size) break // a heal landed in between

                    val harvest = OcrHarvest.harvest(chapterUrl, ua)
                    val merged = harvest?.let {
                        resolveHarvestResult(it, chapterUrl) ?: it.pages.takeIf { p -> p.isNotEmpty() }
                    }
                    if (!merged.isNullOrEmpty()) {
                        val fresh = storeOcrBoxes(imageUrls, merged)
                        if (fresh.isNotEmpty() && mode != MODE_EN) prefetchTranslations(fresh.values, mode)
                    }

                    val after = coveredPageCount(imageUrls)
                    when {
                        after >= imageUrls.size -> {
                            OcrDiagnostics.record("top-up: coverage complete — $after/${imageUrls.size} pages (next open re-renders)")
                            break
                        }
                        after > before -> {
                            OcrDiagnostics.record("top-up: coverage $before → $after/${imageUrls.size} pages (next open re-renders)")
                            continue
                        }
                        else -> {
                            // v51: exhaustion must mean "the DOM ceiling was
                            // REACHED", not "one visit yielded nothing" — the
                            // v50 build let a single early-settled round (its
                            // own settle bug) permanently lock chapters at
                            // 1/56 stored. Only a majority-covered chapter
                            // may be marked exhausted; a low-coverage
                            // zero-gain round falls through to the next gap.
                            val majority = after * 2 >= imageUrls.size
                            if (majority) {
                                exhaustedChapters[chapterUrl] = true
                                OcrDiagnostics.record(
                                    "top-up: no new pages — the site's DOM holds no more OCR ($after/${imageUrls.size} stored); re-opens render instantly",
                                )
                                break
                            }
                            OcrDiagnostics.record(
                                "top-up: round added nothing (coverage still low: $after/${imageUrls.size}) — the site's data was not reached; the next gap retries",
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Transient (visit failed, app tearing down) — the next open
                // re-schedules while coverage is still partial.
            } finally {
                healingChapters.remove(chapterUrl)
            }
        }
    }

    /**
     * How many of the chapter's pages currently have boxes, memory merged
     * over the persistent store. Drives the v49 open split and the v47
     * per-chapter coverage fingerprint token.
     */
    private fun coveredPageCount(imageUrls: List<String>): Int {
        if (imageUrls.isEmpty()) return 0
        var covered = 0
        for (url in imageUrls) {
            val key = url.trim()
            val boxes = ocrData[key]?.boxes ?: loadOcrBoxes(key)
            if (!boxes.isNullOrEmpty()) covered++
        }
        return covered
    }

    /**
     * The v48/v49 in-line harvest wrapper: a foreground open never waits 50
     * seconds behind a background top-up. If the slot is busy, the running
     * background visit is ASKED to wrap up (preempt) and the foreground
     * harvest proceeds as soon as it settles (≤12 s).
     */
    private fun harvestBlocking(chapterUrl: String): OcrHarvest.HarvestResult? {
        if (OcrHarvest.isBusy()) {
            OcrDiagnostics.record("harvest: slot busy (background top-up) — waiting")
        }
        return OcrHarvest.harvest(chapterUrl, headers["User-Agent"] ?: "", preemptCurrent = true)
    }

    /**
     * After raw pages were burned, the FIRST successful OCR landing — this
     * chapter's heal, or simply the next chapter fetching fine — bumps the
     * raw-epoch part of the page-cache fingerprint. Every getPageList after
     * that moment issues fresh page identities, so re-opening the burned
     * chapter refetches its images (and now finds boxes in [ocrData]) instead
     * of hitting Mihon's raw disk cache forever. Mirrors the translation
     * fallback epoch (overlayEpoch), which never fires in English mode — the
     * mode where raw burns are the only failure there is.
     */
    private fun noteOcrLanded() {
        if (!preferences.getBoolean(PREF_RAW_PENDING, false)) return
        preferences.edit()
            .putBoolean(PREF_RAW_PENDING, false)
            .putInt(PREF_RAW_EPOCH, preferences.getInt(PREF_RAW_EPOCH, 0) + 1)
            .apply()
    }

    /**
     * Queues background pre-translation for a batch of text boxes (shared
     * futures: the image interceptor awaits the SAME request instead of
     * issuing its own serial network calls).
     */
    private fun prefetchTranslations(boxesByPage: Collection<List<OcrTextBox>>, target: String) {
        boxesByPage
            .flatten()
            .map { it.text }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { text -> prefetchTranslation(text, target) }
    }

    /**
     * Matches OCR pages against image URLs and MERGES the boxes into
     * [ocrData] — images of earlier chapters may still be in flight
     * (preload/download), so nothing is ever replaced or erased. Returns the
     * boxes stored THIS call, keyed by image URL (empty when nothing
     * matched).
     *
     * v46 three-tier matching — the v45 bare-filename equality dropped every
     * page whose WebView image spelling differed from the page-list URL
     * (resize variants, -scaled suffixes, escapes; the "1 box per 7 pages"
     * field report):
     *  1. NORMALIZED FILENAME — URL-decoded, query/extension stripped,
     *     -scaled/-NNNxNNN suffixes removed, lowercased, on both sides;
     *  2. UNIQUE PAGE-NUMBER TOKEN — the last number group of the normalized
     *     base, when it is unique among the still-unmatched on BOTH sides;
     *  3. GATED POSITIONAL PAIRING — leftovers pair one-to-one only when
     *     tier 1 already proved ≥30% of the pages (so a scrambled order can
     *     never invent matches out of thin air).
     * Every call logs its tier verdict: "stored: X/Y pages (filename a,
     * page-number b, positional c)".
     */
    private fun storeOcrBoxes(pageUrls: List<String>, ocrPages: List<OcrPage>): Map<String, List<OcrTextBox>> {
        class OcrSlot(val keys: Set<String>, val token: Int?, var boxes: List<OcrTextBox>)

        val ocrSlots = mutableListOf<OcrSlot>()
        for (ocrPage in ocrPages) {
            val filename = ocrPage.image?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
            val sweptBoxes = ocrPage.normalisedTexts()
            // v56: site chrome first — ad banners and app promos captured by
            // the sweep must never reach the store (they burned as English
            // boxes even on foreign-language chapters and wasted translation
            // quota on garbage).
            val rawBoxes = sweptBoxes.filter { !isChromeBoxText(it.text) }
            val chromeDropped = sweptBoxes.size - rawBoxes.size
            if (chromeDropped > 0 && adFilterStoreLogs.incrementAndGet() <= 6) {
                OcrDiagnostics.record(
                    "adfilter: $filename — dropped $chromeDropped chrome/ad box(es)",
                )
            }
            if (rawBoxes.isEmpty()) continue
            // Rendering modes: the SITE renders each OCR entry as its own
            // overlay box (it cuts text into many small blocks); paragraph
            // merging is an optional mode. The dedupe bucket is space-aware
            // (v45): fraction boxes need a finer grid than pixel boxes or
            // same-text lines a tenth of the image apart would collapse.
            // v54: the sweep also captures container divs BESIDE their line
            // children (a wrapper's innerText equals its textContent) — a
            // page-spanning wrapper burned its concatenated text as a slab.
            // The prune drops wrappers/duplicates whose text the tighter
            // boxes already carry, so only real bubbles reach the store.
            val textBoxes = when (grouping) {
                GROUP_PARAGRAPH -> pruneContainerBoxes(groupIntoParagraphs(dedupeBoxes(rawBoxes)))
                else -> pruneContainerBoxes(dedupeBoxes(rawBoxes))
            }
            val pruned = rawBoxes.size - textBoxes.size
            if (pruned > 0 && pruneHitLogs.incrementAndGet() <= 6) {
                OcrDiagnostics.record(
                    "prune: $filename — dropped $pruned container/duplicate box(es), kept ${textBoxes.size}",
                )
            }
            if (textBoxes.isEmpty()) continue
            val keys = filenameKeys(filename)
            ocrSlots += OcrSlot(keys, keys.mapNotNull { pageNumberToken(it) }.firstOrNull(), textBoxes)
        }

        // v56: repeated-banner sweep — the SAME text on 3+ different pages is
        // site chrome (persistent ad banners, watermarks, chapter recaps),
        // never dialogue. Ads rotate their headline per page, which is what
        // the keyword filter above is for; banners that keep one creative get
        // caught here.
        run {
            val pagesByText = HashMap<String, HashSet<String>>()
            for (slot in ocrSlots) {
                val pageId = slot.keys.firstOrNull() ?: continue
                for (b in slot.boxes) {
                    val key = chromeRepetitionKey(b.text)
                    if (key.isEmpty()) continue
                    pagesByText.getOrPut(key) { HashSet() }.add(pageId)
                }
            }
            val bannerTexts = pagesByText.filterValues { it.size >= 3 }.keys
            if (bannerTexts.isNotEmpty()) {
                var dropped = 0
                for (slot in ocrSlots) {
                    val before = slot.boxes.size
                    slot.boxes = slot.boxes.filter { chromeRepetitionKey(it.text) !in bannerTexts }
                    dropped += before - slot.boxes.size
                }
                if (dropped > 0) {
                    OcrDiagnostics.record(
                        "adfilter: dropped $dropped repeated banner box(es) — same text on 3+ pages",
                    )
                }
            }
        }

        // Index the OCR slots by every normalized key spelling (later pages
        // win per key — duplicate filenames inside one chapter payload would
        // be a site bug anyway).
        val byKey = HashMap<String, OcrSlot>()
        for (slot in ocrSlots) {
            for (key in slot.keys) byKey[key] = slot
        }

        val slotOfPage = arrayOfNulls<OcrSlot>(pageUrls.size)
        val pageKeys = pageUrls.map { filenameKeys(it) }
        val pageTokens = pageKeys.map { keys -> keys.mapNotNull { pageNumberToken(it) }.distinct() }
        val slotUsed = BooleanArray(ocrSlots.size)
        var byFilename = 0
        var byToken = 0
        var byPosition = 0

        // Tier 1: normalized filename keys.
        for (i in pageUrls.indices) {
            val slot = pageKeys[i].firstNotNullOfOrNull { byKey[it] } ?: continue
            slotOfPage[i] = slot
            slotUsed[ocrSlots.indexOf(slot)] = true
            byFilename++
        }

        // Tier 2: unique page-number tokens among the still-unmatched.
        for (i in pageUrls.indices) {
            if (slotOfPage[i] != null) continue
            val tokens = pageTokens[i]
            if (tokens.size != 1) continue
            val token = tokens[0]
            val candidates = ocrSlots.withIndex()
                .filter { (si, slot) -> !slotUsed[si] && slot.token == token }
                .toList()
            if (candidates.size != 1) continue
            val (si, slot) = candidates[0]
            slotOfPage[i] = slot
            slotUsed[si] = true
            byToken++
        }

        // Tier 3: positional pairing of the leftovers, only with enough
        // tier-1 proof that the payload really belongs to this chapter.
        val tier1 = byFilename
        if (tier1 * 10 >= pageUrls.size * 3 && pageUrls.isNotEmpty()) {
            val leftoverPages = (0 until pageUrls.size).filter { slotOfPage[it] == null }
            val leftoverSlots = ocrSlots.withIndex().filter { !slotUsed[it.index] }.map { it.index }
            if (leftoverPages.size == leftoverSlots.size && leftoverPages.isNotEmpty()) {
                for ((n, pi) in leftoverPages.withIndex()) {
                    val si = leftoverSlots[n]
                    slotOfPage[pi] = ocrSlots[si]
                    slotUsed[si] = true
                    byPosition++
                }
            }
        }

        val now = System.currentTimeMillis()
        val fresh = mutableMapOf<String, List<OcrTextBox>>()
        for (i in pageUrls.indices) {
            val slot = slotOfPage[i] ?: continue
            val key = pageUrls[i].trim()
            ocrData[key] = OcrEntry(slot.boxes, now)
            fresh[key] = slot.boxes
        }
        if (fresh.isNotEmpty()) persistOcr(fresh)

        OcrDiagnostics.record(
            "stored: ${fresh.size}/${pageUrls.size} pages (filename $byFilename, page-number $byToken, positional $byPosition)",
        )
        return fresh
    }

    /**
     * v46 filename normalization: URL-decode, strip query + extension,
     * remove the -scaled / -NNNxNNN resize-suffix family, lowercase. The
     * result is the key both the page-list URL and the harvested image name
     * collapse to.
     */
    private fun normalizeFileKey(name: String): String {
        var s = runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        s = s.substringBefore('?').trim()
        val dot = s.lastIndexOf('.')
        if (dot > 0 && s.lastIndexOf('/') < dot) s = s.substring(0, dot)
        s = s.replace(Regex("""-scaled$"""), "")
        s = s.replace(Regex("""-\d{2,5}x\d{2,5}$"""), "")
        return s.lowercase()
    }

    /** Every normalized spelling a URL or bare filename should match under. */
    private fun filenameKeys(urlOrName: String): Set<String> {
        val bare = urlOrName.substringBefore('?').substringAfterLast('/').trim()
        val keys = mutableSetOf<String>()
        normalizeFileKey(bare).takeIf { it.isNotEmpty() }?.let { keys.add(it) }
        val decoded = runCatching { java.net.URLDecoder.decode(bare, "UTF-8") }.getOrDefault(bare)
        normalizeFileKey(decoded).takeIf { it.isNotEmpty() }?.let { keys.add(it) }
        return keys
    }

    /** Last number group of a normalized key ("page 12" matching token). */
    private fun pageNumberToken(key: String): Int? = Regex("""\d+""").findAll(key).lastOrNull()?.value?.toIntOrNull()

    /**
     * Identical (text + bucketed box) entries collapse. Fraction-space boxes
     * bucket at 0.1% of the image, pixel boxes at 10 px.
     */
    private fun dedupeBoxes(boxes: List<OcrTextBox>): List<OcrTextBox> = boxes.distinctBy { b ->
        val bucket = if (b.normalized) 1000f else 10f
        "${b.text}|${b.box.map { (it * bucket).toInt() }}"
    }

    // ============================== OCR box storage ==============================

    private fun pruneOcrMemory() {
        val cutoff = System.currentTimeMillis() - OCR_MEMORY_TTL_MS
        val iterator = ocrData.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.savedAt < cutoff) iterator.remove()
        }
    }

    /**
     * Persists a chapter's boxes. Value layout: "<savedAt>|<json>". For
     * pixel-space boxes json = [[x, y, w, h, "text"], …]; fraction-space
     * (normalized) boxes carry a leading "n" marker: ["n", x, y, w, h,
     * "text"], … — fractions survive a restart without knowing the image
     * resolution they were measured against. The store is pruned to the
     * newest [OCR_DISK_MAX_ENTRIES] pages whenever it outgrows the cap.
     */
    private fun persistOcr(entries: Map<String, List<OcrTextBox>>) {
        if (entries.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            val editor = ocrStore.edit()
            for ((url, boxes) in entries) {
                val json = buildJsonArray {
                    for (b in boxes) {
                        add(
                            buildJsonArray {
                                if (b.normalized) add("n")
                                add(b.box.getOrElse(0) { 0f }.toDouble())
                                add(b.box.getOrElse(1) { 0f }.toDouble())
                                add(b.box.getOrElse(2) { 0f }.toDouble())
                                add(b.box.getOrElse(3) { 0f }.toDouble())
                                add(b.text)
                            },
                        )
                    }
                }
                editor.putString("p|$url", "$now|$json")
            }
            pruneOcrStore(editor, entries.size)
            editor.apply()
            // v52: freshly stored pages must be visible to the interceptor's
            // normalized-filename index immediately — the once-per-process
            // index kept hiding pages stored after its build for the whole
            // app session (and a miss there is invisible in every log).
            filenameKeyIndex = null
        }
    }

    private fun loadOcrBoxes(url: String): List<OcrTextBox>? {
        val raw = ocrStore.getString("p|$url", null) ?: return null
        val at = raw.substringBefore('|').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > OCR_DISK_TTL_MS) return null

        return runCatching {
            Json.parseToJsonElement(raw.substringAfter('|')).jsonArray.mapNotNull { box ->
                val f = box.jsonArray
                // v45: ["n", x, y, w, h, "text"] = fraction-space box;
                // legacy entries (all numbers) stay pixel-space.
                var idx = 0
                var normalized = false
                if (f.firstOrNull()?.jsonPrimitive?.content == "n") {
                    normalized = true
                    idx = 1
                }
                fun num(i: Int): Float? = f.getOrNull(i)?.jsonPrimitive?.content?.toFloatOrNull()
                val x = num(idx) ?: return@mapNotNull null
                val y = num(idx + 1) ?: return@mapNotNull null
                val w = num(idx + 2) ?: return@mapNotNull null
                val h = num(idx + 3) ?: return@mapNotNull null
                val text = f.getOrNull(idx + 4)?.jsonPrimitive?.content ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                OcrTextBox(floatArrayOf(x, y, w, h), text, normalized)
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Deletes the oldest stored pages once the store outgrows its cap. */
    private fun pruneOcrStore(editor: SharedPreferences.Editor, inserted: Int) {
        val keys = ocrStore.all.keys.filter { it.startsWith("p|") }
        val excess = keys.size + inserted - OCR_DISK_MAX_ENTRIES
        if (excess <= 0) return

        keys.asSequence()
            .map { key -> key to (ocrStore.getString(key, null)?.substringBefore('|')?.toLongOrNull() ?: 0L) }
            .sortedBy { it.second }
            .take(excess + OCR_DISK_PRUNE_SLACK)
            .forEach { (key, _) -> editor.remove(key) }
    }

    // ============================== Translation ==============================
    // The OCR gate only carries English text. For the other overlay languages
    // we translate each text box and cache the result, so each string is
    // translated at most once. Failed strings are NOT cached — they retry on
    // the next chapter load.
    //
    // Backend history: Google's public gtx endpoint (translate_a/single,
    // client=gtx) — the v31 backend — is DEAD as of 2026-09: every request,
    // even a first one, answers the "Sorry..." 429 block page, so every
    // non-English overlay degraded to its English fallback (the reported
    // "only English is working"). The endpoint Chrome extensions use
    // (clients5.google.com/translate_a/t, client=dict-chrome-ex) still
    // works — and, unlike gtx, accepts MANY strings per request via
    // repeated q params (verified live: a 10-string batch returns 10
    // in-order translations in ~1 s). All traffic runs as micro-batches
    // through a minimum gap plus a shared escalating backoff, and failed
    // strings are never cached.

    // In-flight translation requests, deduplicated so the image interceptor
    // can piggyback on the background prefetch instead of doing its own
    // serial network calls (which used to hold every page response hostage
    // for one RTT PER TEXT BOX — the real "chapter loading is slow").
    private val translationsInFlight = ConcurrentHashMap<String, CompletableFuture<String?>>()

    // Queued "<target>|<text>" keys waiting to be packed into a batch.
    private val pendingTranslations = ConcurrentLinkedQueue<String>()

    // Micro-batching scheduler: collects queued strings for a moment, then
    // fires whole batches instead of one request per box.
    private val batchScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ManhuaRMTL-TrBatch").apply { isDaemon = true }
    }
    private val flushQueued = AtomicBoolean(false)

    // Successful translations since the last fingerprint-epoch bump — the
    // "partial render" signal for the fallback self-heal (see overlayEpoch).
    private val translationsSinceEpoch = AtomicInteger(0)

    // ---- translation throttle ---------------------------------------------
    // The translation endpoint rate-limits bursts. The old pipeline fired one
    // request per unique text box through a 6-thread pool, ate a 429 storm
    // after a few chapters, and CACHED THE FAILURES AS ENGLISH — pages
    // rendered half-Arabic/half-English (the reported "combined text"), and
    // the poison stuck for the whole session. All traffic still runs through
    // a minimum gap plus a shared escalating backoff, and failed strings are
    // never cached.

    private val throttleLock = Any()
    private var lastTranslateStart = 0L
    private var translatePauseUntil = 0L
    private var translateBackoffMs = TRANSLATE_BACKOFF_START_MS

    /** Blocks until the caller may start one translation request (worker thread only). */
    private fun acquireTranslateSlot() {
        while (true) {
            val waitMs: Long
            synchronized(throttleLock) {
                val now = System.currentTimeMillis()
                waitMs = maxOf(
                    translatePauseUntil - now,
                    lastTranslateStart + TRANSLATE_MIN_GAP_MS - now,
                    0L,
                )
                if (waitMs <= 0L) lastTranslateStart = now
            }
            if (waitMs <= 0L) return
            Thread.sleep(minOf(waitMs, 400L))
        }
    }

    private fun reportTranslateSuccess() {
        synchronized(throttleLock) {
            translatePauseUntil = 0L
            translateBackoffMs = TRANSLATE_BACKOFF_START_MS
        }
    }

    private fun reportTranslateRateLimited() {
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            if (translatePauseUntil < now) {
                translatePauseUntil = now + translateBackoffMs
                translateBackoffMs = (translateBackoffMs * 2).coerceAtMost(TRANSLATE_BACKOFF_MAX_MS)
            }
        }
    }

    /**
     * Queues one string for background translation and registers the shared
     * future so [getTranslation] can await it (bounded) instead of
     * re-requesting. Queued strings are packed into batches by the
     * scheduler — a whole chapter costs a handful of round-trips instead of
     * one request per box. Completed futures (including failures) are
     * reaped here so the next chapter load retries any string that failed.
     */
    private fun prefetchTranslation(text: String, target: String) {
        if (text.isBlank()) return
        val key = "$target|$text"
        if (translationCache.containsKey(key)) return
        translationsInFlight[key]?.let { existing ->
            if (!existing.isDone) return
            translationsInFlight.remove(key, existing)
        }

        val future = CompletableFuture<String?>()
        if (translationsInFlight.putIfAbsent(key, future) != null) return
        pendingTranslations.add(key)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushQueued.compareAndSet(false, true)) {
            batchScheduler.schedule({
                flushQueued.set(false)
                flushTranslations()
            }, TRANSLATE_BATCH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Drains the queue, groups it by target language and fires batch jobs. */
    private fun flushTranslations() {
        val groups = linkedMapOf<String, MutableList<String>>()
        var drained = 0
        while (drained < TRANSLATE_BATCH_DRAIN_MAX) {
            val key = pendingTranslations.poll() ?: break
            drained++
            groups.getOrPut(key.substringBefore('|')) { mutableListOf() }.add(key)
        }

        for ((target, keys) in groups) {
            // Split into bounded batches: cap strings per request and the
            // total encoded length (URL limits; merged-paragraph mode can
            // produce long strings).
            var batch = mutableListOf<String>()
            var batchChars = 0
            val batches = mutableListOf<List<String>>()
            for (key in keys) {
                val textLen = key.length - target.length - 1
                if (batch.isNotEmpty() && (batch.size >= TRANSLATE_BATCH_MAX_STRINGS || batchChars + textLen > TRANSLATE_BATCH_MAX_CHARS)) {
                    batches.add(batch)
                    batch = mutableListOf()
                    batchChars = 0
                }
                batch.add(key)
                batchChars += textLen
            }
            if (batch.isNotEmpty()) batches.add(batch)

            for (b in batches) {
                translateExecutor.execute { translateBatchAndComplete(b, target) }
            }
        }

        if (pendingTranslations.isNotEmpty()) scheduleFlush()
    }

    /** Runs one batch and completes every string's future with its result. */
    private fun translateBatchAndComplete(keys: List<String>, target: String) {
        val results = translateBatch(keys.map { it.substringAfter('|') }, target)
        var anySuccess = false
        var requeued = 0
        for (index in keys.indices) {
            val key = keys[index]
            val future = translationsInFlight[key] ?: continue
            val translated = results.getOrNull(index)
            if (translated.isNullOrBlank()) {
                // v56: a failed string now re-queues IN-SESSION (bounded)
                // instead of dying after the batch's 3 attempts — the old
                // behavior stranded whole chapters on the English fallback
                // after one 429 storm, and nothing retried until the next
                // chapter load. The future stays registered, so a render
                // waiting on it still receives the late translation; the
                // retry budget exhausted, it completes(null) — never cached,
                // the next chapter load retries.
                val attempts = translateRetries.merge(key, 1, Int::plus) ?: 1
                if (attempts <= TRANSLATE_SESSION_RETRIES) {
                    requeued++
                    pendingTranslations.add(key)
                } else {
                    translateRetries.remove(key)
                    future.complete(null)
                    translationsInFlight.remove(key, future)
                }
            } else {
                anySuccess = true
                translateRetries.remove(key)
                if (translationCache.size > MAX_TRANSLATION_CACHE) translationCache.clear()
                translationCache[key] = translated
                translationsSinceEpoch.incrementAndGet()
                future.complete(translated)
                translationsInFlight.remove(key, future)
            }
        }
        if (translateBatchLogs.incrementAndGet() <= 8) {
            val ok = results.count { !it.isNullOrBlank() }
            OcrDiagnostics.record(
                "translate: $target batch ${keys.size} string(s) → ok $ok, fail ${keys.size - ok}" +
                    (if (requeued > 0) ", requeued $requeued" else ""),
            )
        }
        if (anySuccess) reportTranslateSuccess()
        if (requeued > 0) scheduleFlush()
    }

    /**
     * Cache-only-with-shared-wait lookup used by the IMAGE interceptor.
     * NEVER performs its own network call: the image response must not be
     * held hostage for translation round-trips. Order of preference:
     * 1. translated value already cached,
     * 2. a translation that is already in flight (awaited briefly — batches
     *    usually land well inside the window; a page only ever waits for its
     *    own boxes' batches, never for its own download),
     * 3. the original English text (drawn as-is — never cached). A fallback
     *    burn is MARKED: the next getPageList bumps the page-cache fingerprint
     *    epoch so Mihon refetches those pages and re-renders them with the
     *    real translation. (The v31 fingerprint was static per settings, so
     *    pages burned with a fallback stayed English in Mihon's disk cache
     *    forever — the other half of the "only English works" report.)
     */
    private fun getTranslation(text: String, target: String): String {
        val key = "$target|$text"
        translationCache[key]?.let { return it }

        val future = translationsInFlight[key]
        if (future != null) {
            var stillRunning = false
            try {
                future.get(TRANSLATE_RENDER_WAIT_MS, TimeUnit.MILLISECONDS)?.let { return it }
            } catch (_: Exception) {
                stillRunning = true // keep it around for the next box to await
            }
            if (!stillRunning) translationsInFlight.remove(key, future)
        }

        markFallbackBurn()
        // v56: name the fallback burn — the field paste had no way to tell a
        // dead endpoint from a slow queue; this line (rate-limited) plus the
        // batch lines make the next report decidable.
        if (translateFallbackLogs.incrementAndGet() <= 6) {
            OcrDiagnostics.record(
                "translate: fallback burn — \"${text.take(28)}\" ($target; translation pending or failed)",
            )
        }
        return text
    }

    /** Flags that a fallback render happened (see [overlayEpoch]). */
    private fun markFallbackBurn() {
        if (!preferences.getBoolean(PREF_FALLBACK_DIRTY, false)) {
            preferences.edit().putBoolean(PREF_FALLBACK_DIRTY, true).apply()
        }
    }

    /**
     * The cache-identity epoch of the overlay fingerprint. Bumps exactly when
     * a previous render had to burn fallback text while OTHER translations
     * succeeded — a partial render worth re-burning with the now-cached
     * translations. A fully dead backend bumps nothing (pages stay on their
     * English fallback without refetch storms); the bump happens
     * automatically on the first chapter load where translations flow again.
     */
    private fun overlayEpoch(): Int {
        val current = preferences.getInt(PREF_RENDER_EPOCH, 0)
        if (!preferences.getBoolean(PREF_FALLBACK_DIRTY, false)) return current
        if (translationsSinceEpoch.get() == 0) return current

        translationsSinceEpoch.set(0)
        val next = current + 1
        preferences.edit()
            .putInt(PREF_RENDER_EPOCH, next)
            .putBoolean(PREF_FALLBACK_DIRTY, false)
            .apply()
        return next
    }

    /**
     * One throttled, retried batch round-trip to the dict-chrome-ex endpoint.
     * Returns one result per input (null where a string failed) —
     * deliberately NOT caching a fallback, which used to permanently render
     * English over a chapter the user asked for in Arabic.
     */
    private fun translateBatch(texts: List<String>, target: String): List<String?> {
        var attempt = 0
        while (attempt < TRANSLATE_ATTEMPTS) {
            attempt++
            acquireTranslateSlot()

            val results = try {
                val urlBuilder = "https://clients5.google.com/translate_a/t".toHttpUrl().newBuilder()
                    .addQueryParameter("client", "dict-chrome-ex")
                    .addQueryParameter("sl", "en")
                    .addQueryParameter("tl", target)
                for (text in texts) urlBuilder.addQueryParameter("q", text)

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .header("User-Agent", TRANSLATE_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                auxClient.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 429 -> {
                            reportTranslateRateLimited()
                            null
                        }
                        !resp.isSuccessful -> null
                        else -> resp.body.string().takeIf(String::isNotBlank)?.let { parseBatchResponse(it, texts.size) }
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (results != null) return results
            if (attempt < TRANSLATE_ATTEMPTS) Thread.sleep(500L * attempt)
        }

        return List(texts.size) { null }
    }

    /**
     * dict-chrome-ex answers with a flat JSON array of translated strings,
     * one per q param, in request order (verified live with 10-string
     * batches). The legacy gtx nested shape is tolerated in case the
     * endpoint ever morphs. A count mismatch means the order cannot be
     * trusted — return null and let the retry attempt handle it.
     */
    private fun parseBatchResponse(body: String, expected: Int): List<String?>? {
        val array = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null

        val out = ArrayList<String?>(array.size)
        for (element in array) {
            when (element) {
                is JsonPrimitive -> out.add(element.content.trim().takeIf(String::isNotEmpty))
                is JsonArray -> out.add(
                    element.firstOrNull()
                        ?.let { segment ->
                            runCatching { segment.jsonArray.firstOrNull()?.jsonPrimitive?.content }.getOrNull()
                        }
                        ?.trim()
                        ?.takeIf(String::isNotEmpty),
                )
                else -> out.add(null)
            }
        }

        return out.takeIf { it.size == expected }
    }

    /**
     * Normalized filename → stored page URLs, built lazily ONCE per process
     * from the persistent store (v47). Lets the interceptor find boxes whose
     * stored key spells the filename differently than the wire URL (escapes,
     * resize variants). The build is logged because it is the one thing that
     * can make stored boxes invisible after an app restart.
     *
     * v56: the index keeps EVERY stored URL per spelling instead of letting
     * the last one win. The site names pages "split_001.webp"-style inside
     * every chapter, so spellings collide across chapters AND series — the
     * field log showed 96 stored pages collapsing into 54 spellings. A bare
     * filename match could then serve ANOTHER chapter's boxes (a
     * foreign-language chapter burned an English chapter's dialogue). v56
     * still kept a newest-wins fallback for cross-directory candidates; v58
     * removes it entirely — the lookup is same-directory ONLY (see
     * [lookupFilenameKey]).
     */
    private class InterceptorIndex(
        val bySpelling: Map<String, List<String>>,
        val pageCount: Int,
        val collisions: Int,
    )

    @Volatile
    private var filenameKeyIndex: InterceptorIndex? = null

    // v52: rate-limit counters for the interceptor's serve-path logs —
    // the first misses/hits per process are logged, the rest stay silent
    // so a long reading session can't flood the diagnostics.
    private val serveMissLogs = AtomicInteger(0)
    private val serveWaitLogs = AtomicInteger(0)
    private val serveHitLogs = AtomicInteger(0)
    private val pruneHitLogs = AtomicInteger(0)
    private val wideBoxLogs = AtomicInteger(0)

    // v56: rate-limit counters for the translation and ad-filter diagnostics —
    // the field log had ZERO visibility into why foreign-language chapters
    // burned English (batches failing? queued? endpoint dead?). The first
    // events per process now name it.
    private val serveTopupLogs = AtomicInteger(0)
    private val translateBatchLogs = AtomicInteger(0)
    private val translateFallbackLogs = AtomicInteger(0)
    private val adFilterStoreLogs = AtomicInteger(0)
    private val adFilterBurnLogs = AtomicInteger(0)

    // v56: per-string requeue counts for in-session translation retries.
    private val translateRetries = ConcurrentHashMap<String, Int>()

    /**
     * v59: URL directories of the page list of the chapter being served —
     * set on every getPageList call from that chapter's own image URLs (the
     * exact URLs the harvest stores its boxes under).
     *
     * v58 scoped the filename-index lookup by comparing the WIRE URL's
     * directory against each stored URL's directory. That is too strict:
     * when the reader's image request resolves through a redirect / CDN
     * spelling whose PATH differs from the page-list spelling (or percent
     * -encodes the directory), the equality check refuses the chapter's OWN
     * stored boxes and the page renders RAW — the field report's "no OCR
     * randomly in the middle of the chapter for like 10 pages" (v57 had
     * masked exactly these pages by falling back to other chapters' boxes,
     * which burned at random positions). Scoping by the page list's OWN
     * directories instead keeps the cross-chapter refusal structural —
     * another chapter's directories are never in this set — while every
     * wire-vs-list spelling difference inside the chapter matches again.
     */
    @Volatile
    private var activeChapterDirs: Set<String> = emptySet()

    private fun setActiveChapterDirs(imageUrls: List<String>) {
        activeChapterDirs = imageUrls.map { urlDirectory(it) }.filterTo(HashSet()) { it.isNotEmpty() }
    }

    private fun interceptorFilenameIndex(): InterceptorIndex {
        filenameKeyIndex?.let { return it }
        synchronized(this) {
            filenameKeyIndex?.let { return it }
            val bySpelling = HashMap<String, MutableList<String>>()
            var pages = 0
            for (key in ocrStore.all.keys) {
                if (!key.startsWith("p|")) continue
                val url = key.removePrefix("p|")
                pages++
                for (normalized in filenameKeys(url)) {
                    bySpelling.getOrPut(normalized) { mutableListOf() }.add(url)
                }
            }
            var collisions = 0
            for (urls in bySpelling.values) if (urls.size > 1) collisions += urls.size - 1
            OcrDiagnostics.record(
                "interceptor: filename-key index built — $pages stored pages, " +
                    "normalized=${bySpelling.size} spellings, $collisions cross-chapter name collision(s)",
            )
            val index = InterceptorIndex(bySpelling, pages, collisions)
            filenameKeyIndex = index
            return index
        }
    }

    /** Path part of a URL with the host stripped — different chapters of the
     *  same series (and different series entirely) differ here even when the
     *  bare filename is the generic "split_001.webp". */
    private fun urlDirectory(url: String): String = url
        .substringBefore('?')
        .substringAfter("//")
        .substringAfter('/', "")
        .substringBeforeLast('/')

    /**
     * v59 filename-index lookup: scoped to the chapter being served.
     *
     * The candidate's directory must be one of [activeChapterDirs] — the
     * directories of the page list the reader is currently downloading —
     * which is exactly where this chapter's harvest stored its boxes.
     * Cross-chapter serving stays structurally impossible (v58's goal):
     * another chapter's "split_001.webp" carries a foreign directory that
     * is never in the scope set, so its boxes can never burn here. Unlike
     * v58's wire-directory equality, a redirect/CDN/percent-encoding
     * difference between the wire URL and the page-list spelling can no
     * longer blank the chapter's own pages (see [activeChapterDirs]).
     *
     * Safety net: with NO page list seen yet in this process (scope empty —
     * a reader restore that serves images before getPageList, never
     * observed but cheap to guard), fall back to v58's strict
     * wire-directory equality.
     */
    private fun lookupFilenameKey(requestUrl: String, normalized: String): String? {
        val candidates = interceptorFilenameIndex().bySpelling[normalized] ?: return null
        val scope = activeChapterDirs
        if (scope.isEmpty()) {
            val dir = urlDirectory(requestUrl)
            return candidates.firstOrNull { urlDirectory(it) == dir }
        }
        return candidates.firstOrNull { urlDirectory(it) in scope }
    }

    /**
     * v58: the interceptor's box lookup as ONE function — exact URL (memory,
     * then store), URL-decoded spelling, filename-key index — with store hits
     * cached into memory exactly like the inline tiers did. The harvest-
     * coverage wait re-runs this verbatim, so a page the harvest stores
     * mid-wait is found no matter which tier catches it.
     */
    private fun serveLookup(url: String): List<OcrTextBox>? {
        (ocrData[url]?.boxes ?: ocrData[url.trim()]?.boxes)?.let { return it }
        loadOcrBoxes(url)?.let {
            ocrData[url.trim()] = OcrEntry(it, System.currentTimeMillis())
            return it
        }
        loadOcrBoxes(url.trim())?.let {
            ocrData[url.trim()] = OcrEntry(it, System.currentTimeMillis())
            return it
        }
        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        if (decoded != url) {
            (ocrData[decoded]?.boxes ?: loadOcrBoxes(decoded))?.let {
                ocrData[url.trim()] = OcrEntry(it, System.currentTimeMillis())
                return it
            }
        }
        val bare = url.substringBefore('?').substringAfterLast('/')
        lookupFilenameKey(url, normalizeFileKey(bare))?.let { mapped ->
            (ocrData[mapped]?.boxes ?: loadOcrBoxes(mapped))?.let {
                ocrData[url.trim()] = OcrEntry(it, System.currentTimeMillis())
                return it
            }
        }
        return null
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

        val url = request.url.newBuilder().fragment(null).build().toString()

        // Only process images from the site hosts (covers cdn.manhuarmtl.com)
        if (!url.contains("manhuarmtl.com")) return response

        // Look up OCR text boxes for this image URL — v47 three-step ladder:
        // (1) exact URL (memory, then the persistent store — covers chapters
        // the reader restored from its DB after an app restart, whose
        // re-downloads never saw getPageList); (2) URL-decoded spelling (the
        // store keys use the page-list spelling while okhttp's wire spelling
        // can differ for non-ASCII/escaped names); (3) the filename-key index
        // (normalized keys over every stored page, built once per process).
        var textBoxes = serveLookup(url)
        if (textBoxes.isNullOrEmpty()) {
            // v52: the serve path was the one blind spot — every upstream
            // channel logs, but whether the interceptor BURNED or served RAW
            // was invisible in the paste. Rate-limited to the first misses
            // per process so a long session can't flood the log. v53: empty
            // basenames (XHR endpoints, bare-host requests) are never page
            // images — skip them instead of logging "serve:  → RAW miss".
            val bare = url.substringBefore('?').substringAfterLast('/')
            // v58: the filename ladder is same-chapter only now, so a page
            // whose harvest has not stored yet has legitimately nothing to
            // serve. While a harvest IS running, give it a short bounded
            // window to land this page (poll the full ladder every 300 ms,
            // ≤6 s): the store lands at merge time, and a poll that catches
            // it burns the RIGHT boxes on the FIRST render instead of
            // leaving the page raw until the next open. No harvest running
            // → RAW immediately; chapters the site gives no OCR for must
            // not gain latency.
            if (bare.isNotEmpty() && OcrHarvest.isBusy()) {
                val deadline = System.currentTimeMillis() + SERVE_COVERAGE_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(SERVE_COVERAGE_POLL_MS)
                    textBoxes = serveLookup(url)
                    if (!textBoxes.isNullOrEmpty()) {
                        if (serveWaitLogs.incrementAndGet() <= 6) {
                            OcrDiagnostics.record("serve: $bare → coverage landed during harvest wait")
                        }
                        break
                    }
                    if (!OcrHarvest.isBusy()) break
                }
            }
            if (textBoxes.isNullOrEmpty()) {
                if (bare.isNotEmpty() && serveMissLogs.incrementAndGet() <= 12) {
                    OcrDiagnostics.record(
                        "serve: $bare → RAW miss (store index: ${interceptorFilenameIndex().pageCount} pages)",
                    )
                }
                return response
            }
        }

        // v56: top-up the translation queue for THIS page before rendering.
        // Boxes restored from the disk store (app restart, cache eviction,
        // chapters stored by older builds) never saw this session's store-time
        // prefetch — without this they hit getTranslation with a cold cache
        // and nothing in flight, burning the English fallback instantly. The
        // queued strings land within a couple of batches — inside the render
        // wait — so the FIRST render comes out translated.
        if (mode != MODE_EN) {
            var missing = 0
            for (b in textBoxes) {
                val key = "$mode|${b.text}"
                if (!translationCache.containsKey(key) && !translationsInFlight.containsKey(key)) {
                    prefetchTranslation(b.text, mode)
                    missing++
                }
            }
            if (missing > 0 && serveTopupLogs.incrementAndGet() <= 6) {
                OcrDiagnostics.record(
                    "translate: serve top-up — queued $missing untranslated string(s) for " +
                        url.substringBefore('?').substringAfterLast('/'),
                )
            }
        }
        if (serveHitLogs.incrementAndGet() <= 3) {
            // v53: the paste now carries the box GEOMETRY (space marker + raw
            // values of the first boxes) — "squashed to the left" reports are
            // decidable from the log alone instead of guessed at.
            val geo = textBoxes.take(2).joinToString(" | ") { b ->
                val v = b.box
                "${if (b.normalized) "n" else "p"}:" +
                    "${v.getOrElse(0) { 0f }}" + "," +
                    "${v.getOrElse(1) { 0f }}" + "," +
                    "${v.getOrElse(2) { 0f }}" + "," +
                    "${v.getOrElse(3) { 0f }}"
            }
            OcrDiagnostics.record(
                "serve: ${url.substringBefore('?').substringAfterLast('/')} → burned ${textBoxes.size} box(es) [$geo]",
            )
        }

        // Read the image bytes
        val imageBytes = response.body.bytes()
        if (imageBytes.isEmpty()) return response

        // Overlay text on the image
        val bare = url.substringBefore('?').substringAfterLast('/')
        val modifiedBytes = overlayText(imageBytes, textBoxes, mode, bare) ?: return response

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
     * - v54: text VERTICALLY CENTERED in the box. The sweep proves the site's
     *   boxes are not always tight around their text (a 0.24-page-tall box
     *   carrying one line) — top-aligning left that text floating above its
     *   bubble. Tight boxes are unaffected (center == top within a few px);
     *   tall boxes now anchor mid-bubble like the site's overlay.
     * - v54: container/duplicate boxes are pruned here too — chapters stored
     *   by older builds render clean without waiting for a refetch.
     * - v55: any surviving box wider than half the page is logged as
     *   "wide: <file> …" — the combined-wrapper signature, decisive in the
     *   next paste.
     * - Black text with a white outline.
     */
    private fun overlayText(imageBytes: ByteArray, textBoxes: List<OcrTextBox>, targetLang: String, pageLabel: String): ByteArray? {
        val unpruned = pruneContainerBoxes(textBoxes)
        // v56: burn-time chrome filter — chapters stored by older builds still
        // carry ad-banner boxes; dropping them at render cleans legacy stores
        // without waiting for a refetch (the store-time filter handles fresh
        // harvests).
        val boxes = unpruned.filter { !isChromeBoxText(it.text) }
        val chromeDropped = unpruned.size - boxes.size
        if (chromeDropped > 0 && adFilterBurnLogs.incrementAndGet() <= 6) {
            OcrDiagnostics.record(
                "adfilter: burn $pageLabel — dropped $chromeDropped chrome/ad box(es)",
            )
        }
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
        val imgHeight = mutableBitmap.height.toFloat()

        // v53: one geometry line per process — the bitmap's true dimensions
        // and where the first box lands AFTER scaling. With the serve line's
        // raw values this makes "squashed to the left" decidable from the
        // paste: fraction boxes × bitmap dims must land inside the image.
        if (burnGeometryLogged.compareAndSet(false, true)) {
            boxes.firstOrNull()?.let { b0 ->
                val raw = { i: Int -> b0.box.getOrElse(i) { 0f } }
                val vx = if (b0.normalized) raw(0) * imgWidth else raw(0)
                val vy = if (b0.normalized) raw(1) * imgHeight else raw(1)
                val vw = if (b0.normalized) raw(2) * imgWidth else raw(2)
                val vh = if (b0.normalized) raw(3) * imgHeight else raw(3)
                OcrDiagnostics.record(
                    "burn: bitmap ${imgWidth.toInt()}x${imgHeight.toInt()} — first box " +
                        "(${if (b0.normalized) "fraction" else "pixel"} space) → x=${vx.toInt()}, y=${vy.toInt()}, " +
                        "w=${vw.toInt()}, h=${vh.toInt()}, text=\"${b0.text.take(24)}\"",
                )
            }
        }

        // v55: name any surviving WIDE box — a text box wider than half the
        // page is the signature of a combined multi-bubble wrapper that the
        // prune could not match (or a site-side merge). Rate-limited to the
        // first 6 per process; makes the next paste decisive.
        for (b in boxes) {
            val bw = b.box.getOrElse(2) { 0f }
            if (bw <= 0f) continue
            val fracW = if (b.normalized) bw else bw / imgWidth
            if (fracW <= 0.5f) continue
            if (wideBoxLogs.incrementAndGet() > 6) break
            val bh = b.box.getOrElse(3) { 0f }
            val fracH = if (b.normalized) bh else bh / imgHeight
            OcrDiagnostics.record(
                "wide: $pageLabel — w=$fracW h=$fracH " +
                    "x=${b.box.getOrElse(0) { 0f }} y=${b.box.getOrElse(1) { 0f }} " +
                    "text=\"${b.text.take(32)}\"",
            )
        }

        for (textBox in boxes) {
            // v45: fraction-space (normalized) boxes are relative to the page
            // image and must be scaled to THIS bitmap's dimensions first —
            // the harvest can't know the downloaded file's resolution. Pixel
            // boxes (direct gate path) are used as-is.
            val x0 = textBox.box.getOrElse(0) { 0f }
            val y0 = textBox.box.getOrElse(1) { 0f }
            val w0 = textBox.box.getOrElse(2) { 0f }
            val h0 = textBox.box.getOrElse(3) { 0f }
            val x = if (textBox.normalized) x0 * imgWidth else x0
            val y = if (textBox.normalized) y0 * imgHeight else y0
            val w = if (textBox.normalized) w0 * imgWidth else w0
            val h = if (textBox.normalized) h0 * imgHeight else h0

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

            // v54: vertically center the layout in the box (the site's tall
            // overlay boxes used to leave the text pinned above its bubble),
            // still clamped so tall layouts don't spill past the bitmap.
            val textHeight = strokeLayout.height.toFloat()
            val boxCenterX = x + w / 2f

            val layoutWidth = maxWidth.toFloat()
            val translateX = (boxCenterX - layoutWidth / 2f).coerceIn(0f, (imgWidth - layoutWidth).coerceAtLeast(0f))
            val translateY = (y + (h - textHeight) / 2f).coerceIn(0f, (imgHeight - textHeight).coerceAtLeast(0f))

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

        // v36: solver field diagnostics. The hidden challenge solve runs
        // without any visible surface, so a "still broken" report used to be
        // pure guesswork. Everything the Cloudflare solver does now lands in
        // a rolling persisted log; tapping opens it as selectable text
        // (select-all + copy straight into a bug report). Built as an
        // EditTextPreference because the lib's compile-time Preference API
        // exposes no Context constructor.
        androidx.preference.EditTextPreference(screen.context).apply {
            key = "pref_cf_diagnostics"
            title = "Cloudflare solver diagnostics"
            summary = "Tap to view the recent Cloudflare solver log"
            text = CloudflareSolverDiagnostics.snapshot().ifEmpty { "No Cloudflare events yet" }
            setOnBindEditTextListener { editText ->
                editText.setTextIsSelectable(true)
                editText.setSingleLine(false)
            }
            // Refresh BEFORE the dialog opens (returning false keeps the
            // default tap behavior instead of swallowing it).
            setOnPreferenceClickListener {
                text = CloudflareSolverDiagnostics.snapshot().ifEmpty { "No Cloudflare events yet" }
                false
            }
        }.let(screen::addPreference)

        // v59: the OCR diagnostics pref is BACK (v40 block, verbatim). The
        // v58 regression report arrived with no log — the release builds
        // carried no way to produce one — and two symptoms (blank page
        // stretches, re-combined side-by-side bubbles) are not decidable
        // blind. The rolling OcrDiagnostics log never stopped recording;
        // this only re-exposes it.
        androidx.preference.EditTextPreference(screen.context).apply {
            key = "pref_ocr_diagnostics"
            title = "OCR diagnostics"
            summary = "Tap to view the recent OCR pipeline log"
            text = OcrDiagnostics.snapshot().ifEmpty { "No OCR events yet" }
            setOnBindEditTextListener { editText ->
                editText.setTextIsSelectable(true)
                editText.setSingleLine(false)
            }
            setOnPreferenceClickListener {
                text = OcrDiagnostics.snapshot().ifEmpty { "No OCR events yet" }
                false
            }
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
        // v39: element splitter for the shape-based credential scan — matches
        // a quoted string (escapes allowed) or a bare integer inside the
        // candidate array literals.
        val ELEMENT_SPLIT = Regex("""("(?:[^"\\]|\\.)*"|\d+)""")

        private const val MODE_EN = "en"
        private const val MODE_RAW = "raw"
        private const val GROUP_PARAGRAPH = "paragraph"
        private const val GROUP_LINE = "line"

        // v58: bounded harvest-coverage wait on the serve path — the poll only
        // ever runs while a harvest is actually busy (zero added latency when
        // the store simply has nothing for a page).
        private const val SERVE_COVERAGE_WAIT_MS = 6_000L
        private const val SERVE_COVERAGE_POLL_MS = 300L
        private const val MAX_TRANSLATION_CACHE = 3000
        private const val TRANSLATE_MIN_GAP_MS = 140L

        // v56: the old 20 s initial backoff made ONE transient 429 freeze all
        // translation for 20-180 s while 3-attempt batches died waiting —
        // whole chapters burned the English fallback. Google's burst limits
        // recover in seconds: start at 3 s, cap at 60 s.
        private const val TRANSLATE_BACKOFF_START_MS = 3_000L
        private const val TRANSLATE_BACKOFF_MAX_MS = 60_000L
        private const val TRANSLATE_ATTEMPTS = 3

        // v56: failed strings re-queue up to this many times within the
        // session before giving up (previously: never retried in-session).
        private const val TRANSLATE_SESSION_RETRIES = 2
        private const val TRANSLATE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val TRANSLATE_BATCH_DELAY_MS = 120L
        private const val TRANSLATE_BATCH_MAX_STRINGS = 8
        private const val TRANSLATE_BATCH_MAX_CHARS = 3600
        private const val TRANSLATE_BATCH_DRAIN_MAX = 600
        private const val TRANSLATE_RENDER_WAIT_MS = 6000L
        private const val PREF_FALLBACK_DIRTY = "pref_ocr_fallback_dirty"
        private const val PREF_RENDER_EPOCH = "pref_ocr_render_epoch"
        private const val PREF_RAW_PENDING = "pref_ocr_raw_pending"
        private const val PREF_RAW_EPOCH = "pref_ocr_raw_epoch"

        // In-line gate patience: 4 attempts with escalating gaps (~12 s of
        // waiting — invisible while the gate is healthy, the first attempt
        // answers immediately). Every retry rides FRESH credentials from a
        // cache-busted chapter re-read (v35).
        private val OCR_FETCH_RETRY_GAPS_MS = longArrayOf(1_200L, 2_500L, 5_000L)

        // Background self-heal patience (v35): ~12.75 min of escalating gaps.
        // The old 8 × 30 s (~4 min) window regularly closed before the gate's
        // block did — and a block window outlives the app restart that makes
        // the user's FIRST chapter of a session the raw one.
        private val OCR_HEAL_GAPS_MS = longArrayOf(
            20_000L, 30_000L, 45_000L, 60_000L, 90_000L, 90_000L, 120_000L, 120_000L, 180_000L,
        )

        // Background top-up re-visits (v48): two warm WebView visits (the
        // chapter's images are already in the app's caches) chasing pages the
        // first pass missed. A zero-gain round exhausts the chapter (v49).
        private val OCR_TOPUP_GAPS_MS = longArrayOf(25_000L, 70_000L)

        private const val OCR_MEMORY_TTL_MS = 90 * 60_000L
        private const val OCR_DISK_TTL_MS = 24 * 60 * 60_000L
        private const val OCR_DISK_MAX_ENTRIES = 160
        private const val OCR_DISK_PRUNE_SLACK = 20
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
