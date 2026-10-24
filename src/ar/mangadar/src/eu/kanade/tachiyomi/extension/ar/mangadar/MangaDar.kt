package eu.kanade.tachiyomi.extension.ar.mangadar

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * MangaDar (mangadar.com) — Arabic manga platform (مانجا تايم).
 *
 * The web app is a Vite-built SPA wrapped with Capacitor; every piece of
 * data goes through a same-origin tRPC gateway (superjson, batch=1):
 *   /api/trpc/search.searchSeries          (browse + search + filters)
 *   /api/trpc/homepage.getLatestReleases   (latest updates)
 *   /api/trpc/content.getSeriesBySlug      (details)
 *   /api/trpc/content.getChapters          (chapter list, one call)
 *   /api/trpc/content.getChapterPagesWeb   (reader pages, absolute URLs)
 *   /api/trpc/content.getGenres            (filter genres)
 *
 * mangadar.com — this source's own domain — is the SAME platform behind a
 * Cloudflare challenge: its HTML AND its API paths answer 403 "Just a
 * moment..." to non-browser clients (verified 2026-10). The sister domain
 * mangatime.org serves the identical backend with no challenge, and the
 * site's own web reader already talks to it, so this source (MangaTime's
 * exact code under the mangadar.com brand) points every network call there.
 *
 * superjson gotchas baked into buildSearchInput(): literal `null` for a
 * field the schema types as string|undefined is a 400, so absent fields are
 * marked undefined through meta.values paths — and a path listed there is
 * FORCED undefined server-side, so a field carrying a real value must be
 * removed from meta.values again. The verified recipe keeps nulls in the
 * JSON skeleton AND lists the unused paths in meta.values.
 *
 * Details follow the MangaDot description layout: star rating, a meta info
 * line, the synopsis, a Links section and the alternative names — the same
 * block ProComic ships, driven by the same five options.
 */
@Source
abstract class MangaDar :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        // The SPA runs crawler telemetry (/api/track/*, CrawlerFailureBeacon);
        // keep the request pace civil like the site's own traffic.
        rateLimit(3)
    }

    // ============================== Endpoints =============================

    /**
     * API + image origin. mangadar.com challenges Cloudflare on its API
     * paths (403 verified 2026-10), so every network call goes to the
     * platform's canonical origin — the same backend the site's own reader
     * uses.
     */
    private val apiBase = "https://mangatime.org"

    /** Origin used for the user-facing [Source] links. */
    protected open val displayBase = "https://mangadar.com"

    private val apiDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = true
        }
    }

    private fun parseDate(raw: String?): Long = apiDateFormat.tryParse(raw?.substringBefore("."))

    private fun trpc(procedure: String, input: String): okhttp3.Request {
        val url = "$apiBase/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", input)
            .build()
        return GET(url, headers)
    }

    // ========================= Popular & Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = searchSeries(query = null, page = page)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val input = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put(
                        "json",
                        buildJsonObject {
                            put("page", JsonPrimitive(page))
                            put("limit", JsonPrimitive(PAGE_LIMIT))
                        },
                    )
                },
            )
        }.toString()
        val response = client.newCall(trpc("homepage.getLatestReleases", input)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading the latest MangaDar releases")
            val data = it.parseAs<List<TrpcBatch<LatestReleasesResponseDto>>>()
                .firstOrNull()?.result?.data?.json
                ?: throw IOException("Unexpected MangaDar latest-releases answer")
            return MangasPage(
                data.items.map { row -> row.toSManga() },
                data.hasMore || data.items.size >= PAGE_LIMIT,
            )
        }
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "relevance"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected?.takeIf(String::isNotEmpty)
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selected?.takeIf(String::isNotEmpty)
        val genres = filters.firstInstanceOrNull<GenreFilter>()
            ?.included
            .orEmpty()

        return searchSeries(
            query = query.trim().takeIf(String::isNotEmpty),
            page = page,
            sortBy = sort,
            status = status,
            type = type,
            genres = genres,
        )
    }

    private suspend fun searchSeries(
        query: String?,
        page: Int,
        sortBy: String = "popularity",
        status: String? = null,
        type: String? = null,
        genres: List<String> = emptyList(),
    ): MangasPage {
        val response = client.newCall(trpc("search.searchSeries", buildSearchInput(query, page, sortBy, status, type, genres))).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while browsing MangaDar")
            val data = it.parseAs<List<TrpcBatch<SearchSeriesResponseDto>>>()
                .firstOrNull()?.result?.data?.json
                ?: throw IOException("Unexpected MangaDar search answer")
            return MangasPage(
                data.results.map { row -> row.toSManga() },
                data.hasMore || data.results.size >= PAGE_LIMIT,
            )
        }
    }

    /**
     * The verified searchSeries superjson input. Unused optional fields are
     * listed in meta.values as undefined AND stay null in the JSON skeleton
     * (the exact shape the site's own client sends); a field this request
     * actually uses gets its real value and is REMOVED from meta.values —
     * a path left behind would force the value back to undefined.
     */
    private fun buildSearchInput(
        query: String?,
        page: Int,
        sortBy: String,
        status: String?,
        type: String?,
        genres: List<String>,
    ): String {
        val undefined = JsonArray(listOf(JsonPrimitive("undefined")))
        val metaValues = linkedMapOf<String, JsonElement>()

        val queryJson: JsonElement = if (query.isNullOrBlank()) {
            metaValues["query"] = undefined
            JsonNull
        } else {
            JsonPrimitive(query)
        }

        val statusJson: JsonElement = if (status.isNullOrBlank()) {
            metaValues["filters.status"] = undefined
            JsonNull
        } else {
            JsonArray(listOf(JsonPrimitive(status)))
        }
        val typeJson: JsonElement = if (type.isNullOrBlank()) {
            metaValues["filters.type"] = undefined
            JsonNull
        } else {
            JsonArray(listOf(JsonPrimitive(type)))
        }
        val genresJson: JsonElement = if (genres.isEmpty()) {
            JsonArray(emptyList())
        } else {
            JsonArray(genres.map(::JsonPrimitive))
        }

        val input = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put(
                        "json",
                        buildJsonObject {
                            put("query", queryJson)
                            put(
                                "filters",
                                buildJsonObject {
                                    put("genres", genresJson)
                                    put("status", statusJson)
                                    put("type", typeJson)
                                    put("sortBy", JsonPrimitive("popularity"))
                                    put(
                                        "rating",
                                        buildJsonObject {
                                            metaValues["filters.rating.min"] = undefined
                                            metaValues["filters.rating.max"] = undefined
                                            put("min", JsonNull)
                                            put("max", JsonNull)
                                        },
                                    )
                                    put(
                                        "yearRange",
                                        buildJsonObject {
                                            metaValues["filters.yearRange.from"] = undefined
                                            metaValues["filters.yearRange.to"] = undefined
                                            put("from", JsonNull)
                                            put("to", JsonNull)
                                        },
                                    )
                                    put(
                                        "chapterCount",
                                        buildJsonObject {
                                            metaValues["filters.chapterCount.min"] = undefined
                                            metaValues["filters.chapterCount.max"] = undefined
                                            put("min", JsonNull)
                                            put("max", JsonNull)
                                        },
                                    )
                                    put("isColored", undefinedField(metaValues, "filters.isColored", undefined))
                                    put("isCompleted", undefinedField(metaValues, "filters.isCompleted", undefined))
                                    put("hasAdaptation", undefinedField(metaValues, "filters.hasAdaptation", undefined))
                                },
                            )
                            put("limit", JsonPrimitive(PAGE_LIMIT))
                            put("page", JsonPrimitive(page))
                            put("sortBy", JsonPrimitive(sortBy))
                            put("sortOrder", JsonPrimitive("desc"))
                        },
                    )
                    put(
                        "meta",
                        buildJsonObject {
                            put(
                                "values",
                                buildJsonObject {
                                    metaValues.forEach { (path, marker) -> put(path, marker) }
                                },
                            )
                            put("v", JsonPrimitive(1))
                        },
                    )
                },
            )
        }
        return input.toString()
    }

    private fun undefinedField(
        metaValues: MutableMap<String, JsonElement>,
        path: String,
        undefined: JsonElement,
    ): JsonElement {
        metaValues[path] = undefined
        return JsonNull
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching get() = true

    @kotlinx.serialization.Serializable
    private data class FilterDataDto(
        val genres: List<Pair<String, String>>? = null,
    )

    override suspend fun fetchFilterData(): kotlinx.serialization.json.JsonElement {
        val input = "{\"0\":{\"json\":null,\"meta\":{\"values\":[\"undefined\"]}}}"
        val genres = runCatching {
            client.newCall(trpc("content.getGenres", input)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<Pair<String, String>>()
                val data = response.parseAs<List<TrpcBatch<GenresDto>>>()
                    .firstOrNull()?.result?.data?.json
                    ?: return@use emptyList<Pair<String, String>>()
                data.genres
                    // The raw table carries ~450 rows, many scraped junk with
                    // zero series ("4.2Your Rating", counters, alt titles).
                    .filter { genre ->
                        val name = genre.name.trim()
                        (genre.seriesCount ?: 0) > 0 &&
                            name.length in 2..40 &&
                            !name.first().isDigit()
                    }
                    .map { genre -> genre.name.trim() to genre.slug }
                    .distinctBy { it.second }
            }
        }.getOrDefault(emptyList())
        return FilterDataDto(genres).toJsonElement()
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList {
        val genres = data
            ?.let { runCatching { it.parseAs<FilterDataDto>() }.getOrNull() }
            ?.genres
        return getFilterListInternal(genres)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$displayBase/manga/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val slug = when {
            segments.size >= 2 && segments[0] == "manga" -> segments[1]
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
        val input = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put("json", buildJsonObject { put("slug", JsonPrimitive(manga.url)) })
                },
            )
        }.toString()
        val response = client.newCall(trpc("content.getSeriesBySlug", input)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading series details")
            val data = it.parseAs<List<TrpcBatch<SeriesDetailsDto>>>()
                .firstOrNull()?.result?.data?.json
                ?: throw IOException("Unexpected MangaDar details answer")
            // The details payload omits its own slug: keep the slug the
            // lookup was made with, otherwise SManga.url degrades to the
            // mongo id and every later refresh 404s.
            return data.toSManga().apply { url = manga.url }
        }
    }

    // ============================= Chapters ==============================

    private suspend fun fetchChapterListInternal(manga: SManga): List<SChapter> {
        // The details call resolves the series id; reuse it when the reader
        // flow already fetched details, otherwise resolve it cheaply here.
        val seriesId = resolveSeriesId(manga.url)
        val input = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put(
                        "json",
                        buildJsonObject {
                            put("seriesId", JsonPrimitive(seriesId))
                            put("limit", JsonPrimitive(-1))
                            put("includeUnavailable", JsonPrimitive(true))
                        },
                    )
                },
            )
        }.toString()
        val response = client.newCall(trpc("content.getChapters", input)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading chapters")
            val data = it.parseAs<List<TrpcBatch<ChapterListDto>>>()
                .firstOrNull()?.result?.data?.json
                ?: throw IOException("Unexpected MangaDar chapters answer")
            val mapped = data.chapters
                .filter { chapter -> chapter.isBlocked != true }
                .map { chapter ->
                    chapter.toSChapter(manga.url).apply {
                        date_upload = parseDate(chapter.publishedAt ?: chapter.createdAt)
                    }
                }
                // Mihon expects newest-first; the API answers newest-first already.
                .sortedByDescending { it.chapter_number }
            return combineDuplicateChapters(mapped)
        }
    }

    private suspend fun resolveSeriesId(slug: String): String {
        val input = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put("json", buildJsonObject { put("slug", JsonPrimitive(slug)) })
                },
            )
        }.toString()
        val response = client.newCall(trpc("content.getSeriesBySlug", input)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while resolving the series id")
            val data = it.parseAs<List<TrpcBatch<SeriesDetailsDto>>>()
                .firstOrNull()?.result?.data?.json
                ?: throw IOException("Unexpected MangaDar details answer")
            return data.id
        }
    }

    /**
     * Optional "combine duplicate chapters" — the same option ProComic ships.
     * The platform enforces unique chapter numbers today (recon probed two
     * long series and found none), but it re-imports catalogs, so the TOC
     * can carry repeats. When enabled, one entry per non-zero chapter
     * number survives — the newest upload wins (the list is newest-first).
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

    override fun getChapterUrl(chapter: SChapter): String {
        val ref = chapter.url.parseAs<ChapterRef>()
        return "$displayBase/manga/${ref.s}/chapter/${trimChapterNumber(ref.n)}"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = chapter.url.parseAs<ChapterRef>()
        val numberJson: JsonPrimitive =
            if (ref.n == ref.n.toLong().toFloat()) {
                JsonPrimitive(ref.n.toLong())
            } else {
                JsonPrimitive(ref.n)
            }
        val input = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put(
                        "json",
                        buildJsonObject {
                            put("seriesSlug", JsonPrimitive(ref.s))
                            put("chapterNumber", numberJson)
                        },
                    )
                },
            )
        }.toString()
        val response = client.newCall(trpc("content.getChapterPagesWeb", input)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading chapter pages")
            val data = it.parseAs<List<TrpcBatch<ChapterPagesDto>>>()
                .firstOrNull()?.result?.data?.json
                ?: throw IOException("Unexpected MangaDar pages answer")
            return data.pages
                .filter(String::isNotBlank)
                .mapIndexed { index, imageUrl ->
                    Page(index = index, url = getChapterUrl(chapter), imageUrl = imageUrl)
                }
                .ifEmpty { throw IOException("No pages found for this chapter") }
        }
    }

    private fun trimChapterNumber(n: Float): String = if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()

    // ======================== MangaDot description =======================

    /**
     * MangaDot/ProComic-style description block:
     *   ★★★★★ 4.67
     *   **Type:** Manga · **Status:** Ongoing · **Chapters:** 374 · **39 ratings**
     *   ---
     *   <synopsis>
     *   **Genres:** ...
     *   **Links:** ...
     *   **Alternative Names:** ...
     */
    private fun SeriesRowDto.toSManga() = SManga.create().apply {
        fillCommon(this@toSManga.displayTitle(), this@toSManga.slug, this@toSManga.coverUrl)
        author = this@toSManga.author?.takeIf(String::isNotBlank)
        artist = this@toSManga.artist?.takeIf(String::isNotBlank)
        status = statusFrom(this@toSManga.status)
        genre = genreField(this@toSManga.genres, this@toSManga.tags)
        description = buildDescription(
            rating = this@toSManga.ratingValue(),
            ratingCount = this@toSManga.ratingCount ?: 0,
            type = this@toSManga.type,
            statusText = this@toSManga.status,
            chapterCount = this@toSManga.chapterCount,
            viewCount = this@toSManga.viewCount,
            synopsis = this@toSManga.description,
            genres = this@toSManga.genres,
            tags = this@toSManga.tags,
            altNames = null,
            slug = this@toSManga.slug,
        )
        initialized = true
    }

    private fun SeriesDetailsDto.toSManga() = SManga.create().apply {
        fillCommon(this@toSManga.displayTitle(), this@toSManga.slugOrId(), this@toSManga.coverUrl)
        author = this@toSManga.author?.takeIf(String::isNotBlank)
        artist = this@toSManga.artist?.takeIf(String::isNotBlank)
        status = statusFrom(this@toSManga.status)
        genre = genreField(this@toSManga.genres, this@toSManga.tags)
        description = buildDescription(
            rating = this@toSManga.ratingValue(),
            ratingCount = this@toSManga.ratingCountValue() ?: 0,
            type = this@toSManga.type,
            statusText = this@toSManga.status,
            chapterCount = this@toSManga.chapterCount(),
            viewCount = this@toSManga.viewCount(),
            synopsis = this@toSManga.description,
            genres = this@toSManga.genres,
            tags = this@toSManga.tags,
            altNames = this@toSManga.alternativeTitleNames(),
            slug = this@toSManga.slugOrId(),
        )
        initialized = true
    }

    private fun SeriesDetailsDto.slugOrId(): String = id

    private fun SManga.fillCommon(title: String, slug: String, cover: String?) {
        this.title = title
        url = slug
        thumbnail_url = cover
            ?.takeIf(String::isNotBlank)
            ?.let { if (it.startsWith("http")) it else "$apiBase$it" }
    }

    private fun statusFrom(raw: String?): Int = when (raw?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    /** Genre chips: genres + (optional) tags, minus the blocked list. */
    private fun genreField(genres: List<JsonElement>, tags: List<JsonElement>): String {
        val labels = buildList {
            addAll(genres.mapNotNull(JsonElement::asLabel))
            if (preferences.getBoolean(PREF_SHOW_TAGS, true)) {
                addAll(tags.mapNotNull(JsonElement::asLabel))
            }
        }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
        val blocked = blockedGenres()
        return labels
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
        genres: List<JsonElement>,
        tags: List<JsonElement>,
        altNames: List<String>?,
        slug: String,
    ): String {
        val showExtraInfo = preferences.getBoolean(PREF_SHOW_EXTRA_INFO, true)
        val scorePosition = preferences.getString(PREF_SCORE_POSITION, "end") ?: "end"

        // The site rates 0..10; the description shows the familiar 5-star row.
        val stars = rating?.let {
            val half = (it / 2.0)
            val full = half.roundToInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " " +
                String.format(Locale.US, "%.2f", half)
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                type?.takeIf(String::isNotBlank)?.let {
                    append("**Type:** ${it.replaceFirstChar { c -> c.uppercase() }}")
                }
                when (statusText?.lowercase()) {
                    "ongoing" -> appendSep().append("**Status:** Ongoing")
                    "completed" -> appendSep().append("**Status:** Completed")
                    "hiatus" -> appendSep().append("**Status:** Hiatus")
                    "cancelled" -> appendSep().append("**Status:** Cancelled")
                }
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

            val genreLabels = genres.mapNotNull(JsonElement::asLabel)
                .map(String::trim)
                .filter(String::isNotEmpty)
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
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .orEmpty()
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
            summary = "Display type, status, chapter count, views and ratings"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS
            title = "Show tags in genre chips"
            summary = "Include the title's tags in the genre field"
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
        const val TAG = "MangaDar"
        const val PAGE_LIMIT = 24

        const val PREF_SHOW_TAGS = "pref_show_tags"
        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
    }
}
