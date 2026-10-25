package eu.kanade.tachiyomi.extension.ar.kawaiimanga

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Kawaiimanga (kawaiimanga.org) — Arabic manga platform (كواي مانجا).
 *
 * The web app is a Next.js App Router shell (Arabic RTL) over a separate
 * JSON API on `manga-api.kawaii-anime.com`; page images live on
 * `manga-cdn.kawaii-anime.com`. Every data call is
 *
 *     GET {API}/own?action=<verb>&<params>
 *     headers: x-app-key: km_2026_live, x-app-token: <visitor JWT>
 *
 * where the visitor JWT comes from `GET {API}/token` (x-app-key only) and
 * lives 30 minutes — cached ~28 and refetched once on a 401. Verified live
 * 2026-10-26 (recon-v94/kawaiimanga): both zones occasionally throw a
 * score-based Cloudflare challenge at default UAs, so this source pins a
 * real browser UA onto every request it stamps.
 *
 * Recipes:
 *   - popular:   /own?action=browse&page&limit=20&sort=views
 *   - latest:    /own?action=browse&page&limit=20      (updatedAt desc)
 *   - search:    /own?action=search&q=                 (single-shot; Arabic
 *                normalization on the server — hamza-less queries match)
 *   - details:   /own?action=series&slug=              (carries the FULL
 *                chapter TOC, newest first, plus `rating` 0..5)
 *   - pages:     /own?action=pages&chapterId=          (signed CDN URLs,
 *                hotlink-verified with ZERO headers, ~1 day TTL)
 *
 * The API ignores genre/status/type query params (filters are client-side
 * on the site), so the filter list is empty here; the description options
 * follow the same Comix/MangaDot block ProComic and MangaTime ship, driven
 * by the same four applicable preferences (extra info, blocked genres,
 * score position, chapter dedupe).
 *
 * Novels (/novels, novelSlug in the details payload) are skipped: novel
 * chapters are text, not pages, so they are unusable in a reader.
 */
@Source
abstract class KawaiiManga :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        addInterceptor(::userAgentOverrideInterceptor)
        // The API issues per-IP bursts; keep the pace civil like the site's
        // own browse pagination (~1 req/s at rateLimit(3) with parallelism 1
        // per call chain).
        rateLimit(3)
    }

    /**
     * Both zones score-fingerprint the client UA (a curl-default UA ate a
     * managed challenge on the token endpoint during recon while browser
     * UAs passed), so every request this source stamps carries a modern
     * Android browser UA.
     */
    private fun userAgentOverrideInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") == BROWSER_UA) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("User-Agent", BROWSER_UA).build())
    }

    // ============================== Endpoints =============================

    /** Origin used for the user-facing [Source] links. */
    private val displayBase = "https://kawaiimanga.org"

    /** The JSON API root (a DIFFERENT domain from the site itself). */
    private val apiBase = "https://manga-api.kawaii-anime.com/api/manga"

    private val apiDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = true
        }
    }

    private fun parseDate(raw: String?): Long = apiDateFormat.tryParse(raw?.substringBefore("."))

    // ---------------------------- visitor token ---------------------------

    private val tokenLock = Any()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    /**
     * The visitor JWT, cached ~28 minutes of its 30. Single-flight: host
     * apps fire details+chapters concurrently, and a token stampede would
     * both waste the rate limit and trip the edge's score model.
     */
    private fun bearerToken(): String {
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()
            cachedToken?.let { token ->
                if (now < tokenExpiresAtMs - 120_000) return token
                cachedToken = null
            }
            // The edge scores every request; bursts can earn a challenge
            // (403) or an edge-generated 404 for a route that plainly exists.
            // The site's own app rides this out by retrying — mirror that
            // with a short spaced ladder before giving up.
            var lastError: IOException? = null
            repeat(3) { attempt ->
                if (attempt > 0) Thread.sleep(1_500L * attempt)
                try {
                    val token = fetchVisitorToken()
                    cachedToken = token
                    return token
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("Kawaiimanga visitor token unavailable")
        }
    }

    private fun invalidateToken() {
        synchronized(tokenLock) {
            cachedToken = null
            tokenExpiresAtMs = 0L
        }
    }

    /** ONE token fetch round-trip; throws on any failure (the caller owns
     * the retry ladder). */
    private fun fetchVisitorToken(): String {
        val request = Request.Builder()
            .url("$apiBase/token")
            .header("x-app-key", APP_KEY)
            .header("Accept", "application/json")
            .header("Origin", displayBase)
            .header("Referer", "$displayBase/")
            .build()
        apiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Surface the body class so a device report distinguishes a
                // 403 challenge page from an edge-fabricated 404.
                val snippet = runCatching { response.peekBody(120).string() }.getOrNull().orEmpty()
                throw IOException(
                    "HTTP ${response.code} while fetching the Kawaiimanga visitor token" +
                        (if (snippet.isNotBlank()) " (${snippet.take(80).replace('\n', ' ')})" else ""),
                )
            }
            val data = response.body.string().parseAs<TokenDto>()
            val token = data.token?.takeIf(String::isNotEmpty)
                ?: throw IOException("Kawaiimanga visitor token missing from the answer")
            tokenExpiresAtMs = System.currentTimeMillis() + (data.expiresIn ?: 1800L).coerceIn(60L, 1800L) * 1000
            return token
        }
    }

    /**
     * One API call with the auth envelope. On a 401 the token cache is
     * dropped and the call retried ONCE with a fresh JWT (the token lives
     * 30 minutes; a session parked on the library page outlives it).
     */
    private fun apiCall(url: HttpUrl): Response {
        val template = Request.Builder()
            .url(url)
            .header("x-app-key", APP_KEY)
            .header("Accept", "application/json")
            .header("Referer", "$displayBase/")
            .build()

        val first = client.newCall(
            template.newBuilder().header("x-app-token", bearerToken()).build(),
        ).execute()
        if (first.code != 401) return first
        first.close()
        Log.i(TAG, "visitor token rejected (401); refreshing once")
        invalidateToken()
        return client.newCall(
            template.newBuilder().header("x-app-token", bearerToken()).build(),
        ).execute()
    }

    /** A bare client for the token endpoint only (no app-token needed). */
    private val apiClient: OkHttpClient by lazy {
        client.newBuilder().build()
    }

    private fun ownUrl(action: String, params: Map<String, String>): HttpUrl = "$apiBase/own".toHttpUrl().newBuilder()
        .addQueryParameter("action", action)
        .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
        .build()

    // ========================= Popular & Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, sort = "views")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse(page, sort = null)

    private fun browse(page: Int, sort: String?): MangasPage {
        val params = buildMap {
            put("page", page.toString())
            put("limit", PAGE_LIMIT.toString())
            if (sort != null) put("sort", sort)
        }
        apiCall(ownUrl("browse", params)).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} while browsing Kawaiimanga")
            val data = response.parseAs<BrowseResponseDto>()
            return MangasPage(
                data.results.map { row -> row.toSManga() },
                data.hasMore == true,
            )
        }
    }

    // ============================== Search ===============================

    /**
     * The search endpoint is single-shot (no pagination, no filters): page 1
     * returns the whole result set, later pages answer empty. An empty query
     * falls back to the popularity browse so the search tab still shows a
     * catalog like every other source.
     */
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return browse(page, sort = "views")
        if (page > 1) return MangasPage(emptyList(), false)

        apiCall(ownUrl("search", mapOf("q" to trimmed))).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} while searching Kawaiimanga")
            val data = response.parseAs<SearchResponseDto>()
            return MangasPage(data.results.map { row -> row.toSManga() }, false)
        }
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$displayBase/manga/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val slug = when {
            segments.size >= 2 && segments[0] == "manga" -> segments[1]
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
        // One series call carries BOTH the details and the full chapter TOC;
        // share the payload across the two halves when both are wanted.
        val payloadDeferred = async { fetchSeries(manga.url) }
        val payload = payloadDeferred.await()
        val details = if (fetchDetails) payload.toRow().toSManga() else manga
        val chapterList = if (fetchChapters) chaptersFrom(payload, manga) else chapters
        SMangaUpdate(details, chapterList)
    }

    private fun fetchSeries(slug: String): SeriesDetailsDto {
        apiCall(ownUrl("series", mapOf("slug" to slug))).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} while loading series details")
            return response.parseAs<SeriesDetailsDto>()
        }
    }

    // ============================= Chapters ==============================

    private fun chaptersFrom(payload: SeriesDetailsDto, manga: SManga): List<SChapter> {
        val slug = manga.url.ifBlank { payload.displaySlug() }
        val mapped = payload.chapters
            .map { chapter ->
                SChapter.create().apply {
                    url = ChapterRef(chapter.id, chapter.number, slug).toJson()
                    name = buildString {
                        append("الفصل ").append(formatChapterNumber(chapter.number))
                        val title = chapter.title?.trim()
                        if (!title.isNullOrEmpty() && title != "الفصل ${formatChapterNumber(chapter.number)}") {
                            append(": ").append(title)
                        }
                    }
                    chapter_number = chapter.number.toFloat()
                    date_upload = parseDate(chapter.createdAt)
                    // language is "ar" on every row today; keep it visible
                    // the way MangaTime does when it ever varies.
                    scanlator = chapter.language?.takeIf { it != "ar" }
                }
            }
            // The API answers newest-first; Mihon expects the same.
            .sortedByDescending { it.chapter_number }
        return combineDuplicateChapters(mapped)
    }

    /**
     * Optional "combine duplicate chapters" — the same option ProComic and
     * MangaTime ship. The platform enforces unique numbers today (recon
     * found none), but it re-imports catalogs, so the TOC can carry repeats.
     * When enabled, one entry per non-zero chapter number survives — the
     * newest upload wins (the list is newest-first).
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

    private fun formatChapterNumber(n: Double): String = if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String {
        val ref = ChapterRef.fromJson(chapter.url)
        val slugPart = ref.s?.takeIf(String::isNotBlank)
        return if (slugPart != null) {
            "$displayBase/manga/$slugPart/${formatChapterNumber(ref.n)}"
        } else {
            displayBase
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = ChapterRef.fromJson(chapter.url)
        apiCall(ownUrl("pages", mapOf("chapterId" to ref.c))).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} while loading chapter pages")
            val data = response.parseAs<PagesDto>()
            return data.pages
                .filter(String::isNotBlank)
                .mapIndexed { index, imageUrl ->
                    Page(index = index, url = getChapterUrl(chapter), imageUrl = imageUrl)
                }
                .ifEmpty { throw IOException("No pages found for this chapter") }
        }
    }

    // ======================== MangaDot description =======================

    /**
     * MangaDot/ProComic-style description block — the same layout MangaTime
     * renders, with the site's own 0..5 rating (no halving: the star widget
     * feeds rating.toFixed(1) straight into five stars):
     *   ★★★★☆ 4.20
     *   **Type:** Manhwa · **Status:** Ongoing · **Chapters:** 21 · **46.2k views**
     *   ---
     *   <synopsis>
     *   **Genres:** ...
     *   **Links:** ...
     *   **Alternative Names:** ...
     */
    private fun SeriesRowDto.toSManga() = SManga.create().apply {
        title = displayTitle()
        url = displaySlug()
        thumbnail_url = coverUrl?.takeIf(String::isNotBlank)
        author = this@toSManga.author?.takeIf(String::isNotBlank)
        artist = this@toSManga.artist?.takeIf(String::isNotBlank)
        status = statusFrom(this@toSManga.status)
        genre = genreField(this@toSManga.genres)
        description = buildDescription(
            rating = this@toSManga.rating,
            type = this@toSManga.type,
            statusText = this@toSManga.status,
            chapterCount = this@toSManga.chapterCount ?: this@toSManga.mainChapterCount,
            viewCount = this@toSManga.viewCount,
            synopsis = this@toSManga.description,
            genres = this@toSManga.genres,
            altNames = this@toSManga.altTitles,
            slug = displaySlug(),
        )
        initialized = true
    }

    private fun statusFrom(raw: String?): Int = when (raw?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "coming-soon" -> SManga.ONGOING
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    /** Genre chips minus the blocked list (the platform has no separate
     * tags field — genres ARE the chips). */
    private fun genreField(genres: List<String>): String = genres
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
        .filter { it.lowercase() !in blockedGenres() }
        .joinToString(", ")

    private fun blockedGenres(): Set<String> = preferences.getString(PREF_BLOCKED_GENRES, "")
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter(String::isNotBlank)
        ?.toSet()
        ?: emptySet()

    private fun buildDescription(
        rating: Double?,
        type: String?,
        statusText: String?,
        chapterCount: Int?,
        viewCount: Long?,
        synopsis: String?,
        genres: List<String>,
        altNames: List<String>,
        slug: String,
    ): String {
        val showExtraInfo = preferences.getBoolean(PREF_SHOW_EXTRA_INFO, true)
        val scorePosition = preferences.getString(PREF_SCORE_POSITION, "end") ?: "end"

        // 0..5 scale: full stars = round(rating), label keeps one decimal
        // like the site's own widget (rating.toFixed(1)).
        val stars = rating?.let {
            if (it <= 0.0) return@let null
            val full = it.roundToInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " " +
                String.format(Locale.US, "%.1f", it)
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                type?.takeIf(String::isNotBlank)?.let {
                    append("**Type:** ${it.replaceFirstChar { c -> c.uppercase() }}")
                }
                when (statusText?.lowercase()) {
                    "ongoing" -> appendSep().append("**Status:** Ongoing")
                    "completed" -> appendSep().append("**Status:** Completed")
                    "coming-soon" -> appendSep().append("**Status:** Coming soon")
                    "hiatus" -> appendSep().append("**Status:** Hiatus")
                    "cancelled" -> appendSep().append("**Status:** Cancelled")
                }
                chapterCount?.takeIf { it > 0 }?.let { appendSep().append("**Chapters:** $it") }
                viewCount?.takeIf { it > 0 }?.let { appendSep().append(formatCount(it)) }
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

            listOfNotNull(
                "[Source]($displayBase/manga/$slug)",
            ).also { links ->
                if (links.isNotEmpty()) {
                    append("\n**Links:**\n")
                    links.forEach { link -> append("- ", link, "\n") }
                }
            }

            val altList = altNames
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            if (altList.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                altList.forEach { altTitle -> append("- ", altTitle, "\n") }
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

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ---- Description (the Comix-style options) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status, chapter count and views"
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
        const val TAG = "Kawaiimanga"
        const val PAGE_LIMIT = 20

        /** The app key the site's own API client ships (public static). */
        const val APP_KEY = "km_2026_live"

        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"

        val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** The chapter URL payload: API chapter id + its number + the series slug
 * (for the user-facing chapter links). */
private class ChapterRef(val c: String, val n: Double, val s: String? = null) {

    fun toJson(): String = kotlinx.serialization.json.buildJsonObject {
        put("c", kotlinx.serialization.json.JsonPrimitive(c))
        put("n", kotlinx.serialization.json.JsonPrimitive(n))
        if (!s.isNullOrBlank()) put("s", kotlinx.serialization.json.JsonPrimitive(s))
    }.toString()

    companion object {
        fun fromJson(raw: String): ChapterRef = runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                as kotlinx.serialization.json.JsonObject
            val c = (obj["c"] as kotlinx.serialization.json.JsonPrimitive).content
            val n = (obj["n"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
            val s = (obj["s"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ChapterRef(c, n, s)
        }.getOrElse { ChapterRef(raw, 0.0) }
    }
}
