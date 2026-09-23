package eu.kanade.tachiyomi.extension.en.mkissa

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import java.util.Locale

// ------------------------------------------------------------------
// GraphQL cards (shared between browse/search results)
// ------------------------------------------------------------------

@Serializable
class CardDto(
    val _id: String,
    val name: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val thumbnail: String? = null,
    val tbObj: ThumbObj? = null,
) {
    fun title(): String = englishName ?: name ?: nativeName ?: _id
}

@Serializable
class ThumbObj(
    val u: String? = null,
)

fun CardDto.coverUrl(): String? = when {
    tbObj?.u != null -> "https://aln.youtube-anime.com/${tbObj.u}"
    thumbnail?.startsWith("http") == true -> thumbnail
    else -> null
}

// ------------------------------------------------------------------
// Popular (queryPopular)
// ------------------------------------------------------------------

@Serializable
class PopularDto(
    val data: PopularData? = null,
)

@Serializable
class PopularData(
    val queryPopular: Popular? = null,
)

@Serializable
class Popular(
    val recommendations: List<Recommendation> = emptyList(),
)

@Serializable
class Recommendation(
    val anyCard: CardDto? = null,
)

// ------------------------------------------------------------------
// Search / browse (mangas)
// ------------------------------------------------------------------

@Serializable
class MangaListDto(
    val data: MangaListData? = null,
)

@Serializable
class MangaListData(
    val mangas: MangaList? = null,
)

@Serializable
class MangaList(
    val edges: List<CardDto> = emptyList(),
)

// ------------------------------------------------------------------
// Details (manga)
// ------------------------------------------------------------------

@Serializable
class MangaDetailDto(
    val data: MangaDetailData? = null,
)

@Serializable
class MangaDetailData(
    val manga: MangaDetail? = null,
)

@Serializable
class MangaDetail(
    val _id: String,
    val name: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val altNames: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val status: String? = null,
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val thumbnail: String? = null,
    val tbObj: ThumbObj? = null,
    val airedStart: AiredStart? = null,
    // Date of the most recent chapter, per translation type:
    // {"sub":{"year":2025,"month":8,"date":4,"hour":14,"minute":30}, ...}
    val lastChapterDate: LastChapterDate? = null,
    // AniList-style 0-100 average, usually null for manga entries.
    val score: Double? = null,
    val averageScore: Int? = null,
    val pageStatus: PageStatus? = null,
    val availableChaptersDetail: AvailableChaptersDetail? = null,
) {
    @Serializable
    class PageStatus(
        // MKissa's own user rating out of 10 (e.g. 8.8).
        val userScoreAverValue: Double? = null,
    )

    fun title(): String = englishName ?: name ?: nativeName ?: _id

    fun coverUrl(): String? = when {
        tbObj?.u != null -> "https://aln.youtube-anime.com/${tbObj.u}"
        thumbnail?.startsWith("http") == true -> thumbnail
        else -> null
    }

    /**
     * The rating shown to the user, formatted as "8.8/10" (one decimal,
     * never the raw 8.47980-style values). Prefers MKissa's own user score,
     * then falls back to the AniList average score.
     */
    fun ratingText(): String? {
        val rating = pageStatus?.userScoreAverValue
            ?: averageScore?.takeIf { it > 0 }?.div(10.0)
            ?: score?.takeIf { it > 0 }?.div(10.0)
            ?: return null
        if (rating <= 0.0) return null
        val text = String.format(Locale.ENGLISH, "%.1f", rating).removeSuffix(".0")
        return "$text/10"
    }
}

@Serializable
class AiredStart(
    val year: Int? = null,
)

/**
 * `lastChapterDate` shape: `{"sub":{year,month,date,hour,minute,second},
 * "raw":{...}}`. Months are 1-based as sent by the API.
 */
@Serializable
class LastChapterDate(
    val sub: ChapterDateParts? = null,
    val raw: ChapterDateParts? = null,
) {
    @Serializable
    class ChapterDateParts(
        val year: Int? = null,
        val month: Int? = null,
        val date: Int? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val second: Int? = null,
    )

    /** Epoch millis of the newest chapter of the given translation (0 if absent). */
    fun forTranslation(translation: String): Long {
        val parts = when (translation.lowercase()) {
            "raw" -> raw ?: sub
            else -> sub ?: raw
        } ?: return 0L
        val y = parts.year ?: return 0L
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(
            y,
            (parts.month ?: 1) - 1,
            parts.date ?: 1,
            parts.hour ?: 0,
            parts.minute ?: 0,
            parts.second ?: 0,
        )
        return cal.timeInMillis
    }
}

@Serializable
class AvailableChaptersDetail(
    val sub: List<String> = emptyList(),
    val raw: List<String> = emptyList(),
)

// ------------------------------------------------------------------
// Comix-style description builder
// ------------------------------------------------------------------

fun MangaDetail.toSManga(
    showAltNames: Boolean,
    showExtraInfo: Boolean,
    showTagsInGenre: Boolean,
    blockedGenres: Set<String>,
    scorePosition: String,
): SManga {
    val dtoId = _id
    val dtoTitle = title()
    val dtoDescription = description?.let(::cleanDescription)
    val dtoStatus = status
    val cover = coverUrl()

    val stars = ratingText()?.let { text ->
        val rating = pageStatus?.userScoreAverValue
            ?: averageScore?.takeIf { it > 0 }?.div(10.0)
            ?: score?.takeIf { it > 0 }?.div(10.0)
            ?: 0.0
        val full = (rating / 2).toInt().coerceIn(0, 5)
        "★".repeat(full) + "☆".repeat(5 - full) + " $text"
    }

    val altNames = altNames
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals(dtoTitle, true) }
        .distinct()

    val genreChips = buildList {
        addAll(genres)
        if (showTagsInGenre) {
            addAll(
                tags.map { tag ->
                    // tags are prefixed like "theme:monsters" / "format:full_color"
                    tag.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
                },
            )
        }
    }.distinct()
        .filterNot { it.lowercase() in blockedGenres }
        .joinToString(", ")

    val infoLine = if (showExtraInfo) {
        buildString {
            airedStart?.year?.let { append("**Year:** $it") }
            if (!type.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("**Type:** $type")
            }
            if (!dtoStatus.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("**Status:** $dtoStatus")
            }
        }.ifBlank { null }
    } else {
        null
    }

    val builtDescription = buildString {
        // The rating is rendered exactly once, in the configured position.
        if (scorePosition == "top" && stars != null) {
            append(stars)
            append("\n\n")
        }

        if (infoLine != null) {
            append(infoLine)
            append("\n\n")
        }

        dtoDescription?.trim()?.let { append(it) }

        // Author is shown in Mihon's dedicated author row (SManga.author);
        // no need to duplicate it inside the description.

        if (showAltNames && altNames.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Alternative names:\n")
            append(altNames.joinToString("\n") { "• $it" })
        }

        if (scorePosition == "end" && stars != null) {
            if (isNotEmpty()) append("\n\n")
            append(stars)
        }
    }.trim()

    return SManga.create().apply {
        url = dtoId
        title = dtoTitle
        description = builtDescription.ifBlank { dtoDescription }
        status = when (dtoStatus?.lowercase()) {
            "releasing", "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "discontinued" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = genreChips.ifBlank { null }
        // Without this, Mihon's info tab shows "Unknown" for the author row.
        // Authors arrive duplicated across spellings ("Neida (네이다)",
        // "Neida", "네이다") — collapse the contained variants.
        author = dedupeAuthors(authors).joinToString(", ").ifBlank { null }
        thumbnail_url = cover
        initialized = true
    }
}

/**
 * Collapses author name variants: a name that is contained inside another
 * kept entry ("Neida" ⊂ "Neida (네이다)") is treated as the same person.
 */
internal fun dedupeAuthors(authors: List<String>): List<String> {
    val kept = mutableListOf<String>()
    for (raw in authors) {
        val name = raw.trim()
        if (name.isEmpty()) continue
        val lower = name.lowercase()
        val isVariant = kept.any { existing ->
            val existingLower = existing.lowercase()
            existingLower.contains(lower) || lower.contains(existingLower)
        }
        if (!isVariant) kept.add(name)
    }
    return kept
}

/**
 * The site's descriptions arrive as HTML — "<br>" line breaks, "<b>" /
 * "<i>" emphasis, HTML entities — which Mihon's Markdown renderer shows as
 * literal tag text ("Guts...<br> <br>[Written by MAL Rewrite]<br>"). Convert
 * to clean text: real newlines, Markdown emphasis, decoded entities.
 */
internal fun cleanDescription(raw: String): String {
    if (!HTML_TAG_REGEX.containsMatchIn(raw) && !raw.contains('&')) return raw.trim()

    var text = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)<b>(.*?)</b>"), "**$1**")
        .replace(Regex("(?i)<strong>(.*?)</strong>"), "**$1**")
        .replace(Regex("(?i)<i>(.*?)</i>"), "*$1*")
        .replace(Regex("(?i)<em>(.*?)</em>"), "*$1*")
        .replace(HTML_TAG_REGEX, "") // strip any remaining tags
        .replace(Regex("\\n{3,}"), "\n\n")

    // Decode HTML entities (&amp; &#39; &hellip; ...) via the platform
    // parser, then normalise the non-breaking spaces it emits.
    text = runCatching {
        android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrDefault(text)
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")

    return text.trim()
}

private val HTML_TAG_REGEX = Regex("<[a-zA-Z/][^>]*>")

fun MangaDetail.toChapterList(): List<SChapter> {
    val mangaId = _id
    val detail = availableChaptersDetail

    val subChapters = detail?.sub.orEmpty()
    if (subChapters.isNotEmpty()) {
        return datedChapters(mangaId, subChapters, "sub")
    }

    // No sub chapters: fall back to the raw list so the chapter list is
    // never empty for raw-only manga.
    return datedChapters(mangaId, detail?.raw.orEmpty(), "raw")
}

/**
 * Builds the chapter list with upload dates.
 *
 * The API only exposes the date of the NEWEST chapter (`lastChapterDate`);
 * older chapters carry no dates anywhere (the site's own chapter list shows
 * none either). Leaving `date_upload` at 0 made every entry render as
 * "posted today" in the app, so the older chapters get evenly spaced
 * estimates walking backwards from the real newest date (one week apart —
 * typical weekly serialisation cadence). The newest chapter always carries
 * the exact date from the API.
 */
private fun MangaDetail.datedChapters(
    mangaId: String,
    chapterStrings: List<String>,
    translation: String,
): List<SChapter> {
    val newestDate = lastChapterDate?.forTranslation(translation) ?: 0L
    val chapterCount = chapterStrings.size
    val week = 7L * 24 * 60 * 60 * 1000

    return chapterStrings.mapIndexed { index, chapterString ->
        val date = when {
            newestDate <= 0L -> 0L
            index <= 0 -> newestDate
            else -> newestDate - (index.toLong().coerceAtMost(chapterCount.toLong()) * week)
        }
        chapterString.toSChapter(mangaId, translation, date)
    }
}

private fun String.toSChapter(mangaId: String, translation: String, date: Long): SChapter = SChapter.create().apply {
    val suffix = if (translation == "raw") " (Raw)" else ""
    // NOTE: must be "${this@toSChapter}" — without braces "$this" interpolates
    // the SChapter object being built (toString -> "SChapterImpl@..."), which
    // poisoned every chapter URL with the object identity string and broke
    // all page fetches.
    url = "/manga/$mangaId/chapter-${this@toSChapter}-$translation"
    name = "Ch. ${this@toSChapter.removeSuffix(".0")}$suffix"
    chapter_number = this@toSChapter.toFloatOrNull() ?: -1f
    date_upload = date
}

// ------------------------------------------------------------------
// Chapter pages (reverse engineered reader response)
// ------------------------------------------------------------------

/**
 * Response of the `chapterPages` GraphQL query used by the site's reader.
 * `pictureUrls` is an opaque Object scalar in the schema — live responses
 * carry an array of image paths/URLs; `pictureUrlHead` is the prefix those
 * relative paths resolve against.
 */
@Serializable
class ChapterPagesDto(
    val data: ChapterPagesData? = null,
    val errors: List<ChapterPagesError> = emptyList(),
) {
    /** First server-side error message, if the API rejected the query. */
    fun firstErrorMessage(): String? = errors.firstOrNull()?.message?.takeIf(String::isNotBlank)
}

@Serializable
class ChapterPagesError(
    val message: String? = null,
)

@Serializable
class ChapterPagesData(
    val chapterPages: ChapterPagesConnection? = null,
)

@Serializable
class ChapterPagesConnection(
    val edges: List<ChapterPageEdge> = emptyList(),
)

@Serializable
class ChapterPageEdge(
    val chapterString: String? = null,
    val pictureUrls: List<String> = emptyList(),
    val pictureUrlHead: String? = null,
    val sourceName: String? = null,
)
