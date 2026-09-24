package eu.kanade.tachiyomi.extension.en.mangaball

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Defensive DTOs for the mangaball.net API. Field shapes were mapped from the
 * live API (Yui007/mangaball-downloader) and the Aidoku source's serde struct
 * names, but the exact types of some fields (ids, covers, groups) could not be
 * verified from this environment — so anything non-critical is a JsonElement
 * parsed with tolerant helpers instead of a hard type.
 */

@Serializable
data class MbSearchResponse(
    val data: List<MbTitleDto> = emptyList(),
    val pagination: MbPaginationDto? = null,
    @SerialName("current_page") val currentPage: JsonElement? = null,
    @SerialName("last_page") val lastPage: JsonElement? = null,
) {
    fun hasNextPage(): Boolean {
        val cur = pagination?.currentPage?.toIntOrNull()
            ?: currentPage.toIntOrNull()
            ?: return data.isNotEmpty()
        val last = pagination?.lastPage?.toIntOrNull()
            ?: lastPage.toIntOrNull()
            ?: return data.isNotEmpty()
        return cur < last
    }
}

@Serializable
data class MbPaginationDto(
    @SerialName("current_page") val currentPage: JsonElement? = null,
    @SerialName("last_page") val lastPage: JsonElement? = null,
    val total: JsonElement? = null,
)

@Serializable
data class MbTitleDto(
    @SerialName("_id") val id: JsonElement? = null,
    val name: String? = null,
    val url: String? = null,
    val slug: String? = null,
    val thumbnail: JsonElement? = null,
    val cover: JsonElement? = null,
    val background: JsonElement? = null,
    val image: JsonElement? = null,
    val type: String? = null,
    val format: String? = null,
    val status: String? = null,
    val isAdult: Boolean? = null,
    val languageFlag: String? = null,
    @SerialName("last_chapter") val lastChapter: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val description: JsonElement? = null,
) {
    /** Best display cover: thumbnail → cover → background → image. */
    fun coverUrl(): String? = thumbnail.asStringOrNull()
        ?: cover.asStringOrNull()
        ?: background.asStringOrNull()
        ?: image.asStringOrNull()

    /** Path slug like "nano-machine-12345" (title id is the trailing number). */
    fun titleSlug(): String {
        val raw = url.orEmpty().trim().trim('/')
        val lastSegment = raw.substringAfterLast('/').takeIf { it.isNotBlank() }
        return slug?.takeIf { it.isNotBlank() }
            ?: lastSegment
            ?: id.asStringOrNull()?.let { "title-$it" }
            ?: ""
    }
}

@Serializable
data class MbChapterListingResponse(
    @SerialName("ALL_CHAPTERS") val allChapters: List<MbChapterGroupDto> = emptyList(),
    val data: JsonElement? = null,
    val pagination: MbPaginationDto? = null,
)

@Serializable
data class MbChapterGroupDto(
    val number: JsonElement? = null,
    @SerialName("number_float") val numberFloat: JsonElement? = null,
    val title: String? = null,
    val translations: List<MbChapterTranslationDto> = emptyList(),
) {
    fun chapterNumber(): Double = numberFloat.asDoubleOrNull()
        ?: number.asDoubleOrNull()
        ?: -1.0
}

@Serializable
data class MbChapterTranslationDto(
    val id: JsonElement? = null,
    val language: String? = null,
    val group: JsonElement? = null,
    val date: String? = null,
    val pages: JsonElement? = null,
    val views: JsonElement? = null,
    val likes: JsonElement? = null,
    val volume: JsonElement? = null,
    val url: String? = null,
) {
    fun idOrNull(): String? = id.asStringOrNull() ?: url?.trim('/')?.substringAfterLast('/')

    fun groupNameOrNull(): String? = group.asStringOrNull()

    fun pageCountOrNull(): Int? = pages.toIntOrNull()

    fun viewsOrNull(): Long = views.asLongOrNull() ?: 0L

    fun likesOrNull(): Long = likes.asLongOrNull() ?: 0L

    fun volumeOrNull(): Double? = volume.asDoubleOrNull()?.takeIf { it > 0.0 }
}

// ----------------------------------------------------------------------------
// Tolerant JsonElement helpers (the API may return ids/covers/groups as plain
// strings, numbers, objects or null depending on endpoint and row state).
// ----------------------------------------------------------------------------

internal fun JsonElement?.asStringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.takeUnless { it.isEmpty() || it == "null" }
    is JsonObject -> (this["url"] ?: this["src"] ?: this["name"]).asStringOrNull()
    else -> null
}

internal fun JsonElement?.asDoubleOrNull(): Double? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.toDoubleOrNull() ?: content.trim().removeSuffix(".0").toDoubleOrNull()
    is JsonObject -> this["number_float"].asDoubleOrNull() ?: this["number"].asDoubleOrNull()
    else -> null
}

internal fun JsonElement?.toIntOrNull(): Int? = asDoubleOrNull()?.toInt()

internal fun JsonElement?.asLongOrNull(): Long? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> {
        val raw = content.trim()
        raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
            ?: parseKFormat(raw)
    }
    else -> null
}

/** "1.2k" → 1200, "3.4m" → 3_400_000 (defensive, some fields may be display-formatted). */
private fun parseKFormat(raw: String): Long? {
    val m = Regex("""^([\d.]+)\s*([kKmM])\??$""").find(raw) ?: return null
    val num = m.groupValues[1].toDoubleOrNull() ?: return null
    return when (m.groupValues[2].lowercase()) {
        "k" -> (num * 1_000).toLong()
        "m" -> (num * 1_000_000).toLong()
        else -> null
    }
}
