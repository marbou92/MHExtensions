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
        // "Author", "Artist", "Story", "Art", ... depending on the credit.
        val type: String? = null,
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

/**
 * Titles like "Chapter 195", "Chapiter 195" or a bare "195" only restate the
 * chapter number — the old name builder produced "Ch. 195 · Chapiter 195".
 * A real title ("Chapter 5: The Beginning") keeps its text after the number
 * prefix is stripped.
 */
private val CHAPTER_NUMBER_TITLE = Regex(
    "^(?:ch(?:apter|apiter)?|chapiter|episode|ep|épisode)?\\s*(?:no\\.?|#)?\\s*\\d+(?:\\.\\d+)?$",
    RegexOption.IGNORE_CASE,
)

private val CHAPTER_TITLE_PREFIX = Regex(
    "^(?:ch(?:apter|apiter)?|episode|ep|épisode)\\s*\\d+(?:\\.\\d+)?\\s*[:\\-–—]\\s*",
    RegexOption.IGNORE_CASE,
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

        var t = this@toSChapter.title.trim()
        if (t.isNotEmpty() && t.equals("null", true)) t = ""
        t = t.replaceFirst(CHAPTER_TITLE_PREFIX, "").trim()
        if (t.isNotEmpty() && num != null && CHAPTER_NUMBER_TITLE.matches(t)) t = ""
        if (t.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(t)
        }
        if (isEmpty()) append("Chapter ${num?.toString()?.removeSuffix(".0") ?: id}")
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

/**
 * Page image paths live under `/static/` on the CDN. Paths that already
 * contain `static/` are kept as-is so previously working URLs stay stable.
 */
fun List<PageDto>.toPageList(cdnBase: String): List<eu.kanade.tachiyomi.source.model.Page> = mapIndexed { index, dto ->
    val path = dto.image.trim()
    val imageUrl = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/static/") -> cdnBase + path
        path.startsWith("static/") -> "$cdnBase/$path"
        else -> "$cdnBase/static/${path.removePrefix("/")}"
    }
    eu.kanade.tachiyomi.source.model.Page(index, imageUrl = imageUrl)
}

// ------------------------------------------------------------------
// Shared: Comix-style description builder pieces
// ------------------------------------------------------------------

fun formatAtsuStatus(status: String?): Int = when (status?.lowercase()) {
    "ongoing", "releasing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus", "on hiatus" -> SManga.ON_HIATUS
    "cancelled", "canceled", "discontinued" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
