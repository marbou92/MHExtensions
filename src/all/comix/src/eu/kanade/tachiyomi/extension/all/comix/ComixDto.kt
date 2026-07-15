package eu.kanade.tachiyomi.extension.all.comix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ComixMangaListDto(
    val items: List<ComixMangaListItemDto> = emptyList(),
    val meta: ComixMetaDto? = null,
)

@Serializable
data class ComixMetaDto(
    val total: Long? = null,
    val perPage: Int? = null,
    val page: Int? = null,
    val lastPage: Int? = null,
    val hasNext: Boolean? = null,
    val hasPrev: Boolean? = null,
)

@Serializable
data class ComixMangaListItemDto(
    val id: Long,
    val hid: String,
    val title: String,
    val type: String? = null,
    val status: String? = null,
    val poster: ComixPosterDto? = null,
    val latestChapter: Float? = null,
    val contentRating: String? = null,
    val year: Int? = null,
    val followsTotal: Long? = null,
    val ratedAvg: Double? = null,
    val chapterUpdatedAtFormatted: String? = null,
    val url: String? = null,
)

@Serializable
data class ComixPosterDto(
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
data class ComixMangaDetailDto(
    val id: Long,
    val hid: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val type: String? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val poster: ComixPosterDto? = null,
    val latestChapter: Float? = null,
    val contentRating: String? = null,
    val year: Int? = null,
    val synopsis: String? = null,
    val followsTotal: Long? = null,
    val ratedAvg: Double? = null,
    val ratedCount: Long? = null,
    val url: String? = null,
    val genres: List<ComixTagDto> = emptyList(),
    val demographics: List<ComixTagDto> = emptyList(),
    val formats: List<ComixTagDto> = emptyList(),
    val tags: List<ComixTagDto> = emptyList(),
    val authors: List<ComixTagDto> = emptyList(),
    val artists: List<ComixTagDto> = emptyList(),
    val firstChapterUrl: String? = null,
    val latestChapterUrl: String? = null,
)

@Serializable
data class ComixTagDto(
    val id: Long,
    val title: String,
    val slug: String,
)

@Serializable
data class ComixChapterListDto(
    val items: List<ComixChapterDto> = emptyList(),
    val meta: ComixMetaDto? = null,
)

@Serializable
data class ComixChapterDto(
    val id: Long,
    val mangaId: Long? = null,
    val number: Float? = null,
    val volume: Int? = null,
    val name: String? = null,
    val language: String? = null,
    val isOfficial: Boolean? = null,
    val votes: Int? = null,
    val groupId: Long? = null,
    val group: ComixGroupDto? = null,
    val createdAtFormatted: String? = null,
    val url: String? = null,
)

@Serializable
data class ComixGroupDto(
    val id: Long,
    val name: String,
)

@Serializable
data class ComixChapterPagesDto(
    val id: Long,
    val mangaId: Long? = null,
    val number: Float? = null,
    val name: String? = null,
    val language: String? = null,
    val url: String? = null,
    val pages: ComixPagesContainerDto? = null,
    val prev: JsonElement? = null,
    val next: JsonElement? = null,
)

@Serializable
data class ComixPagesContainerDto(
    val baseUrl: String? = null,
    val items: List<ComixPageDto> = emptyList(),
)

@Serializable
data class ComixPageDto(
    val width: Int? = null,
    val height: Int? = null,
    val s: Int? = null,
    val url: String,
)

@Serializable
data class ComixEncryptedDto(
    val e: String,
)
