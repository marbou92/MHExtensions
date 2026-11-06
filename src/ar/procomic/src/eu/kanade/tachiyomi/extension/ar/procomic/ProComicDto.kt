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

/** The site's app-CDN cover variants (v105): `coverImageApp` on the search
 * rows and series payloads — the `app.<host>` signed-filename AVIFs the
 * site's own cards load. Hosts observed live: app.procomic.net,
 * app.procomic.pro and app.prochan.net (the parent brand serves a share of
 * the catalog's covers). */
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

/** v117: one localized category entry — the migrated series flight
 * ships genres/tags as `{en, ar}` pairs. The English name is what the
 * genre field (and every reader app's long-strip/type recognition)
 * consumes; Arabic is kept for possible future UI use. */
@Serializable
class SeriesCategoryDto(
    val en: String? = null,
    val ar: String? = null,
)

/** v117: the migrated series flight's SIBLING categories object. FIELD
 * ROOT CAUSE of the "type not recognizable" report: the migration moved
 * genres/tags OUT of `series.metadata` (flat strings, now absent) into a
 * `categories` object beside the series object — the old DTO read
 * `metadata.genres = null` and every entry's genre list collapsed to the
 * bare type string, which killed the reader apps' long-strip/RTL
 * auto-detection that feeds on those genre strings. The legacy flat
 * shape still rides the search-API rows (and any .pro-served legacy
 * flight), so both are merged upstream. */
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

/** The reader's own `<img>` sources: `"appImages":[{mobile,desktop}]` —
 * signed-filename AVIF URLs on `app.<host>` that fetch directly from any
 * client (the signature rides in the file name). */
@Serializable
class ReaderImageDto(
    val mobile: String? = null,
    val desktop: String? = null,
)

/**
 * The flight's `deferredMedia` object (v112). When the site caps the SSR
 * embed at the first `publicImageCount` pages (observed live: 3), its own
 * reader fetches the rest through `/chapter-deferred-media/{id}?token=…`
 * with this short-lived JWT (a `chapter_deferred_media` claim set carrying
 * the chapter id, the split size and a 15-minute expiry).
 */
@Serializable
class DeferredMediaDto(
    val token: String? = null,
    val splitIndex: Int? = null,
    val triggerAfterImageIndex: Int? = null,
    val requireTurnstile: Boolean? = null,
    val turnstileMode: String? = null,
)

/**
 * v129: one entry of the deferred exchange's `maps[]` — an ENCRYPTED
 * reconstruction-map token for one protected page (live 2026-11-06:
 * `{token:"eyJ2IjozLCJtIjoiYnJvd3Nlcl9zZXNzaW9uIi4uLg",method:"browser_session"}`;
 * the token's own payload carries `{v:3, m:"browser_session", cid, iv, tag,
 * data}` — the map the site's reader decrypts or exchanges through the
 * plan endpoint). `method` mirrors the site's accepted set
 * ("browser" | "browser_session").
 */
@Serializable
class ChapterMapTokenDto(
    val token: String? = null,
    val method: String? = null,
)

/**
 * v129: the deferred exchange's full answer — `{success,data:{...}}` with
 * the data object carrying BOTH page classes:
 *  - `images[]` — plain signed-filename urls (the pre-v129 shape; empty in
 *    the maps era),
 *  - `maps[]` — one reconstruction-map token per protected page (see
 *    [ChapterMapTokenDto]),
 * plus the delivery meta the site's own reader consumes: `mapDelivery`
 * ("proxy-plan" — the pages are served by planning each token through
 * POST /chapter-map-proxy-plan/{id}), `pieceRenderMode` ("img" — the
 * pieces render as plain <img> layers) and `pass` — the FRESH pages-scope
 * credential (x-chapter-pass) minted alongside the answer.
 *
 * An answer whose `images` AND `maps` are both EMPTY ARRAYS is the
 * server's terminal answer (the walk is complete) — v128 read only
 * `images[]`, so the maps era's empty-but-present `images[]` read as
 * "done" and every chapter stopped at the public embed (the "only the
 * first three pages" field verdict).
 */
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

/**
 * v129: the plan POST's reconstruction map — `{success,data:{map:{…}}}` with
 * `dim` (canvas [width, height]), `mode` (the layout recipe: observed live
 * `grid_2x3`; the site also derives `vertical_{n}` and a flat-slice
 * fallback), `pieces` (the FRESHLY-SIGNED absolute urls on
 * img{1-4}.procomic.im), `order` (draw order — an index permutation into
 * [pieces], observed identity) and `rects` (absolute pixel rects aligned
 * with the DRAW SLOTS — `pieces[order[i]]` draws at `rects[i]`).
 *
 * When `rects` is absent (or its count mismatches), the rects are derived
 * from `mode` exactly like the site's own renderer:
 *  - `grid_{c}x{r}` — c columns × r rows; the width/height remainder is
 *    distributed one pixel at a time to the FIRST cells (floor division +
 *    remainder), tops/lefts are the running offsets;
 *  - `vertical_{n}` — n full-height vertical strips;
 *  - anything else — as many equal horizontal slices as there are pieces.
 */
@Serializable
class ChapterMapPlanDto(
    val dim: List<Double> = emptyList(),
    val mode: String? = null,
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val rects: List<ChapterMapRectDto>? = null,
) {
    /** The draw order as a permutation of [pieces] (identity when absent or
     * malformed — the observed live shape). */
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
     * match the draw slots), derived from [mode] otherwise. Never empty —
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

        // Fallback: equal horizontal slices (the site's default branch —
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

/** v129: one absolute pixel rect of a reconstruction map's draw slot. */
@Serializable
class ChapterMapRectDto(
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
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

/**
 * Resolves one fetchable cover URL out of the field mix the site now ships
 * (v105). Preference order, each branch verified live:
 *
 * 1. `coverImageApp.desktop` / `card.desktop` / `card.mobile` / `mobile` —
 *    absolute `app.<host>` signed-filename AVIFs (app.procomic.net,
 *    app.procomic.pro, app.prochan.net — all 200 to plain clients);
 * 2. absolute `coverImage` — `cdn{N}.procomic.net` raw (200 with a browser
 *    UA, 403 otherwise — the loopback proxy sends the browser UA);
 * 3. absolute `thumbnail`;
 * 4. RELATIVE `coverImage`/`thumbnail` (`/224/image_series/...avif`) joined
 *    onto the row's `cdn_path` host (`cdn2` -> https://cdn2.procomic.net).
 *    The search API switched a wave of series to this shape and the bare
 *    apexes 404 those paths — only the numbered CDN hosts serve them.
 *
 * Returns null when nothing usable is present (the caller keeps the old
 * blank-cover behavior).
 */
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
