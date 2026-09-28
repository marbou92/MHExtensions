package eu.kanade.tachiyomi.extension.en.weebcentral

import androidx.preference.EditTextPreference
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
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * WeebCentral (https://weebcentral.com) — server-rendered HTMX/Alpine site, no JSON API.
 *
 * Endpoints used (verified live):
 * - Browse/search: GET /search/data (HTML fragment) with text, sort, order, official,
 *   anime, adult, display_mode, limit, offset and multi-value included_status /
 *   included_type / included_tag / excluded_tag parameters. The next-page control is
 *   a trailing button[hx-get] element; its absence (or an empty body) marks the end.
 * - Latest: GET /latest-updates/{page} — same card markup, empty body past the end.
 * - Details: GET /series/{ULID} (the slug part of the URL is optional).
 * - Chapters: GET /series/{ULID}/full-chapter-list — one request returns every row.
 * - Pages: GET /chapters/{ULID}/images?is_prev=False — section#chapter-images img[src].
 *
 * The site sits behind Cloudflare but only challenges non-browser User-Agents, so
 * every request MUST send a desktop Chrome UA (see [USER_AGENT]); no Cloudflare
 * interceptor is needed. Page images are absolute URLs on per-series scan hosts and
 * are fetched verbatim with a Referer back to the site.
 */
@Source
abstract class WeebCentral :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = searchDataRequest(
        page = page,
        sort = SORT_POPULARITY,
        order = ORDER_DESCENDING,
        adult = if (preferences.showNsfw()) ADULT_ANY else ADULT_FALSE,
    )

    override fun popularMangaParse(response: Response): MangasPage = mangasPageParse(response)

    // =============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-updates/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = mangasPageParse(response)

    // =============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()
        val sortValue = sortLabels.getOrElse(sort?.state?.index ?: 0) { SORT_BEST_MATCH }

        val order = filters.firstInstanceOrNull<OrderFilter>()
        val orderValue = orderLabels.getOrElse(order?.state ?: 1) { ORDER_DESCENDING }

        val adult = filters.firstInstanceOrNull<AdultFilter>()
        val adultValue = triStateLabels.getOrElse(adult?.state ?: 2) { ADULT_FALSE }

        val official = filters.firstInstanceOrNull<OfficialFilter>()
        val officialValue = triStateLabels.getOrElse(official?.state ?: 0) { OFFICIAL_ANY }

        val statuses = filters.firstInstanceOrNull<StatusFilter>()
            ?.state?.filter { it.state }?.map { it.value }
            .orEmpty()
        val types = filters.firstInstanceOrNull<TypeFilter>()
            ?.state?.filter { it.state }?.map { it.value }
            .orEmpty()
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()

        return searchDataRequest(
            page = page,
            sort = sortValue,
            order = orderValue,
            adult = adultValue,
            official = officialValue,
            text = query,
            statuses = statuses,
            types = types,
            includedTags = genres.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value },
            excludedTags = genres.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value },
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = mangasPageParse(response)

    /**
     * Builds a GET /search/data fragment request. [sort]/[order]/[adult]/[official] take
     * the site's literal parameter values; the multi-value filters are emitted as
     * repeated query parameters (included_status=...&included_status=...).
     */
    private fun searchDataRequest(
        page: Int,
        sort: String,
        order: String,
        adult: String,
        official: String = OFFICIAL_ANY,
        text: String = "",
        statuses: List<String> = emptyList(),
        types: List<String> = emptyList(),
        includedTags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
    ): Request {
        val builder = "$baseUrl/search/data".toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", order)
            .addQueryParameter("official", official)
            .addQueryParameter("anime", ANIME_ANY)
            .addQueryParameter("adult", adult)
            .addQueryParameter("display_mode", DISPLAY_MODE_FULL)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())

        statuses.forEach { builder.addQueryParameter("included_status", it) }
        types.forEach { builder.addQueryParameter("included_type", it) }
        includedTags.forEach { builder.addQueryParameter("included_tag", it) }
        excludedTags.forEach { builder.addQueryParameter("excluded_tag", it) }

        return GET(builder.build(), headers)
    }

    /**
     * Shared parse for /search/data fragments and /latest-updates pages: both render
     * article cards (Full Display nests an inner article for the cover) and end with
     * a button[hx-get] "next page" element unless the list is exhausted.
     */
    private fun mangasPageParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = parseMangaCards(document)
        val hasNext = mangas.isNotEmpty() &&
            document.select("button[hx-get]").last()?.attr("hx-get")?.isNotBlank() == true
        return MangasPage(mangas, hasNext)
    }

    private fun parseMangaCards(document: Document): List<SManga> = document
        .select("article")
        .filter { card -> card.parents().none { it.tagName() == "article" } }
        .mapNotNull { it.toMangaCard() }

    /**
     * Card parser shared by browse and latest. The series anchor may be relative
     * (latest-updates) or absolute (search fragments); manga.url stores only the ULID
     * segment. Title comes from the line-clamp anchor, the card's data-tip, or the
     * cover's alt text.
     */
    private fun Element.toMangaCard(): SManga? {
        val seriesAnchor = selectFirst("a[href*=\"/series/\"]") ?: return null
        val mangaUrl = seriesAnchor.attr("href").substringAfter("/series/").substringBefore('/').trim()
        if (mangaUrl.isEmpty()) return null

        val mangaTitle = selectFirst("a.line-clamp-1")?.text()?.takeIf { it.isNotBlank() }
            ?: attr("data-tip").takeIf { it.isNotBlank() }
            ?: selectFirst("img[alt]")?.attr("alt")?.removeSuffix(" cover")?.takeIf { it.isNotBlank() }
            ?: mangaUrl

        return SManga.create().apply {
            url = mangaUrl
            title = mangaTitle
            thumbnail_url = coverUrl()
        }
    }

    /** Cover image: prefer the img src (fallback JPG), then the first srcset candidate. */
    private fun Element.coverUrl(): String? = absoluteUrl(selectFirst("img[src]")?.attr("src"))
        ?: selectFirst("source[srcset]")?.attr("srcset")
            ?.split(",")?.firstOrNull()?.trim()?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }

    private fun absoluteUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("http") -> value
            value.startsWith("/") -> "$baseUrl$value"
            else -> value
        }
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/series/${manga.url}", headers)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val mangaUrl = response.request.url.encodedPath
            .substringAfter("/series/")
            .substringBefore('/')

        val mangaTitle = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("title")?.text()?.substringBefore(" - ")
            ?: mangaUrl
        val authors = rowValues(document, "Author(s)")
        val tags = rowValues(document, "Tags(s)", "Tags")
        val type = rowValue(document, "Type")
        val statusLabel = rowValue(document, "Status")
        val year = rowValue(document, "Released")
        val official = rowValue(document, "Official Translation")
        val parsedDescription = rowElement(document, "Description")
            ?.selectFirst("p.whitespace-pre-wrap")?.wholeText()?.trim()
            ?: rowValue(document, "Description")

        val genreChips = buildList {
            type?.let { add(it) }
            if (preferences.showTagsInGenre()) addAll(tags)
        }
            .distinct()
            .filterNot { it.lowercase() in preferences.getBlockedGenres() }
            .joinToString(", ")
            .ifBlank { null }

        // Bold info line appended under the description (Comix style).
        val infoLine = if (preferences.showExtraInfo()) {
            buildString {
                type?.let { append("**Type:** $it") }
                year?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**Year:** $it")
                }
                statusLabel?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**Status:** $it")
                }
                official?.let {
                    if (isNotEmpty()) append(" · ")
                    append("**Official Translation:** $it")
                }
            }.ifBlank { null }
        } else {
            null
        }

        return SManga.create().apply {
            url = mangaUrl
            title = mangaTitle
            author = authors.joinToString(", ").ifBlank { null }
            genre = genreChips
            description = buildString {
                if (infoLine != null && preferences.extraInfoAtTop()) {
                    append(infoLine)
                    if (parsedDescription != null) append("\n\n")
                }
                parsedDescription?.let { append(it) }
                if (infoLine != null && !preferences.extraInfoAtTop()) {
                    if (isNotEmpty()) append("\n\n")
                    append(infoLine)
                }
            }.trim().ifBlank { null }
            status = when (statusLabel?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "complete" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "canceled", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = absoluteUrl(document.selectFirst("picture img[src]")?.attr("src"))
            initialized = true
        }
    }

    /**
     * Details rows are li elements whose first strong holds the label
     * ("Author(s): ", "Tags(s): ", "Type: ", "Status: ", "Released: ", ...).
     */
    private fun rowElement(document: Document, vararg labels: String): Element? = document
        .select("li")
        .firstOrNull { li ->
            val label = li.selectFirst("strong")?.text()?.trim()?.trimEnd(':')?.trim()
                ?: return@firstOrNull false
            labels.any { label.equals(it, ignoreCase = true) }
        }

    /** Single value of a details row: first link, then first span, then own text. */
    private fun rowValue(document: Document, vararg labels: String): String? {
        val row = rowElement(document, *labels) ?: return null
        val value = row.select("a").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?: row.select("span").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?: row.ownText().takeIf { it.isNotBlank() }
        return value?.trim()
    }

    /** Multi-value details rows (authors, tags): link texts, falling back to spans. */
    private fun rowValues(document: Document, vararg labels: String): List<String> {
        val row = rowElement(document, *labels) ?: return emptyList()
        val nodes = row.select("a").ifEmpty { row.select("span") }
        return nodes.mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/series/${manga.url}/full-chapter-list", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a[href*=\"/chapters/\"]")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSChapter() }
    }

    /**
     * Chapter row: a[href="/chapters/{ULID}"] holding the label span
     * ("Chapter 1194" / "Episode 203") and a time[datetime] ISO-8601 date.
     */
    private fun Element.toSChapter(): SChapter? {
        val chapterUrl = attr("href").trim()
        if (!chapterUrl.contains("/chapters/")) return null

        val chapterName = select("span.grow > span").firstOrNull { it.text().isNotBlank() }?.text()
            ?: select("span").firstOrNull { it.ownText().isNotBlank() }?.ownText()
            ?: "Chapter"
        val label = chapterName.replace(Regex("""\s+"""), " ").trim()

        return SChapter.create().apply {
            url = chapterUrl
            name = label
            chapter_number = numberRegex.findAll(label).lastOrNull()?.value?.toFloatOrNull() ?: -1f
            date_upload = parseDate(selectFirst("time[datetime]")?.attr("datetime"))
            scanlator = null
        }
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return dateFormats.firstNotNullOfOrNull { format ->
            runCatching { format.parse(raw)?.time }.getOrNull()
        } ?: 0L
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}/images?is_prev=False", headers)

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = document.select("section#chapter-images img[src]")
            .mapIndexed { index, img -> Page(index, imageUrl = absoluteUrl(img.attr("src"))) }
        if (pages.isEmpty()) throw IOException("No pages found — try opening the chapter in WebView")
        return pages
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
        AdultFilter(),
        OfficialFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            sortLabels,
            Filter.Sort.Selection(0, false),
        )

    private class OrderFilter : Filter.Select<String>("Order", orderLabels, 1)

    private class AdultFilter : Filter.Select<String>("Adult content (NSFW)", triStateLabels, 2)

    private class OfficialFilter : Filter.Select<String>("Official translation", triStateLabels, 0)

    private class StatusFilter :
        Filter.Group<Checkbox>(
            "Status",
            listOf(
                Checkbox("Ongoing", "Ongoing"),
                Checkbox("Complete", "Complete"),
                Checkbox("Hiatus", "Hiatus"),
                Checkbox("Canceled", "Canceled"),
            ),
        )

    private class TypeFilter :
        Filter.Group<Checkbox>(
            "Type",
            listOf(
                Checkbox("Manga", "Manga"),
                Checkbox("Manhwa", "Manhwa"),
                Checkbox("Manhua", "Manhua"),
                Checkbox("OEL", "OEL"),
            ),
        )

    /** Tri-state genres: tap once to include, twice to exclude (included_tag/excluded_tag). */
    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            GENRES.map { GenreTriState(it, it) },
        )

    private class GenreTriState(name: String, val value: String) : Filter.TriState(name)

    private class Checkbox(name: String, val value: String, default: Boolean = false) : Filter.CheckBox(name, default)

    private inline fun <reified T : Filter<*>> FilterList.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    // ========================================================================
    // Settings / Preferences
    // ========================================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_NSFW
            title = "Show NSFW content"
            summary = "Include adult series in Popular and Latest browse; when off, adult content is filtered out. " +
                "Search results follow the \"Adult content\" filter"
            setDefaultValue(false)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, year, status and official translation in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_EXTRA_INFO_POSITION
            title = "Extra info position"
            summary = "Where the extra info line appears (%s)"
            entries = arrayOf("Above the description", "Below the description")
            entryValues = arrayOf("top", "below")
            setDefaultValue("below")
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Where the extra info line appears ($newValue)"
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genre chips"
            summary = "Include the tag list in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.showNsfw(): Boolean = getBoolean(PREF_SHOW_NSFW, false)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.extraInfoAtTop(): Boolean = getString(PREF_EXTRA_INFO_POSITION, "below") == "top"

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.getBlockedGenres(): List<String> = getString(PREF_BLOCKED_GENRES, "")
        ?.split(",")
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val PAGE_SIZE = 32

        private const val SORT_BEST_MATCH = "Best Match"
        private const val SORT_POPULARITY = "Popularity"
        private const val ORDER_DESCENDING = "Descending"
        private const val ADULT_ANY = "Any"
        private const val ADULT_FALSE = "False"
        private const val OFFICIAL_ANY = "Any"
        private const val ANIME_ANY = "Any"
        private const val DISPLAY_MODE_FULL = "Full Display"

        private const val PREF_SHOW_NSFW = "pref_show_nsfw"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_EXTRA_INFO_POSITION = "pref_extra_info_position"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"

        private val numberRegex = Regex("""\d+(?:\.\d+)?""")

        private val sortLabels = arrayOf(
            "Best Match",
            "Alphabet",
            "Popularity",
            "Subscribers",
            "Recently Added",
            "Latest Updates",
        )

        private val orderLabels = arrayOf("Ascending", "Descending")

        private val triStateLabels = arrayOf("Any", "True", "False")

        private val GENRES = listOf(
            "Action",
            "Adult",
            "Adventure",
            "Comedy",
            "Doujinshi",
            "Drama",
            "Ecchi",
            "Fantasy",
            "Gender Bender",
            "Harem",
            "Hentai",
            "Historical",
            "Horror",
            "Isekai",
            "Josei",
            "Lolicon",
            "Martial Arts",
            "Mature",
            "Mecha",
            "Mystery",
            "Psychological",
            "Romance",
            "School Life",
            "Sci-fi",
            "Seinen",
            "Shotacon",
            "Shoujo",
            "Shoujo Ai",
            "Shounen",
            "Shounen Ai",
            "Slice of Life",
            "Smut",
            "Sports",
            "Supernatural",
            "Tragedy",
            "Yaoi",
            "Yuri",
            "Other",
        )

        private val dateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        ).apply {
            forEach { it.timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}
