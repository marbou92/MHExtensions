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
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
 *    search = the query. The site's sort/filter tabs are client-side only.
 *  - Latest         GET /rss/latest.xml — 100 newest chapter releases with
 *    series title, cover enclosure and chapter id. Series URLs resolve on
 *    details open (the reader payload carries `title_id`).
 *  - Details        GET /title/{id6}; selectors below.
 *  - Chapters       embedded in the title page: group boxes
 *    (`div.border.border-base-300`) each with a flag-emoji span
 *    (`span.font-family-NotoColorEmoji`), a `/source/{id}` group link and
 *    rows `a[href^="/chapter/"]` + `time[data-time]` (epoch millis).
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

    // ========================================================================
    // Browse / search
    // ========================================================================

    override fun popularMangaRequest(page: Int): Request = GET("$mirror/search?word=&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = searchCardParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$mirror/rss/latest.xml", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = rssLatestParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$mirror/search?word=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = searchCardParse(response)

    /** Server-rendered search cards: cover link `a[href^="/title/"]:has(img)`. */
    private fun searchCardParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val coverLinks = document.select("a[href^=/title/]").filter { it.selectFirst("img") != null }
        val seen = mutableSetOf<String>()
        val mangas = coverLinks.mapNotNull { coverLink ->
            val href = coverLink.attr("href").substringBefore("?")
            if (!seen.add(href)) return@mapNotNull null
            val title = coverLink.selectFirst("img")?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() }
                ?: coverLink.parent()?.selectFirst("a.link-pri")?.text()?.trim()
                ?: href.substringAfterLast('/')
            SManga.create().apply {
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
        }
        // 48 cards per page; size threshold doubles as the hasNext heuristic.
        return MangasPage(mangas, mangas.size >= 40)
    }

    /** Chapter-level RSS → series cards (cover from the enclosure). */
    private fun rssLatestParse(response: Response): MangasPage {
        val xml = response.body.string()
        val mangas = ITEM_REGEX.findAll(xml).mapNotNull { match ->
            val item = match.value
            val chapterId = GUID_REGEX.find(item)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawTitle = XML_TITLE_REGEX.find(item)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null

            // "🇬🇧 Series Name - Volume 1 Chapter 2" → "Series Name"
            val seriesTitle = rawTitle
                .replace(FLAG_REGEX, "")
                .trim()
                .replace(Regex("""\s*-\s*(Volume\s*\d+\s*)?[Cc]h(apter)?\.?\s*[\dvolVOL.\s]*$"""), "")
                .trim()
                .ifBlank { rawTitle }

            val cover = ENCLOSURE_REGEX.find(item)?.groupValues?.get(1)

            SManga.create().apply {
                // Resolved to the real title slug when details are fetched.
                url = "c/$chapterId"
                title = seriesTitle
                thumbnail_url = cover
            }
        }.toList()
        return MangasPage(mangas, false)
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

        // Header info line: type / rating / year / status / genres.
        var type: String? = null
        var year: String? = null
        var statusText: String? = null
        val genres = mutableListOf<String>()
        document.selectFirst("div.space-y-3")?.let { infoBlock ->
            var statusSeen = false
            for (span in infoBlock.select("span")) {
                val text = span.text().trim()
                when {
                    text.isEmpty() -> {}
                    span.hasClass("font-family-NotoColorEmoji") -> {}
                    type == null && text in TYPE_WORDS -> type = text
                    text.toDoubleOrNull() != null -> {} // rating
                    text.matches(Regex("""\d{4}""")) && year == null -> year = text
                    !statusSeen && text.lowercase() in STATUS_WORDS -> {
                        statusText = text
                        statusSeen = true
                    }
                    text in TYPE_WORDS -> {}
                    text.lowercase() in CONTENT_RATINGS -> {}
                    statusSeen && span.hasClass("whitespace-nowrap") -> genres += text
                }
            }
        }

        // Alt titles live in the qwik state: "alt_titles",4,[0,"a",0,"b",…]
        val altTitles = ALT_TITLES_REGEX.find(html)?.groupValues?.get(1)
            ?.let { segment -> STRING_REGEX.findAll(segment).map { it.groupValues[1] }.toList() }
            .orEmpty()

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
            }.ifBlank { null }
        } else {
            null
        }

        val desc = buildString {
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
            this.genre = genres.distinct().joinToString(", ").ifBlank { null }
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

        val document = response.asJsoup()
        val preferredLang = preferences.chapterLanguage()

        val chapters = mutableListOf<SChapter>()
        for (groupBox in document.select("div.border.border-base-300")) {
            val flag = groupBox.selectFirst("span.font-family-NotoColorEmoji")?.text()?.trim().orEmpty()
            val lang = flagToLanguage(flag)
            if (preferredLang != "all" && lang != preferredLang) continue

            val groupName = groupBox.selectFirst("a[href^=/source/]")?.text()?.trim()?.takeIf { it.isNotBlank() }

            for (row in groupBox.select("a[href^=/chapter/]")) {
                val chapterId = row.attr("href").trim('/').removePrefix("chapter/").substringBefore('?')
                if (chapterId.isBlank()) continue

                val label = row.text().trim()
                val timeEl = row.parent()?.selectFirst("time[data-time]")
                val dateMs = timeEl?.attr("data-time")?.toLongOrNull() ?: parseDate(timeEl?.text())

                chapters += SChapter.create().apply {
                    url = "$chapterId|$titleSlug"
                    name = buildString {
                        append(label.ifBlank { "Chapter" })
                        if (preferredLang == "all" && lang.isNotBlank()) {
                            append(" [")
                            append(lang)
                            append("]")
                        }
                    }
                    chapter_number = CHAPTER_NUMBER_REGEX.findAll(label)
                        .lastOrNull()?.value?.toFloatOrNull() ?: -1f
                    date_upload = dateMs
                    scanlator = resolveScanlator(groupName, lang)
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

        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun resolveScanlator(group: String?, lang: String): String? = group?.takeIf { it.isNotBlank() } ?: lang.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)

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
        Filter.Header("The site's filters (sort, type, genre, language)"),
        Filter.Header("are client-side only — no server support."),
        Filter.Separator(),
    )

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
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display status, year and type above the description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.mirrorIndex(): Int = MIRRORS.indexOf(getString(PREF_MIRROR, MIRRORS.first())).coerceAtLeast(0)

    private fun android.content.SharedPreferences.customMirror(): String? = getString(PREF_CUSTOM_MIRROR, "")?.takeIf { it.isNotBlank() }

    private fun android.content.SharedPreferences.chapterLanguage(): String = getString(PREF_CHAPTER_LANGUAGE, "all") ?: "all"

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    // ========================================================================
    // Helpers
    // ========================================================================

    private val rssDateFormats = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yyyy HH:mm:ss Z",
    ).map { SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        for (format in rssDateFormats) {
            try {
                @Suppress("DEPRECATION")
                return format.parse(raw.trim())?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0L
    }

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
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"

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
        )

        private val TYPE_WORDS = setOf("Manga", "Manhwa", "Manhua", "Comic", "Webtoon", "OEL", "One-shot")

        private val STATUS_WORDS = setOf("ongoing", "hiatus", "completed", "cancelled", "canceled", "dropped")

        private val CONTENT_RATINGS = setOf("safe", "suggestive", "erotica", "pornographic")

        /** `"title_id",0,"{slug}"` in the reader qwik/json state. */
        private val TITLE_ID_REGEX = Regex(""""title_id",0,"([a-z0-9]+)"""")

        private val COVER_URL_REGEX = Regex(""""cover_url",0,"([^"]+)"""")

        private val ALT_TITLES_REGEX = Regex(""""alt_titles",4,\[(.*?)\]""")

        private val STRING_REGEX = Regex(""""((?:[^"\\]|\\.)*)"""")

        private val ITEM_REGEX = Regex("""<item>[\s\S]*?</item>""")

        private val XML_TITLE_REGEX = Regex("""<title>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?</title>""")

        private val GUID_REGEX = Regex("""<guid[^>]*>([^<]+)</guid>""")

        private val ENCLOSURE_REGEX = Regex("""<enclosure[^>]*url="([^"]+)"""")

        private val FLAG_REGEX = Regex("""[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]""")

        private val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")

        /** Absolute page URLs in the reader's qwik state (iXX.imgXX.org and friends). */
        private val IMAGE_URL_REGEX = Regex("""(https://[a-zA-Z0-9.\-]+/_f/[a-zA-Z0-9./_\-]+)""")

        private val RELATIVE_IMAGE_REGEX = Regex("""(/_f/[a-zA-Z0-9./_\-]+)""")
    }
}
