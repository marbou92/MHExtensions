package eu.kanade.tachiyomi.extension.en.atsumaru

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The API is inconsistent about numeric fields: search documents send
 * `"views": 10265475` (bare number) while browse/details send
 * `"views": "16M"` (string). Serialising as String crashed with
 * JsonDecodingException: "Expected quotation mark, but had '1'".
 * This serializer accepts both shapes (null on anything else).
 */
object StringOrNumberSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) throw SerializationException("Expected JSON input")
        return when (val element = decoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

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
        @Serializable(with = StringOrNumberSerializer::class)
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
        // Search documents send views as a bare number ("views": 10265475)
        // while other endpoints send strings ("16M") — accept both.
        @Serializable(with = StringOrNumberSerializer::class)
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
    @Serializable(with = StringOrNumberSerializer::class)
    val views: String? = null,
    val avgRating: Double? = null,
    val otherNames: List<String> = emptyList(),
    val authors: List<Author> = emptyList(),
    val genres: List<TagDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val poster: PosterDto? = null,
    val released: Long? = null,
    val scanlators: List<ScanlatorDto> = emptyList(),
    // NOTE: `totalChapterCount` is deliberately NOT declared — the API sends
    // it as an inconsistent numeric type (sometimes "112.7"), which crashed
    // strict Int parsing with JsonDecodingException. Unknown keys are skipped.
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
    // Id of the scanlation group that uploaded this chapter. Resolve it to a
    // display name via MangaPage.scanlators ("show the scanlator's name").
    val scanlationMangaId: String? = null,
)

/**
 * Chapter naming matches the site: the scanlator-given title is shown as-is
 * ("Chapter 195", "Episode 7", "Afterword 3", ...). Only when the site
 * provides no usable title do we fall back to a generic "Chapter N".
 */
fun ChapterDto.toSChapter(scanlatorName: String? = null): SChapter = SChapter.create().apply {
    // chapter.url is the chapter id; the manga id is joined in the source.
    url = id
    chapter_number = number?.toFloat() ?: -1f
    date_upload = createdAt ?: 0L
    scanlator = scanlatorName?.takeIf { it.isNotBlank() }

    var t = this@toSChapter.title.trim()
    if (t.isNotEmpty() && t.equals("null", true)) t = ""

    name = t.ifBlank {
        "Chapter ${number?.toString()?.removeSuffix(".0") ?: id}"
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
