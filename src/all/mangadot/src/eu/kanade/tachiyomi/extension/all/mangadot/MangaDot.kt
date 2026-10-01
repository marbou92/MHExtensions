package eu.kanade.tachiyomi.extension.all.mangadot

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * MangaDot (mangadot.net) — a Nuxt SPA over a private JSON API, behind
 * Cloudflare. Endpoint map (verified live 2026-10 against the site's own
 * Nuxt data routes, cross-checked with two open-source MangaDotNet tools):
 *
 *  - Browse/search: GET /search.data?search=<term>&sortBy=<views|latest|tracked|rating>
 *    → a Nuxt "devalue" packed payload (one flat array; object keys are "_N"
 *    pointing at field-name strings, integer property values are indexes into
 *    the same array). This route is NOT behind the Cloudflare challenge, so
 *    browse and search work before any WebView solve. The endpoint returns a
 *    fixed 28 items per response and ignores every pagination parameter it
 *    advertises (page / offset / cursor / search_after were all probed), so
 *    each listing honestly ends after 28 entries.
 *  - Details:  GET /api/manga/{id}/                → { manga: {...}, total_chapters, ... }
 *  - Chapters: GET /api/manga/{id}/chapters/list   → [ ... ] (duplicates across groups!)
 *  - Pages:    GET /api/uploads/{chapterId}/images → { images: [ { url, w, h, filename } ] }
 *
 *  The /api/ routes ARE behind the Cloudflare managed challenge — the core
 *  CloudflareSolverInterceptor (the v37 solver ManhuaRMTL uses) auto-solves
 *  what it can and hands the rest to the app's WebView flow.
 *
 * Description presentation and settings mirror the Comix extension on purpose
 * (score stars + bold info line + alternative names, all toggleable).
 */
@Source
abstract class MangaDot :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(CloudflareSolverInterceptor(setOf(baseUrl.toHttpUrl().host)))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ========================================================================
    // Browse / search (the open /search.data route)
    // ========================================================================

    override fun popularMangaRequest(page: Int): Request = searchRequest(BROWSE_TERM, SORT_VIEWS)

    override fun popularMangaParse(response: Response): MangasPage = searchParse(response)

    override fun latestUpdatesRequest(page: Int): Request = searchRequest(BROWSE_TERM, SORT_LATEST)

    override fun latestUpdatesParse(response: Response): MangasPage = searchParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortFilter = filters.firstInstance<SortFilter>()
        val sortBy: String? = sortOptions.getOrNull(sortFilter?.state?.index ?: 0)
        return searchRequest(query.trim().ifEmpty { BROWSE_TERM }, sortBy)
    }

    override fun searchMangaParse(response: Response): MangasPage = searchParse(response)

    private fun searchRequest(term: String, sortBy: String?): Request {
        val url = "$baseUrl/search.data".toHttpUrl().newBuilder()
            .addQueryParameter("search", term)
            .apply { if (sortBy != null) addQueryParameter("sortBy", sortBy) }
            .build()
        return GET(url, apiHeaders)
    }

    private fun searchParse(response: Response): MangasPage {
        val body = response.body.string()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return MangasPage(emptyList(), false) }
        val payload = findPayload(arr) ?: return MangasPage(emptyList(), false)
        val list = payload["manga_list"] as? JsonArray ?: return MangasPage(emptyList(), false)

        val hideExplicit = preferences.hideExplicit()
        val mangas = list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val title = obj.str("title") ?: return@mapNotNull null
            if (hideExplicit && obj.bool("is_blurworthy")) return@mapNotNull null
            SManga.create().apply {
                url = id
                this.title = title
                thumbnail_url = obj.str("photo")?.absoluteUrl()
            }
        }
        // The endpoint returns a fixed 28-item page and ignores pagination
        // parameters — advertise the end honestly instead of looping.
        return MangasPage(mangas, hasNextPage = false)
    }

    /**
     * Finds the SearchPage payload inside the devalue flat array (the object
     * that follows the literal "payload" string) and returns it fully
     * resolved, with object keys renamed from "_N" to their field names.
     */
    private fun findPayload(arr: JsonArray): JsonObject? {
        for (i in 0 until arr.size - 1) {
            val item = arr[i]
            if (item is JsonPrimitive && item.content == "payload") {
                val wrapper = arr[i + 1]
                if (wrapper is JsonObject) return resolveDevalue(arr, wrapper) as? JsonObject
            }
        }
        return null
    }

    /**
     * Resolves the Nuxt devalue packed format:
     *  - object keys "_N" name the field stored at array index N
     *  - integer property values are indexes into the same array
     * A resolved primitive is returned AS-IS (never re-resolved — a year
     * value like 1999 must not become data[1999]); only objects and arrays
     * recurse.
     */
    private fun resolveDevalue(arr: JsonArray, element: JsonElement, depth: Int = 0): JsonElement {
        if (depth > 12) return element
        return when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    val name = key.removePrefix("_").toIntOrNull()
                        ?.let { (arr.getOrNull(it) as? JsonPrimitive)?.content } ?: key
                    put(name, resolveDevalue(arr, value, depth + 1))
                }
            }
            is JsonArray -> JsonArray(element.map { resolveDevalue(arr, it, depth + 1) })
            is JsonPrimitive -> {
                val idx = element.content.toIntOrNull()
                if (idx != null && idx >= 0 && idx < arr.size) {
                    when (val target = arr[idx]) {
                        is JsonObject -> resolveDevalue(arr, target, depth + 1)
                        is JsonArray -> resolveDevalue(arr, target, depth + 1)
                        else -> target
                    }
                } else {
                    element
                }
            }
        }
    }

    // ========================================================================
    // Details
    // ========================================================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/api/manga/${manga.url}/", apiHeaders)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val m = root["manga"] as? JsonObject ?: root

        val authors = m["authors"].stringList()
        val artists = m["artists"].stringList()
        val altTitles = m["alt_titles"].stringList()

        // Score stars (Comix style): avg_rating is 0-10, five stars total.
        val avg = m.str("avg_rating")?.toDoubleOrNull()
        val ratingCount = m.str("rating_count")?.toDoubleOrNull()?.toInt() ?: 0
        val hasScore = avg != null && avg > 0.0 && ratingCount > 0
        val stars = if (hasScore) {
            val full = (avg / 2.0).toInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " " + trimFloat(avg)
        } else {
            null
        }

        val infoLine = if (preferences.showExtraInfo()) {
            buildString {
                val year = m.str("year")?.toDoubleOrNull()?.toInt()
                if (year != null && year > 0) append("**Year:** $year")
                val chapters = m.str("chapter_count")?.toDoubleOrNull()?.toInt() ?: 0
                if (chapters > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Chapters:** $chapters")
                }
                val tracked = m.str("tracked_count")?.toDoubleOrNull()?.toInt() ?: 0
                if (tracked > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Tracked:** $tracked")
                }
                val rating = m.str("content_rating")
                if (!rating.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Content Rating:** ${rating.replaceFirstChar { it.uppercase() }}")
                }
                if (hasScore) {
                    if (isNotEmpty()) append(" · ")
                    append("**$ratingCount ratings**")
                }
            }.ifBlank { null }
        } else {
            null
        }

        val blockedGenres = preferences.getBlockedGenres()
        val genreChips = m["genres"].stringList()
            .filterNot { it.lowercase() in blockedGenres }
            .joinToString(", ")
            .ifBlank { null }

        val synopsis = m.str("description")
        val showAltNames = preferences.showAltNames()
        val scorePosition = preferences.getScorePosition()

        val desc = buildString {
            if (scorePosition == "top" && stars != null) {
                append(stars).append("\n")
                if (infoLine != null) {
                    append(infoLine).append("\n\n")
                }
            }

            synopsis?.let { append(it) }

            if (showAltNames && altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }

            if (scorePosition == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
                if (infoLine != null) {
                    append("\n").append(infoLine)
                }
            }

            if (scorePosition == "none" && infoLine != null) {
                if (isNotEmpty()) append("\n\n")
                append(infoLine)
            }
        }.trim()

        return SManga.create().apply {
            url = m.str("id") ?: "0"
            title = m.str("title").orEmpty()
            author = authors.joinToString(", ").ifBlank { null }
            artist = artists.joinToString(", ").ifBlank { null }
            genre = genreChips
            description = desc.ifBlank { synopsis }
            status = when (m.str("status")?.lowercase()) {
                "ongoing", "releasing" -> SManga.ONGOING
                "completed", "finished" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "cancelled", "canceled", "dropped", "discontinued" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = m.str("photo")?.absoluteUrl()
            initialized = true
        }
    }

    // ========================================================================
    // Chapters
    // ========================================================================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/api/manga/${manga.url}/chapters/list", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse { return emptyList() }
        val arr = root as? JsonArray
            ?: (root as? JsonObject)?.values?.firstOrNull { it is JsonArray } as? JsonArray
            ?: return emptyList()

        var items = arr.mapNotNull { it as? JsonObject }

        // Language preference first (site chapters carry "en"/"ko"/"zh"/...).
        val langPref = preferences.getChapterLanguage().trim().lowercase()
        if (langPref.isNotBlank()) {
            items = items.filter { it.str("language")?.lowercase() == langPref }
        }

        // Deduplicate chapters by number — the site lists the same chapter
        // once per uploading group. Keep the "best" version of each.
        if (preferences.deduplicateChapters()) {
            val bestByKey = linkedMapOf<String, JsonObject>()
            for (dto in items) {
                val key = "${dto.str("volume_number")}-${dto.str("chapter_number")}"
                val existing = bestByKey[key]
                if (existing == null || isBetterChapter(dto, existing)) bestByKey[key] = dto
            }
            val best = bestByKey.values.toSet()
            items = items.filter { it in best }
        }

        // Scanlator preference.
        val scanlatorPref = preferences.getScanlatorFilter()
        if (scanlatorPref.isNotBlank()) {
            val tokens = scanlatorPref.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            items = items.filter { dto ->
                scanlatorNames(dto).any { name -> tokens.any { token -> name.contains(token, ignoreCase = true) } }
            }
        }

        return items.map { it.toSChapter() }
    }

    /** Group-uploaded chapters beat scraper imports; then more pages wins. */
    private fun isBetterChapter(a: JsonObject, b: JsonObject): Boolean {
        val aUser = a.str("source") == "user"
        val bUser = b.str("source") == "user"
        if (aUser != bUser) return aUser
        val aPages = a.str("page_count")?.toDoubleOrNull() ?: 0.0
        val bPages = b.str("page_count")?.toDoubleOrNull() ?: 0.0
        return aPages > bPages
    }

    private fun scanlatorNames(dto: JsonObject): List<String> = buildList {
        dto.str("scanlator_name")?.let { add(it) }
        dto.str("group_name")?.let { add(it) }
        (dto["groups"] as? JsonArray)?.forEach { group ->
            (group as? JsonObject)?.str("name")?.let { add(it) }
        }
    }.distinct()

    private fun JsonObject.toSChapter(): SChapter {
        val id = str("id") ?: "0"
        val num = str("chapter_number")?.toDoubleOrNull()
        val vol = str("volume_number")?.toDoubleOrNull()
        val title = str("chapter_title")
        return SChapter.create().apply {
            url = id
            name = buildString {
                if (vol != null && vol > 0.0) {
                    append("Vol. ").append(trimFloat(vol)).append(" ")
                }
                if (num != null && num > 0.0) {
                    append("Ch. ").append(trimFloat(num))
                }
                if (!title.isNullOrBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(title)
                }
                if (isEmpty()) append("Chapter $id")
            }
            chapter_number = num?.toFloat() ?: -1f
            date_upload = parseDate(str("date_added"))
            scanlator = scanlatorNames(this@toSChapter).joinToString(", ").ifBlank { null }
        }
    }

    // ========================================================================
    // Pages
    // ========================================================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/api/uploads/${chapter.url}/images", apiHeaders)

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/"

    override fun pageListParse(response: Response): List<Page> {
        val root = runCatching { json.parseToJsonElement(response.body.string()).jsonObject }.getOrElse { return emptyList() }
        val images = root["images"] as? JsonArray ?: return emptyList()
        return images.mapIndexedNotNull { index, element ->
            val img = element as? JsonObject ?: return@mapIndexedNotNull null
            val url = img.str("url")?.takeIf { it.isNotBlank() }?.absoluteUrl() ?: return@mapIndexedNotNull null
            Page(index, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(SortFilter())

    /** null = no sortBy parameter (the endpoint's relevance order). */
    private val sortOptions = listOf<String?>(null, SORT_VIEWS, SORT_LATEST, SORT_TRACKED, SORT_RATING)

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf("Relevance", "Most viewed", "Latest update", "Most tracked", "Highest rated"),
            Selection(0, false),
        )

    private inline fun <reified T : Filter<*>> FilterList.firstInstance(): T? = filterIsInstance<T>().firstOrNull()

    // ========================================================================
    // Settings / Preferences (mirrors Comix, adapted to MangaDot's data)
    // ========================================================================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_EXPLICIT
            title = "Hide explicit results"
            summary = "Filter blur-marked (potentially explicit) manga out of browse and search"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "Keep only one chapter per number (multiple groups upload the same chapter)"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_FILTER
            title = "Scanlator filter"
            summary = "Comma-separated scanlator names to show (empty = show all)"
            setDefaultValue("")
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CHAPTER_LANGUAGE
            title = "Chapter language filter"
            summary = "Only show chapters in this language, e.g. en (empty = all languages)"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display year, chapter count, tracked users, content rating and ratings"
            setDefaultValue(true)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.hideExplicit(): Boolean = getBoolean(PREF_HIDE_EXPLICIT, false)

    private fun android.content.SharedPreferences.getBlockedGenres(): List<String> = getString(PREF_BLOCKED_GENRES, "")
        ?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }
        ?: emptyList()

    private fun android.content.SharedPreferences.deduplicateChapters(): Boolean = getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)

    private fun android.content.SharedPreferences.getScanlatorFilter(): String = getString(PREF_SCANLATOR_FILTER, "") ?: ""

    private fun android.content.SharedPreferences.getChapterLanguage(): String = getString(PREF_CHAPTER_LANGUAGE, "") ?: ""

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.getScorePosition(): String = getString(PREF_SCORE_POSITION, "end") ?: "end"

    // ========================================================================
    // Parsing helpers
    // ========================================================================

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"

    /** Handles both real arrays and JSON-encoded string lists (the API mixes both). */
    private fun JsonElement?.stringList(): List<String> = when (this) {
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
        is JsonPrimitive -> if (this is JsonNull) {
            emptyList()
        } else {
            runCatching {
                (json.parseToJsonElement(content) as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
            }.getOrNull().orEmpty()
        }
        else -> emptyList()
    }.filter { it.isNotBlank() }

    private fun String.absoluteUrl(): String = when {
        startsWith("http") -> this
        startsWith("/") -> "$baseUrl$this"
        else -> "$baseUrl/$this"
    }

    private fun trimFloat(value: Double): String = value.toString().removeSuffix(".0")

    private val dateFormats by lazy {
        listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        ).map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = true
            }
        }
    }

    private val relativeDateRegex = Regex("""(\d+)\s*(s|sec|m|min|h|hr|d|day|w|wk|mo|mos|y|yr)s?\s*ago""")

    /** Epoch millis, ISO 8601, "3d ago" — whatever the chapter carries. */
    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L

        raw.toLongOrNull()?.let { return if (it > 10_000_000_000L) it else it * 1000 }

        val normalized = raw
            .replace(Regex("""\.\d+"""), "")
            .replace(Regex("""(Z|[+-]\d{2}:?\d{2})$"""), "")
        for (format in dateFormats) {
            try {
                return format.parse(normalized)?.time ?: continue
            } catch (_: ParseException) {
                // try the next pattern
            }
        }

        val match = relativeDateRegex.find(raw) ?: return 0L
        val (numStr, unit) = match.destructured
        val num = numStr.toLongOrNull() ?: return 0L
        val millis = when (unit) {
            "s", "sec" -> num * 1000L
            "m", "min" -> num * 60_000L
            "h", "hr" -> num * 3_600_000L
            "d", "day" -> num * 86_400_000L
            "w", "wk" -> num * 7 * 86_400_000L
            "mo", "mos" -> num * 30 * 86_400_000L
            "y", "yr" -> num * 365 * 86_400_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - millis
    }

    private companion object {
        private const val BROWSE_TERM = "a"

        private const val SORT_VIEWS = "views"
        private const val SORT_LATEST = "latest"
        private const val SORT_TRACKED = "tracked"
        private const val SORT_RATING = "rating"

        private const val PREF_HIDE_EXPLICIT = "pref_hide_explicit"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SCANLATOR_FILTER = "pref_scanlator_filter"
        private const val PREF_CHAPTER_LANGUAGE = "pref_chapter_language"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}
