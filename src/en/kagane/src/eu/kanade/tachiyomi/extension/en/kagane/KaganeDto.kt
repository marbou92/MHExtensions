package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class SearchDto(
    val content: List<Book> = emptyList(),
    val last: Boolean = true,
) {
    fun hasNextPage() = !last

    @Serializable
    class Book(
        @SerialName("series_id")
        val id: String,
        val title: String,
        @SerialName("start_year")
        val startYear: Int? = null,
        @SerialName("cover_image_id")
        val coverImage: String? = null,
        @SerialName("alternate_titles")
        val alternateTitles: List<String> = emptyList(),
        @SerialName("current_books")
        val booksCount: Int? = null,
    )
}

@Serializable
class DetailsDto(
    val title: String,
    val description: String? = null,
    @SerialName("upload_status")
    val publicationStatus: String? = null,
    val format: String? = null,
    @SerialName("start_year")
    val startYear: Int? = null,
    @SerialName("content_rating")
    val contentRating: String? = null,
    @SerialName("series_staff")
    val seriesStaff: List<Staff> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val tags: List<Tag> = emptyList(),
    @SerialName("series_alternate_titles")
    val seriesAlternateTitles: List<AlternateTitle> = emptyList(),
    @SerialName("series_books")
    val seriesBooks: List<Book> = emptyList(),
    @SerialName("series_covers")
    val covers: List<SeriesCover> = emptyList(),
) {
    @Serializable
    class Staff(
        val name: String,
        val role: String,
    )

    @Serializable
    class Genre(
        @SerialName("genre_name")
        val genreName: String,
    )

    @Serializable
    class Tag(
        @SerialName("tag_name")
        val tagName: String,
    )

    @Serializable
    class AlternateTitle(
        val title: String,
        val label: String? = null,
    )

    @Serializable
    class SeriesCover(
        @SerialName("image_id")
        val imageId: String,
    )

    @Serializable
    class Book(
        @SerialName("book_id")
        val id: String,
        val title: String = "",
        @SerialName("chapter_no")
        val chapterNo: String? = null,
        @SerialName("volume_no")
        val volumeNo: String? = null,
        @SerialName("sort_no")
        val sortNo: Float? = null,
        @SerialName("page_count")
        val pageCount: Int? = null,
        @SerialName("created_at")
        val createdAt: String? = null,
        val groups: List<Group> = emptyList(),
        val views: Int? = null,
    )

    @Serializable
    class Group(
        val title: String,
    )

    /**
     * Comix-style details: bold info line + alternative names, with optional
     * tags merged into the genre chips.
     */
    fun toSManga(
        apiUrl: String,
        seriesId: String,
        showAltNames: Boolean,
        showExtraInfo: Boolean,
        showTagsInGenre: Boolean,
        blockedGenres: Set<String>,
    ): SManga = SManga.create().apply {
        url = seriesId
        title = this@DetailsDto.title.trim()

        author = seriesStaff.filter {
            it.role.contains("Author", ignoreCase = true) ||
                it.role.contains("Story", ignoreCase = true)
        }.map { it.name }.distinct().joinToString(", ").ifBlank { null }

        artist = seriesStaff.filter {
            it.role.contains("Artist", ignoreCase = true) ||
                it.role.contains("Art", ignoreCase = true)
        }.map { it.name }.distinct().joinToString(", ").ifBlank { null }

        status = when (publicationStatus?.uppercase()) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "ABANDONED" -> SManga.CANCELLED
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        thumbnail_url = covers.firstOrNull()?.imageId?.let { "$apiUrl/image/$it" }

        val genreChips = buildList {
            format?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(genres.map { it.genreName })
            if (showTagsInGenre) addAll(tags.map { it.tagName })
        }.distinct()
            .filterNot { it.lowercase() in blockedGenres }
            .joinToString(", ")

        genre = genreChips.ifBlank { null }

        // ---- Comix-style description ----
        val infoLine = if (showExtraInfo) {
            buildString {
                if (startYear != null) append("**Year:** $startYear")
                if (!format.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Format:** $format")
                }
                if (!publicationStatus.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Status:** ${publicationStatus.lowercase().replaceFirstChar { it.uppercase() }}")
                }
                if (!contentRating.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("**Content Rating:** $contentRating")
                }
            }.ifBlank { null }
        } else {
            null
        }

        description = buildString {
            if (infoLine != null) {
                append(infoLine)
                append("\n\n")
            }

            this@DetailsDto.description?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }

            if (showAltNames && seriesAlternateTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(seriesAlternateTitles.joinToString("\n") { "• ${it.title}" })
            }
        }.trim().ifBlank { null }
    }
}

@Serializable
class IntegrityDto(
    val token: String,
    val exp: Long = 0,
)

@Serializable
class ChallengeDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("cache_url")
    val cacheUrl: String,
    val manifest: ManifestDto? = null,
)

@Serializable
class ManifestDto(
    val pages: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    @SerialName("page_no")
    val pageNumber: Int,
    @SerialName("page_id")
    val pageId: String,
    val ext: String? = null,
)

fun DetailsDto.Book.toSChapter(
    seriesId: String,
): SChapter = SChapter.create().apply {
    url = "/series/$seriesId/reader/$id"
    date_upload = KAGANE_DATE_FORMAT.tryParse(createdAt)
    chapter_number = chapterNo?.toFloatOrNull() ?: sortNo ?: -1f
    scanlator = groups.joinToString(", ") { it.title }.ifBlank { null }
    name = buildString {
        val chNo = chapterNo
        val bookTitle = this@toSChapter.title.trim()
        if (!chNo.isNullOrBlank()) {
            append("Ch. ")
            append(chNo.removeSuffix(".0"))
        } else if (sortNo != null) {
            append("Ch. ")
            append(sortNo.toString().removeSuffix(".0"))
        }
        if (bookTitle.isNotEmpty() && bookTitle != "null") {
            if (isNotEmpty()) append(" · ")
            append(bookTitle)
        }
        if (isEmpty()) append("Chapter ${chNo ?: sortNo ?: id}")
    }
}

internal val KAGANE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
