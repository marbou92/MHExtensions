package eu.kanade.tachiyomi.extension.ar.mangadar

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// tRPC/superjson envelope. Every answer is
//   [ { "result": { "data": { "json": <payload> } } } ]
// ---------------------------------------------------------------------------

@Serializable
class TrpcBatch<T>(val result: TrpcResult<T>)

@Serializable
class TrpcResult<T>(val data: TrpcData<T>)

@Serializable
class TrpcData<T>(val json: T)

// ---------------------------------------------------------------------------
// Rows (browse / latest / search all carry the same series shape)
// ---------------------------------------------------------------------------

@Serializable
class SeriesRowDto(
    val id: String,
    val title: String? = null,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val slug: String,
    val coverUrl: String? = null,
    val type: String? = null,
    val status: String? = null,
    // 0..10 scale on both rows and details (details nest it in stats).
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val chapterCount: Int? = null,
    val viewCount: Long? = null,
    // search rows use favoriteCount, latest rows use favorites
    val favoriteCount: Int? = null,
    val favorites: Int? = null,
    val description: String? = null,
    // Plain Arabic genre strings on rows; details may vary, so absorb both.
    val genres: List<JsonElement> = emptyList(),
    val tags: List<JsonElement> = emptyList(),
    val author: String? = null,
    val artist: String? = null,
    val latestChapter: LatestChapterDto? = null,
) {
    fun displayTitle(): String = nameAr?.takeIf(String::isNotBlank)
        ?: title?.takeIf(String::isNotBlank)
        ?: nameEn
        ?: slug

    fun ratingValue(): Double? = rating?.takeIf { it > 0.0 }

    fun favoritesCount(): Int? = favoriteCount ?: favorites
}

@Serializable
class LatestChapterDto(
    val number: Float? = null,
    val releasedAt: String? = null,
)

@Serializable
class SearchSeriesResponseDto(
    val results: List<SeriesRowDto> = emptyList(),
    val currentPage: Int? = null,
    val hasMore: Boolean = false,
    val totalPages: Int? = null,
)

@Serializable
class LatestReleasesResponseDto(
    val items: List<SeriesRowDto> = emptyList(),
    val page: Int? = null,
    val hasMore: Boolean = false,
)

// ---------------------------------------------------------------------------
// Details
// ---------------------------------------------------------------------------

@Serializable
class SeriesDetailsDto(
    val id: String,
    val slug: String? = null,
    val title: String? = null,
    val nameAr: String? = null,
    val nameEn: String? = null,
    // Wire shape 2026-10: [{"title":"Blue Lock","language":"en"}] — objects,
    // not strings; alternativeTitleNames() absorbs both.
    val alternativeTitles: List<JsonElement>? = null,
    val description: String? = null,
    val type: String? = null,
    val status: String? = null,
    val genres: List<JsonElement> = emptyList(),
    val tags: List<JsonElement> = emptyList(),
    val author: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val lastChapterAt: String? = null,
    val stats: SeriesStatsDto? = null,
) {
    fun displayTitle(): String = nameAr?.takeIf(String::isNotBlank)
        ?: title?.takeIf(String::isNotBlank)
        ?: nameEn
        ?: ""

    fun favoriteCount(): Int? = stats?.favorites

    fun viewCount(): Long? = stats?.views

    fun chapterCount(): Int? = stats?.chapterCount

    fun ratingValue(): Double? = stats?.rating?.takeIf { it > 0.0 }

    fun ratingCountValue(): Int? = stats?.ratingCount

    fun alternativeTitleNames(): List<String> = alternativeTitles
        .orEmpty()
        .mapNotNull { element ->
            when (element) {
                is kotlinx.serialization.json.JsonObject ->
                    (
                        (element["title"] as? JsonPrimitive)?.content
                            ?: (element["name"] as? JsonPrimitive)?.content
                        )?.takeIf(String::isNotBlank)
                is JsonPrimitive -> element.content.takeIf(String::isNotBlank)
                else -> null
            }
        }
        .filter(String::isNotEmpty)
}

@Serializable
class SeriesStatsDto(
    val views: Long? = null,
    val favorites: Int? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val commentCount: Int? = null,
    val chapterCount: Int? = null,
)

// ---------------------------------------------------------------------------
// Chapters
// ---------------------------------------------------------------------------

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: String,
    val seriesId: String? = null,
    // Fractional numbers exist (364.5) — always a JSON number.
    val number: Float,
    val title: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val isPremium: Boolean? = null,
    val isUnlocked: Boolean? = null,
    val isBlocked: Boolean? = null,
    val publishedAt: String? = null,
    val createdAt: String? = null,
)

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

@Serializable
class ChapterPagesDto(
    val id: String? = null,
    val seriesSlug: String? = null,
    val pageCount: Int? = null,
    val pages: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Genres
// ---------------------------------------------------------------------------

@Serializable
class GenresDto(
    val genres: List<GenreDto> = emptyList(),
)

@Serializable
class GenreDto(
    val id: String,
    val name: String,
    val slug: String,
    val seriesCount: Int? = null,
)

// ---------------------------------------------------------------------------
// Mappers
// ---------------------------------------------------------------------------

/** Persisted inside SChapter.url: everything the reader flow needs. */
@Serializable
class ChapterRef(
    val s: String,
    val n: Float,
)

fun ChapterDto.toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
    url = ChapterRef(s = seriesSlug, n = this@toSChapter.number).toJsonString()
    val numberText = trimNumber(this@toSChapter.number)
    name = buildString {
        append("الفصل ").append(numberText)
        val title = this@toSChapter.title.orEmpty().trim()
        if (title.isNotEmpty() && title != numberText) {
            append(": ").append(title)
        }
        if (this@toSChapter.isPremium == true) {
            append(" 🔒")
        }
    }
    chapter_number = this@toSChapter.number
    scanlator = null // the platform has no upload teams
    date_upload = 0L // the source fills this from publishedAt
}

private fun trimNumber(number: Float): String = if (number == number.toLong().toFloat()) {
    number.toLong().toString()
} else {
    number.toString()
}

/** Genre elements arrive as plain strings ("اكشن"); absorb objects too. */
internal fun JsonElement.asLabel(): String? = when (this) {
    is JsonPrimitive -> content.takeIf(String::isNotBlank)
    is JsonObject ->
        (
            (this["name"] as? JsonPrimitive)?.content
                ?: (this["slug"] as? JsonPrimitive)?.content
            )?.takeIf(String::isNotBlank)
    is JsonArray -> firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }
        ?.takeIf(String::isNotBlank)
    else -> null
}
