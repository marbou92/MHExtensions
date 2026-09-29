package eu.kanade.tachiyomi.extension.all.mangaball

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOs for the new MangaBall stack (mangaball.com + api.mangaball.com/api/v1).
 * Every endpoint used here was captured live from the real site's own client
 * (the Next.js app on mangaball.com, 2026-09) and the exact field shapes below
 * come from real response bodies. Types that looked unstable across endpoints
 * (ids, covers, stats) stay JsonElement and are read with tolerant helpers.
 */

// ---------------------------------------------------------------------------
// Browse / search
// ---------------------------------------------------------------------------

@Serializable
data class MbSearchResponse(
    val data: List<MbTitleDto> = emptyList(),
    val pagination: MbPaginationDto? = null,
) {
    fun hasNextPage(): Boolean {
        val page = JsonElements.asLongOrNull(pagination?.page) ?: return data.isNotEmpty()
        val total = JsonElements.asLongOrNull(pagination?.totalPages) ?: return data.isNotEmpty()
        return page < total
    }
}

@Serializable
data class MbPaginationDto(
    val page: JsonElement? = null,
    @SerialName("total_pages") val totalPages: JsonElement? = null,
    val total: JsonElement? = null,
    val limit: JsonElement? = null,
)

@Serializable
data class MbTitleDto(
    val id: JsonElement? = null,
    @SerialName("_id") val idAlt: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
    val image: MbImageDto? = null,
    val status: String? = null,
    val is18plus: JsonElement? = null,
) {
    fun idOrNull(): String? = JsonElements.asStringOrNull(id) ?: JsonElements.asStringOrNull(idAlt)
    fun isAdult(): Boolean = JsonElements.asBoolOrNull(is18plus) ?: false
}

// ---------------------------------------------------------------------------
// Details
// ---------------------------------------------------------------------------

@Serializable
data class MbDetailResponse(
    val data: MbDetailDto? = null,
)

@Serializable
data class MbDetailDto(
    val id: JsonElement? = null,
    @SerialName("_id") val idAlt: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
    val description: List<String> = emptyList(),
    val alternateName: List<String> = emptyList(),
    val authors: List<MbPersonDto> = emptyList(),
    val author: List<MbPersonDto> = emptyList(),
    val tags: List<MbTagDto> = emptyList(),
    val image: MbImageDto? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val publicationDemographic: String? = null,
    val date_published: JsonElement? = null,
    val is18plus: JsonElement? = null,
    val availableTranslatedLanguages: List<String> = emptyList(),
    val links: MbLinksDto? = null,
    val stats: MbStatsDto? = null,
    val chapters_count: JsonElement? = null,
    val likes_count: JsonElement? = null,
    val views_count: JsonElement? = null,
) {
    fun idOrNull(): String? = JsonElements.asStringOrNull(id) ?: JsonElements.asStringOrNull(idAlt)
    fun isAdult(): Boolean = JsonElements.asBoolOrNull(is18plus) ?: false
}

@Serializable
data class MbPersonDto(
    val name: String? = null,
)

@Serializable
data class MbTagDto(
    val id: JsonElement? = null,
    @SerialName("_id") val idAlt: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
    val group: String? = null,
) {
    fun idOrNull(): String? = JsonElements.asStringOrNull(id) ?: JsonElements.asStringOrNull(idAlt)
}

@Serializable
data class MbLinksDto(
    val mangadex: String? = null,
    val mangaUpdate: String? = null,
    val myanimelist: String? = null,
    val animePlanet: String? = null,
    val kitsu: String? = null,
)

@Serializable
data class MbStatsDto(
    val views: JsonElement? = null,
    val followers: JsonElement? = null,
)

// ---------------------------------------------------------------------------
// Chapter listing (flat chapter rows, one per (chapter, group, lang))
// ---------------------------------------------------------------------------

@Serializable
data class MbChapterListingResponse(
    val data: List<MbChapterDto> = emptyList(),
    /** Row total across pages (the /title/chapter-listing wrapper carries it). */
    val total: JsonElement? = null,
)

@Serializable
data class MbChapterDto(
    val id: JsonElement? = null,
    @SerialName("_id") val idAlt: JsonElement? = null,
    val title_id: JsonElement? = null,
    val name: String? = null,
    val number: JsonElement? = null,
    val chapter_number: JsonElement? = null,
    val volume: JsonElement? = null,
    val lang: String? = null,
    val site: String? = null,
    val status: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val views: JsonElement? = null,
    val group: MbGroupDto? = null,
    val group_name: String? = null,
) {
    fun idOrNull(): String? = JsonElements.asStringOrNull(id) ?: JsonElements.asStringOrNull(idAlt)
    fun chapterNumber(): Double = JsonElements.asDoubleOrNull(chapter_number) ?: JsonElements.asDoubleOrNull(number) ?: 0.0
    fun volumeNumber(): Double? = JsonElements.asDoubleOrNull(volume)
    fun viewsCount(): Long = JsonElements.asLongOrNull(views) ?: 0L
    fun groupName(): String = group_name ?: group?.name.orEmpty()
}

@Serializable
data class MbGroupDto(
    val name: String? = null,
    val slug: String? = null,
)

// ---------------------------------------------------------------------------
// Chapter pages
// ---------------------------------------------------------------------------

@Serializable
data class MbChapterDetailResponse(
    val data: MbChapterDetailData? = null,
)

@Serializable
data class MbChapterDetailData(
    val chapter: MbChapterDetailChapter? = null,
)

@Serializable
data class MbChapterDetailChapter(
    val id: JsonElement? = null,
    val pages: List<String> = emptyList(),
    val title_id: JsonElement? = null,
    val chapter_number: JsonElement? = null,
    val lang: String? = null,
)

// ---------------------------------------------------------------------------
// Filter taxonomy (GET /tag/get-grouped → {"data": {group: [tag,...]}})
// ---------------------------------------------------------------------------

@Serializable
data class MbTagGroupsDto(
    val format: List<MbFilterTag> = emptyList(),
    val genre: List<MbFilterTag> = emptyList(),
    val theme: List<MbFilterTag> = emptyList(),
    val content: List<MbFilterTag> = emptyList(),
)

@Serializable
data class MbFilterTag(
    val id: JsonElement? = null,
    @SerialName("_id") val idAlt: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
) {
    fun idOrNull(): String? = JsonElements.asStringOrNull(id) ?: JsonElements.asStringOrNull(idAlt)
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Serializable
data class MbImageDto(
    val file: JsonElement? = null,
    val cover: MbCoverDto? = null,
    val cdn_mangadex: String? = null,
)

@Serializable
data class MbCoverDto(
    val name: String? = null,
    val path: String? = null,
)

/** Tolerant JsonElement readers shared by every DTO above. */
internal object JsonElements {
    fun asStringOrNull(element: JsonElement?): String? {
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content
        return primitive.content.takeIf { it.isNotBlank() && it != "null" }
    }

    fun asDoubleOrNull(element: JsonElement?): Double? = when (element) {
        null -> null
        is kotlinx.serialization.json.JsonPrimitive ->
            element.content.toDoubleOrNull() ?: element.content.removeSuffix(".0").toDoubleOrNull()
        else -> null
    }

    fun asLongOrNull(element: JsonElement?): Long? = asDoubleOrNull(element)?.toLong()

    fun asBoolOrNull(element: JsonElement?): Boolean? = when (element) {
        null -> null
        is kotlinx.serialization.json.JsonPrimitive -> when (element.content.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }
}
