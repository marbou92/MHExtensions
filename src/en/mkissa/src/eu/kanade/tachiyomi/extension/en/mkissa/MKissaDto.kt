package eu.kanade.tachiyomi.extension.en.mkissa

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

// ------------------------------------------------------------------
// GraphQL cards (shared between browse/search results)
// ------------------------------------------------------------------

@Serializable
class CardDto(
    val _id: String,
    val name: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val type: String? = null,
    val score: Double? = null,
    val thumbnail: String? = null,
    val tbObj: ThumbObj? = null,
    val availableChapters: AvailableChapters? = null,
    val airedStart: AiredStart? = null,
    val lastChapterDate: ChapterDateMap? = null,
    val status: String? = null,
) {
    fun title(): String = englishName ?: name ?: nativeName ?: _id
}

@Serializable
class ThumbObj(
    val u: String? = null,
    val sm: Int? = null,
    val md: Int? = null,
)

@Serializable
class AvailableChapters(
    val sub: Int? = null,
    val raw: Int? = null,
)

@Serializable
class AiredStart(
    val year: Int? = null,
    val month: Int? = null,
    val date: Int? = null,
)

@Serializable
class ChapterDateMap(
    val sub: ChapterDateParts? = null,
    val raw: ChapterDateParts? = null,
)

@Serializable
class ChapterDateParts(
    val year: Int? = null,
    val month: Int? = null,
    val date: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val second: Int? = null,
)

fun CardDto.coverUrl(): String? = when {
    tbObj?.u != null -> "https://aln.youtube-anime.com/${tbObj.u}"
    thumbnail?.startsWith("http") == true -> thumbnail
    else -> null
}

fun CardDto.lastChapterMillis(): Long = lastChapterDate?.sub?.toMillis() ?: 0L

fun ChapterDateParts.toMillis(): Long {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    if (year != null) cal.set(year, (month ?: 1) - 1, date ?: 1, hour ?: 0, minute ?: 0, second ?: 0)
    return cal.timeInMillis
}

// ------------------------------------------------------------------
// Popular (queryPopular persisted query)
// ------------------------------------------------------------------

@Serializable
class PopularResponse(
    val data: PopularData? = null,
)

@Serializable
class PopularData(
    val queryPopular: Popular? = null,
)

@Serializable
class Popular(
    val total: Int = 0,
    val recommendations: List<Recommendation> = emptyList(),
)

@Serializable
class Recommendation(
    val anyCard: CardDto? = null,
)

// ------------------------------------------------------------------
// Search / browse (queryManga persisted query)
// ------------------------------------------------------------------

@Serializable
class MangaListResponse(
    val data: MangaListData? = null,
)

@Serializable
class MangaListData(
    val mangas: MangaList? = null,
)

@Serializable
class MangaList(
    val edges: List<MangaEdge> = emptyList(),
)

@Serializable
class MangaEdge(
    val _id: String,
    val name: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val type: String? = null,
    val score: Double? = null,
    val thumbnail: String? = null,
    val tbObj: ThumbObj? = null,
    val availableChapters: AvailableChapters? = null,
    val airedStart: AiredStart? = null,
    val lastChapterDate: ChapterDateMap? = null,
    val status: String? = null,
) {
    fun toCard(): CardDto = CardDto(
        _id = _id,
        name = name,
        englishName = englishName,
        nativeName = nativeName,
        type = type,
        score = score,
        thumbnail = thumbnail,
        tbObj = tbObj,
        availableChapters = availableChapters,
        airedStart = airedStart,
        lastChapterDate = lastChapterDate,
        status = status,
    )
}

// ------------------------------------------------------------------
// Details (queryMangaById persisted query)
// ------------------------------------------------------------------

@Serializable
class MangaDetailResponse(
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
    val type: String? = null,
    val score: Double? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val thumbnail: String? = null,
    val tbObj: ThumbObj? = null,
    val airedStart: AiredStart? = null,
    @kotlinx.serialization.SerialName("availableChaptersDetail")
    val availableChaptersDetail: AvailableChaptersDetail? = null,
) {
    fun title(): String = englishName ?: name ?: nativeName ?: _id
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
): SManga = SManga.create().apply {
    url = _id
    title = title()

    val altNames = buildList {
        nativeName?.takeIf { it.isNotBlank() && it != title() }?.let { add(it) }
        name?.takeIf { it.isNotBlank() && it != englishName && it != title() }?.let { add(it) }
    }

    val genreChips = buildList {
        addAll(genres)
        if (showTagsInGenre) {
            addAll(tags.map { tag ->
                // tags are prefixed like "theme:monsters" / "format:full_color"
                tag.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
            })
        }
    }.distinct()
        .filterNot { it.lowercase() in blockedGenres }
        .joinToString(", ")

    val stars = score?.takeIf { it > 0 }?.let { value ->
        val full = (value / 2).toInt().coerceIn(0, 5)
        "★".repeat(full) + "☆".repeat(5 - full) + " $value"
    }

    val infoLine = if (showExtraInfo) {
        buildString {
            airedStart?.year?.let { append("**Year:** $it") }
            if (!type.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("**Type:** $type")
            }
            if (!status.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("**Status:** $status")
            }
            if (stars != null) {
                if (isNotEmpty()) append(" · ")
                append("**$stars**")
            }
        }.ifBlank { null }
    } else {
        null
    }

    description = buildString {
        if (scorePosition != "none" && stars != null && scorePosition != "end") {
            // "top"
            append(stars)
            append("\n")
            if (infoLine != null) {
                append(infoLine)
                append("\n\n")
            }
        } else if (infoLine != null) {
            append(infoLine)
            append("\n\n")
        }

        this@MangaDetail.description?.let { append(it.trim()) }

        if (showAltNames && altNames.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Alternative names:\n")
            append(altNames.joinToString("\n") { "• $it" })
        }

        if (scorePosition == "end" && stars != null) {
            if (isNotEmpty()) append("\n\n")
            append(stars)
        }
    }.trim().ifBlank { description }

    status = when (status?.lowercase()) {
        "releasing", "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled", "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    genre = genreChips.ifBlank { null }
    thumbnail_url = coverUrl()
    initialized = true
}

fun MangaDetail.toChapterList(): List<SChapter> {
    val mangaId = _id
    return availableChaptersDetail?.sub.orEmpty().map { chapterString ->
        SChapter.create().apply {
            url = "/manga/$mangaId/chapter-$chapterString-sub"
            name = buildString {
                append("Ch. ")
                append(chapterString)
            }
            chapter_number = chapterString.toFloatOrNull() ?: -1f
            date_upload = 0L
        }
    }
}
