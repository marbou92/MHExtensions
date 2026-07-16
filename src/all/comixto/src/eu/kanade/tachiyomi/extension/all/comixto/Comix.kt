package eu.kanade.tachiyomi.extension.all.comixto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
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
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@Source
abstract class Comix :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::signRequestInterceptor)
        .addInterceptor(::decryptResponseInterceptor)
        .build()

    // Default headers: only Referer (safe for both API and image requests)
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // API-specific headers (JSON + XHR) — used only for /api/v1/ calls
    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // ========================================================================
    // API
    // ========================================================================

    private val apiBaseUrl = "$baseUrl/api/v1"

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        // "Most followed" as popular
        return mangaListRequest(page, sortBy = "follows_total", query = null)
    }

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = mangaListRequest(page, sortBy = "chapter_updated_at", query = null)

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortFilter = filters.firstInstance<SortFilter>()
        val sortIndex = sortFilter?.state?.index ?: 0
        val sortAscending = sortFilter?.state?.ascending ?: false
        val sortBy = sortOptions[sortIndex] ?: "relevance"
        val sortDir = if (sortAscending) "asc" else "desc"

        // Use defaults from preferences, override with filter selections if any
        val defaultTypes = preferences.getDefaultTypes()
        val defaultDemos = preferences.getDefaultDemographics()
        val defaultContentRatings = preferences.getContentRatings()

        val types = filters.firstInstance<TypeFilter>()?.state?.filter { it.state }?.map { it.value }
            ?.ifEmpty { defaultTypes }
        val statuses = filters.firstInstance<StatusFilter>()?.state?.filter { it.state }?.map { it.value } ?: emptyList()
        val demographics = filters.firstInstance<DemographicFilter>()?.state?.filter { it.state }?.map { it.id }
            ?.ifEmpty { defaultDemos.mapNotNull { it.toIntOrNull() } }
        val genresIncl = filters.firstInstance<GenreFilter>()?.state?.filter { it.state }?.map { it.id } ?: emptyList()
        val contentRatings = filters.firstInstance<ContentRatingFilter>()?.state?.filter { it.state }?.map { it.value }
            ?.ifEmpty { defaultContentRatings }
        val minChapters = (filters.firstInstance<MinChaptersFilter>()?.state as? String)?.toIntOrNull()?.toString() ?: ""
        val yearFrom = (filters.firstInstance<YearFromFilter>()?.state as? String)?.toIntOrNull()?.toString() ?: ""
        val yearTo = (filters.firstInstance<YearToFilter>()?.state as? String)?.toIntOrNull()?.toString() ?: ""

        return mangaListRequest(
            page = page,
            sortBy = sortBy,
            sortDir = sortDir,
            query = query.takeIf { it.isNotBlank() },
            types = types ?: emptyList(),
            statuses = statuses,
            contentRatings = contentRatings ?: emptyList(),
            demographics = demographics ?: emptyList(),
            genresIncl = genresIncl,
            minChapters = minChapters,
            yearFrom = yearFrom,
            yearTo = yearTo,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val hid = manga.url
        return GET("$apiBaseUrl/manga/$hid", apiHeaders)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = response.parseAs<ComixMangaDetailDto>()
        return detail.toSManga()
    }

    // ============================= Chapters =============================

    override fun chapterListRequest(manga: SManga): Request {
        val hid = manga.url
        return GET("$apiBaseUrl/manga/$hid/chapters?page=1&limit=100", apiHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ComixChapterListDto>()
        val items = data.items.toMutableList()
        val hid = response.request.url.encodedPath
            .substringAfter("/manga/")
            .substringBefore("/chapters")
        var page = 1
        while (data.meta?.hasNext == true) {
            page++
            val nextReq = GET("$apiBaseUrl/manga/$hid/chapters?page=$page&limit=100", apiHeaders)
            val nextResp = client.newCall(nextReq).execute()
            val nextData = nextResp.parseAs<ComixChapterListDto>()
            items.addAll(nextData.items)
            nextResp.close()
            if (nextData.meta?.hasNext != true) break
        }

        var chapters = items.map { it.toSChapter() }

        // Deduplicate chapters by number — keep the best version of each
        if (preferences.deduplicateChapters()) {
            // Build a map of chapter_number -> best DTO, then preserve original order
            val bestByKey = mutableMapOf<Float, ComixChapterDto>()
            for (dto in items) {
                val key = dto.number ?: -1f
                val existing = bestByKey[key]
                if (existing == null || isBetterChapter(dto, existing)) {
                    bestByKey[key] = dto
                }
            }
            val bestIds = bestByKey.values.map { it.id }.toSet()
            chapters = items.filter { it.id in bestIds }.map { it.toSChapter() }
        }

        // Filter by scanlator preference
        val scanlatorPref = preferences.getScanlatorFilter()
        if (scanlatorPref.isNotBlank()) {
            chapters = chapters.filter { ch ->
                scanlatorPref.split(",").any { s ->
                    ch.scanlator?.contains(s.trim(), ignoreCase = true) == true
                }
            }
        }

        return chapters
    }

    /**
     * Returns true if [a] is a better chapter than [b] for deduplication.
     * Priority: official > more votes > more recent.
     */
    private fun isBetterChapter(a: ComixChapterDto, b: ComixChapterDto): Boolean {
        // Official chapters always win
        if (a.isOfficial == true && b.isOfficial != true) return true
        if (b.isOfficial == true && a.isOfficial != true) return false
        // Then higher votes
        val aVotes = a.votes ?: 0
        val bVotes = b.votes ?: 0
        if (aVotes != bVotes) return aVotes > bVotes
        // Then more recent (by relative date — can't parse exact, so keep original order)
        return false
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
        return GET("$apiBaseUrl/chapters/$chapterId", apiHeaders)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url is the numeric chapter ID; we cannot reconstruct the full web URL without
        // the manga slug, so we point back at the site root where the reader lives.
        return "$baseUrl/title/${chapter.url}"
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ComixChapterPagesDto>()
        val container = data.pages
        val base = container?.baseUrl.orEmpty()
        val pages = container?.items ?: emptyList()
        return pages.mapIndexed { index, pageDto ->
            val cleanUrl = (base + pageDto.url).substringBefore("?")
            // For scrambled pages (s=1), pass the URL through imageUrlParse
            // which downloads and descrambles the image
            val finalUrl = if (pageDto.s == 1) "$cleanUrl?__descramble=1" else cleanUrl
            Page(index, imageUrl = finalUrl)
        }
    }

    // Mihon downloads images using a SEPARATE client. The image CDNs require
    // a proper User-Agent and Referer, otherwise they may return a Cloudflare
    // challenge page (HTML) instead of image data, causing decoder errors.
    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers.newBuilder().add("Accept", "image/*,*/*;q=0.8").build())

    override fun imageUrlParse(response: Response): String {
        // Check if this is a descramble request (page URL had ?__descramble=1)
        val url = response.request.url.toString()
        if (!url.contains("__descramble")) {
            return throw UnsupportedOperationException()
        }
        
        // Read scramble params from response headers
        val scrambleGrid = response.header("X-Scramble-Grid") ?: return throw UnsupportedOperationException()
        val scrambleSeed = response.header("X-Scramble-Seed")?.toLongOrNull() ?: 0L
        val scrambleHash = response.header("X-Scramble-Hash") ?: ""
        val algo = if (response.header("X-Scramble-Algo") == "3") 2 else 1

        val grid = scrambleGrid.split("x")
        val cols = grid.getOrNull(0)?.toIntOrNull() ?: 5
        val rows = grid.getOrNull(1)?.toIntOrNull() ?: 5

        val hashSeed = SCRAMBLE_HASH_TABLE[scrambleHash.trim()] ?: 0
        val permSeed = (scrambleSeed xor hashSeed.toLong()) and 0xFFFFFFFFL

        val imageBytes = response.body?.bytes() ?: return throw UnsupportedOperationException()
        val bitmap = BitmapFactory.decodeStream(imageBytes.inputStream()) ?: return throw UnsupportedOperationException()

        val tileW = bitmap.width / cols
        val tileH = bitmap.height / rows
        if (tileW < 1 || tileH < 1) return throw UnsupportedOperationException()

        val order = buildOrder(permSeed, cols * rows, algo)
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        for (i in 0 until cols * rows) {
            val dst = order[i]
            val dstCol = dst % cols
            val dstRow = dst / cols
            val srcCol = i % cols
            val srcRow = i / cols
            val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        }

        val outBytes = java.io.ByteArrayOutputStream().apply {
            output.compress(Bitmap.CompressFormat.WEBP, 90, this)
        }.toByteArray()
        bitmap.recycle()
        output.recycle()

        val b64 = Base64.encodeToString(outBytes, Base64.DEFAULT)
        return "data:image/webp;base64,$b64"
    }

    // ========================================================================
    // List request builder
    // ========================================================================

    private fun mangaListRequest(
        page: Int,
        sortBy: String,
        sortDir: String = "desc",
        query: String?,
        types: List<String> = emptyList(),
        statuses: List<String> = emptyList(),
        contentRatings: List<String> = listOf("safe", "suggestive"),
        demographics: List<Int> = emptyList(),
        genresIncl: List<Int> = emptyList(),
        minChapters: String = "",
        yearFrom: String = "",
        yearTo: String = "",
    ): Request {
        val url = "$apiBaseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "28")
            .addQueryParameter("order[$sortBy]", sortDir)
            .apply {
                query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("keyword", it) }
                types.forEachIndexed { i, v -> addQueryParameter("types[$i]", v) }
                statuses.forEachIndexed { i, v -> addQueryParameter("statuses[$i]", v) }
                contentRatings.forEachIndexed { i, v -> addQueryParameter("content_rating[$i]", v) }
                demographics.forEachIndexed { i, v -> addQueryParameter("demographics[$i]", v.toString()) }
                genresIncl.forEachIndexed { i, v -> addQueryParameter("genres_in[$i]", v.toString()) }
                if (minChapters.isNotBlank()) addQueryParameter("min_chap", minChapters)
                if (yearFrom.isNotBlank()) addQueryParameter("year_from", yearFrom)
                if (yearTo.isNotBlank()) addQueryParameter("year_to", yearTo)
            }
            .build()

        return GET(url, apiHeaders)
    }

    private fun mangaListParse(response: Response): MangasPage {
        val data = response.parseAs<ComixMangaListDto>()
        val mangas = data.items.map { it.toSManga() }
        val hasNext = data.meta?.hasNext == true
        return MangasPage(mangas, hasNext)
    }

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        DemographicFilter(),
        GenreFilter(),
        ContentRatingFilter(),
        MinChaptersFilter(),
        YearFromFilter(),
        YearToFilter(),
    )

    private val sortOptions = mapOf(
        0 to "relevance",
        1 to "chapter_updated_at",
        2 to "created_at",
        3 to "title",
        4 to "year",
        5 to "score",
        6 to "views_7d",
        7 to "views_30d",
        8 to "views_90d",
        9 to "views_total",
        10 to "follows_total",
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf(
                "Relevance",
                "Latest update",
                "Recently added",
                "Title",
                "Year",
                "Highest rated",
                "Most viewed (7 days)",
                "Most viewed (30 days)",
                "Most viewed (90 days)",
                "Most viewed (all time)",
                "Most followed",
            ),
            Selection(0, false),
        )

    private class TypeFilter :
        Filter.Group<CheckboxFilter>(
            "Type",
            listOf(
                CheckboxFilter("Manga", "manga"),
                CheckboxFilter("Manhwa", "manhwa"),
                CheckboxFilter("Manhua", "manhua"),
                CheckboxFilter("Other", "other"),
            ),
        )

    private class StatusFilter :
        Filter.Group<CheckboxFilter>(
            "Status",
            listOf(
                CheckboxFilter("Releasing", "releasing"),
                CheckboxFilter("Finished", "finished"),
                CheckboxFilter("On hiatus", "on_hiatus"),
                CheckboxFilter("Discontinued", "discontinued"),
            ),
        )

    private class DemographicFilter :
        Filter.Group<IdCheckboxFilter>(
            "Demographic",
            listOf(
                IdCheckboxFilter("Shounen", 2),
                IdCheckboxFilter("Seinen", 4),
                IdCheckboxFilter("Shoujo", 1),
                IdCheckboxFilter("Josei", 3),
            ),
        )

    private class GenreFilter :
        Filter.Group<IdCheckboxFilter>(
            "Genres (include)",
            listOf(
                IdCheckboxFilter("Action", 6),
                IdCheckboxFilter("Adventure", 7),
                IdCheckboxFilter("Boys Love", 8),
                IdCheckboxFilter("Comedy", 9),
                IdCheckboxFilter("Crime", 10),
                IdCheckboxFilter("Drama", 11),
                IdCheckboxFilter("Fantasy", 12),
                IdCheckboxFilter("Girls Love", 13),
                IdCheckboxFilter("Harem", 40),
                IdCheckboxFilter("Historical", 14),
                IdCheckboxFilter("Horror", 15),
                IdCheckboxFilter("Isekai", 16),
                IdCheckboxFilter("Magical Girls", 17),
                IdCheckboxFilter("Mecha", 18),
                IdCheckboxFilter("Medical", 19),
                IdCheckboxFilter("Mystery", 20),
                IdCheckboxFilter("Philosophical", 21),
                IdCheckboxFilter("Psychological", 22),
                IdCheckboxFilter("Romance", 23),
                IdCheckboxFilter("Sci-Fi", 24),
                IdCheckboxFilter("Slice of Life", 25),
                IdCheckboxFilter("Sports", 26),
                IdCheckboxFilter("Superhero", 27),
                IdCheckboxFilter("Thriller", 28),
                IdCheckboxFilter("Tragedy", 29),
                IdCheckboxFilter("Wuxia", 30),
                IdCheckboxFilter("Adult", 87264),
                IdCheckboxFilter("Ecchi", 87265),
                IdCheckboxFilter("Hentai", 87266),
                IdCheckboxFilter("Mature", 87267),
                IdCheckboxFilter("Smut", 87268),
            ),
        )

    private class ContentRatingFilter :
        Filter.Group<CheckboxFilter>(
            "Content rating",
            listOf(
                CheckboxFilter("Safe", "safe", true),
                CheckboxFilter("Suggestive", "suggestive", true),
                CheckboxFilter("Erotica", "erotica"),
                CheckboxFilter("Pornographic", "pornographic"),
            ),
        )

    private class MinChaptersFilter : Filter.Text("Min chapters", "")

    private class YearFromFilter : Filter.Text("Year from", "")

    private class YearToFilter : Filter.Text("Year to", "")

    private class CheckboxFilter(name: String, val value: String, default: Boolean = false) : Filter.CheckBox(name, default)

    private class IdCheckboxFilter(name: String, val id: Int, default: Boolean = false) : Filter.CheckBox(name, default)

    // ========================================================================
    // Sign + Decrypt (reverse-engineered API protection)
    // ========================================================================

    /**
     * Intercepts outgoing GET requests to the manga and chapters API endpoints
     * and appends the `_` signature query parameter that the server validates.
     *
     * The signature is a 3-stage chained S-box substitution (base64url encoded) over
     * the request path (minus the `/api/v1` prefix) plus the sorted query string.
     */
    private fun signRequestInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)

        val path = request.url.encodedPath
        // Only sign the protected endpoints
        if (!path.startsWith("/api/v1/manga") && !path.matches(Regex("^/api/v1/chapters/[^/]+$"))) {
            return chain.proceed(request)
        }

        // Build the "normalized" path+query that the server expects for signing:
        //  - strip the /api/v1 prefix
        //  - strip the existing _ param (if any)
        //  - serialize remaining params as raw "key=value" with sorted keys, arrays as key[0], key[1]...
        val normalizedPath = path.removePrefix("/api/v1")
        val paramsToSign = request.url.queryParameterNames
            .filter { it != "_" }
            .sorted()

        val queryParts = mutableListOf<String>()
        for (name in paramsToSign) {
            val values = request.url.queryParameterValues(name)
            if (values.size == 1 && !name.endsWith("[]")) {
                queryParts.add("$name=${values[0]}")
            } else {
                values.forEachIndexed { i, v ->
                    val baseName = name.removeSuffix("[]")
                    queryParts.add("$baseName[$i]=$v")
                }
            }
        }

        val toSign = if (queryParts.isEmpty()) {
            normalizedPath
        } else {
            "$normalizedPath?${queryParts.joinToString("&")}"
        }

        val signature = sign(toSign)

        val newUrl = request.url.newBuilder()
            .removeAllQueryParameters("_")
            .addQueryParameter("_", signature)
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }

    /**
     * Intercepts responses: decrypts `x-enc: 1` bodies, then unwraps the
     * `{"status":"ok","result":...}` envelope so parseAs gets the inner data.
     */
    private fun decryptResponseInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Only process API responses — never touch image downloads
        val path = response.request.url.encodedPath
        if (!path.startsWith("/api/v1/")) return response

        val body = response.body ?: return response
        var content = body.string()

        // Step 1: Decrypt if x-enc: 1
        if (response.headers["x-enc"] == "1") {
            content = try {
                val encrypted = content.parseAs<ComixEncryptedDto>()
                decrypt(encrypted.e)
            } catch (_: Exception) {
                content
            }
        }

        // Step 2: Unwrap {"status":"ok","result":...} envelope
        content = try {
            val json = org.json.JSONObject(content)
            if (json.optString("status") == "ok" && json.has("result")) {
                json.get("result").toString()
            } else {
                content
            }
        } catch (_: Exception) {
            content
        }

        return response.newBuilder()
            .body(content.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Generates a permutation of [count] elements using a seeded PRNG.
     * Implements the exact algorithm from the comix.to WASM (decompiled via wasm2wat).
     * V2 uses xorshift(21,1,5) + accumulator mixing + Fisher-Yates shuffle.
     */
    private fun buildOrder(seed: Long, count: Int, algo: Int): IntArray {
        val arr = IntArray(count) { it }
        if (count < 2) return arr

        var state = (seed.toInt() or 1)

        if (algo == 2) {
            // buildOrderV2: shifts 21, 1, 5 with accumulator
            val shl1 = 21
            val shr = 1
            val shl2 = 5
            var acc = 0

            // Initialize array and accumulator
            for (i in 0 until count) {
                arr[i] = i
                acc = (acc xor i) + i * shl1
            }

            // Fisher-Yates shuffle
            var n = count
            while (n >= 2) {
                state = state xor (state shl shl1)
                acc += state * n
                state = state xor (state ushr shr)
                acc = (acc shl 9 or (acc ushr 23)) xor state
                state = state xor (state shl shl2)

                val j = java.lang.Integer.remainderUnsigned(state, n)
                val oldN1 = arr[n - 1]
                val oldJ = arr[j]
                arr[n - 1] = oldJ
                arr[j] = oldN1
                acc = (acc shl 5) xor (oldN1 + oldJ)
                n--
            }
        } else {
            // buildOrderV1: LCG-based Fisher-Yates
            var n = count
            while (n >= 2) {
                state = state * 22695477 + 1
                val j = ((state ushr 16) and 0x7FFFFFFF) % n
                val tmp = arr[n - 1]
                arr[n - 1] = arr[j]
                arr[j] = tmp
                n--
            }
        }

        return arr
    }

    // --- S-box constants (extracted from the site JS) ---

    private val sbox1 = Base64.decode(SBOX1_B64, Base64.DEFAULT)
    private val key1 = Base64.decode(KEY1_B64, Base64.DEFAULT)
    private val sbox2 = Base64.decode(SBOX2_B64, Base64.DEFAULT)
    private val key2 = Base64.decode(KEY2_B64, Base64.DEFAULT)
    private val sbox3 = Base64.decode(SBOX3_B64, Base64.DEFAULT)
    private val key3 = Base64.decode(KEY3_B64, Base64.DEFAULT)

    // Inverse S-boxes for decryption
    private val invSbox1: IntArray = IntArray(256).also { inv -> sbox1.forEachIndexed { i, v -> inv[v.toInt() and 0xFF] = i } }
    private val invSbox2: IntArray = IntArray(256).also { inv -> sbox2.forEachIndexed { i, v -> inv[v.toInt() and 0xFF] = i } }
    private val invSbox3: IntArray = IntArray(256).also { inv -> sbox3.forEachIndexed { i, v -> inv[v.toInt() and 0xFF] = i } }

    private fun sboxTransform(data: ByteArray, sbox: ByteArray, key: ByteArray, seed: Int): ByteArray {
        val out = ByteArray(data.size)
        var u = seed
        for (a in data.indices) {
            val f = sbox[255 and (data[a].toInt() and 0xFF xor (key[a % key.size].toInt() and 0xFF) xor u)]
            out[a] = f
            u = f.toInt() and 0xFF
        }
        return out
    }

    private fun invSboxTransform(data: ByteArray, invSbox: IntArray, key: ByteArray, seed: Int): ByteArray {
        val out = ByteArray(data.size)
        var u = seed
        for (a in data.indices) {
            val orig = invSbox[data[a].toInt() and 0xFF] xor (key[a % key.size].toInt() and 0xFF) xor u
            out[a] = orig.toByte()
            u = data[a].toInt() and 0xFF
        }
        return out
    }

    private fun sign(input: String): String {
        var bytes = input.toByteArray(Charsets.UTF_8)
        bytes = sboxTransform(bytes, sbox1, key1, 189)
        bytes = sboxTransform(bytes, sbox2, key2, 133)
        bytes = sboxTransform(bytes, sbox3, key3, 32)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun decrypt(eField: String): String {
        val raw = Base64.decode(eField, Base64.URL_SAFE)
        var t = raw
        // Reverse order: undo stage 3, then 2, then 1
        t = invSboxTransform(t, invSbox3, key3, 32)
        t = invSboxTransform(t, invSbox2, key2, 133)
        t = invSboxTransform(t, invSbox1, key1, 189)
        return String(t, Charsets.UTF_8)
    }

    // ========================================================================
    // DTO -> SManga / SChapter conversions
    // ========================================================================

    private fun ComixMangaListItemDto.toSManga(): SManga = SManga.create().apply {
        url = hid
        title = this@toSManga.title
        thumbnail_url = poster?.large ?: poster?.medium
    }

    private fun ComixMangaDetailDto.toSManga(): SManga {
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()
        val showTagsInGenre = preferences.showTagsInGenre()
        val blockedGenres = preferences.getBlockedGenres()
        val scorePosition = preferences.getScorePosition()

        // Build genre chips
        val genreChips = buildList {
            if (showTagsInGenre) {
                addAll(genres.map { it.title })
                addAll(demographics.map { it.title })
                addAll(formats.map { it.title })
                addAll(tags.map { it.title })
            } else {
                addAll(genres.map { it.title })
                addAll(demographics.map { it.title })
            }
        }.distinct()
            .filterNot { it.lowercase() in blockedGenres }
            .joinToString(", ")

        // Build score line (white star ☆)
        val hasScore = ratedAvg != null && ratedCount != null && ratedCount > 0
        val scoreLine = if (hasScore) "☆ $ratedAvg/10 — $ratedCount votes" else null

        // Build description
        val desc = buildString {
            // Score at top
            if (scorePosition == "top" && scoreLine != null) {
                append(scoreLine)
                append("\n\n")
            }

            synopsis?.let { append(it) }

            if (showAltNames && altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }

            if (showExtraInfo) {
                if (isNotEmpty()) append("\n\n")
                append("Type: ${type ?: "Unknown"}\n")
                append("Status: ${formatStatus(this@toSManga.status)}\n")
                append("Year: ${year ?: "?"}\n")
                append("Content: ${formatContentRating(contentRating)}\n")
                append("Original language: ${formatLanguage(originalLanguage)}\n")
                if (followsTotal != null && followsTotal > 0) {
                    append("Follows: $followsTotal\n")
                }
                if (latestChapter != null && latestChapter > 0) {
                    append("Latest chapter: ${latestChapter.toString().removeSuffix(".0")}\n")
                }
            }

            // Score at end
            if (scorePosition == "end" && scoreLine != null) {
                if (isNotEmpty()) append("\n\n")
                append(scoreLine)
            }
        }.trim()

        return SManga.create().apply {
            url = hid
            title = this@toSManga.title
            author = authors.joinToString(", ") { it.title }.ifBlank { null }
            artist = artists.joinToString(", ") { it.title }.ifBlank { null }
            genre = genreChips.ifBlank { null }
            description = desc.ifBlank { synopsis }
            status = when (this@toSManga.status) {
                "releasing" -> SManga.ONGOING
                "finished" -> SManga.COMPLETED
                "cancelled" -> SManga.CANCELLED
                "on_hiatus" -> SManga.ON_HIATUS
                "discontinued" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = poster?.large ?: poster?.medium
            initialized = true
        }
    }

    private fun formatStatus(status: String?): String = when (status) {
        "releasing" -> "Releasing"
        "finished" -> "Finished"
        "on_hiatus" -> "On hiatus"
        "discontinued" -> "Discontinued"
        "cancelled" -> "Cancelled"
        else -> "Unknown"
    }

    private fun formatContentRating(rating: String?): String = when (rating) {
        "safe" -> "Safe"
        "suggestive" -> "Suggestive"
        "erotica" -> "Erotica"
        "pornographic" -> "Pornographic"
        else -> "Unknown"
    }

    private fun formatLanguage(lang: String?): String = when (lang) {
        "ko" -> "Korean"
        "ja" -> "Japanese"
        "zh" -> "Chinese"
        "en" -> "English"
        else -> lang ?: "Unknown"
    }

    private fun ComixChapterDto.toSChapter(): SChapter {
        val chNum = number
        val chName = name
        return SChapter.create().apply {
            // Store the numeric chapter ID as the URL (used for pageListRequest)
            url = id.toString()
            name = buildString {
                if (chNum != null && chNum > 0) {
                    append("Ch. ")
                    append(chNum.toString().removeSuffix(".0"))
                }
                if (!chName.isNullOrBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(chName)
                }
                if (isEmpty()) append("Chapter ${chNum ?: id}")
            }
            chapter_number = chNum ?: -1f
            date_upload = parseRelativeDate(createdAtFormatted)
            scanlator = group?.name
        }
    }

    /**
     * Parses relative date strings like "4d ago", "1mo ago", "39s ago", "9mos ago".
     * Returns an approximate epoch-millis timestamp.
     */
    private fun parseRelativeDate(relative: String?): Long {
        if (relative.isNullOrBlank()) return 0L
        val now = System.currentTimeMillis()
        val regex = Regex("""(\d+)\s*(s|sec|m|min|h|hr|d|day|w|wk|mo|mos|y|yr)s?\s*ago""")
        val match = regex.find(relative) ?: return 0L
        val (numStr, unit) = match.destructured
        val num = numStr.toLongOrNull() ?: return 0L
        val millis = when (unit) {
            "s", "sec" -> num * 1000
            "m", "min" -> num * 60 * 1000
            "h", "hr" -> num * 60 * 60 * 1000
            "d", "day" -> num * 24 * 60 * 60 * 1000
            "w", "wk" -> num * 7 * 24 * 60 * 60 * 1000
            "mo", "mos" -> num * 30 * 24 * 60 * 60 * 1000
            "y", "yr" -> num * 365 * 24 * 60 * 60 * 1000
            else -> 0
        }
        return now - millis
    }

    private inline fun <reified T : Filter<*>> FilterList.firstInstance(): T? = filterIsInstance<T>().firstOrNull()

    // ========================================================================
    // Settings / Preferences
    // ========================================================================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Content rating
        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Default content rating"
            summary = "Content ratings to show by default in browse/search"
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf("safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(setOf("safe", "suggestive"))
        }.let(screen::addPreference)

        // Default type
        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_TYPES
            title = "Default type filter"
            summary = "Manga types to show by default (empty = all)"
            entries = arrayOf("Manga", "Manhwa", "Manhua", "Other")
            entryValues = arrayOf("manga", "manhwa", "manhua", "other")
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        // Default demographics
        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_DEMOGRAPHICS
            title = "Default demographic filter"
            summary = "Demographics to show by default (empty = all)"
            entries = arrayOf("Shounen", "Seinen", "Shoujo", "Josei")
            entryValues = arrayOf("shounen", "seinen", "shoujo", "josei")
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        // Blocked genres
        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
        }.let(screen::addPreference)

        // Deduplicate chapters
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "Keep only one chapter per number (useful when multiple scanlators upload the same chapter)"
            setDefaultValue(false)
        }.let(screen::addPreference)

        // Scanlator filter
        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_FILTER
            title = "Scanlator filter"
            summary = "Comma-separated scanlator names to show (empty = show all)"
            setDefaultValue("")
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
            summary = "Display type, status, year, content rating, follows, rating, latest chapter"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show tags in genre chips
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genre chips"
            summary = "Include format tags (Long Strip, Full Color, etc.) in the genre field"
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
    }

    private fun android.content.SharedPreferences.getDefaultTypes(): List<String> = getStringSet(PREF_DEFAULT_TYPES, emptySet())?.toList() ?: emptyList()

    private fun android.content.SharedPreferences.getDefaultDemographics(): List<String> = getStringSet(PREF_DEFAULT_DEMOGRAPHICS, emptySet())?.toList() ?: emptyList()

    private fun android.content.SharedPreferences.getContentRatings(): List<String> = getStringSet(PREF_CONTENT_RATING, setOf("safe", "suggestive"))?.toList() ?: listOf("safe", "suggestive")

    private fun android.content.SharedPreferences.getBlockedGenres(): List<String> = getString(PREF_BLOCKED_GENRES, "")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun android.content.SharedPreferences.deduplicateChapters(): Boolean = getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)

    private fun android.content.SharedPreferences.getScanlatorFilter(): String = getString(PREF_SCANLATOR_FILTER, "") ?: ""

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.getScorePosition(): String = getString(PREF_SCORE_POSITION, "end") ?: "end"

    companion object {
        private val SCRAMBLE_HASH_TABLE = mapOf(
            "03632" to 58414,
            "02900" to 117532,
        )

        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_DEFAULT_TYPES = "pref_default_types"
        private const val PREF_DEFAULT_DEMOGRAPHICS = "pref_default_demographics"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SCANLATOR_FILTER = "pref_scanlator_filter"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"

        private const val SBOX1_B64 = "gbicCvAMzfcXEtGAyjvvhmb2yCWzWhjqcxXZ7ZhpzANOzoQLo3nuPZ2vK9dkb9hJExC0Vni/hdQBceI+mw611gkhQFjBuf4bJg1TxYqM+SL4YDqtwjxiGSdeH7so7Fn1HiRo37Z+RNvl44twXWVhomtMjw+8bemfmv9XEXr7mS82MxaCOJZRR0oHd9PLI5O+gyBGT6hcLoduNa7yCObVVCk3bFWsoD+xcqTrBcP6dNJN/NB1Br2QGhSN2snHAqeRNKVFQiyeAFLPSKGwY8aq9EPgsi17qd4ywPMxiH8w6N1qX1tLKtzhOeemHWeJQfFQ5H23q7qSlJUcjgTEl3x2/Q=="
        private const val KEY1_B64 = "rafYl4oSAKQX+GYoic9oW4iGwiYpZzs0"
        private const val SBOX2_B64 = "2lQehmgyYFAoWUi0haazZqHy5zZ34NN+VzlfsoB2Y1yY0IuMLjgVcV2xt8t4moH+AP0NMJ5qekW7DFIHEWKkOgIBIMhDdA8lbM6iHKjDlq6IChpb3CnA9NmsvQW/afdt1SfJjTdwcvpKqunCJLxBFmXX9hecm6tGb+HRxD7BC3njoxPxgnX5pdKP1IMSkd4/O3NRfZSE6DVLG2s9uexaipA05cpJzE8Qkv/z5jzHAwlEWOLd3yxA+0cvVbpOoJPFGc8f1lb4vu2HUxjuuEwEQk0GsPCVnyKvfOoh9TG2YYmZLV4I67UU2NsrrakqZ47k/O+ne25/DjPGZCMdnZcmzQ=="
        private const val KEY2_B64 = "2USAq+VTo5ht4bQn+K9DUcpUQRTtrB56"
        private const val SBOX3_B64 = "+mhJSFwzaV+PQPDyKp2scO/S9SdFsy/7e56UWT8XHbK3E2+19nEPwfwOgE9uVCaDtOAWTobCZX+cBCXlIbBqyDyQB1beKLspW6kGPhBCV9x0jf0KUeFhHjmlMf7qMFIB41PfDFprZ3bJiK4YxrZDv+K6dcwJmggVO8f5ktrXTM0cZL4fer0SpnkbvNajPbHxfuTz5lVEBarOI4rdc+2V6zTsjpfQYjgN1MMr6EvA6eehN6dQ1bgUogt9rZOBbQBeNnLYY00uZqSoJBnFi5gthCJsWF33ykosn9v/9KB8udMCz0YRYImrA4VHr5mMgpH4xDXLeEHRd5vZOiAalofuMg=="
        private const val KEY3_B64 = "yNHlokVEnuecesDrB/lDhVuUNiheWc3a47VtkwZ2ENg="
    }
}
