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
class CoverAppDto(
    val desktop: String? = null,
    val mobile: String? = null,
    val card: CoverCardDto? = null,
)

@Serializable
class CoverCardDto(
    val desktop: String? = null,
    val mobile: String? = null,
)

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
    val cdn_path: String? = null,
    val coverImage: String? = null,
    val coverImageApp: CoverAppDto? = null,
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
    val coverImage: String? = null,
    val coverImageApp: CoverAppDto? = null,
    val metadata: SeriesMetaDto? = null,
)

@Serializable
class SeriesCategoryDto(
    val en: String? = null,
    val ar: String? = null,
)

@Serializable
class SeriesCategoriesDto(
    val genres: List<SeriesCategoryDto>? = null,
    val tags: List<SeriesCategoryDto>? = null,
) {
    fun genreNames(): List<String> = genres.orEmpty().mapNotNull { it.en?.trim() }.filter { it.isNotEmpty() }

    fun tagNames(): List<String> = tags.orEmpty().mapNotNull { it.en?.trim() }.filter { it.isNotEmpty() }
}

/** The `{"series":{…},"categories":{…},…}` props object the migrated
 * series flight embeds; the categories sibling is null on legacy
 * flights. */
@Serializable
class SeriesFlightWrapperDto(
    val series: SeriesDetailsDto,
    val categories: SeriesCategoriesDto? = null,
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

/** The reader's own `<img>` sources: `"appImages":[{mobile,desktop}]`.
 * signed-filename AVIF URLs on `app.<host>` that fetch directly from any
 * client (the signature rides in the file name). */
@Serializable
class ReaderImageDto(
    val mobile: String? = null,
    val desktop: String? = null,
)

@Serializable
class DeferredMediaDto(
    val token: String? = null,
    val splitIndex: Int? = null,
    val triggerAfterImageIndex: Int? = null,
    val requireTurnstile: Boolean? = null,
    val turnstileMode: String? = null,
)

@Serializable
class ChapterMapTokenDto(
    val token: String? = null,
    val method: String? = null,
)

@Serializable
class DeferredAnswerDto(
    val chapterId: Int? = null,
    val splitIndex: Int? = null,
    val images: List<String> = emptyList(),
    val maps: List<ChapterMapTokenDto> = emptyList(),
    val mapDelivery: String? = null,
    val pieceRenderMode: String? = null,
    val pass: String? = null,
    val source: String? = null,
)

@Serializable
class ChapterMapPlanDto(
    val dim: List<Double> = emptyList(),
    val mode: String? = null,
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val rects: List<ChapterMapRectDto>? = null,
) {
    /** The draw order as a permutation of [pieces] (identity when absent or
     * malformed. The observed live shape). */
    fun drawOrder(): List<Int> {
        val identity = pieces.indices.toList()
        if (order.isEmpty()) return identity
        if (order.size != pieces.size) return identity
        val seen = HashSet<Int>()
        order.forEach { if (it < 0 || it >= pieces.size || !seen.add(it)) return identity }
        return order
    }

    /**
     * The per-slot rects: absolute when the map ships them (count must
     * match the draw slots), derived from [mode] otherwise. Never empty.
     * callers check the size against [pieces].
     */
    fun effectiveRects(width: Int, height: Int): List<ChapterMapRectDto> {
        val slotCount = pieces.size
        if (slotCount == 0) return emptyList()
        val shipped = rects
        if (shipped != null && shipped.size == slotCount) return shipped

        val recipe = mode?.trim().orEmpty()
        val parts = recipe.split("_", limit = 2)
        val shape = parts.getOrNull(0).orEmpty()
        val arg = parts.getOrNull(1).orEmpty()
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)

        if (shape == "grid") {
            val split = arg.split("x")
            val cols = split.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val rows = split.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val colWidths = splitWithRemainder(w, cols)
            val rowHeights = splitWithRemainder(h, rows)
            val lefts = runningOffsets(colWidths)
            val tops = runningOffsets(rowHeights)
            return List(slotCount) { slot ->
                val col = slot % cols
                val row = slot / cols
                ChapterMapRectDto(
                    left = lefts.getOrElse(col) { 0 },
                    top = tops.getOrElse(row) { 0 },
                    width = colWidths.getOrElse(col) { w },
                    height = rowHeights.getOrElse(row) { h },
                )
            }
        }

        if (shape == "vertical") {
            val strips = arg.toIntOrNull()?.takeIf { it > 0 } ?: slotCount
            val widths = splitWithRemainder(w, strips)
            val lefts = runningOffsets(widths)
            return List(slotCount) { slot ->
                ChapterMapRectDto(
                    left = lefts.getOrElse(slot) { 0 },
                    top = 0,
                    width = widths.getOrElse(slot) { w },
                    height = h,
                )
            }
        }

        // Fallback: equal horizontal slices (the site's default branch.
        // count = the draw slots, remainder to the first slices).
        val sliceHeights = splitWithRemainder(h, slotCount)
        val tops = runningOffsets(sliceHeights)
        return List(slotCount) { slot ->
            ChapterMapRectDto(
                left = 0,
                top = tops.getOrElse(slot) { 0 },
                width = w,
                height = sliceHeights.getOrElse(slot) { h },
            )
        }
    }

    companion object {
        /** Floor-split [total] into [count] parts, one remainder pixel to
         * each of the first [total % count] parts (the site's own rule). */
        private fun splitWithRemainder(total: Int, count: Int): List<Int> {
            if (count <= 0) return listOf(total)
            val base = total / count
            val remainder = total % count
            return List(count) { index ->
                base + if (index < remainder) 1 else 0
            }
        }

        fun runningOffsets(sizes: List<Int>): List<Int> {
            val out = ArrayList<Int>(sizes.size)
            var cursor = 0
            for (size in sizes) {
                out.add(cursor)
                cursor += size
            }
            return out
        }
    }
}

@Serializable
class ChapterMapRectDto(
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

// ============================== Chapters API ============================

/**
 * GET /api/public/chapters?contentId={id}&page={n}&limit=50.
 * `{chapters:[...], total, hasMore}`. Newest first, dates included. The
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

internal fun resolveCoverUrl(
    coverImageApp: CoverAppDto?,
    coverImage: String?,
    thumbnail: String?,
    cdnPath: String?,
): String? {
    val absolute = listOf(
        coverImageApp?.desktop,
        coverImageApp?.card?.desktop,
        coverImageApp?.card?.mobile,
        coverImageApp?.mobile,
        coverImage,
        thumbnail,
    ).firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }
    if (absolute != null) return absolute

    val relative = listOf(coverImage, thumbnail)
        .firstOrNull { !it.isNullOrBlank() && it.startsWith("/") }
        ?: return null
    val host = cdnPath?.takeIf { Regex("cdn[1-9]").matches(it) } ?: return null
    return "https://$host.procomic.net$relative"
}

/** Persisted inside SChapter.url: everything the reader flow needs. */
@Serializable
class ChapterRef(
    val seriesSlug: String,
    val chapterNumber: String,
    val chapterId: Int,
)
