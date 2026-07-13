package eu.kanade.tachiyomi.extension.all.comix

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@Source
class Comix : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::signRequestInterceptor)
        .addInterceptor(::decryptResponseInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "application/json")
        .set("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "$baseUrl/")

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

    override fun latestUpdatesRequest(page: Int): Request {
        return mangaListRequest(page, sortBy = "chapter_updated_at", query = null)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortFilter = filters.firstInstance<SortFilter>()
        val sortIndex = sortFilter?.state?.index ?: 0
        val sortAscending = sortFilter?.state?.ascending ?: false
        val sortBy = sortOptions[sortIndex] ?: "relevance"
        // "title" is naturally ascending; everything else descending by default
        val sortDir = if (sortAscending) "asc" else "desc"

        val types = filters.firstInstance<TypeFilter>()?.values?.filter { it.state }?.map { it.value } ?: emptyList()
        val statuses = filters.firstInstance<StatusFilter>()?.values?.filter { it.state }?.map { it.value } ?: emptyList()
        val contentRatings = filters.firstInstance<ContentRatingFilter>()?.values?.filter { it.state }?.map { it.value }
            ?: listOf("safe", "suggestive")

        return mangaListRequest(
            page = page,
            sortBy = sortBy,
            sortDir = sortDir,
            query = query.takeIf { it.isNotBlank() },
            types = types,
            statuses = statuses,
            contentRatings = contentRatings,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val hid = manga.url
        return GET("$apiBaseUrl/manga/$hid", headers)
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/title/${manga.url}"
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = response.parseAs<ComixMangaDetailDto>()
        return detail.toSManga()
    }

    // ============================= Chapters =============================

    override fun chapterListRequest(manga: SManga): Request {
        val hid = manga.url
        return GET("$apiBaseUrl/manga/$hid/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ComixChapterListDto>()
        return data.items.map { it.toSChapter() }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
        return GET("$apiBaseUrl/chapters/$chapterId", headers)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url is the numeric chapter ID; we cannot reconstruct the full web URL without
        // the manga slug, so we point back at the site root where the reader lives.
        return "$baseUrl/title/${chapter.url}"
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ComixChapterPagesDto>()
        val pages = data.pages?.items ?: emptyList()
        return pages.mapIndexed { index, pageDto ->
            Page(index, imageUrl = pageDto.url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

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
    ): Request {
        val url = "$apiBaseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "28")
            .addQueryParameter("order[$sortBy]", sortDir)
            .apply {
                query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                types.forEach { addQueryParameter("types[]", it) }
                statuses.forEach { addQueryParameter("statuses[]", it) }
                contentRatings.forEach { addQueryParameter("content_rating[]", it) }
            }
            .build()

        return GET(url, headers)
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
        ContentRatingFilter(),
    )

    private val sortOptions = mapOf(
        0 to "relevance",
        1 to "chapter_updated_at",
        2 to "created_at",
        3 to "title",
        4 to "year",
        5 to "score",
        6 to "views_total",
        7 to "follows_total",
    )

    private class SortFilter : Filter.Sort(
        "Sort by",
        arrayOf("Relevance", "Latest update", "Recently added", "Title", "Year", "Highest rated", "Most viewed", "Most followed"),
        Selection(0, false),
    )

    private class TypeFilter : Filter.Group<CheckboxFilter>(
        "Type",
        listOf(
            CheckboxFilter("Manga", "manga"),
            CheckboxFilter("Manhwa", "manhwa"),
            CheckboxFilter("Manhua", "manhua"),
        ),
    )

    private class StatusFilter : Filter.Group<CheckboxFilter>(
        "Status",
        listOf(
            CheckboxFilter("Releasing", "releasing"),
            CheckboxFilter("Finished", "finished"),
            CheckboxFilter("Cancelled", "cancelled"),
            CheckboxFilter("Hiatus", "hiatus"),
        ),
    )

    private class ContentRatingFilter : Filter.Group<CheckboxFilter>(
        "Content rating",
        listOf(
            CheckboxFilter("Safe", "safe", true),
            CheckboxFilter("Suggestive", "suggestive", true),
            CheckboxFilter("Erotica", "erotica"),
            CheckboxFilter("Pornographic", "pornographic"),
        ),
    )

    private class CheckboxFilter(name: String, val value: String, default: Boolean = false) : Filter.CheckBox(name, default)

    // ========================================================================
    // Sign + Decrypt (reverse-engineered API protection)
    // ========================================================================

    /**
     * Intercepts outgoing GET requests to `/api/v1/manga*` and `/api/v1/chapters/*`
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
     * Intercepts responses with the `x-enc: 1` header and decrypts the body.
     * The body is a JSON object `{"e": "base64url-encrypted-payload"}`.
     * Decryption reverses the 3-stage S-box substitution.
     */
    private fun decryptResponseInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.headers["x-enc"] != "1") return response

        val body = response.body ?: return response
        val contentType = body.contentType()
        val content = body.string()
        return try {
            val encrypted = content.parseAs<ComixEncryptedDto>()
            val decrypted = decrypt(encrypted.e)
            response.newBuilder()
                .body(decrypted.toResponseBody("application/json".toMediaType()))
                .build()
        } catch (e: Exception) {
            // If decryption fails, return original body so the caller can see the error
            response.newBuilder()
                .body(content.toResponseBody(contentType))
                .build()
        }
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

    private fun ComixMangaDetailDto.toSManga(): SManga = SManga.create().apply {
        url = hid
        title = this@toSManga.title
        author = authors.joinToString(", ") { it.title }
        artist = artists.joinToString(", ") { it.title }
        genre = buildList {
            addAll(genres.map { it.title })
            addAll(demographics.map { it.title })
            addAll(formats.map { it.title })
            addAll(tags.map { it.title })
        }.distinct().joinToString(", ")
        description = synopsis
        status = when (this@toSManga.status) {
            "releasing" -> SManga.ONGOING
            "finished" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        thumbnail_url = poster?.large ?: poster?.medium
        initialized = true
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

    private inline fun <reified T : Filter<*>> FilterList.firstInstance(): T? =
        filterIsInstance<T>().firstOrNull()

    companion object {
        private const val SBOX1_B64 = "gbicCvAMzfcXEtGAyjvvhmb2yCWzWhjqcxXZ7ZhpzANOzoQLo3nuPZ2vK9dkb9hJExC0Vni/hdQBceI+mw611gkhQFjBuf4bJg1TxYqM+SL4YDqtwjxiGSdeH7so7Fn1HiRo37Z+RNvl44twXWVhomtMjw+8bemfmv9XEXr7mS82MxaCOJZRR0oHd9PLI5O+gyBGT6hcLoduNa7yCObVVCk3bFWsoD+xcqTrBcP6dNJN/NB1Br2QGhSN2snHAqeRNKVFQiyeAFLPSKGwY8aq9EPgsi17qd4ywPMxiH8w6N1qX1tLKtzhOeemHWeJQfFQ5H23q7qSlJUcjgTEl3x2/Q=="
        private const val KEY1_B64 = "rafYl4oSAKQX+GYoic9oW4iGwiYpZzs0"
        private const val SBOX2_B64 = "2lQehmgyYFAoWUi0haazZqHy5zZ34NN+VzlfsoB2Y1yY0IuMLjgVcV2xt8t4moH+AP0NMJ5qekW7DFIHEWKkOgIBIMhDdA8lbM6iHKjDlq6IChpb3CnA9NmsvQW/afdt1SfJjTdwcvpKqunCJLxBFmXX9hecm6tGb+HRxD7BC3njoxPxgnX5pdKP1IMSkd4/O3NRfZSE6DVLG2s9uexaipA05cpJzE8Qkv/z5jzHAwlEWOLd3yxA+0cvVbpOoJPFGc8f1lb4vu2HUxjuuEwEQk0GsPCVnyKvfOoh9TG2YYmZLV4I67UU2NsrrakqZ47k/O+ne25/DjPGZCMdnZcmzQ=="
        private const val KEY2_B64 = "2USAq+VTo5ht4bQn+K9DUcpUQRTtrB56"
        private const val SBOX3_B64 = "+mhJSFwzaV+PQPDyKp2scO/S9SdFsy/7e56UWT8XHbK3E2+19nEPwfwOgE9uVCaDtOAWTobCZX+cBCXlIbBqyDyQB1beKLspW6kGPhBCV9x0jf0KUeFhHjmlMf7qMFIB41PfDFprZ3bJiK4YxrZDv+K6dcwJmggVO8f5ktrXTM0cZL4fer0SpnkbvNajPbHxfuTz5lVEBarOI4rdc+2V6zTsjpfQYjgN1MMr6EvA6eehN6dQ1bgUogt9rZOBbQBeNnLYY00uZqSoJBnFi5gthCJsWF33ykosn9v/9KB8udMCz0YRYImrA4VHr5mMgpH4xDXLeEHRd5vZOiAalofuMg=="
        private const val KEY3_B64 = "yNHlokVEnuecesDrB/lDhVuUNiheWc3a47VtkwZ2ENg="
    }
}
