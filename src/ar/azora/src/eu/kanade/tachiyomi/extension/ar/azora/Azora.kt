package eu.kanade.tachiyomi.extension.ar.azora

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.iken.Iken
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Azora (azorafly.com) — Iken, Arabic (أزورا).
 *
 * The stock Iken parse is kept as-is; on top of it this source ships the
 * family's Comix/MangaDot details block — the same options ProComic, MangaTime
 * and Kawaiimanga carry:
 *
 *   - an organized description: the API's 0..10 `averageRating` rendered as
 *     the familiar ★★★★☆ row, a Type/Status/extra-info line, the synopsis,
 *     then Genres / Links / Alternative Names sections
 *   - "Show extra info" / "Blocked genres" / "Score display position"
 *     preferences driving that description
 *   - "Combine duplicate chapters" folding repeated chapter numbers into one
 *     entry (newest upload wins)
 *
 * The enrichment data rides the SAME `/api/post` response the Iken base
 * already fetches for its own parse: an interceptor captures the raw body
 * (keyed by `postSlug`), and after the base returns, the description is
 * rebuilt from it — no extra network round-trips.
 */
@Source
abstract class Azora : Iken() {

    private val preferences: SharedPreferences = getPreferences()

    // ========================= Payload capture ===========================

    /** Raw `/api/post` bodies by slug, read right after the base's own fetch. */
    private val capturedPostPayloads = ConcurrentHashMap<String, String>()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(::postPayloadInterceptor)
    }

    private fun postPayloadInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.url.encodedPath.endsWith("/api/post")) {
            runCatching {
                val slug = request.url.queryParameter("postSlug") ?: return@runCatching
                if (capturedPostPayloads.size > MAX_CAPTURED_PAYLOADS) capturedPostPayloads.clear()
                capturedPostPayloads[slug] = response.peekBody(MAX_PAYLOAD_BYTES).string()
            }
        }
        return response
    }

    // ========================= MangaDot details ==========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val update = super.fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        if (fetchDetails) enrichMangaDotDetails(update.manga)
        return SMangaUpdate(update.manga, combineDuplicateChapters(update.chapters))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = super.getMangaByUrl(url) ?: return null
        enrichMangaDotDetails(manga)
        return manga
    }

    private fun enrichMangaDotDetails(manga: SManga) {
        val slug = manga.url.substringBeforeLast('#')
        val payload = capturedPostPayloads[slug]
            ?.let { raw -> runCatching { raw.parseAs<PostDetailsDto>() }.getOrNull() }
            ?: return

        val type = payload.post.seriesType?.toTypeLabel()
        val genres = payload.post.genres.map { it.name }

        manga.genre = genreField(genres)
        manga.description = buildDescription(
            rating = payload.post.averageRating,
            ratingCount = payload.post.totalRatings ?: 0,
            type = type,
            statusText = payload.post.seriesStatus?.toStatusLabel(),
            chapterCount = payload.totalChapterCount,
            viewCount = payload.post.totalViews,
            synopsis = payload.post.postContent?.let { content ->
                Jsoup.parse(content.replace("\n", "<br>")).text()
            },
            genres = genres,
            altNames = payload.post.alternativeTitles
                ?.split(" / ", "/", "،", ",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty(),
            sourceUrl = "$baseUrl/series/$slug",
        )
    }

    private fun String.toTypeLabel(): String? = when (uppercase(Locale.US)) {
        "MANGA" -> "Manga"
        "MANHWA" -> "Manhwa"
        "MANHUA" -> "Manhua"
        "NOVEL" -> null // novels are unusable in a reader
        else -> takeIf(String::isNotBlank)
            ?.lowercase(Locale.US)
            ?.replaceFirstChar { it.uppercase(Locale.US) }
    }

    private fun String.toStatusLabel(): String? = when (uppercase(Locale.US)) {
        "ONGOING", "COMING_SOON", "MASS_RELEASED" -> "Ongoing"
        "COMPLETED" -> "Completed"
        "ON_HIATUS" -> "Hiatus"
        "CANCELLED", "DROPPED" -> "Cancelled"
        else -> null
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

    /** Chapter URLs end in a numbered slug (/series/<series>/chapter-12#<id>). */
    private fun chapterNumberOf(chapter: SChapter): Float {
        chapter.chapter_number.takeIf { it > 0f }?.let { return it }
        val slug = chapter.url.substringBeforeLast('#').trimEnd('/').substringAfterLast('/')
        return CHAPTER_NUMBER_REGEX.find(slug)?.value?.toFloatOrNull() ?: 0f
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Keep the Iken options ("show inaccessible chapters") first.
        super.setupPreferenceScreen(screen)

        // ---- Description (the Comix-style options) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status, chapter count, views and ratings"
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
        const val MAX_CAPTURED_PAYLOADS = 48
        const val MAX_PAYLOAD_BYTES = 2_000_000L

        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"

        val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
    }
}

/** The subset of the `/api/post` payload the MangaDot description needs. */
@Serializable
private class PostDetailsDto(
    val post: PostDto,
    @SerialName("totalChapterCount") val totalChapterCount: Int? = null,
) {
    @Serializable
    class PostDto(
        val postContent: String? = null,
        val alternativeTitles: String? = null,
        val seriesType: String? = null,
        val seriesStatus: String? = null,
        val averageRating: Double? = null,
        val totalRatings: Int? = null,
        val totalViews: Long? = null,
        val genres: List<GenreDto> = emptyList(),
    )

    @Serializable
    class GenreDto(
        val name: String,
    )
}
