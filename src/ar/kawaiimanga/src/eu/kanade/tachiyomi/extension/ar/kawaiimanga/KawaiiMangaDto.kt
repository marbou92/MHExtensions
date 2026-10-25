package eu.kanade.tachiyomi.extension.ar.kawaiimanga

import kotlinx.serialization.Serializable

// ============================ Auth / envelope ============================

/** GET /api/manga/token — the visitor JWT every data call must carry. */
@Serializable
class TokenDto(
    val token: String? = null,
    val expiresIn: Long? = null,
)

// ============================== Catalog rows =============================

/**
 * The browse/search/series row shape. `rating` is the site's own 0..5 float
 * (the star widget calls rating.toFixed(1)); coverUrl is an absolute CDN
 * link (the unsigned /cover.webp answers plain requests).
 */
@Serializable
class SeriesRowDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val titleAr: String? = null,
    val titleEn: String? = null,
    val altTitles: List<String> = emptyList(),
    val type: String? = null,
    val status: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val viewCount: Long? = null,
    val chapterCount: Int? = null,
    val mainChapterCount: Int? = null,
    val updatedAt: String? = null,
    val createdAt: String? = null,
) {
    /** The catalog rows never omit the slug, but guard the fallbacks anyway:
     * the URL field must stay the request-side slug (see MangaTime's
     * slugOrId lesson — an id here 404s every later refresh). */
    fun displaySlug(): String = slug?.takeIf(String::isNotBlank)
        ?: id?.takeIf(String::isNotBlank)
        ?: ""

    fun displayTitle(): String = title?.takeIf(String::isNotBlank)
        ?: titleAr?.takeIf(String::isNotBlank)
        ?: titleEn.orEmpty().ifBlank { "غير معروف" }
}

@Serializable
class BrowseResponseDto(
    val results: List<SeriesRowDto> = emptyList(),
    val totalCount: Int? = null,
    val page: Int? = null,
    val hasMore: Boolean? = null,
)

@Serializable
class SearchResponseDto(
    val results: List<SeriesRowDto> = emptyList(),
)

// =============================== Chapters ===============================

/** Lives inside the series payload — the whole TOC, newest first. */
@Serializable
class ChapterDto(
    val id: String,
    val number: Double,
    val title: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val createdAt: String? = null,
    /** Unsigned CDN path — always 403s; never used (kept for parsing). */
    val thumbnail: String? = null,
)

@Serializable
class SeriesDetailsDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val titleAr: String? = null,
    val titleEn: String? = null,
    val altTitles: List<String> = emptyList(),
    val type: String? = null,
    val status: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val viewCount: Long? = null,
    val chapterCount: Int? = null,
    val mainChapterCount: Int? = null,
    val updatedAt: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
    val novelSlug: String? = null,
) {
    fun displaySlug(): String = slug?.takeIf(String::isNotBlank)
        ?: id?.takeIf(String::isNotBlank)
        ?: ""

    fun displayTitle(): String = title?.takeIf(String::isNotBlank)
        ?: titleAr?.takeIf(String::isNotBlank)
        ?: titleEn.orEmpty().ifBlank { "غير معروف" }

    fun toRow(): SeriesRowDto = SeriesRowDto(
        id = id,
        slug = slug,
        title = title,
        titleAr = titleAr,
        titleEn = titleEn,
        altTitles = altTitles,
        type = type,
        status = status,
        author = author,
        artist = artist,
        description = description,
        coverUrl = coverUrl,
        genres = genres,
        rating = rating,
        viewCount = viewCount,
        chapterCount = chapterCount ?: mainChapterCount ?: chapters.size,
        mainChapterCount = mainChapterCount,
        updatedAt = updatedAt,
        createdAt = null,
    )
}

// ================================ Pages =================================

/** GET /own?action=pages&chapterId=... — signed CDN links (~24-48h TTL). */
@Serializable
class PagesDto(
    val pages: List<String> = emptyList(),
)
