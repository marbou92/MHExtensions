package eu.kanade.tachiyomi.extension.en.mangago

import android.content.SharedPreferences
import android.webkit.WebResourceResponse
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.runWebView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * MangaGo (www.mangago.me).
 *
 * Classic server-rendered HTML site:
 * - browse: /genre/All/{page}/?f=1&o=1&sortby=... (pic_list cards),
 * - search: /r/l_search/?name=...&page=...,
 * - details: the manga page (status, author + release year, genres,
 *   alternative names, rating + votes, summary),
 * - chapters: full listing in the manga page's #chapter_table,
 * - pages: the reader builds its image list in-JS (encrypted `imgsrcs`
 *   payload + WASM decoder + Cloudflare), so page extraction runs the
 *   reader URL in the repo's window-attached WebView and harvests the
 *   <img> sources the site's own script renders.
 */
@Source
abstract class MangaGo :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(CloudflareSolverInterceptor(setOf("mangago.me")))
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ========================= Popular & Latest ==========================

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/genre/All/$page/?f=1&o=1&sortby=view&e=", headers)

    override fun popularMangaParse(response: Response) = browseParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/genre/All/$page/?f=1&o=1&sortby=update_date&e=", headers)

    override fun latestUpdatesParse(response: Response) = browseParse(response)

    // ============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            return deeplinkHandler(query)
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/r/l_search/".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("page", page.toString())
            .build()

        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        if (genreFilter != null && genreFilter.selected.isNotEmpty()) {
            return GET("$baseUrl/genre/${genreFilter.selected.joinToString(",")}/$page/?f=1&o=1&e=", headers)
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = if (response.request.url.encodedPath.startsWith("/r/l_search")) {
        searchParse(response)
    } else {
        browseParse(response)
    }

    private fun searchParse(response: Response): MangasPage {
        val document = response.asJsoup()
        // 2026-10 layout: #search_list > li > (div.left > a cover) + span.tit > a title.
        // The old div.box wrapper is gone; select the manga link at li level
        // so both layouts parse.
        val mangas = document.select("#search_list li").mapNotNull { li ->
            val a = li.selectFirst("a[href*=/read-manga/]") ?: return@mapNotNull null
            val path = a.attr("href").toHttpUrlOrNull()?.encodedPath ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                url = path
                this.title = title
                thumbnail_url = li.selectFirst("img")?.attr("abs:src")
            }
        }
        return MangasPage(mangas, mangas.size >= 20)
    }

    private fun deeplinkHandler(query: String): Observable<MangasPage> {
        val path = query.toHttpUrlOrNull()?.encodedPath
        if (path == null || !path.startsWith("/read-manga/")) {
            return Observable.just(MangasPage(emptyList(), false))
        }
        return client.newCall(GET("$baseUrl$path", headers))
            .asObservableSuccess()
            .map { MangasPage(listOf(mangaDetailsParse(it)), false) }
    }

    // ============================== Browse ===============================

    private fun browseParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.pic_list div.listitem").mapNotNull { item ->
            val a = item.selectFirst("div.left a[href*=/read-manga/]") ?: return@mapNotNull null
            val path = a.attr("href").toHttpUrlOrNull()?.encodedPath ?: return@mapNotNull null
            SManga.create().apply {
                url = path
                title = item.selectFirst("span.title a")?.attr("title")?.ifBlank { null }
                    ?: item.selectFirst("span.title a")?.text()
                    ?: a.attr("title")
                thumbnail_url = item.selectFirst("img[data-src]")?.attr("abs:data-src")
                    ?: item.selectFirst("img")?.attr("abs:src")
            }
        }
        return MangasPage(mangas, mangas.size >= 20)
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()

        val status = document.select("td").firstOrNull { td ->
            td.selectFirst("label")?.text()?.contains("Status") == true
        }?.ownText()?.ifBlank { null }
            ?: document.select("label:containsOwn(Status) + span, td:has(label:containsOwn(Status)) span")
                .firstOrNull()?.text()?.trim().orEmpty()

        val authorRow = document.select("td").firstOrNull { td ->
            td.selectFirst("label")?.text()?.contains("Author") == true
        }
        val authors = authorRow?.select("a[href*=/r/l_search/]")
            ?.mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .orEmpty()
            .distinct()
        val releaseYear = authorRow?.ownText()?.let { text ->
            Regex("""(\d{4}) released""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        }

        val genres = document.select("td a[href^=/genre/]").mapNotNull {
            it.text().trim().takeIf(String::isNotBlank)
        }.distinct()

        // The alternative row is served in mis-encoded UTF-8 — repair it.
        val altRaw = document.select("td").firstOrNull { td ->
            td.selectFirst("label")?.text()?.contains("Alternative") == true
        }?.ownText()?.trim().orEmpty()
        val altNames = fixMojibake(altRaw)
            .split(";", ",")
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .filter { it.isNotBlank() && !it.equals(title, true) }

        val rating = document.selectFirst("span.rating_num")?.text()?.trim()
            ?.removePrefix("&nbsp;")?.trim()?.toDoubleOrNull()
        val votes = document.select("a[title*=voted]").text()
            .let { Regex("""(\d+)\s*voted""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val summary = document.selectFirst("div.manga_summary")?.wholeText()?.trim().orEmpty()

        val thumbnail = document.selectFirst("div.left.cover img")?.attr("abs:src")

        val infoLine = if (showExtraInfoPref()) {
            buildList {
                releaseYear?.let { add("**Year:** $it") }
                val chapterCount = chapterRows(document).size
                if (chapterCount > 0) add("**Chapters:** $chapterCount")
                votes?.takeIf { it > 0 }?.let { add("**$it ratings**") }
            }.joinToString(" · ")
        } else {
            ""
        }

        val stars = rating?.let { toStars(it) }

        val desc = buildString {
            val position = scorePositionPref()
            if (position == "top" && stars != null) {
                append(stars, "\n")
                if (infoLine.isNotEmpty()) append(infoLine, "\n\n")
            }
            if (position == "none" && infoLine.isNotEmpty()) append(infoLine, "\n\n")

            append(summary)

            if (showAltNamesPref() && altNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                altNames.forEach { append("• $it\n") }
            }

            if (position == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
                if (infoLine.isNotEmpty()) append("\n", infoLine)
            }
        }.trim()

        return SManga.create().apply {
            url = response.request.url.encodedPath
            this.title = title
            author = authors.joinToString(", ").ifBlank { null }
            artist = null
            genre = genres.joinToString(", ").ifBlank { null }
            this.status = when (status.lowercase(Locale.ROOT)) {
                "ongoing" -> SManga.ONGOING
                "complete", "completed", "finished" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "cancelled", "canceled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = thumbnail
            description = desc.ifBlank { null }
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val rows = chapterRows(document)
        if (rows.isEmpty()) throw Exception("No chapters found — open the manga page once to refresh")

        return rows.mapNotNull { row ->
            // 2026-10 layout: <td><a class="chico" href="https://www.mangago.zone/chapter/…">
            // where the host can be any mirror (mangago.zone, youhim.me, …).
            // Legacy layout: <a href*=read-manga> on mangago.me itself.
            val a = row.selectFirst("a.chico")
                ?: row.selectFirst("td a[href*=chapter]")
                ?: row.selectFirst("a[href*=read-manga]")
                ?: return@mapNotNull null

            // Keep the ABSOLUTE url - chapters are served from mirror hosts
            // now, and prepending $baseUrl would point at a dead address.
            val link = a.attr("abs:href").ifBlank { a.attr("href") }
            val chapterUrl = when {
                link.startsWith("http") -> link
                link.startsWith("/") -> "$baseUrl$link"
                else -> return@mapNotNull null
            }

            val rawTitle = a.text().trim()
            val bold = a.selectFirst("b")?.text()?.trim().orEmpty()
            val namePart = rawTitle.removePrefix(bold).trim().removePrefix(":").trim()

            SChapter.create().apply {
                url = chapterUrl
                name = when {
                    bold.isBlank() -> rawTitle
                    namePart.isNotBlank() -> "$bold : $namePart"
                    else -> bold
                }
                chapter_number = parseChapterNumber(chapterUrl, rawTitle)
                date_upload = (
                    row.select("td.no").lastOrNull()?.text()?.trim()
                        ?: row.select("td").lastOrNull()?.text()?.trim()
                    )?.parseDate() ?: 0L
                // New layout lists the uploader/group in the second column.
                scanlator = row.select("td").getOrNull(1)
                    ?.selectFirst("a[href*=/home/people/]")?.text()?.trim()
                    ?.takeIf(String::isNotBlank)
            }
        }
    }

    /** Chapter rows across both known layouts. */
    private fun chapterRows(document: Document): Elements {
        document.select("#chapter_table tr").takeIf(Elements::isNotEmpty)?.let { return it }
        // New layout: the table follows the "Chapters(N)" heading.
        document.selectFirst("h4:containsOwn(Chapters)")?.nextElementSibling()
            ?.select("tr")?.takeIf(Elements::isNotEmpty)?.let { return it }
        return document.select("table.uk-table tr")
    }

    private fun parseChapterNumber(url: String, display: String): Float {
        Regex("""(?:^|[^a-z])ch\.?\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(display)?.groupValues?.get(1)?.let { return it.toFloatOrNull() ?: 0f }
        Regex("""-chapter-([0-9.]+)""").find(url)?.groupValues?.get(1)?.let {
            return it.toFloatOrNull() ?: 0f
        }
        Regex("""/c([0-9.]+)/""").find(url)?.groupValues?.get(1)?.let {
            return it.toFloatOrNull() ?: 0f
        }
        Regex("""(\d+[0-9.]*)\s*$""").find(display)?.groupValues?.get(1)?.let {
            return it.toFloatOrNull() ?: 0f
        }
        return 0f
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"

    // =============================== Pages ===============================
    //
    // The reader page assembles its image list in-JS (the encrypted
    // `imgsrcs` payload is decoded by the site's own obfuscated script and
    // Cloudflare gates the whole host), so the extraction runs the reader
    // URL inside the repo's window-attached WebView and lets the site's own
    // code decrypt + render — we only collect what it produces.

    @Serializable
    internal class WebViewPages(
        val urls: List<String> = emptyList(),
        val expected: Int = 0,
        val page: Int = 0,
    )

    // =============================== Pages ===============================
    //
    // 2026-10: the reader became ONE PAGE PER URL — /…/pg-1/ renders a single
    // <img> (the encrypted `imgsrcs` payload still carries the whole chapter,
    // but the old all-pages slider markup is gone). The v70 flow waited for
    // total_pages <img> tags to appear at once, which can never happen now,
    // so it burned its whole 60s budget and timed out on every chapter.
    //
    // The replacement harvests pages two ways:
    //  1. FAST LANE: the site's own image requests are intercepted — if it
    //     preloads upcoming pages (its imgReady/imgonload machinery suggests
    //     it does), their urls land in the set while only page 1 renders.
    //     Resolve fires as soon as the set reaches total_pages.
    //  2. SEQUENTIAL WALKER: otherwise the WebView is driven through
    //     /pg-{N}/ (the site's own next-page path) one page at a time,
    //     harvesting each page's rendered <img> — total_pages read from the
    //     reader globals, navigation capped at that many pages.
    // Insertion order = the order the site produced the images in, which is
    // the reading order (page 1 renders first, preloads follow).

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val pages = runWebViewPages(chapterUrl)
        if (pages.isEmpty()) {
            throw Exception(
                "MangaGo reader did not render any pages. " +
                    "Open the chapter once in WebView — the site needs one Cloudflare pass.",
            )
        }
        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    /** The reader is one page per URL: {chapterBase}/pg-{N}/ (live-verified
     * 2026-10). Older chapter urls may already carry a /pg-N/ segment. */
    private fun buildPageUrl(chapterUrl: String, page: Int): String? {
        val base = if (chapterUrl.contains("/pg-")) {
            chapterUrl.substringBefore("/pg-")
        } else {
            chapterUrl.trimEnd('/')
        }
        return "$base/pg-$page/".takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun runWebViewPages(chapterUrl: String): List<String> {
        val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())

        val collector = """
            (function() {
                var found = [];
                var push = function(u) {
                    if (!u) return;
                    if (u.indexOf('data:') === 0) return;
                    if (u.indexOf('mangapicgallery.com') < 0 && u.indexOf('/r/') < 0) return;
                    if (found.indexOf(u) >= 0) return;
                    found.push(u);
                };
                var nodes = document.querySelectorAll(
                    '#readmangaslider img, .readmangaslider img, #slider img, ' +
                    '.mangaread img, .chapter_container img, div[id*=read] img, ' +
                    '#pic_container img, img[src], img[data-src]'
                );
                for (var i = 0; i < nodes.length; i++) {
                    push(nodes[i].getAttribute('src'));
                    push(nodes[i].getAttribute('data-src'));
                }
                var m = /\/pg-(\d+)/.exec(location.pathname);
                return JSON.stringify({
                    urls: found,
                    expected: (typeof total_pages === 'number' ? total_pages : 0),
                    page: (m ? parseInt(m[1], 10) : 0)
                });
            })();
        """.trimIndent()

        // --- shared harvest state (poll runs on the main thread, request
        // interception on a WebView background thread — hence synchronize).
        val collected = LinkedHashSet<String>()
        var expectedPages = 0
        val donePages = HashSet<Int>()
        val idlePolls = HashMap<Int, Int>()
        val navigated = HashSet<String>()
        var lastSize = 0
        var inFlight = false

        return runCatching {
            runBlocking {
                runWebView<List<String>>(timeout = 300.seconds) {
                    blockImages = true

                    interceptRequest { request ->
                        val raw = request.url?.toString()
                        val requestUrl = raw?.toHttpUrlOrNull()
                            ?: return@interceptRequest emptyResponse
                        // FAST LANE: collect the reader's own image requests.
                        if (raw.contains("mangapicgallery.com") || raw.contains("/r/")) {
                            synchronized(collected) { collected.add(raw) }
                        }
                        // Chapters now load on mirror hosts (mangago.zone,
                        // youhim.me, …) - always allow the host the chapter
                        // itself lives on, on top of the static allowlist.
                        val chapterHost = runCatching { chapterUrl.toHttpUrlOrNull()?.host }.getOrNull()
                        val allowed = requestUrl.host == chapterHost ||
                            requestUrl.host == "www.mangago.me" ||
                            requestUrl.host.endsWith(".mangago.me") ||
                            requestUrl.host.endsWith("mangago.zone") ||
                            requestUrl.host.endsWith("youhim.me") ||
                            requestUrl.host.endsWith("mangapicgallery.com") ||
                            requestUrl.host == "challenges.cloudflare.com" ||
                            requestUrl.host.endsWith(".cloudflare.com") ||
                            requestUrl.host.endsWith("doubleclick.net") ||
                            requestUrl.host.endsWith("google-analytics.com") ||
                            requestUrl.host.endsWith("googletagmanager.com") ||
                            requestUrl.host.endsWith("pubfuture.com")
                        if (allowed) null else emptyResponse
                    }

                    poll(1.seconds) {
                        if (inFlight) return@poll
                        inFlight = true
                        evaluateJs(collector) { value ->
                            try {
                                runCatching {
                                    // evaluateJavascript wraps the result in JSON string quotes.
                                    val payload = Json.decodeFromString<String>(value)
                                    val data = Json.decodeFromString<WebViewPages>(payload)
                                    synchronized(collected) { data.urls.forEach { collected.add(it) } }
                                    if (data.expected > 0) expectedPages = data.expected

                                    val size = synchronized(collected) { collected.size }
                                    val grew = size > lastSize
                                    lastSize = size

                                    if (expectedPages > 0 && size >= expectedPages) {
                                        resolve(synchronized(collected) { collected.toList() })
                                        return@runCatching
                                    }
                                    if (expectedPages <= 0 && size > 0) {
                                        // Legacy all-pages slider (no total_pages global).
                                        resolve(synchronized(collected) { collected.toList() })
                                        return@runCatching
                                    }

                                    // SEQUENTIAL WALKER: a page counts as done
                                    // when its image landed while we were on it,
                                    // or after a few idle polls (don't wedge on
                                    // a page whose render failed — its url may
                                    // still arrive via the request stream).
                                    if (data.page in 1..expectedPages) {
                                        if (grew) {
                                            donePages.add(data.page)
                                        } else {
                                            val idle = (idlePolls[data.page] ?: 0) + 1
                                            if (idle >= 4) donePages.add(data.page) else idlePolls[data.page] = idle
                                        }
                                    }

                                    val missing = (1..expectedPages).firstOrNull { it !in donePages }
                                    if (missing != null) {
                                        val target = buildPageUrl(chapterUrl, missing)
                                        if (target != null && navigated.add(target)) {
                                            loadUrl(target)
                                        }
                                    }
                                }
                            } finally {
                                inFlight = false
                            }
                        }
                    }

                    loadUrl(chapterUrl)
                }
            }
        }.getOrElse { emptyList() }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================ Preferences ============================

    private fun showExtraInfoPref() = preferences.getBoolean(PREF_EXTRA_INFO, true)
    private fun showAltNamesPref() = preferences.getBoolean(PREF_ALT_NAMES, true)
    private fun scorePositionPref() = preferences.getString(PREF_SCORE_POSITION, "top") ?: "top"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Year, chapter count and ratings below the score"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALT_NAMES
            title = "Show alternative names"
            summary = "Append the site's alternative titles to the description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    // ============================= Helpers ===============================

    private fun toStars(rating: Double): String {
        val full = (rating / 2).toInt().coerceIn(0, 5)
        return "★".repeat(full) + "☆".repeat(5 - full) + " $rating"
    }

    /** The site serves its alternative-title row as UTF-8 bytes shown as
     * Latin-1 (classic mojibake). Repair when the tell-tale chars appear. */
    private fun fixMojibake(text: String): String {
        if (!text.contains('Ã') && !text.contains('å') && !text.contains('ã')) return text
        return runCatching {
            String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }.getOrDefault(text)
    }

    private fun String.parseDate(): Long = runCatching {
        SIMPLE_DATE.parse(this)?.time ?: 0L
    }.getOrDefault(0L)

    companion object {
        private val SIMPLE_DATE = SimpleDateFormat("MMM d, yyyy", Locale.US)

        private const val PREF_EXTRA_INFO = "pref_extra_info"
        private const val PREF_ALT_NAMES = "pref_alt_names"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}

// =============================== Filters ================================

internal class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

internal class GenreFilter :
    Filter.Group<GenreCheckBox>(
        "Genres",
        listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Historical",
            "Horror", "Mystery", "Romance", "School Life", "Sci-fi", "Shotacon",
            "Shounen Ai", "Shounen", "Seinen", "Josei", "Smuts", "Sports",
            "Supernatural", "Tragedy", "Yaoi", "Yuri", "Webtoons", "One shot",
        ).map { GenreCheckBox(it, it.replace(' ', '_')) },
    ) {
    val selected: List<String>
        get() = state.filter { it.state }.map { it.value }
}
