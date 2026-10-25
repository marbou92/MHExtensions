package eu.kanade.tachiyomi.extension.ar.hijala

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.textOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.jsoup.nodes.Document
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hijala (hijala.com) — MangaThemesia, Arabic (حجالة).
 *
 * Beyond the stock MangaThemesia parse (plus the site's split-slice page
 * unscrambler), this source ships the family's Comix/MangaDot details block —
 * the same options ProComic, MangaTime and Kawaiimanga carry:
 *
 *   - an organized description: the theme's 0..10 rating rendered as the
 *     familiar ★★★★☆ row, a Type/Status/extra-info line, the synopsis, then
 *     Genres / Links / Alternative Names sections
 *   - "Show extra info" / "Blocked genres" / "Score display position"
 *     preferences driving that description
 *   - "Combine duplicate chapters" folding repeated chapter numbers into one
 *     entry (newest upload wins)
 */
@Source
abstract class Hijala :
    MangaThemesia(),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(::scrambledImageInterceptor)

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host != SCRAMBLED_IMAGE_HOST) {
            return chain.proceed(request)
        }

        val leftPieceUrl = url.queryParameter("leftImage")
            ?: throw IllegalStateException("Missing leftImage")
        val rightPieceUrl = url.queryParameter("rightImage")
            ?: throw IllegalStateException("Missing rightImage")

        val pieceBitmaps = runBlocking {
            listOf(leftPieceUrl, rightPieceUrl).map { pieceUrl ->
                async(Dispatchers.IO) {
                    val pieceRequest = request.newBuilder().url(pieceUrl).build()
                    client.newCall(pieceRequest).await().use { response ->
                        response.body.use { body ->
                            BitmapFactory.decodeStream(body.byteStream())
                                ?: throw Exception("Failed to decode $pieceUrl")
                        }
                    }
                }
            }.awaitAll()
        }

        require(pieceBitmaps.size == 2) { "Expected exactly 2 bitmaps" }

        val leftBitmap = pieceBitmaps[0]
        val rightBitmap = pieceBitmaps[1]

        val width = leftBitmap.width + rightBitmap.width
        val height = maxOf(leftBitmap.height, rightBitmap.height)

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        try {
            canvas.drawBitmap(leftBitmap, 0f, 0f, null)
            canvas.drawBitmap(rightBitmap, leftBitmap.width.toFloat(), 0f, null)

            val buffer = Buffer().apply {
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream())
            }

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(buffer.asResponseBody("image/jpeg".toMediaType(), buffer.size))
                .build()
        } finally {
            pieceBitmaps.forEach { it.recycle() }
            resultBitmap.recycle()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = super.pageListParse(document)
        document.selectFirst("#chapter-pages-js-before") ?: return pages

        val chapterUrl = document.location()
        val pairs = mutableListOf<List<String>>()
        var i = 0
        while (i < pages.size) {
            val pair = pages.subList(i, minOf(i + 2, pages.size))
            if (pair.size == 2) {
                pairs.add(pair.mapNotNull { it.imageUrl })
            }
            i += 2
        }

        return pairs.mapIndexed { index, pair ->
            val imageUrl = HttpUrl.Builder()
                .scheme("http")
                .host(SCRAMBLED_IMAGE_HOST)
                .addQueryParameter("leftImage", pair[0])
                .addQueryParameter("rightImage", pair[1])
                .build()
                .toString()

            Page(index, chapterUrl, imageUrl)
        }
    }

    // ========================= MangaDot details ==========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)
        enrichMangaDotDetails(manga, document)
        return manga
    }

    private fun enrichMangaDotDetails(manga: SManga, document: Document) {
        // Classic TS themes expose the score as schema.org AggregateRating
        // metadata inside the rating widget (0..10 scale).
        val rating = document.selectFirst(".rating-prc .num")?.text()
            ?.trim()?.toDoubleOrNull()
        val ratingCount = document.selectFirst(".rating-prc meta[itemprop=ratingCount]")
            ?.attr("content")?.toIntOrNull() ?: 0
        val type = tsInfoValue(document, TYPE_LABELS)
        val statusText = tsInfoValue(document, STATUS_LABELS)?.toStatusLabel()
        val synopsis = document.selectFirst(seriesDescriptionSelector)?.textOrNull()
        val genres = document.select(seriesGenreSelector).eachText()
            .map(String::trim)
            .filter(String::isNotEmpty)
        val altNames = document.selectFirst(seriesAltNameSelector)?.ownText()
            ?.split(',', '،')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

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
            altNames = altNames,
            sourceUrl = document.location(),
        )
    }

    /** The `<i>` value of the tsinfo item whose label matches (النوع/الحالة). */
    private fun tsInfoValue(document: Document, labels: List<String>): String? = document
        .select(".tsinfo .imptdt, .imptdt")
        .firstOrNull { element ->
            val label = element.ownText().trim()
            labels.any { label.contains(it, ignoreCase = true) }
        }
        ?.selectFirst("i")
        ?.text()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    /** Arabic/English status text onto the family's English info-line label. */
    private fun String.toStatusLabel(): String? = when {
        contains("ongoing", ignoreCase = true) || contains("مستمرة") || contains("مستمر") -> "Ongoing"
        contains("completed", ignoreCase = true) || contains("مكتمل") -> "Completed"
        contains("hiatus", ignoreCase = true) || contains("متوقف") || contains("on hold", ignoreCase = true) -> "Hiatus"
        contains("cancel", ignoreCase = true) || contains("ملغي") -> "Cancelled"
        else -> this
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
        altNames: List<String>,
        sourceUrl: String,
    ): String {
        val showExtraInfo = preferences.getBoolean(PREF_SHOW_EXTRA_INFO, true)
        val scorePosition = preferences.getString(PREF_SCORE_POSITION, "end") ?: "end"

        // The site rates 0..10; the description shows the familiar 5-star row.
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

            if (altNames.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                altNames.forEach { altTitle -> append("- ", altTitle, "\n") }
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

    override fun chapterListParse(document: Document): List<SChapter> = combineDuplicateChapters(super.chapterListParse(document))

    /**
     * Optional "combine duplicate chapters" — the same option ProComic and
     * MangaTime ship. When enabled, one entry per non-zero chapter number
     * survives — the newest upload wins (the list is newest-first).
     */
    private fun combineDuplicateChapters(chapters: List<SChapter>): List<SChapter> = if (preferences.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)) {
        mutableListOf<SChapter>().apply {
            for ((_, group) in chapters.groupBy { chapterNumberOf(it) }) {
                if (chapterNumberOf(group.first()) <= 0f || group.size == 1) {
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

    /** Chapter URLs end in a numbered slug (/manga/<series>/chapter-12/). */
    private fun chapterNumberOf(chapter: SChapter): Float {
        chapter.chapter_number.takeIf { it > 0f }?.let { return it }
        val slug = chapter.url.trimEnd('/').substringAfterLast('/')
        return CHAPTER_NUMBER_REGEX.find(slug)?.value?.toFloatOrNull() ?: 0f
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
        const val RATING_SCALE = 10.0

        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"

        val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")

        val TYPE_LABELS = listOf("النوع", "Type", "Tür")
        val STATUS_LABELS = listOf("الحالة", "Status", "Durum")
    }
}

private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"
