package eu.kanade.tachiyomi.extension.ar.mangatek

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * MangaTek (mangatek.com) — Arabic manga platform (مانجاتك), Next.js "axis"
 * backend with its catalog gateway on the api. subdomain:
 *
 *   GET /api/catalog/search?limit=&offset=&sort=&order=&mature=&q=&status=&includeGenres=&genreMode=
 *   GET /api/catalog/genres
 *   GET /api/catalog/series/by-slug/{slug}
 *   GET /api/catalog/series/{id}/chapters
 *   GET /api/catalog/chapters/by-slug/{seriesSlug}/{chapterSlug}   (raw pages)
 *
 * v100 — single origin. Earlier builds carried an automatic failover onto
 * mangatuk.com, a twin deployment of the same platform with its OWN
 * separate catalog. The failover engaged silently on gateway hiccups and
 * then persisted, so readers kept seeing the wrong site's titles — exactly
 * the "you are using the wrong site" report. The twin is gone from the
 * host sets entirely: every call (API and page images) goes to mangatek.com
 * and its subdomains only.
 *
 * Pages are raw, direct image URLs (pages[].imageUrl on content.mangatek.com)
 * served exactly as the site's own reader receives them. Every request —
 * API and images alike — runs through the same client with the Cloudflare
 * solver for all mangatek hosts, so the cookie jar carries the site's own
 * session cookies (the same cookies the browser reader holds, including the
 * ones gating the Arabic OCR page variants) on every call.
 */
@Source
abstract class MangaTek :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        // The site, its gateway and its content CDN sit behind Cloudflare.
        // Solving challenges in-app keeps cf_clearance (and with it the
        // site's session cookies) in this client's cookie jar, so the raw
        // pages behave like the ones the browser reader sees.
        addInterceptor(
            CloudflareSolverInterceptor(
                setOf(
                    "mangatek.com",
                    "www.mangatek.com",
                    "api.mangatek.com",
                    "content.mangatek.com",
                ),
            ),
        )
        // Keep the request pace civil like the site's own traffic.
        rateLimit(3)
    }

    // ============================== Endpoints =============================

    /** Catalog gateway. v100: the ONLY origin — no twin, no failover. */
    protected open val gatewayBase = "https://api.mangatek.com"

    /** Origin used for the user-facing [Source] links and deep links. */
    protected open val siteBase = "https://mangatek.com"

    private val knownSiteHosts = setOf("mangatek.com", "www.mangatek.com")

    private val apiDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = true
        }
    }

    private fun parseDate(raw: String?): Long = apiDateFormat.tryParse(raw?.substringBefore("."))

    private fun catalogUrl(sort: String, page: Int): HttpUrl = "$gatewayBase/api/catalog/search".toHttpUrl().newBuilder()
        .addQueryParameter("limit", PAGE_LIMIT.toString())
        .addQueryParameter("offset", ((page - 1) * PAGE_LIMIT).toString())
        .addQueryParameter("sort", sort)
        .addQueryParameter("order", "desc")
        .addQueryParameter("mature", "include")
        .build()

    // ========================= Popular & Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = getCatalogPage("popular", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getCatalogPage("latest", page)

    private suspend fun getCatalogPage(sort: String, page: Int): MangasPage {
        val response = retryTransient { client.newCall(GET(catalogUrl(sort, page), headers)).execute() }
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while browsing MangaTek")
            val data = it.parseAs<SearchResponseDto>().data
            return MangasPage(
                data.map { row -> row.toSManga() },
                data.size >= PAGE_LIMIT,
            )
        }
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "relevance"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected?.takeIf(String::isNotEmpty)
        val genres = filters.firstInstanceOrNull<GenreFilter>()
            ?.included
            .orEmpty()

        val urlBuilder = catalogUrl(sort, page).newBuilder()
        if (!query.isBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }
        status?.let { urlBuilder.addQueryParameter("status", it) }
        if (genres.isNotEmpty()) {
            urlBuilder.addQueryParameter("includeGenres", genres.joinToString(","))
            urlBuilder.addQueryParameter("genreMode", "any")
        }

        val response = retryTransient { client.newCall(GET(urlBuilder.build(), headers)).execute() }
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while searching MangaTek")
            val data = it.parseAs<SearchResponseDto>().data
            return MangasPage(
                data.map { row -> row.toSManga() },
                data.size >= PAGE_LIMIT,
            )
        }
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching: Boolean get() = true

    @kotlinx.serialization.Serializable
    private data class FilterDataDto(
        val genres: List<Pair<String, String>>? = null,
    )

    override suspend fun fetchFilterData(): JsonElement {
        val genres = runCatching {
            client.newCall(GET("$gatewayBase/api/catalog/genres", headers)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<Pair<String, String>>()
                response.parseAs<List<GenreDto>>()
                    .map { genre -> genre.name.trim() to genre.id }
                    .filter { (name, _) -> name.isNotEmpty() }
                    .distinctBy { it.second }
            }
        }.getOrDefault(emptyList())
        return FilterDataDto(genres).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data
            ?.let { runCatching { it.parseAs<FilterDataDto>() }.getOrNull() }
            ?.genres
        return getFilterListInternal(genres)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$siteBase/series/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in knownSiteHosts) return null
        val segments = url.pathSegments
        val slug = when {
            segments.size >= 2 && segments[0] == "series" -> segments[1]
            else -> return null
        }.takeIf(String::isNotBlank) ?: return null

        val tmpManga = SManga.create().apply { this.url = slug }
        return fetchMangaUpdate(tmpManga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) fetchSeriesDetails(manga) else manga
        }
        val chaptersDeferred = async {
            if (fetchChapters) fetchChapterListInternal(manga) else chapters
        }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun fetchSeriesDetails(manga: SManga): SManga {
        val slug = manga.url
        if (slug.isBlank()) {
            throw IOException("MangaTek: this entry lost its series slug — remove it and open it again from the source")
        }
        val response = retryTransient {
            client.newCall(GET("$gatewayBase/api/catalog/series/by-slug/$slug", headers)).execute()
        }
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading series details")
            val data = it.parseAs<SeriesDto>()
            // Keep the slug the lookup was made with — the entry's url must
            // survive every refresh (a wiped url is what once made this
            // source's sibling flood its chapter list).
            return data.toSManga().apply { url = slug }
        }
    }

    // ============================= Chapters ==============================

    private suspend fun fetchChapterListInternal(manga: SManga): List<SChapter> {
        val seriesId = fetchSeriesId(manga.url)
        val response = retryTransient {
            client.newCall(GET("$gatewayBase/api/catalog/series/$seriesId/chapters", headers)).execute()
        }
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading chapters")
            val data = it.parseAs<ChapterListDto>().data
            val mapped = data
                .map { chapter ->
                    chapter.toSChapter(manga.url).apply {
                        date_upload = parseDate(chapter.publishedAt ?: chapter.updatedAt ?: chapter.createdAt)
                    }
                }
                // Mihon expects newest-first; the API answers newest-first already.
                .sortedByDescending { it.chapter_number }
            return combineDuplicateChapters(mapped)
        }
    }

    private suspend fun fetchSeriesId(slug: String): String {
        if (slug.isBlank()) {
            throw IOException("MangaTek: this entry lost its series slug — remove it and open it again from the source")
        }
        val response = retryTransient {
            client.newCall(GET("$gatewayBase/api/catalog/series/by-slug/$slug", headers)).execute()
        }
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while resolving the series id")
            return it.parseAs<SeriesDto>().id
        }
    }

    /** Runs [block] once more when it fails transiently (no HTTP code =
     * connect/read timeout; 429 and 5xx = edge pressure). A deterministic
     * failure (404, parse shape) propagates immediately. */
    private suspend fun <T> retryTransient(block: () -> T): T {
        val httpCode = Regex("HTTP (\\d{3})")
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastError = e
                val code = httpCode.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                val transient = code == null || code == 429 || code in 500..599
                if (!transient || attempt == 1) throw e
            }
        }
        throw lastError ?: IOException("MangaTek: request failed")
    }

    /**
     * Optional "combine duplicate chapters" + "team priority" — the same
     * options this source has shipped since the MangaDot-options round.
     * When combining is enabled, one entry per non-zero chapter number
     * survives; with a team priority list set, the preferred scanlator's
     * upload wins (unknown scanlators rank after the listed ones, input
     * order breaks ties). Zero/unknown numbers are never combined.
     */
    private fun combineDuplicateChapters(chapters: List<SChapter>): List<SChapter> = if (preferences.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)) {
        val preferred = preferences.getString(PREF_PREFERRED_SCANLATORS, "")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        mutableListOf<SChapter>().apply {
            for ((_, group) in chapters.groupBy { it.chapter_number }) {
                if (group.first().chapter_number <= 0f || group.size == 1) {
                    addAll(group)
                } else {
                    // group preserves the newest-first input order
                    add(group.pickPreferred(preferred))
                }
            }
        }
    } else {
        chapters
    }

    private fun List<SChapter>.pickPreferred(preferred: List<String>): SChapter {
        if (preferred.isEmpty()) return first()
        return minByOrNull { chapter ->
            val name = chapter.scanlator?.trim()?.lowercase()
            val index = preferred.indexOfFirst { it.lowercase() == name }
            if (index >= 0) index else preferred.size
        } ?: first()
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String {
        val ref = chapter.url.parseAs<ChapterRef>()
        return "$siteBase/series/${ref.seriesSlug}/${ref.chapterSlug}"
    }

    /**
     * Raw pages: the axis chapters-by-slug payload carries plain absolute
     * image URLs on content.mangatek.com — no stitching, no proxy. The
     * image requests ride the same solved-cookie client as everything
     * else, so what the site's reader serves is what lands in the reader.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = chapter.url.parseAs<ChapterRef>()
        val response = retryTransient {
            client.newCall(
                GET("$gatewayBase/api/catalog/chapters/by-slug/${ref.seriesSlug}/${ref.chapterSlug}", headers),
            ).execute()
        }
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading chapter pages")
            val pages = it.parseAs<ChapterDetailDto>()
                .pages
                .mapNotNull { page -> page.imageUrl?.takeIf(String::isNotBlank) }
            if (pages.isEmpty()) throw IOException("No pages found for this chapter")
            return pages.mapIndexed { index, imageUrl ->
                Page(index = index, url = getChapterUrl(chapter), imageUrl = imageUrl)
            }
        }
    }

    // ======================== MangaDot description =======================

    /**
     * MangaDot/ProComic-style description block:
     *   ★★★★☆ 8.00
     *   **Type:** Manhwa · **Status:** Ongoing · **Chapters:** 19 · 12.3k views · 39 ratings
     *   ---
     *   <synopsis>
     *   **Genres:** ...
     *   **Links:** ...
     *   **Alternative Names:** ...
     */
    private fun SeriesDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = this@toSManga.slug
        thumbnail_url = this@toSManga.coverUrl
        author = this@toSManga.authorText
        artist = this@toSManga.artistText
        status = statusFrom(this@toSManga.status)
        genre = this@toSManga.genreLabels.joinToString(", ")
        description = buildDescription(this@toSManga)
        initialized = true
    }

    private fun statusFrom(raw: String?): Int = when (raw?.lowercase(Locale.ROOT)) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun buildDescription(series: SeriesDto): String {
        val showGenres = preferences.getBoolean(PREF_SHOW_GENRES, true)

        return buildString {
            // The site rates 0..10; the description shows the familiar 5-star row.
            val rating = series.ratingAvgValue
            if (rating != null) {
                val stars = (rating / 2f).roundToInt().coerceIn(0, 5)
                append("★".repeat(stars) + "☆".repeat(5 - stars) + " ")
                append(String.format(Locale.US, "%.2f", rating))
                append("\n\n")
            }

            val metaInfo = buildList {
                series.type?.takeIf(String::isNotBlank)?.let {
                    add("**Type:** ${it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }}")
                }
                series.format?.takeIf(String::isNotBlank)?.let {
                    add("**Format:** ${it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }}")
                }
                when (series.status?.lowercase(Locale.ROOT)) {
                    "ongoing" -> add("**Status:** Ongoing")
                    "completed" -> add("**Status:** Completed")
                    "hiatus" -> add("**Status:** Hiatus")
                    "cancelled" -> add("**Status:** Cancelled")
                }
                series.chapterCountText?.let { add("**Chapters:** $it") }
                (series.viewCount ?: 0).takeIf { it > 0 }?.let { add("${formatCount(it)} views") }
                (series.ratingCount ?: 0).takeIf { it > 0 }?.let { add("$it ratings") }
            }
            if (metaInfo.isNotEmpty()) {
                append(metaInfo.joinToString(" · "))
                append("\n\n")
            }

            if (isNotEmpty()) {
                append("---\n\n")
            }

            series.description?.let {
                append(
                    it.replace("\r\n", "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim(),
                    "\n\n",
                )
            }

            if (showGenres) {
                val labels = series.genreLabels
                if (labels.isNotEmpty()) {
                    append("**Genres:**\n")
                    labels.forEach { label -> append("- ", label, "\n") }
                    append("\n")
                }
            }

            append("**Links:**\n")
            append("- [Source]($siteBase/series/${series.slug})\n")

            val altList = series.altTitleList
            if (altList.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                altList.forEach { altTitle -> append("- ", altTitle, "\n") }
            }
        }.trim()
    }

    private fun formatCount(count: Int): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fk", count / 1_000.0)
        else -> count.toString()
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_GENRES
            title = "Show genres in details"
            summary = "List the title's genres in its description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // ---- Chapter list (the duplicate-combining option) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Combine duplicate chapters"
            summary = "Keep one entry per chapter number — the preferred team's upload wins"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PREFERRED_SCANLATORS
            title = "Team priority"
            summary = "Comma-separated team names, first has priority when combining"
            setDefaultValue("")
        }.let(screen::addPreference)
    }

    private companion object {
        const val PAGE_LIMIT = 24

        const val PREF_SHOW_GENRES = "pref_show_genres"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        const val PREF_PREFERRED_SCANLATORS = "pref_preferred_scanlators"
    }
}
