package eu.kanade.tachiyomi.extension.ar.mangalek

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.tryParseDate
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mangalek (mangalik.net) — Madara, Arabic (مانجاليك).
 *
 * Beyond the stock Madara parse, this source ships the family's
 * Comix/MangaDot details block — the same options ProComic, MangaTime and
 * Kawaiimanga carry:
 *
 *   - an organized description: the site's own 0..5 rating rendered as the
 *     familiar ★★★★☆ row, a Type/Status/extra-info line, the synopsis, then
 *     Genres / Links / Alternative Names sections
 *   - "Show extra info" / "Show tags in genre chips" / "Blocked genres" /
 *     "Score display position" preferences driving that description
 *   - "Combine duplicate chapters" folding repeated chapter numbers into one
 *     entry (newest upload wins)
 *
 * The Madara template on mangalik.net labels its summary items in Arabic
 * (النوع = type, الحالة = status, اسماء اخرى = alternative names), so the
 * enrichment selects those labels directly; the stock `:contains(Alt)`
 * selector can't see them.
 */
@Source
abstract class Mangalek :
    Madara(),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    override val filterNonMangaItems get() = false

    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.forLanguageTag("ar"))
    private val formatTwo = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    override fun parseChapterDate(date: String?) = chapterDateFormat.tryParseDate(date).takeIf {
        it != 0L
    } ?: formatTwo.tryParseDate(date)

    // ========================= MangaDot details ==========================

    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga {
        val manga = super.parseDetails(document, id, preserveUrl)
        enrichMangaDotDetails(manga, document)
        return manga
    }

    private fun enrichMangaDotDetails(manga: SManga, document: Document) {
        val rating = document.selectFirst("div.post-total-rating span.score")?.text()
            ?.trim()?.toDoubleOrNull()
        val ratingCount = document.selectFirst(".post-content_item:contains(التقييم)")?.text()
            ?.let { VOTES_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 0
        val type = summaryItem(document, TYPE_ITEM_LABELS)
            ?.takeUnless { it.isPlaceholder() }
        val statusText = summaryItem(document, STATUS_ITEM_LABELS)
            ?.takeUnless { it.isPlaceholder() }
            ?.toStatusLabel()
        val synopsis = document.selectFirst(mangaDetailsSelectorDescription)?.let { element ->
            element.select("p").takeIf(List<Element>::isNotEmpty)?.joinToString("\n\n") { it.text() } ?: element.text()
        }
        val genres = document.select(mangaDetailsSelectorGenre).eachText().filterNot { it.isPlaceholder() }
        val tags = document.select(mangaDetailsSelectorTag).eachText().filterNot { it.isPlaceholder() }
        val altNames = summaryItem(document, ALT_ITEM_LABELS)
            ?.takeUnless { it.isPlaceholder() }
            ?.split(',', '،')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

        manga.genre = genreField(genres, tags)
        manga.description = buildDescription(
            rating = rating,
            ratingCount = ratingCount,
            type = type,
            statusText = statusText,
            chapterCount = null,
            viewCount = null,
            synopsis = synopsis,
            genres = genres,
            tags = tags,
            altNames = altNames,
            sourceUrl = document.location(),
        )
    }

    /** The value of a summary item (label -> `.summary-content`) by its heading text. */
    private fun summaryItem(document: Document, labels: List<String>): String? = document
        .selectFirst(labels.joinToString(", ") { ".post-content_item:contains($it)" })
        ?.selectFirst(".summary-content")
        ?.text()
        ?.trim()

    private fun String.isPlaceholder(): Boolean = isBlank() ||
        equals("Updating", ignoreCase = true) ||
        equals("-", ignoreCase = true) ||
        equals("N/A", ignoreCase = true)

    /** Arabic/English status text onto the family's English info-line label. */
    private fun String.toStatusLabel(): String? = when {
        contains("ongoing", ignoreCase = true) || contains("مستمرة") || contains("مستمر") -> "Ongoing"
        contains("completed", ignoreCase = true) || contains("مكتمل") -> "Completed"
        contains("hiatus", ignoreCase = true) || contains("متوقف") || contains("on hold", ignoreCase = true) -> "Hiatus"
        contains("cancel", ignoreCase = true) || contains("ملغي") -> "Cancelled"
        isBlank() -> null
        else -> this
    }

    /** Genre chips: genres + (optional) tags, minus the blocked list. */
    private fun genreField(genres: List<String>, tags: List<String>): String {
        val labels = buildList {
            addAll(genres)
            if (preferences.getBoolean(PREF_SHOW_TAGS, true)) {
                addAll(tags)
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
        genres: List<String>,
        tags: List<String>,
        altNames: List<String>,
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

    override suspend fun fetchChapters(mangaPath: String, id: String, mangaPage: Document?): List<SChapter> = combineDuplicateChapters(super.fetchChapters(mangaPath, id, mangaPage))

    /**
     * Optional "combine duplicate chapters" — the same option ProComic and
     * MangaTime ship. Madara TOCs can carry repeats when a series is
     * re-imported; when enabled, one entry per non-zero chapter number
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

    /** Madara chapter URLs end in the numeric chapter segment (/manga/<series>/<n>/). */
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
        const val RATING_SCALE = 5.0

        const val PREF_SHOW_TAGS = "pref_show_tags"
        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"

        /** "Average 4 / 5 out of 3" — the Madara rating item text. */
        val VOTES_REGEX = Regex("""out of (\d+)""")

        val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")

        val TYPE_ITEM_LABELS = listOf("النوع", "Type")
        val STATUS_ITEM_LABELS = listOf("الحالة", "Status")
        val ALT_ITEM_LABELS = listOf("اسماء اخرى", "الأسماء البديلة", "Alt")
    }
}
