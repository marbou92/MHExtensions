package eu.kanade.tachiyomi.extension.all.swarm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Listing card used by `/api/analytics/popular`, `/api/manga/filtered` and `/api/comic/search`.
 *
 * The site is inconsistent about field casing: the popular/analytics and browse endpoints
 * return camelCase (`altTitles`, `availableLangs`, `lastUpdated`), while the search endpoint
 * and the backend API return snake_case (`alt_titles`, `available_langs`, `last_updated`).
 * Both variants are declared so one DTO covers every listing shape; browse cards omit most
 * optional fields entirely.
 */
@Serializable
data class SwarmCardDto(
    val id: String? = null,
    val title: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val covers: List<String>? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val type: String? = null,
    // camelCase variants (popular / browse)
    val altTitles: List<String>? = null,
    val availableLangs: List<String>? = null,
    val chapterCount: JsonElement? = null,
    val lastUpdated: String? = null,
    val year: JsonElement? = null,
    val nsfw: Boolean? = null,
    // snake_case variants (search / backend API)
    val alt_titles: List<String>? = null,
    val available_langs: List<String>? = null,
    val chapter_count: JsonElement? = null,
    val last_updated: String? = null,
) {
    val altTitlesResolved: List<String> get() = altTitles ?: alt_titles.orEmpty()
    val languagesResolved: List<String> get() = availableLangs ?: available_langs.orEmpty()
    val chapterCountResolved: Int? get() = chapterCount.asIntOrNull() ?: chapter_count.asIntOrNull()
}

/**
 * Full manga object. Identical shape on the SSR/RSC page (`/comic/<slug>` → `initialData.manga`)
 * and on the backend API (`https://api.swarm.ws/api/manga/<id>` → `data`).
 */
@Serializable
data class SwarmMangaFullDto(
    val id: String? = null,
    val title: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val covers: List<String>? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    // author/artist may arrive as a string or a list depending on the source record
    val author: JsonElement? = null,
    val authors: JsonElement? = null,
    val artist: JsonElement? = null,
    val artists: JsonElement? = null,
    val year: JsonElement? = null,
    val status: String? = null,
    val type: String? = null,
    val alt_titles: List<String>? = null,
    val available_langs: List<String>? = null,
    val nsfw: Boolean? = null,
    val last_updated: String? = null,
    val chapter_count: JsonElement? = null,
    val latest_chapter_number: JsonElement? = null,
)

/**
 * Chapter row from the SSR `initialData.chapters` array and from
 * `https://api.swarm.ws/api/chapter?manga=<id>&limit=9999` (`data`).
 *
 * `number` is a JSON string in every captured response ("86", "86.5") but is declared as a
 * raw [JsonElement] so a numeric literal would decode too. `rank`/`priority` are lowercase
 * letters where "a" is the best known quality tier.
 */
@Serializable
data class SwarmChapterDto(
    val id: String? = null,
    val lang: String? = null,
    val number: JsonElement? = null,
    val title: String? = null,
    val published_date: String? = null,
    val scan_group: String? = null,
    val source: String? = null,
    val is_official: Boolean? = null,
    val rank: String? = null,
    val priority: String? = null,
)

// ---------------------------------------------------------------------------
// Envelopes
// ---------------------------------------------------------------------------

/** `{data:[...]}` — also tolerated as a root array by a fallback in the source. */
@Serializable
data class SwarmCardListDto(
    val data: List<SwarmCardDto> = emptyList(),
)

/** `https://api.swarm.ws/api/chapter` → `{result:"ok", data:[rows]}`. */
@Serializable
data class SwarmChapterListDto(
    val data: List<SwarmChapterDto> = emptyList(),
)

/** `https://api.swarm.ws/api/manga/<id>` → `{data:{...manga...}}`. */
@Serializable
data class SwarmMangaEnvelopeDto(
    val data: SwarmMangaFullDto? = null,
)

/** Request body for `POST /api/sp` — see [SwarmCrypto]. */
@Serializable
data class SwarmPagesRequestDto(
    val payload: String,
)

/** `POST /api/sp` → MangaDex@Home-style page response. */
@Serializable
data class SwarmPagesDto(
    val baseUrl: String? = null,
    val chapter: SwarmPagesContainerDto? = null,
)

@Serializable
data class SwarmPagesContainerDto(
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)

/**
 * The SSR series page (`/comic/<slug>`) embeds `initialData = {manga, chapters}` inside its
 * React Flight payload; extracted with the core `extractNextJs` RSC parser.
 */
@Serializable
data class SwarmRscSeriesDto(
    val manga: SwarmMangaFullDto? = null,
    val chapters: List<SwarmChapterDto> = emptyList(),
)

/**
 * The SSR reader page (`/comic/<slug>/chapter/<number>`) embeds
 * `{slug, chapterNumber, chapterId, baseUrl, paths:[...]}`; used as the page-list fallback
 * when `POST /api/sp` returns nothing usable.
 */
@Serializable
data class SwarmRscReaderDto(
    val chapterId: String? = null,
    val baseUrl: String? = null,
    val paths: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// JsonElement normalizers (the API mixes strings/numbers/lists in several fields)
// ---------------------------------------------------------------------------

internal fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> emptyList()
}

internal fun JsonElement?.asFloatOrNull(): Float? = (this as? JsonPrimitive)?.floatOrNull

internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

/** Joins a string-or-list author/artist field: `"A"` → `A`, `["A","B"]` → `A, B`. */
internal fun JsonElement?.joinedNames(): String? = asStringList()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(", ")
    .ifBlank { null }
