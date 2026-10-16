package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@Serializable
class GenreDto(
    val id: String,
    @SerialName("genre_name")
    val genreName: String,
)

@Serializable
class TagDto(
    val id: String,
    @SerialName("tag_name")
    val tagName: String,
)

@Serializable
class SourcesDto(
    val sources: List<SourceDto>,
)

@Serializable
data class SourceDto(
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_type") val sourceType: String, // "Official", "Unofficial", "Mixed"
    val title: String,
)

@Serializable
class SearchDto(
    val content: List<Book> = emptyList(),
    val last: Boolean = true,
    @SerialName("total_elements")
    val totalElements: Int = 0,
    @SerialName("total_pages")
    val totalPages: Int = 0,
) {
    fun hasNextPage() = !last

    @Serializable
    class Book(
        @SerialName("series_id")
        val id: String,
        val title: String,
        @SerialName("source_id")
        val sourceId: String? = null,
        @SerialName("current_books")
        val booksCount: Int,
        @SerialName("start_year")
        val startYear: Int? = null,
        @SerialName("cover_image_id")
        val coverImage: String? = null,
        @SerialName("alternate_titles")
        val alternateTitles: List<String> = emptyList(),
    ) {

        fun toSManga(apiUrl: String, showSource: Boolean, sources: Map<String, String>, cleanTitle: Boolean): SManga = SManga.create().apply {
            title = if (showSource && !sources.isEmpty()) "${this@Book.title.trim()} [${sources[this@Book.sourceId]}]" else this@Book.title.clean(cleanTitle)
            url = id
            thumbnail_url = coverImage?.let { "$apiUrl/image/$it" }
        }
    }
}

@Serializable
class TrackerDto(
    @SerialName("book_series")
    val bookSeries: List<Book> = emptyList(),
) {
    @Serializable
    class Book(
        val id: String,
        val title: String,
        @SerialName("source_id")
        val sourceId: String? = null,
        @SerialName("current_books")
        val booksCount: Int,
        @SerialName("cover_image_id")
        val coverImage: String? = null,
    ) {

        fun toSManga(apiUrl: String, showSource: Boolean, sources: Map<String, String>, cleanTitle: Boolean): SManga = SManga.create().apply {
            title = if (showSource && !sources.isEmpty()) "${this@Book.title.trim()} [${sources[this@Book.sourceId]}]" else this@Book.title.clean(cleanTitle)
            url = id
            thumbnail_url = coverImage?.let { "$apiUrl/image/$it" }
        }
    }
}

@Serializable
class AlternateSeries(
    @SerialName("current_books")
    val booksCount: Int,
    @SerialName("start_year")
    val startYear: Int? = null,
)

@Serializable
class DetailsDto(
    val title: String,
    val description: String?,
    @SerialName("upload_status")
    val publicationStatus: String,
    val format: String?,
    @SerialName("source_id")
    val sourceId: String?,
    @SerialName("series_staff")
    val seriesStaff: List<SeriesStaff> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val tags: List<Tag> = emptyList(),
    @SerialName("series_alternate_titles")
    val seriesAlternateTitles: List<AlternateTitle> = emptyList(),
    @SerialName("series_books")
    val seriesBooks: List<ChapterDto.Book> = emptyList(),
    @SerialName("edition_info")
    val editionInfo: String? = null,
    @SerialName("tracker_id")
    val trackerId: String? = null,
    @SerialName("series_covers")
    val covers: List<SeriesCover> = emptyList(),
    // Optional rating fields — parsed when the API exposes them, silently
    // null otherwise (kotlinx skips unknown keys both ways).
    val rating: Double? = null,
    @SerialName("average_rating")
    val averageRating: Double? = null,
) {
    @Serializable
    class SeriesStaff(
        val name: String,
        val role: String,
    )

    @Serializable
    class Genre(
        @SerialName("genre_id")
        val genreId: String? = null,
        @SerialName("genre_name")
        val genreName: String,
    )

    @Serializable
    class Tag(
        @SerialName("tag_id")
        val tagId: String? = null,
        @SerialName("tag_name")
        val tagName: String,
    )

    @Serializable
    class AlternateTitle(
        val title: String,
        val label: String?,
    )

    @Serializable
    class SeriesCover(
        @SerialName("image_id")
        val imageId: String,
    )

    /**
     * Star rating line (like our other sources), e.g. "★★★★☆ 4.3/5".
     *
     * The API's `rating`/`average_rating` values are 0–100 PERCENTILES
     * (observed live: 70.1, 85.6 — the old code treated anything >5 as a
     * 0–10 scale and users saw "42.8/10" for 85.6). Small values are only
     * plausible as true 0–5 scores, so: ≤5 → as-is, otherwise divide by 20.
     */
    fun ratingStars(): String? {
        val rating = rating ?: averageRating ?: return null
        if (rating <= 0.0) return null
        val outOfFive = rating <= 5.0
        val normalized = if (outOfFive) rating else rating / 20.0
        val full = normalized.roundToInt().coerceIn(0, 5)
        val text = String.format(Locale.ENGLISH, "%.1f", normalized).removeSuffix(".0")
        return "★".repeat(full) + "☆".repeat(5 - full) + " $text/5"
    }

    fun toSManga(apiUrl: String, sourceName: String? = null, baseUrl: String = "", showEdition: Boolean = false, showSource: Boolean = false, cleanTitle: Boolean): SManga = SManga.create().apply {
        val base = this@DetailsDto.title.trim()
        val withEdition = if (showEdition && !this@DetailsDto.editionInfo.isNullOrBlank()) "$base (${this@DetailsDto.editionInfo})" else base
        title = if (showSource && sourceName != null) "$withEdition [$sourceName]" else withEdition.clean(cleanTitle)
        thumbnail_url = covers.firstOrNull()?.imageId?.let { "$apiUrl/image/$it" }
        val desc = StringBuilder()

        // Rating stars (rendered once, at the top — like MKissa/ManhuaRMTL)
        ratingStars()?.let {
            desc.append(it).append("\n\n")
        }

        // Extra info block: publisher platform, publication status, book count
        val infoLine = buildString {
            this@DetailsDto.format?.takeIf { it.isNotBlank() }?.let { append("**Publisher:** $it") }
            if (this@DetailsDto.publicationStatus.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("**Status:** ${this@DetailsDto.publicationStatus.trim().replaceFirstChar { c -> c.uppercase() }}")
            }
            if (this@DetailsDto.seriesBooks.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("**Books:** ${this@DetailsDto.seriesBooks.size}")
            }
        }.ifBlank { null }
        if (infoLine != null) {
            desc.append(infoLine).append("\n\n")
        }

        // Tag list (tags are not part of the genre chips)
        if (tags.isNotEmpty()) {
            desc.append("**Tags:** ").append(tags.joinToString(", ") { it.tagName }).append("\n\n")
        }

        // Main description
        this@DetailsDto.description?.takeIf { it.isNotBlank() }?.let {
            desc.append(Jsoup.parse(it.trim().replace("\n", "<br>")).wholeText())
            desc.append("\n\n")
        }

        // Source name
        if (sourceName != null && this@DetailsDto.sourceId != null) {
            desc.append("Source: [$sourceName]($baseUrl/sources/${this@DetailsDto.sourceId})\n\n")
        }

        // Alternate titles at the end
        if (seriesAlternateTitles.isNotEmpty()) {
            desc.append("Associated Name(s):\n")
            seriesAlternateTitles.forEach {
                desc.append("• ${it.title}\n")
            }
        }

        // Extract authors and artists from staff (roles like "Author", "Artist", "Story", "Art")
        val authors = seriesStaff.filter {
            it.role.contains("Author", ignoreCase = true) || it.role.contains("Story", ignoreCase = true)
        }.map { it.name }.distinct()
        val artists = seriesStaff.filter {
            it.role.contains("Artist", ignoreCase = true) || it.role.contains("Art", ignoreCase = true)
        }
            .map { it.name }
            .distinct()
            .joinToString(", ")

        artist = artists
        author = authors.joinToString()
        description = desc.toString().trim()
        genre = buildList {
            this@DetailsDto.format?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(genres.map { it.genreName })
        }.joinToString()
        status = this@DetailsDto.publicationStatus.toStatus()
    }

    private fun String.toStatus(): Int = when (this.uppercase()) {
        "ONGOING" -> SManga.ONGOING
        "COMPLETED" -> SManga.COMPLETED
        "HIATUS" -> SManga.ON_HIATUS
        "ABANDONED" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

@Serializable
class ChapterDto(
    @SerialName("series_books")
    val seriesBooks: List<Book>,
) {
    @Serializable
    class Book(
        @SerialName("book_id")
        val id: String,
        @SerialName("series_id")
        val seriesId: String? = null,
        val title: String,
        @SerialName("created_at")
        val createdAt: String?,
        @SerialName("page_count")
        val pagesCount: Int,
        @SerialName("sort_no")
        val number: Float,
        @SerialName("chapter_no")
        val chapterNo: String?,
        @SerialName("volume_no")
        val volumeNo: String?,
        val groups: List<Group> = emptyList(),
    ) {
        fun toSChapter(actualSeriesId: String, useSourceChapterNumber: Boolean = false, chapterTitleMode: String = "optional"): SChapter = SChapter.create().apply {
            url = "/series/$actualSeriesId/reader/$id"
            name = buildChapterName(chapterTitleMode)
            date_upload = dateFormat.tryParse(createdAt)
            if (useSourceChapterNumber) {
                chapter_number = number
            }
            scanlator = buildString {
                append(groups.joinToString(", ") { it.title })

                // Extract group tags in chapter title
                CHAPTER_GROUP_REGEX.matchEntire(METADATA_REGEX.replace(title.trim(), ""))?.let { match ->
                    val groupTag = match.groups[1]?.value ?: match.groups[2]?.value
                    groupTag?.let { append(" ($it)") }
                }
            }
        }

        private fun buildChapterName(mode: String = "optional"): String {
            val trimmedTitle = title.trim()
            return when (mode) {
                "optional" -> {
                    when {
                        trimmedTitle.isEmpty() && chapterNo.isNullOrBlank() && !volumeNo.isNullOrBlank() -> buildChapterName("vol_chapter")
                        trimmedTitle.isEmpty() && !chapterNo.isNullOrBlank() -> "Ch.$chapterNo"
                        else -> trimmedTitle
                    }
                }

                "always" -> {
                    when {
                        chapterNo.isNullOrBlank() && !volumeNo.isNullOrBlank() -> buildChapterName("vol_chapter")
                        trimmedTitle.isEmpty() -> "Ch.$chapterNo"
                        else -> "Ch.$chapterNo $trimmedTitle"
                    }
                }

                "vol_chapter", "vol_local" -> {
                    val volPart = if (!volumeNo.isNullOrBlank()) "Vol.$volumeNo " else ""
                    val chPart = if (!chapterNo.isNullOrBlank()) "Ch.$chapterNo" else ""
                    val numPart = "$volPart$chPart".trim()
                    when {
                        numPart.isEmpty() -> trimmedTitle
                        trimmedTitle.isEmpty() || mode == "vol_local" -> numPart
                        else -> "$numPart $trimmedTitle"
                    }
                }

                else -> trimmedTitle
            }
        }
    }

    @Serializable
    class Group(
        val title: String,
    )
    companion object {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
    }
}

/**
 * The books INDEX endpoint (`GET /series/{id}/books/index?fields=List`) —
 * the v72 replacement for the chapter list. The details response stopped
 * embedding `series_books`, and the plain `/books` endpoint caps at 100
 * rows and ignores its cursor, while the index returns EVERY book in one
 * request (live-checked 2026-10: a 202-chapter series and a 120-book one
 * both come back complete, on every mirror).
 *
 * The payload is columnar: [fields] names the columns, every entry of
 * [books] is a positional row:
 *   fields = [book_id, sort_no, chapter_no, volume_no, group_ix,
 *             released_at, title, page_count, published_on, internal_release]
 * with [groups] resolving the group_ix indices to scanlator names.
 */
@Serializable
class BooksIndexDto(
    val count: Int = 0,
    val fields: List<String> = emptyList(),
    val groups: List<ChapterDto.Group> = emptyList(),
    val books: List<List<JsonElement>> = emptyList(),
) {
    fun toSChapterList(
        seriesId: String,
        useSourceChapterNumber: Boolean = false,
        chapterTitleMode: String = "optional",
    ): List<SChapter> {
        val columnOf = fields.withIndex().associate { (index, name) -> name to index }

        fun cell(row: List<JsonElement>, name: String): JsonElement? = columnOf[name]?.let { row.getOrNull(it) }

        fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it !== JsonNull }?.content

        return books.mapNotNull { row ->
            val bookId = cell(row, "book_id").asText() ?: return@mapNotNull null
            val groupTitles = (cell(row, "group_ix") as? JsonArray)
                ?.mapNotNull { entry -> entry.asText()?.toIntOrNull() }
                ?.mapNotNull { groupIx -> groups.getOrNull(groupIx)?.title }
                .orEmpty()

            // The index carries an epoch `released_at` instead of the old
            // ISO `created_at`, so build through the shared Book mapping and
            // stamp the date afterwards.
            ChapterDto.Book(
                id = bookId,
                title = cell(row, "title").asText().orEmpty(),
                createdAt = null,
                pagesCount = cell(row, "page_count").asText()?.toIntOrNull() ?: 0,
                number = cell(row, "sort_no").asText()?.toFloatOrNull() ?: 0f,
                chapterNo = cell(row, "chapter_no").asText(),
                volumeNo = cell(row, "volume_no").asText(),
                groups = groupTitles.map { ChapterDto.Group(it) },
            ).toSChapter(seriesId, useSourceChapterNumber, chapterTitleMode).apply {
                date_upload = (cell(row, "released_at").asText()?.toLongOrNull() ?: 0L) * 1000
            }
        }.reversed()
    }
}

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
    val pageUuid: String,
    val ext: String? = null,
)

@Serializable
class IntegrityDto(
    val token: String,
    val exp: Long,
)

private val BRACKET_REGEX = Regex("""(\([^()]*\)|\[[^\[\]]*\])\s*$""")

private val METADATA_REGEX = Regex("""(?:\s*\{[^{}]*\})+\s*$""")

private val CHAPTER_GROUP_REGEX = Regex(
    """^Chapter\s+.*(?:-\s*Volume\s+.*\(([^()]+)\)|\[([^\[\]]+)\])\s*$""",
    RegexOption.IGNORE_CASE,
)

private fun String.clean(removeExtras: Boolean): String = if (removeExtras) this.replace(BRACKET_REGEX, "").trim() else this.trim()
