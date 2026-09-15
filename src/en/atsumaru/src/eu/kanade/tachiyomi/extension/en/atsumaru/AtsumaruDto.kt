package eu.kanade.tachiyomi.extension.en.atsumaru

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

// ------------------------------------------------------------------
// Browse (home2 endpoints)
// ------------------------------------------------------------------

@Serializable
class BrowseDto(
    val items: List<Item> = emptyList(),
) {
    @Serializable
    class Item(
        val id: String,
        val title: String,
        val image: String? = null,
        val smallImage: String? = null,
        val isAdult: Boolean = false,
        val type: String? = null,
        val medium: String? = null,
        val mbRating: Double? = null,
        val views: String? = null,
        val createdAt: Long? = null,
        val updatedAt: Long? = null,
    )
}

// ------------------------------------------------------------------
// Search (Typesense)
// ------------------------------------------------------------------

@Serializable
class SearchDto(
    val found: Int = 0,
    val page: Int = 1,
    val hits: List<Hit> = emptyList(),
) {
    fun hasNextPage(perPage: Int) = page * perPage < found

    @Serializable
    class Hit(
        val document: Document,
    )

    @Serializable
    class Document(
        val id: String,
        val title: String,
        val synopsis: String? = null,
        val status: String? = null,
        val type: String? = null,
        val medium: String? = null,
        val authors: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val otherNames: List<String> = emptyList(),
        val mbRating: Double? = null,
        val mbContentRating: String? = null,
        val isAdult: Boolean = false,
        val releaseYear: Int? = null,
        val chapterCount: Int? = null,
        val views: String? = null,
        val poster: String? = null,
        val posterMedium: String? = null,
        val posterSmall: String? = null,
    )
}

// ------------------------------------------------------------------
// Details
// ------------------------------------------------------------------

@Serializable
class MangaPageDto(
    val mangaPage: MangaPage,
)

@Serializable
class MangaPage(
    val id: String,
    val title: String,
    val synopsis: String? = null,
    val status: String? = null,
    val type: String? = null,
    val medium: String? = null,
    val isAdult: Boolean = false,
    val views: String? = null,
    val avgRating: Double? = null,
    val otherNames: List<String> = emptyList(),
    val authors: List<Author> = emptyList(),
    val genres: List<TagDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val poster: PosterDto? = null,
    val released: Long? = null,
    val totalChapterCount: Int? = null,
    val scanlators: List<ScanlatorDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
) {
    @Serializable
    class Author(
        val id: String,
        val name: String,
    )

    @Serializable
    class TagDto(
        val id: String,
        val name: String,
    )

    @Serializable
    class PosterDto(
        val image: String? = null,
    )

    @Serializable
    class ScanlatorDto(
        val id: String,
        val name: String,
    )
}

// ------------------------------------------------------------------
// Chapters
// ------------------------------------------------------------------

@Serializable
class AllChaptersDto(
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: String,
    val title: String = "",
    val number: Double? = null,
    val createdAt: Long? = null,
    val pageCount: Int? = null,
)

fun ChapterDto.toSChapter(): SChapter = SChapter.create().apply {
    // chapter.url is the chapter id; the manga id is joined in the source.
    url = id
    chapter_number = number?.toFloat() ?: -1f
    date_upload = createdAt ?: 0L
    name = buildString {
        val num = number
        if (num != null) {
            append("Ch. ")
            append(num.toString().removeSuffix(".0"))
        }
        val t = this@toSChapter.title.trim()
        if (t.isNotEmpty() && t != "null") {
            if (isNotEmpty()) append(" · ")
            append(t)
        }
        if (isEmpty()) append("Chapter ${num ?: id}")
    }
}

// ------------------------------------------------------------------
// Pages
// ------------------------------------------------------------------

@Serializable
class ReadChapterDto(
    val readChapter: ReadChapter,
)

@Serializable
class ReadChapter(
    val id: String,
    val title: String? = null,
    val pages: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    val id: String,
    val image: String,
    val number: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
)

fun List<PageDto>.toPageList(cdnBase: String): List<eu.kanade.tachiyomi.source.model.Page> =
    mapIndexed { index, dto ->
        eu.kanade.tachiyomi.source.model.Page(index, imageUrl = cdnBase + dto.image)
    }

// ------------------------------------------------------------------
// Shared: Comix-style description builder pieces
// ------------------------------------------------------------------

fun formatAtsuStatus(status: String?): Int = when (status?.lowercase()) {
    "ongoing", "releasing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus", "on hiatus" -> SManga.ON_HIATUS
    "cancelled", "discontinued" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
