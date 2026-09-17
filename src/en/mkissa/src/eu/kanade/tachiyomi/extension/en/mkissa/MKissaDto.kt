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
    val dtoDescription = description
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

        // The author is rendered once, in its own labelled block.
        val authorLine = authors
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
        if (authorLine.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("**Author:** $authorLine")
        }

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
        thumbnail_url = cover
        initialized = true
    }
}

fun MangaDetail.toChapterList(): List<SChapter> {
    val mangaId = _id
    val detail = availableChaptersDetail

    val subChapters = detail?.sub.orEmpty()
    if (subChapters.isNotEmpty()) {
        return subChapters.map { chapterString ->
            chapterString.toSChapter(mangaId, "sub")
        }
    }

    // No sub chapters: fall back to the raw list so the chapter list is
    // never empty for raw-only manga.
    return detail?.raw.orEmpty().map { chapterString ->
        chapterString.toSChapter(mangaId, "raw")
    }
}

private fun String.toSChapter(mangaId: String, translation: String): SChapter = SChapter.create().apply {
    val suffix = if (translation == "raw") " (Raw)" else ""
    url = "/manga/$mangaId/chapter-$this@toSChapter-$translation"
    name = "Ch. ${this@toSChapter.removeSuffix(".0")}$suffix"
    chapter_number = this@toSChapter.toFloatOrNull() ?: -1f
    date_upload = 0L
}
