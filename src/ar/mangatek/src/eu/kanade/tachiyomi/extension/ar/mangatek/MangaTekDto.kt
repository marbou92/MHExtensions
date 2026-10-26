package eu.kanade.tachiyomi.extension.ar.mangatek

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Axis-platform wire shapes. Every field is nullable/shape-flexible on
// purpose: the platform evolves its payloads between deployments and the
// catalog rows and details responses drift independently.
// ---------------------------------------------------------------------------

@Serializable
internal data class SearchResponseDto(
    val data: List<SeriesDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
internal data class ChapterListDto(
    val data: List<ChapterDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
internal data class SeriesDto(
    val id: String,
    val slug: String,
    val title: String,
    val associatedNames: String? = null,
    val description: String? = null,
    // string OR array of strings depending on the deployment
    val author: JsonElement? = null,
    val artist: JsonElement? = null,
    val status: String? = null,
    val type: String? = null,
    val format: String? = null,
    val coverImage: String? = null,
    val viewCount: Int? = null,
    val bookmarkCount: Int? = null,
    val ratingCount: Int? = null,
    // "8.00" or 8 depending on the deployment
    val ratingAvg: JsonPrimitive? = null,
    // "19" or 19 depending on the deployment
    val chapterCount: JsonPrimitive? = null,
    // slug strings on older deployments, {id, name, slug} objects on newer
    val genres: List<JsonElement> = emptyList(),
    val genreNames: List<JsonElement> = emptyList(),
) {
    /** Absolute cover URL; relative paths can't be resolved safely, drop them. */
    val coverUrl: String?
        get() = coverImage?.takeIf { it.startsWith("http") }

    val authorText: String?
        get() = textOrJoined(author)

    val artistText: String?
        get() = textOrJoined(artist)

    val ratingAvgValue: Float?
        get() = ratingAvg?.content?.toFloatOrNull()?.takeIf { it > 0f }

    val chapterCountText: String?
        get() = chapterCount?.content?.takeIf(String::isNotBlank)

    val genreLabels: List<String>
        get() = (genres + genreNames)
            .mapNotNull(::jsonElementText)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)

    val altTitleList: List<String>
        get() = associatedNames
            ?.split('\n')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
}

@Serializable
internal data class ChapterDto(
    val id: String,
    val seriesId: String,
    val number: String? = null,
    val title: String? = null,
    val slug: String,
    val uploadedByTeamName: String? = null,
    val coinValue: Int? = null,
    val isAdult: Boolean? = null,
    val createdAt: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class ChapterDetailDto(
    val id: String,
    val seriesId: String,
    val number: String? = null,
    val slug: String,
    val pages: List<PageDto> = emptyList(),
)

@Serializable
internal data class PageDto(
    val number: Int? = null,
    val imageUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class GenreDto(
    val id: String,
    val name: String,
    val slug: String? = null,
)

/** Persisted inside SChapter.url — everything the reader flow needs. */
@Serializable
internal data class ChapterRef(
    val seriesId: String,
    val seriesSlug: String,
    val chapterId: String,
    val chapterSlug: String,
)

// ---------------------------------------------------------------------------
// Shape helpers
// ---------------------------------------------------------------------------

internal fun jsonElementText(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.content.takeIf(String::isNotBlank)
    is JsonObject -> (element["name"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        ?: (element["title"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
    is JsonArray -> element.mapNotNull(::jsonElementText).joinToString(", ").takeIf(String::isNotBlank)
}

/** `author`/`artist` arrive as a plain string OR an array of strings. */
private fun textOrJoined(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> element.content.takeIf(String::isNotBlank)
    is JsonArray -> element.mapNotNull(::jsonElementText)
        .joinToString(", ")
        .takeIf(String::isNotBlank)
    is JsonObject -> jsonElementText(element)
}

/** SChapter from a chapter row. [seriesSlug] is what the details lookup used. */
internal fun ChapterDto.toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
    val ref = ChapterRef(
        seriesId = this@toSChapter.seriesId,
        seriesSlug = seriesSlug,
        chapterId = this@toSChapter.id,
        chapterSlug = this@toSChapter.slug,
    )
    url = ref.toJsonString()

    val number = this@toSChapter.number.orEmpty()
        .removeSuffix(".00")
        .removeSuffix(".0")
    val title = this@toSChapter.title?.trim().orEmpty()
    name = buildString {
        append("الفصل ")
        append(number)
        if (title.isNotEmpty() && title != number) {
            append(": ")
            append(title)
        }
        if ((this@toSChapter.coinValue ?: 0) > 0) {
            // coin-locked on the site — mark it so readers know up front
            append(" 🔒")
        }
    }
    chapter_number = number.toFloatOrNull() ?: 0f
    scanlator = this@toSChapter.uploadedByTeamName?.takeIf(String::isNotBlank)
}

/** Used by [MangaTek.getChapterUrl] and [MangaTek.getPageList]. */
internal fun String.parseChapterRef(): ChapterRef = parseAs<ChapterRef>()
