package eu.kanade.tachiyomi.extension.ar.mangaswat

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

@Serializable
class MangaListDto(
    val results: List<MangaDto>,
    private val next: String? = null,
) {
    fun hasNext(): Boolean = next != null
}

@Serializable
class MangaDto(
    private val id: Int? = null,
    private val serie_id: Int? = null,
    private val title: String,
    private val poster: PosterDto,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@MangaDto.title
        thumbnail_url = poster.medium
        url = (id ?: serie_id)!!.toString()
    }
}

/**
 * The series endpoint carries everything the Comix/MangaDot description
 * block needs: the 0..5 `rating` (with `ratings_count`), `views_count`,
 * `chapters_count`, `type`/`status` and the `alternative` names.
 */
@Serializable
internal class MangaDetailsDto(
    val slug: String? = null,
    val title: String,
    val poster: PosterDto,
    val genres: List<AttributesDto> = emptyList(),
    val story: String? = null,
    val alternative: String? = null,
    val author: AttributesDto? = null,
    val artist: AttributesDto? = null,
    val type: AttributesDto? = null,
    val status: AttributesDto? = null,
    val rating: String? = null,
    @SerialName("ratings_count") val ratingsCount: Int? = null,
    @SerialName("views_count") val viewsCount: Long? = null,
    @SerialName("chapters_count") val chaptersCount: Int? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@MangaDetailsDto.title
        thumbnail_url = poster.medium
        description = story
        genre = genres.joinToString { it.name }
        author = this@MangaDetailsDto.author?.name
        artist = this@MangaDetailsDto.artist?.name
        status = when (this@MangaDetailsDto.status?.name) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterListDto(
    val results: List<ChapterDto>,
    val next: String? = null,
)

@Serializable
class PageListDto(
    val images: List<PageDto>,
)

@Serializable
class PosterDto(
    val medium: String,
)

@Serializable
class AttributesDto(
    val name: String,
)

@Serializable
class ChapterDto(
    private val id: Int,
    private val slug: String,
    private val chapter: String,
    @SerialName("created_at") private val createdAt: String,
) {
    fun toSChapter(): SChapter = SChapter.create().apply {
        name = chapter
        url = "/chapters/$id/$slug/"
        date_upload = Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
        chapter_number = chapter.toFloatOrNull() ?: 0f

        // Not used
        memo = buildJsonObject {
            put("id", id)
            put("slug", slug)
        }
    }
}

@Serializable
class PageDto(
    val image: String,
)
