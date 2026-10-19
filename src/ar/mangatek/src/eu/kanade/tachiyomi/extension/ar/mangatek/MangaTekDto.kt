package eu.kanade.tachiyomi.extension.ar.mangatek

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class GenreDto(
    val id: String,
    val name: String,
    val slug: String,
)

@Serializable
class SeriesDto(
    val id: String,
    val slug: String,
    val title: String,
    val associatedNames: String? = null,
    val description: String? = null,
    // The wire mixes shapes between deployments: author/artist arrive as a
    // string on some series and as an array of names on others;
    // chapterCount arrives as "19" or 19.
    val author: JsonElement? = null,
    val artist: JsonElement? = null,
    val status: String? = null,
    val type: String? = null,
    val format: String? = null,
    val coverImage: String? = null,
    val viewCount: Int? = null,
    val bookmarkCount: Int? = null,
    val ratingCount: Int? = null,
    // "8.00" on rated series, plain number 0 on unrated ones.
    val ratingAvg: JsonPrimitive? = null,
    val chapterCount: JsonPrimitive? = null,
    // Details carry slug strings, search rows carry {id, slug, name} objects.
    val genres: List<JsonElement> = emptyList(),
) {
    fun coverUrl(): String? = coverImage?.takeIf { it.startsWith("http") }

    fun authorText(): String? = textOrJoined(author)

    fun artistText(): String? = textOrJoined(artist)

    fun ratingAvgValue(): Float? = ratingAvg
        ?.content
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }

    fun chapterCountText(): String? = chapterCount
        ?.content
        ?.takeIf { it.isNotBlank() && it != "null" }

    fun genreLabels(): List<String> = genres.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.content.takeIf(String::isNotBlank)
            is kotlinx.serialization.json.JsonObject ->
                (
                    (element["name"] as? JsonPrimitive)?.content
                        ?: (element["slug"] as? JsonPrimitive)?.content
                    )?.takeIf(String::isNotBlank)
            else -> null
        }
    }

    companion object {
        private fun textOrJoined(element: JsonElement?): String? = when (element) {
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "null" }
            is JsonArray ->
                element
                    .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

@Serializable
class SearchResponseDto(
    val data: List<SeriesDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
class ChapterDto(
    val id: String,
    val seriesId: String,
    val number: String,
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
class ChapterListDto(
    val data: List<ChapterDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
class PageDto(
    val number: Int? = null,
    val imageUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
class ChapterDetailDto(
    val id: String,
    val seriesId: String,
    val number: String,
    val slug: String,
    val pages: List<PageDto> = emptyList(),
)

/** Persisted inside SChapter.url: everything the reader flow needs. */
@Serializable
class ChapterRef(
    val seriesSlug: String,
    val chapterSlug: String,
    val seriesId: String,
    val chapterId: String,
)

fun ChapterDto.toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
    val ref = ChapterRef(
        seriesSlug = seriesSlug,
        chapterSlug = this@toSChapter.slug,
        seriesId = this@toSChapter.seriesId,
        chapterId = this@toSChapter.id,
    )
    url = ref.toJsonString()
    name = buildString {
        val number = this@toSChapter.number?.removeSuffix(".00")?.removeSuffix(".0") ?: ""
        val title = this@toSChapter.title.orEmpty().trim()
        append("الفصل ").append(number)
        if (title.isNotEmpty() && title != number) {
            append(": ").append(title)
        }
        if ((this@toSChapter.coinValue ?: 0) > 0) {
            append(" 🔒")
        }
    }
    chapter_number = this@toSChapter.number?.toFloatOrNull() ?: 0f
    scanlator = this@toSChapter.uploadedByTeamName?.takeIf { it.isNotBlank() }
    date_upload = 0L // the source fills this from publishedAt/updatedAt
}
