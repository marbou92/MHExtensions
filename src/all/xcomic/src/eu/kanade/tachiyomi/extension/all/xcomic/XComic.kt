package eu.kanade.tachiyomi.extension.all.xcomic

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Locale

/**
 * XComic (xcomic.me) — Qwik City SSR site. FOUR mirrors share one backend and
 * one ID space (the same /title/{id6} and /chapter/{id} resolve identically
 * on all of them): xcomic.me (primary), xcomic.net, comik.to, yona.to. The
 * user can switch mirrors — or enter a custom one — in the extension
 * settings; every request is built against the chosen mirror.
 *
 * The site hosts English, Arabic AND French content (plus more): language is
 * a per-source(-group) attribute rendered as flag emojis on the series page,
 * so chapters are grouped per source group × language and the "Chapter
 * language" setting filters those groups.
 *
 * Endpoints (live-verified 2026-09, desktop Chrome UA, no CF challenges):
 *  - Browse/search  GET /search?word={query}&page={N} — the ONLY server-side
 *    paginated listing (48 cards/page). Popular = empty word (site order),
 *    search = the query. The `sortby` query param IS honored server-side
 *    (verified: field_update / field_name_* reorder the SSR cards); the
 *    type / demographic / content-rating / status panel controls are
 *    client-side only on the site — this source reads those exact panels from
 *    the card chips and applies them in searchMangaParse (v1.4.5).
 *  - Latest         GET /latest — NOW server-rendered series cards (24 per
 *    page; the v1 recon's "client-side only" no longer holds, and the old
 *    /rss/latest.xml feed was chapter-level and capped at 100 entries).
 *    Pagination follows the page's own "Older →" link: /latest?before={the
 *    oldest release timestamp on the page}. Cards carry the REAL title slug,
 *    so the RSS-era two-hop details resolution is no longer needed on the
 *    browse path (kept for old library entries).
 *  - Details        GET /title/{id6}; selectors below. The header stats row
 *    carries the star rating (+ user count), follows, reviews, comments.
 *  - Chapters       TWO-STEP: the title page renders one box per VISIBLE
 *    source group, each showing only that group's LATEST chapter (plus flag
 *    + /source/{id} link) — the v1 build mistook those for the full list.
 *    The grid is also INCOMPLETE: the page state's `comic_ids` array is the
 *    site's COMPLETE source list, and sources missing from the DOM (adult/
 *    sfw-filtered ones like Reset Scans or Xscans) keep live /source/{id}
 *    pages with real chapters. The FULL chapter list of a group lives on
 *    its /source/{id} page (`a[href^=/chapter/]` rows + `time[data-time]`
 *    epoch millis), so we fetch one /source/{id} per group — rendered and
 *    hidden — that matches the language setting. The group's REAL name is
 *    the badge chip next to the "source #id" caption on that page (the
 *    title-page boxes display series-title-style names instead). The same
 *    chapter number is frequently uploaded by several groups — the
 *    "Deduplicate chapters" setting keeps one per number × language.
 *  - Pages          GET /chapter/{id} — the reader has no <img> tags; the
 *    page URLs are absolute (`https://iXX.imgXX.org/_f/...`) inside the
 *    `"imageUrls"` qwik/json state. Parsed positionally with a URL regex;
 *    relative `/_f/...` paths (if any) are prefixed with the mirror.
 */
@Source
abstract class XComic :
    HttpSource(),
    ConfigurableSource {

    override val name = "XComic"

    override val supportsLatest = true

    private val preferences = getPreferences()

    /** The active mirror — one of the fixed list or a user-entered custom URL. */
    private val mirror: String
        get() {
            preferences.customMirror()?.let { custom ->
                val trimmed = custom.trim().trimEnd('/')
                if (trimmed.startsWith("https://") && trimmed.toHttpUrlOrNull() != null) return trimmed
            }
            return MIRRORS.getOrElse(preferences.mirrorIndex()) { MIRRORS.first() }
        }

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", CHROME_UA)
        .set("Referer", "$mirror/")

    // A chapter refresh fetches one /source/{id} page PER source group — a
    // big series can have 40+ groups (the page state's complete list). Keep
    // that burst polite. (lib-1.4 HttpSource has no configureClient hook —
    // the client itself is overridden, same as Comix.)
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(5)
        .build()

    /** Latest pagination state: the site paginates /latest with a `before`
     * cursor (oldest release timestamp of the page), so the cursor handed
     * back by page N's parse is stored for page N+1. Mihon calls browse
     * requests strictly sequentially, so a small map is sufficient.
     */
    private var latestRequestedPage = 1
    private val latestCursors = HashMap<Int, String>()

    /** Client-side search selections, stashed by [searchMangaRequest] and
     * consumed by [searchMangaParse] (the site applies these panels in JS). */
    private var activeTypes: Set<String> = emptySet()
    private var activeContents: Set<String> = emptySet()
    private var activeStatuses: Set<String> = emptySet()
    private var activeDemographics: Set<String> = emptySet()

    // ========================================================================
    // Browse / search
    // ========================================================================

    override fun popularMangaRequest(page: Int): Request = GET("$mirror/search?word=&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = popularParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        latestRequestedPage = page
        val cursor = latestCursors[page]
        return GET(if (cursor.isNullOrBlank()) "$mirror/latest" else "$mirror/latest?before=$cursor", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // The page's own "Older →" link carries the next cursor: the oldest
        // release timestamp on this page. No link = last page.
        document.selectFirst("a[href^=/latest?before=]")?.attr("href")
            ?.substringAfter("before=")?.substringBefore("&")
            ?.takeIf { it.isNotBlank() }
            ?.let { latestCursors[latestRequestedPage + 1] = it }

        val mangas = parseTitleCards(document)
        return MangasPage(mangas, latestCursors.containsKey(latestRequestedPage + 1))
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // The site's ONLY server-side search lever (verified live: field_update
        // and field_name_* reorder the SSR cards; everything else in the
        // filter panel is client-side-only).
        val sortFilter = filters.firstOrNull { it is SortByFilter } as? SortByFilter
        val sortValue = SORT_VALUES.getOrElse(sortFilter?.state ?: 0) { SORT_VALUES.first() }

        // The remaining panels are applied client-side in [searchMangaParse]
        // over the same chip data the site's own JS reads.
        activeTypes = filters.firstInstance<TypeFilter>()?.state
            ?.filter { it.state }?.mapTo(mutableSetOf()) { it.value.lowercase(Locale.ROOT) } ?: emptySet()
        activeContents = filters.firstInstance<ContentRatingFilter>()?.state
            ?.filter { it.state }?.mapTo(mutableSetOf()) { it.value.lowercase(Locale.ROOT) } ?: emptySet()
        activeStatuses = filters.firstInstance<StatusFilter>()?.state
            ?.filter { it.state }?.mapTo(mutableSetOf()) { it.value.lowercase(Locale.ROOT) } ?: emptySet()
        activeDemographics = filters.firstInstance<DemographicFilter>()?.state
            ?.filter { it.state }?.mapTo(mutableSetOf()) { it.value } ?: emptySet()

        return GET("$mirror/search?word=$encoded&page=$page&sortby=$sortValue", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val cards = parseCards(response.asJsoup())
        val hasNext = cards.size >= 40

        val mangas = cards
            .filter { card ->
                (activeTypes.isEmpty() || card.type.lowercase(Locale.ROOT) in activeTypes) &&
                    (activeContents.isEmpty() || card.contentRating.lowercase(Locale.ROOT) in activeContents) &&
                    (activeStatuses.isEmpty() || card.status.lowercase(Locale.ROOT) in activeStatuses) &&
                    (activeDemographics.isEmpty() || card.demographics.any { it in activeDemographics })
            }
            .map { it.manga }

        return MangasPage(mangas, hasNext)
    }

    /** First filter of a type in the list. */
    private inline fun <reified T : Filter<*>> FilterList.firstInstance(): T? = filterIsInstance<T>().firstOrNull()

    /** Search/popular listings: 48 cards per page; the size threshold doubles
     * as the hasNext heuristic (the pages have no server-side next link).
     */
    private fun popularParse(response: Response): MangasPage {
        val mangas = parseTitleCards(response.asJsoup())
        return MangasPage(mangas, mangas.size >= 40)
    }

    /** Server-rendered cards (shared by search and latest): cover link
     * `a[href^="/title/"]:has(img)` — each series card renders the link
     * twice (cover + heading), so dedupe by href.
     */
    private fun parseTitleCards(document: Document): List<SManga> = parseCards(document).map { it.manga }

    /** One listing card plus the chip metadata the site's own filter panels
     * read (type · content rating · year · status · genre/tag chips).
     */
    private class CardData(
        val manga: SManga,
        val type: String,
        val contentRating: String,
        val status: String,
        val demographics: Set<String>,
    )

    private fun parseCards(document: Document): List<CardData> {
        val coverLinks = document.select("a[href^=/title/]").filter { it.selectFirst("img") != null }
        val seen = mutableSetOf<String>()
        return coverLinks.mapNotNull { coverLink ->
            val href = coverLink.attr("href").substringBefore("?")
            if (!seen.add(href)) return@mapNotNull null
            val title = coverLink.selectFirst("img")?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() }
                ?: coverLink.parent()?.selectFirst("a.link-pri")?.text()?.trim()
                ?: href.substringAfterLast('/')
            val manga = SManga.create().apply {
                url = href.removePrefix("/title/").trim('/')
                this.title = title
                thumbnail_url = coverLink.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
                    ?: coverLink.selectFirst("img")?.attr("src")?.let { src ->
                        when {
                            src.startsWith("http") -> src
                            else -> mirror + src
                        }
                    }
            }

            // Chip row: 🇨🇳 Manhua · Safe · 2017 · completed · tags… — the
            // type span directly follows the flag emoji span; the content
            // rating and status are small-caps spans (the rating one lives in
            // a text-success wrapper, the status one after the circle icon).
            val cardRoot = coverLink.parents().firstOrNull { it.hasClass("border-b") }
            val type = cardRoot?.selectFirst("span.font-family-NotoColorEmoji + span")?.text()?.trim().orEmpty()
            val smallCaps = cardRoot?.select("span.font-variant-small-caps")?.map { it.text().trim() }.orEmpty()
            val contentRating = smallCaps.firstOrNull { it.lowercase(Locale.ROOT) in CONTENT_RATINGS }.orEmpty()
            val status = smallCaps.firstOrNull { it.lowercase(Locale.ROOT) in STATUS_WORDS }.orEmpty()
            val cardText = cardRoot?.text()?.lowercase(Locale.ROOT).orEmpty()
            val demographics = DEMOGRAPHIC_TAGS.filterTo(mutableSetOf()) { it.lowercase(Locale.ROOT) in cardText }

            CardData(manga, type, contentRating, status, demographics)
        }
    }

    // ========================================================================
    // Details
    // ========================================================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = manga.url
        return if (url.startsWith("c/")) {
            // Latest-updates entry: chapter page first — its payload carries
            // the series slug (title_id) for the real title page.
            GET("$mirror/chapter/${url.removePrefix("c/")}", headers)
        } else {
            GET("$mirror/title/$url", headers)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        if (response.request.url.encodedPath.startsWith("/chapter/")) {
            val titleSlug = TITLE_ID_REGEX.find(response.body.string())?.groupValues?.get(1)
                ?: throw IOException("XComic: couldn't resolve this chapter's series.")
            val document = client.newCall(GET("$mirror/title/$titleSlug", headers)).execute().use {
                it.asJsoup()
            }
            return titlePageToSManga(document, titleSlug)
        }

        val slug = response.request.url.encodedPath.trim('/').removePrefix("title/")
        return titlePageToSManga(response.asJsoup(), slug)
    }

    private fun titlePageToSManga(document: Document, slug: String): SManga {
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()
        val html = document.outerHtml()

        val title = document.selectFirst("h1 a")?.text()?.trim()
            ?: document.selectFirst("h1")?.ownText()?.trim()
            ?: slug

        // Cover: the site proxies images as /imgproxy/plain/x250@1/<base64 url>;
        // decoding the base64 gives the ORIGINAL full-resolution image.
        val coverRaw = COVER_URL_REGEX.find(html)?.groupValues?.get(1)
        val cover = coverRaw?.let { decodeProxyCover(it) }

        // Authors / artists row: "Authors:" and "Artists:" labels with link
        // siblings in one flex container.
        var authors: String? = null
        var artists: String? = null
        document.select("span.opacity-60").firstOrNull { it.ownText().startsWith("Authors") }
            ?.parent()?.let { row ->
                val authorsList = mutableListOf<String>()
                val artistsList = mutableListOf<String>()
                var current: MutableList<String>? = null
                for (child in row.children()) {
                    val label = child.ownText().trim()
                    when {
                        label.startsWith("Authors") -> current = authorsList
                        label.startsWith("Artists") -> current = artistsList
                        child.tagName() == "a" && current != null -> current += child.text().trim()
                    }
                }
                authors = authorsList.distinct().joinToString(", ").ifBlank { null }
                artists = artistsList.distinct().joinToString(", ").ifBlank { null }
            }

        // Description: the block right before the authors row; fallback to
        // the meta description.
        val authorsRow = document.select("span.opacity-60")
            .firstOrNull { it.ownText().startsWith("Authors") }?.parent()
        val description = authorsRow?.previousElementSibling()?.text()?.trim()
            ?.takeIf { it.length > 40 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // Header info row: flag (original language) / type / content rating /
        // year / status / genre+tag chips. Stats row separately: star rating
        // (+ user count) and follows.
        var type: String? = null
        var year: String? = null
        var statusText: String? = null
        val contentRatings = mutableListOf<String>()
        val chips = mutableListOf<String>()
        document.selectFirst("div.space-y-3")?.let { infoBlock ->
            var statusSeen = false
            for (span in infoBlock.select("span")) {
                val text = span.text().trim()
                when {
                    text.isEmpty() -> {}
                    span.hasClass("font-family-NotoColorEmoji") -> {}
                    type == null && text in TYPE_WORDS -> type = text
                    text.matches(Regex("""\d{4}""")) && year == null -> year = text
                    text.lowercase() in CONTENT_RATINGS -> {
                        contentRatings += text.replaceFirstChar { it.uppercase(Locale.ROOT) }
                    }
                    !statusSeen && text.lowercase() in STATUS_WORDS -> {
                        statusText = text
                        statusSeen = true
                    }
                    text in TYPE_WORDS -> {}
                    statusSeen && span.hasClass("whitespace-nowrap") -> chips += text
                }
            }
        }

        // Rating: the span right after the star icon span ("6.9"), with the
        // "by N users" count following it. Follows: span whose NEXT sibling
        // label reads "follows".
        var rating: String? = null
        var ratingUsers: String? = null
        var follows: String? = null
        for (span in document.select("span")) {
            if (span.selectFirst("i[name=star]") != null) {
                val value = span.nextElementSibling()?.text()?.trim()
                if (!value.isNullOrBlank()) {
                    rating = value
                    ratingUsers = span.nextElementSibling()
                        ?.nextElementSibling()?.text()?.trim()
                        ?.substringAfter("by ")?.substringBefore(" ")
                }
                break
            }
        }
        for (span in document.select("span")) {
            if (span.nextElementSibling()?.ownText() == "follows") {
                follows = span.text().trim().takeIf { it.isNotBlank() }
                break
            }
        }

        // Genres vs tags: the site mixes MangaUpdates-style genres and format
        // tags in one chip row. Known format/tag words can be hidden behind
        // the "Show tags in genres" setting.
        val showTags = preferences.showTagsInGenres()
        val genreChips = chips
            .filter { showTags || it !in TAG_WORDS }
            .distinct()

        // Alt titles live in the qwik state: "alt_titles",4,[0,"a",0,"b",…]
        val altTitles = ALT_TITLES_REGEX.find(html)?.groupValues?.get(1)
            ?.let { segment -> STRING_REGEX.findAll(segment).map { it.groupValues[1] }.toList() }
            .orEmpty()

        // Comix-style score stars: "★★★★☆ 6.9 (by 41 users)". The site's
        // score is 10-point, so half of it is the number of full stars out of
        // 5 (same math as the Comix extension).
        val scoreLine = rating?.trim()?.let { ratingText ->
            val score = ratingText.toFloatOrNull() ?: return@let null
            val fullStars = (score / 2f).toInt().coerceIn(0, 5)
            buildString {
                append("★".repeat(fullStars))
                append("☆".repeat(5 - fullStars))
                append(' ').append(ratingText)
                if (!ratingUsers.isNullOrBlank()) {
                    append(" (by ").append(ratingUsers).append(" users)")
                }
            }
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                if (!statusText.isNullOrBlank()) {
                    append("**Status:** ").append(statusText!!.replaceFirstChar { it.uppercase(Locale.ROOT) })
                }
                if (year != null) {
                    if (isNotEmpty()) append(" · ")
                    append("**Year:** ").append(year)
                }
                if (type != null) {
                    if (isNotEmpty()) append(" · ")
                    append("**Type:** ").append(type)
                }
                if (follows != null) {
                    if (isNotEmpty()) append(" · ")
                    append("**Follows:** ").append(follows)
                }
                if (contentRatings.isNotEmpty() && contentRatings.singleOrNull() != "Safe") {
                    if (isNotEmpty()) append(" · ")
                    append("**Content:** ").append(contentRatings.joinToString("/"))
                }
            }.ifBlank { null }
        } else {
            null
        }

        val desc = buildString {
            // Score stars first (Comix-style), then the info line, then the
            // description body.
            if (scoreLine != null) {
                append(scoreLine)
                append("\n\n")
            }
            if (infoLine != null) {
                append(infoLine)
                append("\n\n")
            }
            append(description.orEmpty())
            if (showAltNames && altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }
        }.trim()

        return SManga.create().apply {
            url = slug
            this.title = title
            this.author = authors
            this.artist = artists
            this.description = desc.ifBlank { null }
            this.genre = genreChips.joinToString(", ").ifBlank { null }
            this.thumbnail_url = cover
            this.status = statusStringToSManga(statusText)
            this.initialized = true
        }
    }

    /** `"cover_url",0,"https://cdn.mangabaka.dev/imgproxy/plain/x250@1/<b64>"` → original URL. */
    private fun decodeProxyCover(url: String): String {
        val encoded = url.substringAfterLast('/')
        return runCatching {
            val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            decoded.takeIf { it.startsWith("http") } ?: url
        }.getOrDefault(url)
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

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        // A details response for an rss "c/{id}" entry is the TITLE page
        // (mangaDetailsParse followed the hop); read the slug off the final URL.
        val titleSlug = response.request.url.encodedPath
            .trim('/').removePrefix("title/").substringBefore('?')
            .ifBlank { throw IOException("XComic: unexpected chapter list response.") }

        val html = response.body.string()
        val document = Jsoup.parse(html, mirror)
        val preferredLang = preferences.chapterLanguage()

        // The title page renders ONE box per VISIBLE source group, each with
        // only that group's LATEST chapter. Collect (language, source id,
        // display name) from the boxes…
        data class Group(val lang: String, val sourceId: String, val domName: String)

        val groups = document.select("div.border.border-base-300").mapNotNull { groupBox ->
            val flag = groupBox.selectFirst("span.font-family-NotoColorEmoji")?.text()?.trim().orEmpty()
            val lang = flagToLanguage(flag)
            val sourceId = groupBox.selectFirst("a[href^=/source/]")?.attr("href")
                ?.trim('/')?.removePrefix("source/")?.substringBefore('?')
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sourceName = groupBox.selectFirst("a[href^=/source/]")?.text()?.trim().orEmpty()
            Group(lang, sourceId, sourceName)
        }

        // …then reconcile with the page state: its `comic_ids` array is the
        // site's COMPLETE source list for the title, and the Sources grid
        // only renders a subset of it (adult/sfw-filtered sources stay
        // hidden from the DOM while their /source/{id} pages — and chapters
        // — stay live). The hidden ids are fetched like any other group.
        val renderedIds = groups.mapTo(mutableSetOf()) { it.sourceId }
        val hiddenIds = hiddenSourceIds(html, renderedIds)

        if (groups.isEmpty() && hiddenIds.isEmpty()) {
            throw IOException("XComic: couldn't read the source groups on this series page (layout change?).")
        }

        val selected = groups
            .filter { preferredLang == "all" || it.lang == preferredLang }
            .ifEmpty {
                // The requested language has no group on this series — fall
                // back to everything rather than returning an empty list.
                groups
            }

        // One /source/{id} page per group carries the group's FULL chapter
        // list, its flag (the only language signal for hidden groups) and
        // the REAL group name (the badge chip next to the "source #id"
        // caption — the title-page boxes display series-title-style names
        // instead). Hidden groups must be fetched to learn their language;
        // they are filtered afterwards.
        val renderedFetches = selected.map { Triple(it.sourceId, it.lang, it.domName) }
        val candidates = renderedFetches.map { it.first } + hiddenIds

        val pagesById: Map<String, SourcePageData> = if (candidates.size == 1) {
            mapOf(candidates.first() to fetchSourcePage(candidates.first()))
        } else {
            // Overlap the ~0.5-1 s per-group page latency instead of stacking
            // it (language "all" on a multi-group series used to serialize up
            // to ten sequential fetches; a big series can have 40+ groups).
            runBlocking {
                candidates.distinct()
                    .map { id -> async(Dispatchers.IO) { id to fetchSourcePage(id) } }
                    .awaitAll()
                    .toMap()
            }
        }

        data class FinalGroup(val lang: String, val sourceId: String, val groupName: String?)

        val finalGroups = buildList {
            for ((sourceId, boxLang, domName) in renderedFetches) {
                val page = pagesById[sourceId] ?: continue
                val lang = boxLang.ifBlank { page.lang }
                add(FinalGroup(lang, sourceId, page.groupName ?: domName.ifBlank { null }))
            }
            for (sourceId in hiddenIds) {
                val page = pagesById[sourceId] ?: continue
                if (preferredLang != "all" && page.lang != preferredLang) continue
                if (page.rows.isEmpty()) continue
                add(FinalGroup(page.lang, sourceId, page.groupName))
            }
        }

        val dedupe = preferences.deduplicateChapters()

        val chapters = mutableListOf<SChapter>()
        val seenNumbers = mutableSetOf<String>()

        for (group in finalGroups) {
            val rows = pagesById[group.sourceId]?.rows ?: continue
            for ((chapterId, name, dateMs) in rows) {
                val number = chapterNumberOf(name)
                // With "Deduplicate chapters" ON the groups merge in site
                // order (the site's own ranking puts the primary group
                // first) and one row per number × language survives: the
                // first group carrying a number wins, later copies of that
                // number collapse. Rows with an unparseable number are
                // never deduped against each other. OFF = every selected
                // group's full list.
                val dedupeKey = "$number|${group.lang}"
                if (dedupe && number > 0f && !seenNumbers.add(dedupeKey)) continue

                chapters += SChapter.create().apply {
                    url = "$chapterId|$titleSlug"
                    this.name = buildString {
                        append(name.ifBlank { "Chapter" })
                        if (preferredLang == "all" && group.lang.isNotBlank()) {
                            append(" [")
                            append(group.lang)
                            append("]")
                        }
                    }
                    chapter_number = number
                    date_upload = dateMs
                    scanlator = group.groupName?.ifBlank { null }
                }
            }
        }

        if (chapters.isEmpty()) {
            throw IOException(
                "XComic: no chapters found" +
                    (if (preferredLang != "all") " for language \"$preferredLang\"" else "") +
                    ". Try another chapter language in the extension settings.",
            )
        }

        return chapters.sortedWith(
            compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload },
        )
    }

    /**
     * The chapter number inside a label. The number that follows a chapter
     * word ("Chapter 12", "Ch.12", "Episode 7", "Cap. 5") always wins —
     * labels like "Chapter 48 - S2 START" used to parse as chapter 2 (the
     * last number token) and land between chapters 2 and 3. Without a
     * chapter word, the last number NOT glued to a letter is taken
     * ("One Punch 98" → 98; "S2" alone → no number).
     */
    private fun chapterNumberOf(label: String): Float {
        CHAPTER_WORD_NUMBER_REGEX.find(label)?.groupValues?.get(1)?.let { token ->
            return token.replace(',', '.').toFloatOrNull() ?: -1f
        }
        return NUMBER_TOKEN_REGEX.findAll(label)
            .lastOrNull()?.value
            ?.replace(',', '.')?.toFloatOrNull()
            ?: -1f
    }

    /**
     * One /source/{id} page: the group's full chapter list as (chapter id,
     * label, epoch millis) triples, the page flag's language, and the real
     * group-name badge.
     */
    private class SourcePageData(
        val rows: List<Triple<String, String, Long>>,
        val lang: String,
        val groupName: String?,
    )

    private fun fetchSourcePage(sourceId: String): SourcePageData {
        sourcePageCache[sourceId]?.let { return it }

        val data = runCatching {
            client.newCall(GET("$mirror/source/$sourceId", headers)).execute().use { pageResponse ->
                if (!pageResponse.isSuccessful) return@runCatching SourcePageData(emptyList(), "", null)
                val pageDocument = pageResponse.asJsoup()

                val rows = pageDocument.select("a[href^=/chapter/]").map { row ->
                    val href = row.attr("href").trim('/').removePrefix("chapter/").substringBefore('?')
                    // Chapter title suffix lives in a sibling span (": Extra").
                    val suffix = row.nextElementSibling()
                        ?.takeIf { it.tagName() == "span" && !it.hasClass("font-variant-small-caps") }
                        ?.text()?.trim().orEmpty()
                    val name = buildString {
                        append(row.text().trim())
                        if (suffix.isNotEmpty() && !name.endsWith(suffix)) append(suffix)
                    }
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                    // The timestamp lives in a sibling cell of the row container.
                    val dateMs = row.parents()
                        .firstOrNull { it.selectFirst("time[data-time]") != null }
                        ?.selectFirst("time[data-time]")?.attr("data-time")?.toLongOrNull() ?: 0L
                    Triple(href, name, dateMs)
                }

                // The group's language flag sits inside the header heading.
                // (The page also renders the SERIES' original-language flag
                // outside the heading — never fall back to a page-wide flag
                // lookup, it grabs the wrong country.)
                val lang = pageDocument.selectFirst("h3 span.font-family-NotoColorEmoji")
                    ?.text()?.trim()?.let { flagToLanguage(it) }.orEmpty()

                SourcePageData(rows, lang, sourceGroupName(pageDocument))
            }
        }.getOrDefault(SourcePageData(emptyList(), "", null))

        sourcePageCache[sourceId] = data
        return data
    }

    /** The group's REAL name: the badge chip rendered right after the
     * "source #id" caption ("WebToon", "Thunderscans", "Reset Scans"…).
     * The chip carries the rounded-box class — the heading's flag emoji
     * span does not, which keeps a missing badge from resolving to the
     * flag. Absent on badge-less groups; callers fall back to the
     * title-page box display name.
     */
    private fun sourceGroupName(document: Document): String? = document.select("div.font-variant-small-caps")
        .firstOrNull { it.ownText().startsWith("source #") }
        ?.nextElementSibling()
        ?.selectFirst("span.rounded-box")
        ?.text()?.trim()?.takeIf { it.isNotBlank() }

    /** Bounded per-session cache of source-group pages (refresh speed — a
     * big title's full sweep is 40+ pages). */
    private val sourcePageCache = object : LinkedHashMap<String, SourcePageData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourcePageData>): Boolean = size > 64
    }

    /**
     * The title page's qwik state carries `"comic_ids",4,[0,"vgreew",…]` —
     * the site's COMPLETE source list for the title. The Sources grid only
     * renders a subset of it; the ids missing from the DOM are the hidden
     * groups whose /source/{id} pages stay live with full chapter lists.
     * Returns those ids in state order.
     */
    private fun hiddenSourceIds(html: String, renderedIds: Set<String>): List<String> = COMIC_IDS_REGEX.find(html)?.groupValues?.get(1)
        ?.let { segment -> STATE_ID_REGEX.findAll(segment).map { it.groupValues[1] }.toList() }
        ?.filter { it !in renderedIds }
        ?.distinct()
        .orEmpty()

    /** 🇬🇧 → "gb" → "en". Regional-indicator pair → country code → language. */
    private fun flagToLanguage(flag: String): String {
        if (flag.length < 4) return ""
        val codePoints = flag.codePoints().toArray()
        if (codePoints.size != 2) return ""
        val country = codePoints.map { ('a' + (it - 0x1F1E6)) }.joinToString("") { it.toString() }
        return COUNTRY_TO_LANG[country] ?: country
    }

    // ========================================================================
    // Pages
    // ========================================================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$mirror/chapter/${chapter.url.substringBefore('|')}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()

        // The qwik/json state is the only place the page URLs exist (the
        // reader renders zero <img> tags server-side).
        val pageUrls = IMAGE_URL_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .ifEmpty {
                // Fallback: relative /_f/ paths (e.g. mirror-side rewrites).
                RELATIVE_IMAGE_REGEX.findAll(html)
                    .map { mirror + it.groupValues[1] }
                    .distinct()
                    .toList()
            }

        if (pageUrls.isEmpty()) {
            throw IOException("XComic: couldn't read this chapter's pages (layout change?).")
        }

        return pageUrls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageRequest(page: Page) = GET(
        page.imageUrl!!,
        headers.newBuilder().set("Referer", "$mirror/").build(),
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Sort is applied by the site (server-side)."),
        Filter.Header("The other panels match the site's filters (applied by the extension)."),
        Filter.Separator(),
        SortByFilter(),
        TypeFilter(),
        ContentRatingFilter(),
        StatusFilter(),
        DemographicFilter(),
    )

    private class SortByFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Rating Score", "Last Update", "Date Added", "Name A-Z", "Name Z-A"),
        )

    private class CheckBoxFilter(name: String, val value: String, default: Boolean = false) : Filter.CheckBox(name, default)

    private class TypeFilter : Filter.Group<CheckBoxFilter>("Type", TYPE_FILTER_VALUES.map { CheckBoxFilter(it, it) })

    private class ContentRatingFilter : Filter.Group<CheckBoxFilter>("Content rating", CONTENT_FILTER_VALUES.map { CheckBoxFilter(it, it) })

    private class StatusFilter : Filter.Group<CheckBoxFilter>("Status", STATUS_FILTER_VALUES.map { CheckBoxFilter(it, it) })

    private class DemographicFilter : Filter.Group<CheckBoxFilter>("Demographic", DEMOGRAPHIC_TAGS.map { CheckBoxFilter(it, it) })

    // ========================================================================
    // Settings
    // ========================================================================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_MIRROR
            title = "Mirror"
            summary = "All mirrors share the same content and IDs (%s)"
            entries = MIRROR_NAMES
            entryValues = MIRRORS
            setDefaultValue(MIRRORS.first())
            setOnPreferenceChangeListener { _, newValue ->
                summary = "All mirrors share the same content and IDs ($newValue)"
                true
            }
        }.let(screen::addPreference)

        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_MIRROR
            title = "Custom mirror"
            summary = "Full https:// URL — overrides the mirror list when set (e.g. a new or self-hosted mirror)"
            setDefaultValue("")
        }.let(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_CHAPTER_LANGUAGE
            title = "Chapter language"
            summary = "Which source-group language to show (%s)"
            setDefaultValue("all")
            entries = CHAPTER_LANGUAGES.map { it.second }.toTypedArray()
            entryValues = CHAPTER_LANGUAGES.map { it.first }.toTypedArray()
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Which source-group language to show ($newValue)"
                true
            }
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "ON: merge every group and keep one row per chapter " +
                "number per language (the primary group's upload wins; " +
                "later copies collapse). OFF: keep every group's full list"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genres"
            summary = "Include format tags (Long Strip, Full Color…) in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display status, year, type and follows above the description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.mirrorIndex(): Int = MIRRORS.indexOf(getString(PREF_MIRROR, MIRRORS.first())).coerceAtLeast(0)

    private fun android.content.SharedPreferences.customMirror(): String? = getString(PREF_CUSTOM_MIRROR, "")?.takeIf { it.isNotBlank() }

    private fun android.content.SharedPreferences.chapterLanguage(): String = getString(PREF_CHAPTER_LANGUAGE, "all") ?: "all"

    private fun android.content.SharedPreferences.deduplicateChapters(): Boolean = getBoolean(PREF_DEDUPLICATE_CHAPTERS, true)

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showTagsInGenres(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    companion object {
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private val MIRRORS = arrayOf(
            "https://xcomic.me",
            "https://xcomic.net",
            "https://comik.to",
            "https://yona.to",
        )

        private val MIRROR_NAMES = arrayOf(
            "xcomic.me (default)",
            "xcomic.net",
            "comik.to",
            "yona.to",
        )

        private const val PREF_MIRROR = "pref_mirror"
        private const val PREF_CUSTOM_MIRROR = "pref_custom_mirror"
        private const val PREF_CHAPTER_LANGUAGE = "pref_chapter_language"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"

        /** Server-side `sortby` values, in the order the site offers them. */
        private val SORT_VALUES = arrayOf(
            "field_score",
            "field_update",
            "field_create",
            "field_name_asc",
            "field_name_desc",
        )

        private val CHAPTER_LANGUAGES = listOf(
            "all" to "All languages",
            "en" to "English",
            "ar" to "Arabic",
            "fr" to "French",
            "pt-br" to "Portuguese (Brazil)",
            "es" to "Spanish",
            "ru" to "Russian",
            "vi" to "Vietnamese",
            "it" to "Italian",
            "tr" to "Turkish",
            "id" to "Indonesian",
            "pl" to "Polish",
            "fa" to "Persian",
            "hu" to "Hungarian",
            "sk" to "Slovak",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "de" to "German",
        )

        private val COUNTRY_TO_LANG = mapOf(
            "gb" to "en", "us" to "en", "au" to "en", "ca" to "en", "nz" to "en",
            "sa" to "ar", "fr" to "fr", "br" to "pt-br", "pt" to "pt",
            "mx" to "es", "ar" to "es", "co" to "es", "cl" to "es", "es" to "es",
            "ru" to "ru", "vn" to "vi", "it" to "it", "tr" to "tr", "id" to "id",
            "jp" to "ja", "kr" to "ko", "cn" to "zh", "tw" to "zh", "hk" to "zh",
            "de" to "de", "pl" to "pl", "th" to "th", "nl" to "nl",
            "ir" to "fa", "hu" to "hu", "sk" to "sk", "cz" to "cs",
            "ua" to "uk", "se" to "sv", "dk" to "da", "no" to "no",
            "fi" to "fi", "ro" to "ro", "bg" to "bg", "il" to "he", "gr" to "el",
        )

        private val TYPE_WORDS = setOf("Manga", "Manhwa", "Manhua", "Comic", "Webtoon", "OEL", "One-shot")

        private val STATUS_WORDS = setOf("ongoing", "releasing", "hiatus", "completed", "cancelled", "canceled", "dropped", "upcoming", "unknown")

        private val CONTENT_RATINGS = setOf("safe", "suggestive", "erotica", "pornographic")

        /** Search-filter panel values (chip vocabulary on the site's cards). */
        private val TYPE_FILTER_VALUES = listOf(
            "Manga",
            "Manhua",
            "Manhwa",
            "Cartoon",
            "Imageset",
            "OEL",
            "Other",
            "Western",
        )

        private val CONTENT_FILTER_VALUES = listOf("Safe", "Suggestive", "Erotica", "Pornographic")

        private val STATUS_FILTER_VALUES = listOf(
            "Releasing",
            "Completed",
            "Hiatus",
            "Cancelled",
            "Upcoming",
            "Unknown",
        )

        /** MangaUpdates-style demographic chips the cards carry as tags. */
        private val DEMOGRAPHIC_TAGS = listOf(
            "Shounen(B)", "Shoujo(G)", "Seinen(M)", "Josei(W)", "Kodomo(Kid)",
            "Silver & Golden", "Non-human", "Male Oriented", "Female Oriented",
        )

        /**
         * MangaUpdates-style format/tag words the site mixes into the genre
         * chip row — hiding them (settings) leaves the actual genres.
         */
        private val TAG_WORDS = setOf(
            "Long Strip", "Full Color", "Web Comic", "Webtoon", "1-Koma", "4-Koma",
            "Oneshot", "One-shot", "Doujinshi", "Colored", "Official Colored",
            "Fan Colored", "Self-Published", "Series", "Adaptation", "Anthology",
            "Award Winning", "High Quality", "Gore", "Smut", "Video Game",
            "Time Loop", "Nitro+", "Harmony", "Ironic", "Broadcast", "Hardcore",
        )

        /** `"title_id",0,"{slug}"` in the reader qwik/json state. */
        private val TITLE_ID_REGEX = Regex(""""title_id",0,"([a-z0-9]+)"""")

        private val COVER_URL_REGEX = Regex(""""cover_url",0,"([^"]+)"""")

        private val ALT_TITLES_REGEX = Regex(""""alt_titles",4,\[(.*?)\]""")

        private val STRING_REGEX = Regex(""""((?:[^"\\]|\\.)*)"""")

        /** The number right after a chapter word ("Chapter 12", "Ch.12",
         * "Episode 7", "Cap. 5", "Capítulo 3,5"). */
        private val CHAPTER_WORD_NUMBER_REGEX = Regex(
            """(?:chapter|chap|ch|episode|ep|capítulo|capitulo|cap)\s*\.?\s*(\d+(?:[.,]\d+)?)""",
            RegexOption.IGNORE_CASE,
        )

        /** Numbers NOT glued to a letter ("S2" is excluded, "#98" isn't). */
        private val NUMBER_TOKEN_REGEX = Regex("""(?<![A-Za-z])\d+(?:[.,]\d+)?""")

        /** `"comic_ids",4,[0,"vgreew",0,"82xv6a",…]` — the title page's
         * qwik state carries the site's COMPLETE source list there, while
         * the rendered Sources grid hides a subset of it. */
        private val COMIC_IDS_REGEX = Regex(""""comic_ids",4,\[(.*?)\]""")

        /** Quoted lowercase id tokens inside the comic_ids array. */
        private val STATE_ID_REGEX = Regex(""""([a-z0-9]{3,10})""")

        /** Absolute page URLs in the reader's qwik state (iXX.imgXX.org and friends). */
        private val IMAGE_URL_REGEX = Regex("""(https://[a-zA-Z0-9.\-]+/_f/[a-zA-Z0-9./_\-]+)""")

        private val RELATIVE_IMAGE_REGEX = Regex("""(/_f/[a-zA-Z0-9./_\-]+)""")
    }
}
