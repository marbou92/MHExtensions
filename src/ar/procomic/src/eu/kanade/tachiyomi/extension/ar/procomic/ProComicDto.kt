package eu.kanade.tachiyomi.extension.ar.procomic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// ============================ Search / Browse ============================

@Serializable
class SearchMetaDto(
    val total: Int? = null,
    val page: Int? = null,
    val limit: Int? = null,
    val pages: Int? = null,
)

@Serializable
class SeriesMetaDto(
    // The search API mixes shapes: author/artist arrive as strings on most
    // series and as arrays on others.
    val author: JsonElement? = null,
    val artist: JsonElement? = null,
    val year: String? = null,
    val origin: String? = null,
    val descriptions: Map<String, String>? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val coverImage: String? = null,
) {
    fun authorText(): String? = textOrJoined(author)

    fun artistText(): String? = textOrJoined(artist)

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
class SeriesSearchDto(
    val id: Int,
    val title: String,
    val slug: String,
    val description: String? = null,
    val type: String? = null,
    val progress: String? = null,
    val thumbnail: String? = null,
    val updated_at: String? = null,
    val metadata: SeriesMetaDto? = null,
)

@Serializable
class SearchResponseDto(
    val data: List<SeriesSearchDto> = emptyList(),
    val meta: SearchMetaDto? = null,
)

// ============================== Details ================================

@Serializable
class SeriesDetailsDto(
    val id: Int,
    val title: String,
    val slug: String,
    val type: String? = null,
    val description: String? = null,
    val average_rating: Double? = null,
    val ratings_count: Int? = null,
    val favorites_count: Int? = null,
    val chaptersCount: Int? = null,
    val thumbnail: String? = null,
    val cdn_path: String? = null,
    val metadata: SeriesMetaDto? = null,
)

// ============================== Chapters ===============================

/** Chapter rows embedded in the series details page (latest 30, with dates). */
@Serializable
class ChapterRowDto(
    val id: Int,
    val chapter_number: String? = null,
    val title: String? = null,
    val language: String? = null,
    val translator: String? = null,
    val published_at: String? = null,
    val lockedByCoins: Boolean? = null,
    val lockedForever: Boolean? = null,
)

/** Full TOC rows on the reader page (all chapters, no dates). */
@Serializable
class TocChapterDto(
    val id: Int,
    val chapterNumber: String? = null,
    val language: String? = null,
    val group: String? = null,
    val translator: String? = null,
    /** Only set by the chapters API rows (the reader TOC carries none). */
    val publishedAt: String? = null,
)

// =============================== Reader ================================

/** The reader's own `<img>` sources: `"appImages":[{mobile,desktop}]` —
 * signed-filename AVIF URLs on `app.<host>` that fetch directly from any
 * client (the signature rides in the file name). */
@Serializable
class ReaderImageDto(
    val mobile: String? = null,
    val desktop: String? = null,
)

// ============================== Chapters API ============================

/**
 * GET /api/public/chapters?contentId={id}&page={n}&limit=50 —
 * `{chapters:[...], total, hasMore}` — newest first, dates included. The
 * server caps `limit` at 50. Works for client-rendered series too.
 */
@Serializable
class ApiChapterDto(
    val id: Int,
    val content_id: Int? = null,
    val chapter_number: String? = null,
    val title: String? = null,
    val language: String? = null,
    val translator: String? = null,
    val status: String? = null,
    val created_at: String? = null,
    val published_at: String? = null,
)

@Serializable
class ApiChaptersResponseDto(
    val chapters: List<ApiChapterDto> = emptyList(),
    val hasMore: Boolean? = null,
)

// ========================= Latest-updates feed ==========================

@Serializable
class UpdateChapterDto(
    val id: Int,
    val slug: String? = null,
    val number: String? = null,
    val language: String? = null,
    val publishedAt: String? = null,
)

@Serializable
class UpdateRowDto(
    val mangaId: Int,
    val mangaSlug: String,
    val mangaTitle: String,
    val coverImage: String? = null,
    val cdn_path: String? = null,
    val type: String? = null,
    val status: String? = null,
    val chapters: List<UpdateChapterDto> = emptyList(),
)

/** Persisted inside SChapter.url: everything the reader flow needs. */
@Serializable
class ChapterRef(
    val seriesSlug: String,
    val chapterNumber: String,
    val chapterId: Int,
)
