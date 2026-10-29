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
