package eu.kanade.tachiyomi.extension.ar.teamx

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Team X (olympustaff.com) — custom Laravel reader, Arabic (تيم إكس).
 *
 * Beyond browse/search/filters, this source ships the family's Comix/MangaDot
 * details block — the same options ProComic, MangaTime and Kawaiimanga carry:
 *
 *   - an organized description: the site's own 0..5 rating (`#average_rating`)
 *     rendered as the familiar ★★★★☆ row, a Type/Status/extra-info line, the
 *     synopsis, then Genres / Links sections
 *   - "Show extra info" / "Blocked genres" / "Score display position"
 *     preferences driving that description
 *   - "Combine duplicate chapters" folding repeated chapter numbers into one
 *     entry (newest upload wins)
 */
@Source
abstract class TeamX :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(10, 1.seconds)
    }

    private val nextPageSelector = "a[rel=next]"

    private val thumbnailSuffix = "thumbnail_"

    private fun Int.pageNumber() = if (this > 1) "?page=$this" else ""

    // ============================== Popular ==============================

    private fun parsePopularManga(response: Response): MangasPage {
        val document = response.asJsoup()

        val entries = document.select("div.listupd div.bsx").map { element ->
            SManga.create().apply {
                title = element.selectFirst("a")!!.attr("title")
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = element.selectFirst("img")?.let {
                    it.absUrl("data-src").ifEmpty { it.absUrl("src") }
                }
            }
        }

        val hasNextPage = document.selectFirst(nextPageSelector) != null

        return MangasPage(entries, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/series/${page.pageNumber()}")
        return parsePopularManga(response)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl${page.pageNumber()}").asJsoup()

        val mangaList = document.select("div.last-chapter div.box")
            .mapNotNull { element ->
                SManga.create().apply {
                    val linkElement = element.selectFirst("div.info a")!!
                    title = linkElement.selectFirst("h3")!!.text()

                    setUrlWithoutDomain(linkElement.absUrl("href"))
                    thumbnail_url = element.selectFirst("div.imgu img")
                        ?.absUrl("src")
                        ?.replace(thumbnailSuffix, "")
                }
            }

        return MangasPage(mangaList, document.selectFirst(nextPageSelector) != null)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .build()

            val document = client.get(url).asJsoup()
            val mangas = document.select("div.tx-grid a.tx-card").map { element ->
                SManga.create().apply {
                    title = element.selectFirst("h3")!!.text()
                    thumbnail_url = element.selectFirst("img")?.absUrl("src")
                        ?.replace(thumbnailSuffix, "")
                    setUrlWithoutDomain(element.absUrl("href"))
                }
            }

            return MangasPage(mangas, false)
        }

        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<UrlPartFilter>().forEach {
                it.addUrlParameter(this)
            }
        }.build()

        return parsePopularManga(client.get(url))
    }

    // ============================== Deeplinking =============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.size >= 2) {
            val segment = url.pathSegments[1]
            val manga = SManga.create().apply { this.url = "/series/$segment" }

            return fetchMangaUpdate(manga, emptyList(), true, false).manga.apply {
                this.url = manga.url
                initialized = true
            }
        }

        throw Exception("Unsupported URL")
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl${manga.url}")
        val document = response.asJsoup()

        val updatedManga = parseMangaDetails(document).apply {
            enrichMangaDotDetails(this, document)
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = if (fetchChapters) combineDuplicateChapters(getChapterList(document)) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("div.author-info-title h1")!!.text()

        var desc = document.select("div.review-content").text()
        if (desc.isEmpty()) {
            desc = document.select("div.review-content p").text()
        }
        description = desc

        genre = document.select("div.review-author-info a").joinToString { it.text() }
        thumbnail_url = document.selectFirst("div.text-right img")?.absUrl("src")
        status = document.selectFirst(".full-list-info > small:first-child:contains(الحالة) + small")
            ?.text().toStatus()

        author = document.selectFirst(".full-list-info > small:first-child:contains(الرسام) + small")
            ?.text()
            ?.takeIf { it != "غير معروف" }
    }

    // ========================= MangaDot details ==========================

    private fun enrichMangaDotDetails(manga: SManga, document: Document) {
        // "#average_rating" holds "4/5"; "#rating_count" the vote total.
        val rating = document.selectFirst("#average_rating")?.text()
            ?.let { RATING_REGEX.find(it)?.value?.toDoubleOrNull() }
        val ratingCount = document.selectFirst("#rating_count")?.text()
            ?.trim()?.toIntOrNull() ?: 0
        val type = document.selectFirst(".full-list-info > small:first-child:contains(النوع) + small")
            ?.text()
            ?.takeUnless { it.isBlank() || it == "غير معروف" }
        val statusText = document.selectFirst(".full-list-info > small:first-child:contains(الحالة) + small")
            ?.text()
            ?.toStatusLabel()
        val synopsis = buildString {
            append(document.select("div.review-content").text())
            if (isEmpty()) append(document.select("div.review-content p").text())
        }.takeIf(String::isNotBlank)
        val genres = document.select("div.review-author-info a").eachText()
            .map(String::trim)
            .filter(String::isNotEmpty)

        manga.genre = genreField(genres)
        manga.description = buildDescription(
            rating = rating,
            ratingCount = ratingCount,
            type = type,
            statusText = statusText,
            chapterCount = null,
            viewCount = null,
            synopsis = synopsis,
            genres = genres,
            sourceUrl = document.location(),
        )
    }

    private fun String.toStatusLabel(): String? = when (this) {
        "مستمرة" -> "Ongoing"
        "قادم قريبًا" -> "Coming soon"
        "مكتمل" -> "Completed"
        "متوقف" -> "Hiatus"
        else -> takeIf(String::isNotBlank)
    }

    /** Genre chips minus the blocked list. */
    private fun genreField(genres: List<String>): String {
        val blocked = blockedGenres()
        return genres
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .filter { it.lowercase() !in blocked }
            .joinToString(", ")
    }

    private fun blockedGenres(): Set<String> = preferences.getString(PREF_BLOCKED_GENRES, "")
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter(String::isNotBlank)
        ?.toSet()
        ?: emptySet()

    private fun buildDescription(
        rating: Double?,
        ratingCount: Int,
        type: String?,
        statusText: String?,
        chapterCount: Int?,
        viewCount: Long?,
        synopsis: String?,
        genres: List<String>,
        sourceUrl: String,
    ): String {
        val showExtraInfo = preferences.getBoolean(PREF_SHOW_EXTRA_INFO, true)
        val scorePosition = preferences.getString(PREF_SCORE_POSITION, "end") ?: "end"

        // The site rates 0..5; the description shows the familiar 5-star row.
        val stars = rating?.let {
            if (it <= 0.0) return@let null
            val normalized = (it / RATING_SCALE) * 5.0
            val full = normalized.roundToInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " " +
                String.format(Locale.US, "%.2f", normalized)
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                type?.takeIf(String::isNotBlank)?.let {
                    append("**Type:** ${it.replaceFirstChar { c -> c.uppercase() }}")
                }
                statusText?.takeIf(String::isNotBlank)?.let { appendSep().append("**Status:** ").append(it) }
                chapterCount?.takeIf { it > 0 }?.let { appendSep().append("**Chapters:** $it") }
                viewCount?.takeIf { it > 0 }?.let { appendSep().append(formatCount(it)) }
                ratingCount.takeIf { it > 0 }?.let { appendSep().append("**$it ratings**") }
            }.toString().ifBlank { null }
        } else {
            null
        }

        return buildString {
            if (scorePosition == "top" && stars != null) {
                append(stars).append("\n")
                if (infoLine != null) {
                    append(infoLine).append("\n\n")
                } else {
                    append("\n\n")
                }
            }

            synopsis?.let {
                append(
                    it.replace("\r\n", "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim(),
                    "\n\n",
                )
            }

            if (scorePosition == "end" && stars != null) {
                append(stars).append("\n")
                if (infoLine != null) {
                    append(infoLine).append("\n\n")
                }
            }

            if (isNotEmpty()) {
                append("---\n\n")
            }

            val genreLabels = genres.map(String::trim).filter(String::isNotEmpty)
            if (genreLabels.isNotEmpty()) {
                append("\n**Genres:**\n")
                genreLabels.forEach { label -> append("- ", label, "\n") }
            }

            if (sourceUrl.isNotBlank()) {
                append("\n**Links:**\n- [Source]($sourceUrl)\n")
            }
        }.trim()
    }

    private fun StringBuilder.appendSep(): StringBuilder = apply {
        if (isNotEmpty()) append(" · ")
    }

    private fun formatCount(count: Long): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM views", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fk views", count / 1_000.0)
        else -> "$count views"
    }

    // ============================= Chapters ==============================

    private suspend fun getChapterList(document: Document): List<SChapter> = coroutineScope {
        val firstPageChapters = parseChapterElements(document)

        val lastPage = document.select("ul.pagination a.page-link")
            .mapNotNull { it.text().toIntOrNull() }
            .maxOrNull() ?: 1

        if (lastPage <= 1) {
            return@coroutineScope firstPageChapters
        }

        val url = document.location()

        val remainingChapters = (2..lastPage).map { page ->
            async {
                val response = client.get("$url?page=$page")
                parseChapterElements(response.asJsoup())
            }
        }.awaitAll()

        firstPageChapters + remainingChapters.flatten()
    }

    private fun parseChapterElements(document: Document): List<SChapter> {
        return document.select("div.chapter-card").mapNotNull { element ->
            if (element.selectFirst("span.locked") != null) return@mapNotNull null
            SChapter.create().apply {
                val chpNum = element.attr("data-number")
                val chpTitle = element.selectFirst("div.chapter-info div.chapter-title")?.text()
                val invalidTitles = setOf(chpNum, "الفصل $chpNum", "الفصل رقم $chpNum")

                name = buildString {
                    append("الفصل $chpNum")
                    if (!chpTitle.isNullOrEmpty() && chpTitle !in invalidTitles) {
                        append(" - $chpTitle")
                    }
                    append("\u200F")
                }

                // data-date is Unix timestamp (seconds)
                date_upload = element.attr("data-date")
                    .toLongOrNull()
                    ?.times(1000)
                    ?: 0L

                // data-number is the chapter's numeric value
                chapter_number = chpNum.toFloatOrNull() ?: 0f

                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
            }
        }
    }

    /**
     * Optional "combine duplicate chapters" — the same option ProComic and
     * MangaTime ship. When enabled, one entry per non-zero chapter number
     * survives — the newest upload wins (the list is newest-first).
     */
    private fun combineDuplicateChapters(chapters: List<SChapter>): List<SChapter> = if (preferences.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)) {
        mutableListOf<SChapter>().apply {
            for ((_, group) in chapters.groupBy { it.chapter_number }) {
                if (group.first().chapter_number <= 0f || group.size == 1) {
                    addAll(group)
                } else {
                    // group preserves the newest-first input order
                    add(group.first())
                }
            }
        }
    } else {
        chapters
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl${chapter.url}")
        return response.asJsoup()
            .select("div.image_list canvas[data-src], div.image_list img[src]")
            .mapIndexed { i, element ->
                val url = when {
                    element.hasAttr("src") -> element.absUrl("src")
                    else -> element.absUrl("data-src")
                }
                Page(i, imageUrl = url)
            }
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/series").asJsoup()

        fun parseOptions(selector: String): Options = document.select(selector).map { FilterOption(it.text(), it.attr("value")) }

        return FilterData(
            genres = parseOptions("#select_genre option"),
            types = parseOptions("#select_type option"),
            states = parseOptions("#select_state option"),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.let { it.parseAs<FilterData>() } ?: return FilterList()

        return FilterList(
            listOf(
                Filter.Header("ملاحظة: تُهمل عوامل التصفية عند البحث"),
                Filter.Separator(),
                TypeFilter(filterData.types),
                StatusFilter(filterData.states),
                GenreFilter(filterData.genres),
            ),
        )
    }
    // ============================= Utilities =============================

    private fun String?.toStatus() = when (this) {
        "مستمرة", "قادم قريبًا" -> SManga.ONGOING // "coming soon"
        "مكتمل" -> SManga.COMPLETED
        "متوقف" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ---- Description (the Comix-style options) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status and ratings"
            setDefaultValue(true)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from the genre field"
            setDefaultValue("")
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)

        // ---- Chapter list (the duplicate-combining option) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Combine duplicate chapters"
            summary = "Keep one entry per chapter number — newest upload wins"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private companion object {
        const val RATING_SCALE = 5.0

        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"

        /** "#average_rating" text like "4/5" — grab the numeric part. */
        val RATING_REGEX = Regex("""\d+(?:\.\d+)?""")
    }
}
