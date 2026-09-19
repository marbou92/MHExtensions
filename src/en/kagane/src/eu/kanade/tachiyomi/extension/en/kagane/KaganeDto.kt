package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

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
    // Site rating (0-5 scale). `average_rating` is the raw user average,
    // `bayesian_rating` the weighted one; `total_ratings` the vote count.
    @SerialName("average_rating")
    val averageRating: Double? = null,
    @SerialName("bayesian_rating")
    val bayesianRating: Double? = null,
    @SerialName("total_ratings")
    val totalRatings: Int? = null,
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
        scorePosition: String = "top",
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

        // ---- Rating ----
        // The API returns the rating on a percentile scale (e.g. 70.1 == 70%),
        // while older fixtures used 0-5. Normalise: anything above 5 is
        // treated as a 0-100 value and converted to the site's 0-5 display
        // scale (70.1 -> 3.5/5).
        fun normalizedRating(raw: Double?): Double? = raw
            ?.takeIf { it > 0.0 }
            ?.let { if (it > 5.0) it / 20.0 else it }

        val rating = normalizedRating(averageRating)
            ?: normalizedRating(bayesianRating)
        val stars = rating?.let { value ->
            val text = String.format(Locale.ENGLISH, "%.1f/5", value)
            val votes = totalRatings?.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
            val full = value.roundToInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " $text$votes"
        }

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
            // The rating is rendered exactly once, in the configured position.
            if (scorePosition == "top" && stars != null) {
                append(stars)
                append("\n\n")
            }

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

            if (scorePosition == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
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

/**
 * Leading chapter-number restatements: "Chapter 5", "Ch. 5", "Episode 5",
 * "#5", "5", "Chapter 5.1", ... optionally followed by a separator
 * (":", "-", "·", ".", "|"). Stripping them keeps real titles while making
 * restatement-only titles fall back to the plain "Chapter N" form.
 */
private val CHAPTER_PREFIX = Regex(
    "^\\s*(?:ch(?:apter|apiter)?|episode|ep)\\s*(?:no\\.?|#)?\\s*\\d+(?:\\.\\d+)?\\s*(?:[:,.\u00b7\\-|]\\s*)?" +
        "|^\\s*#\\s*\\d+(?:\\.\\d+)?\\s*(?:[:,.\u00b7\\-|]\\s*)?" +
        // A bare number only counts as a restatement when it is the WHOLE
        // title ("5" -> restatement); "5 Centimeters" keeps its title.
        "|^\\s*\\d+(?:\\.\\d+)?\\s*[:,.\u00b7\\-|]?\\s*$",
    RegexOption.IGNORE_CASE,
)

fun DetailsDto.Book.toSChapter(
    seriesId: String,
): SChapter = SChapter.create().apply {
    url = "/series/$seriesId/reader/$id"
    date_upload = KAGANE_DATE_FORMAT.tryParse(createdAt)
    chapter_number = chapterNo?.toFloatOrNull() ?: sortNo ?: -1f
    scanlator = groups.joinToString(", ") { it.title }.ifBlank { null }

    val chNo = chapterNo
        ?.takeIf { it.isNotBlank() }
        // "5.0" -> "5"
        ?.let { if (it.endsWith(".0")) it.removeSuffix(".0") else it }
    val numText = chNo ?: sortNo?.let { n ->
        if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
    }

    val bookTitle = this@toSChapter.title.trim().takeIf { it.isNotEmpty() && it != "null" }

    // Chapter names follow the Atsumaru convention: show the site-given
    // title as-is. Titles that merely restate the chapter number
    // ("Chapter 5", "#5", "5") fall back to the plain "Chapter N" form
    // instead of being duplicated ("Ch. 5 · Chapter 5").
    name = when {
        bookTitle == null -> "Chapter ${numText ?: id}"
        else -> {
            val stripped = CHAPTER_PREFIX.replace(bookTitle, "").trim()
            when {
                stripped.isEmpty() -> "Chapter ${numText ?: id}"
                else -> stripped
            }
        }
    }
}

internal val KAGANE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
