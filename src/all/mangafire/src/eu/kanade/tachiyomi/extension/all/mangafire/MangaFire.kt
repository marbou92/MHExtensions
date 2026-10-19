package eu.kanade.tachiyomi.extension.all.mangafire

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MangaFire (mangafire.to).
 *
 * React SPA backed by a clean JSON API. Every protected endpoint requires a
 * `vrf` protection token computed by the site's own obfuscated script — this
 * extension runs that exact script offline inside an app WebView (see
 * [VrfGenerator] for why the WebView is the only engine that can sign here)
 * and signs each request the way the site does:
 *
 *   canonical = path + "?" + sorted("k=v"; arrays k[0]=v, objects k[key]=v)
 *   request   = path + "?" + url-encoded params + "&vrf=" + token
 *
 * One source per content language (Mihon's source-language UI), matching the
 * site's own language editions: en, fr, es, es-la, pt, pt-br, ja.
 */
@Source
abstract class MangaFire :
    HttpSource(),
    ConfigurableSource {

    private val apiUrl: String
        get() = "$baseUrl/api"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // mangafire.to sits behind Cloudflare and increasingly challenges
        // non-browser TLS fingerprints with a 403 "Just a moment" page (the
        // site itself opens fine in WebView - the cookies the WebView earns
        // are shared back to okhttp through this interceptor's solver).
        .addInterceptor(CloudflareSolverInterceptor(setOf("mangafire.to")))
        .rateLimit(3)
        // v75 self-heal: when the API rejects a carried token (site rotated
        // its keys, stale cache entry), drop every cached token, re-sign THE
        // SAME canonical fresh through the WebView engine and replay the
        // request once.
        .addInterceptor(::selfHealInvalidToken)
        .build()

    init {
        // Warm the signing WebView while the app is still setting up the
        // Browse screen so the first request does not pay the cold start.
        Thread({ VrfGenerator.warmUp() }, "MangaFire-vrf-warmup").apply {
            isDaemon = true
            start()
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("X-Requested-With", "XMLHttpRequest")

    // ========================= Popular & Latest ==========================

    override fun popularMangaRequest(page: Int) = titlesRequest(page) {
        put("limit", VrfGenerator.scalar("24"))
        // 2026-10: the server rebuilds the signed canonical from the PARSED
        // query and PHP re-indexes `languages[]` into `languages[0]` — a
        // `languages[]=`-style wire form no longer matches the token and every
        // listing dies with 403 "Invalid token.". Send the language as an
        // indexed object so BOTH the canonical and the wire carry
        // `languages[0]=<lang>` (verified live: 200 with this shape, 403 with
        // the old one).
        put("languages", VrfGenerator.obj(mapOf("0" to lang)))
        put("order", VrfGenerator.obj(mapOf("trending" to "desc")))
        put("page", VrfGenerator.scalar(page.toString()))
    }

    override fun popularMangaParse(response: Response) = titlesParse(response)

    override fun latestUpdatesRequest(page: Int) = titlesRequest(page) {
        put("limit", VrfGenerator.scalar("24"))
        // Same indexed-language rule as popular — see the note there.
        put("languages", VrfGenerator.obj(mapOf("0" to lang)))
        put("order", VrfGenerator.obj(mapOf("chapter_updated_at" to "desc")))
        put("page", VrfGenerator.scalar(page.toString()))
    }

    override fun latestUpdatesParse(response: Response) = titlesParse(response)

    // ============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            return deeplinkHandler(query)
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = titlesRequest(page) {
        put("limit", VrfGenerator.scalar("24"))
        put("page", VrfGenerator.scalar(page.toString()))
        // Same indexed-language rule as popular — see the note there.
        put("languages", VrfGenerator.obj(mapOf("0" to lang)))

        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) put("keyword", VrfGenerator.scalar(trimmed))

        filters.firstInstanceOrNull<TypeFilter>()?.checked?.takeIf(List<Any>::isNotEmpty)?.let {
            put("types", VrfGenerator.list(it))
        }
        filters.firstInstanceOrNull<StatusFilter>()?.checked?.takeIf(List<Any>::isNotEmpty)?.let {
            put("statuses", VrfGenerator.list(it))
        }
        filters.firstInstanceOrNull<ContentRatingFilter>()?.checked?.takeIf(List<Any>::isNotEmpty)?.let {
            put("content_rating", VrfGenerator.list(it))
        }
        val include = filters.firstInstanceOrNull<GenreFilter>()?.included().orEmpty()
        if (include.isNotEmpty()) put("genres_in", VrfGenerator.list(include))
        val exclude = filters.firstInstanceOrNull<GenreFilter>()?.excluded().orEmpty()
        if (exclude.isNotEmpty()) put("genres_ex", VrfGenerator.list(exclude))
        filters.firstInstanceOrNull<ThemeFilter>()?.included()?.takeIf(List<Any>::isNotEmpty)?.let {
            put("theme_ids", VrfGenerator.list(it))
        }
        filters.firstInstanceOrNull<DemographicFilter>()?.checked?.takeIf(List<Any>::isNotEmpty)?.let {
            put("demographics", VrfGenerator.list(it))
        }
        filters.firstInstanceOrNull<MinChapterFilter>()?.state?.takeIf(String::isNotBlank)?.let {
            put("min_chap", VrfGenerator.scalar(it))
        }
        filters.firstInstanceOrNull<YearFromFilter>()?.state?.takeIf(String::isNotBlank)?.let {
            put("year_from", VrfGenerator.scalar(it))
        }
        filters.firstInstanceOrNull<YearToFilter>()?.state?.takeIf(String::isNotBlank)?.let {
            put("year_to", VrfGenerator.scalar(it))
        }
        filters.firstInstanceOrNull<SortFilter>()?.selected?.takeIf { it.isNotBlank() }?.let { order ->
            val (field, dir) = order.split(':', ' ').let { it[0] to it[1] }
            put("order", VrfGenerator.obj(mapOf(field to dir)))
        }
    }

    override fun searchMangaParse(response: Response) = titlesParse(response)

    private fun deeplinkHandler(query: String): Observable<MangasPage> {
        val url = query.toHttpUrl()
        if (url.host != "mangafire.to") return Observable.just(MangasPage(emptyList(), false))
        val hid = url.pathSegments.getOrNull(1)?.substringBefore('-')
            ?: return Observable.just(MangasPage(emptyList(), false))

        return client.newCall(detailsRequestByUrl(hid))
            .asObservableSuccess()
            .map { MangasPage(listOf(mangaDetailsParse(it)), false) }
    }

    private fun titlesRequest(page: Int, block: MutableMap<String, VrfGenerator.Param>.() -> Unit): Request {
        val params = buildMap(block)
        val vrf = VrfGenerator.token("/titles", params)
        val url = buildUrl("/titles", params, vrf)
        return GET(url, headers)
    }

    private fun titlesParse(response: Response): MangasPage {
        val data = response.parseAs<TitlesResponse>()
        val mangas = data.items.map { it.toSManga() }
        return MangasPage(mangas, data.meta?.hasNext ?: (data.items.size >= 24))
    }

    private fun TitleDto.toSManga() = SManga.create().apply {
        url = "/title/$hid-$slug"
        title = this@toSManga.title
        thumbnail_url = poster?.medium ?: poster?.large
        status = when (this@toSManga.status) {
            "releasing" -> SManga.ONGOING
            "finished" -> SManga.COMPLETED
            "on_hiatus" -> SManga.ON_HIATUS
            "discontinued" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Details ==============================

    private fun detailsRequestByUrl(hid: String): Request {
        val vrf = VrfGenerator.token("/titles/$hid", emptyMap())
        return GET("$apiUrl/titles/$hid?vrf=$vrf", headers)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = detailsRequestByUrl(hidOf(manga))

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<TitleDetailsResponse>().data
        val showTags = showTagsPref()

        val stars = dto.rating?.takeIf { dto.ratingCount == null || dto.ratingCount > 0 }
            ?.let { rating ->
                val full = (rating / 2).toInt().coerceIn(0, 5)
                "★".repeat(full) + "☆".repeat(5 - full) + " $rating"
            }

        val infoLine = if (showExtraInfoPref()) {
            buildList {
                dto.year?.let { add("**Year:** $it") }
                dto.latestChapter?.let { add("**Chapters:** ${it.toString().removeSuffix(".0")}") }
                dto.follows?.takeIf { it > 0 }?.let { add("**Tracked:** $it") }
                dto.contentRating?.let { add("**Content Rating:** ${it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }}") }
                dto.ratingCount?.takeIf { it > 0 }?.let { add("**$it ratings**") }
            }.joinToString(" · ")
        } else {
            ""
        }

        val synopsis = dto.synopsisHtml?.let { html ->
            Jsoup.parseBodyFragment(html).wholeText()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }.orEmpty()

        val desc = buildString {
            val position = scorePositionPref()
            if (position == "top" && stars != null) {
                append(stars, "\n")
                if (infoLine.isNotEmpty()) append(infoLine, "\n\n")
            }
            if (position == "none" && infoLine.isNotEmpty()) append(infoLine, "\n\n")

            append(synopsis)

            if (isNotEmpty()) {
                append("\n\n")
            }
            append("---\n\n")

            val links = listOfNotNull(
                dto.links?.mal?.let { "[MyAnimeList]($it)" },
                dto.links?.al?.let { "[AniList]($it)" },
                dto.links?.mu?.let { "[MangaUpdates]($it)" },
                dto.links?.md?.let { "[MangaDex]($it)" },
            )
            if (links.isNotEmpty()) {
                append("**Links:**\n")
                links.forEach { append("- $it\n") }
            }

            if (showAltNamesPref() && dto.altTitles.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                dto.altTitles.forEach { append("- ", it.trim(), "\n") }
            }

            if (position == "end" && stars != null) {
                if (isNotEmpty()) append("\n")
                append(stars)
                if (infoLine.isNotEmpty()) append("\n", infoLine)
            }
        }.trim()

        return SManga.create().apply {
            url = "/title/${dto.hid}-${dto.slug}"
            title = dto.title
            author = dto.authors?.mapNotNull { it.title }?.joinToString(", ")?.ifBlank { null }
            artist = dto.artists?.mapNotNull { it.title }?.joinToString(", ")?.ifBlank { null }
            genre = buildList {
                dto.genres?.forEach { add(it.title) }
                dto.themes?.takeIf { showTags }?.forEach { add(it.title) }
                dto.demographics?.takeIf { showTags }?.forEach { add(it.title) }
            }.distinct().joinToString(", ").ifBlank { null }
            status = when (dto.status) {
                "releasing" -> SManga.ONGOING
                "finished" -> SManga.COMPLETED
                "on_hiatus" -> SManga.ON_HIATUS
                "discontinued" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = dto.poster?.large ?: dto.poster?.medium
            description = desc.ifBlank { synopsis.ifBlank { null } }
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val hid = hidOf(manga)
        return fetchChapterPage(hid, 1)
    }

    private fun fetchChapterPage(hid: String, page: Int): Request {
        val params = linkedMapOf(
            "language" to VrfGenerator.scalar(lang),
            "limit" to VrfGenerator.scalar("200"),
            "page" to VrfGenerator.scalar(page.toString()),
            "sort" to VrfGenerator.scalar("number"),
            "order" to VrfGenerator.scalar("desc"),
        )
        val vrf = VrfGenerator.token("/titles/$hid/chapters", params)
        val url = buildUrl("/titles/$hid/chapters", params, vrf)
        return GET(url, headers)
    }

    private val chapterPages = ConcurrentHashMap<String, MutableList<ChapterDto>>()

    override fun chapterListParse(response: Response): List<SChapter> {
        val hid = response.request.url.pathSegments.getOrNull(2) ?: ""
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val data = response.parseAs<ChaptersPageResponse>()

        val collected = chapterPages.getOrPut(hid) { mutableListOf() }
        collected += data.items

        val lastPage = data.meta?.lastPage ?: 1
        if (page < lastPage && data.items.isNotEmpty()) {
            client.newCall(fetchChapterPage(hid, page + 1)).execute().use { next ->
                collected += next.parseAs<ChaptersPageResponse>().items
            }
        }
        chapterPages.remove(hid)

        val officialBadge = "Official"
        return collected
            .sortedByDescending { it.number ?: 0f }
            .map { dto ->
                SChapter.create().apply {
                    url = (dto.id ?: 0L).toString()
                    name = buildString {
                        val number = dto.number?.toString()?.removeSuffix(".0").orEmpty()
                        append("Chapter ", number)
                        dto.name?.takeIf(String::isNotBlank)?.let { append(": ", it) }
                        if (dto.type == "official") append(" [$officialBadge]")
                    }.trim()
                    chapter_number = dto.number ?: 0f
                    date_upload = (dto.createdAt ?: 0L) * 1000L
                    scanlator = if (dto.type == "official") officialBadge else null
                }
            }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
        val vrf = VrfGenerator.token("/chapters/$chapterId", emptyMap())
        return GET("$apiUrl/chapters/$chapterId?vrf=$vrf", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ChapterPagesResponse>().data
        return data.pages.mapIndexed { index, image ->
            Page(index, imageUrl = image.url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================ Preferences ============================

    private fun showTagsPref() = preferences.getBoolean(PREF_TAGS, true)
    private fun showExtraInfoPref() = preferences.getBoolean(PREF_EXTRA_INFO, true)
    private fun showAltNamesPref() = preferences.getBoolean(PREF_ALT_NAMES, true)
    private fun scorePositionPref() = preferences.getString(PREF_SCORE_POSITION, "top") ?: "top"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TAGS
            title = "Show themes and demographics in genres"
            summary = "Adds theme and demographic tags below the main genres"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Year, chapter count, tracking and ratings below the score"
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALT_NAMES
            title = "Show alternative names"
            summary = "Append the site's alternative titles to the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score position"
            summary = "%s"
            entries = arrayOf("Top of description", "Bottom of description", "Hidden")
            entryValues = arrayOf("top", "end", "none")
            setDefaultValue("top")
        }.let(screen::addPreference)
    }

    // ============================= Helpers ===============================

    /**
     * Replays a 403 "Invalid token." response with a freshly signed token.
     * Everything else (Cloudflare 403s carry no vrf and an HTML body; image
     * hosts are other domains) passes through untouched.
     */
    private fun selfHealInvalidToken(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url

        if (response.code != 403 || url.queryParameter("vrf") == null || url.host != baseUrl.toHttpUrl().host) {
            return response
        }
        val bodyText = runCatching { response.peekBody(4096L).string() }.getOrNull()
        if (bodyText?.contains("Invalid token") != true) return response

        val params = rebuildSignedParams(url) ?: return response
        val signPath = url.encodedPathSegments
            .takeIf { it.firstOrNull() == "api" }
            ?.let { "/" + it.drop(1).joinToString("/") }
            ?: url.encodedPath
        VrfGenerator.invalidate()
        val freshVrf = runCatching {
            VrfGenerator.token(signPath, params, forceWebView = true)
        }.getOrNull() ?: return response

        response.close()
        return chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().setQueryParameter("vrf", freshVrf).build())
                .build(),
        )
    }

    /** Rebuilds the [VrfGenerator.Param] map a signed request URL carried:
     * `k[]=v` (repeated) is a list, `k[name]=v` an object, bare `k=v` text. */
    private fun rebuildSignedParams(url: HttpUrl): Map<String, VrfGenerator.Param>? {
        val texts = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        val maps = linkedMapOf<String, MutableList<Pair<String, String>>>()
        for (name in url.queryParameterNames) {
            if (name == "vrf") continue
            val values = url.queryParameterValues(name)
            when {
                name.endsWith("[]") ->
                    lists.getOrPut(name.removeSuffix("[]")) { mutableListOf() }
                        .addAll(values.mapIndexed { index, v -> index to (v ?: "") })
                name.endsWith("]") -> {
                    val key = name.substringBefore('[')
                    val index = name.substringAfter('[', "").removeSuffix("]")
                    maps.getOrPut(key) { mutableListOf() }
                        .add(index to values.firstOrNull().orEmpty())
                }
                else -> texts[name] = values.firstOrNull().orEmpty()
            }
        }
        if (texts.isEmpty() && lists.isEmpty() && maps.isEmpty()) return emptyMap()
        return buildMap {
            texts.forEach { (k, v) -> put(k, VrfGenerator.scalar(v)) }
            lists.forEach { (k, v) ->
                put(k, VrfGenerator.list(v.sortedBy { it.first }.map { it.second }))
            }
            maps.forEach { (k, v) -> put(k, VrfGenerator.obj(v.toMap())) }
        }
    }

    private fun hidOf(manga: SManga): String {
        val path = manga.url.substringBefore('#')
        val segment = path.substringAfterLast('/')
        return segment.substringBefore('-')
    }

    private fun buildUrl(
        path: String,
        params: Map<String, VrfGenerator.Param>,
        vrf: String,
    ): HttpUrl {
        val builder = "$apiUrl$path".toHttpUrl().newBuilder()
        params.forEach { (key, value) ->
            when (value) {
                is VrfGenerator.Param.Text -> builder.addQueryParameter(key, value.value)
                is VrfGenerator.Param.ListValue -> value.values.forEach { builder.addQueryParameter("$key[]", it) }
                is VrfGenerator.Param.MapValue -> value.values.forEach { (k, v) -> builder.addQueryParameter("$key[$k]", v) }
            }
        }
        builder.addQueryParameter("vrf", vrf)
        return builder.build()
    }

    companion object {
        private const val PREF_TAGS = "pref_tags_in_genre"
        private const val PREF_EXTRA_INFO = "pref_extra_info"
        private const val PREF_ALT_NAMES = "pref_alt_names"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}

// ================================ DTOs ==================================

@Serializable
internal class TitlesResponse(
    val items: List<TitleDto> = emptyList(),
    val meta: MetaDto? = null,
)

@Serializable
internal class MetaDto(
    val page: Int = 1,
    @kotlinx.serialization.SerialName("lastPage") val lastPage: Int = 1,
    @kotlinx.serialization.SerialName("hasNext") val hasNext: Boolean = false,
)

@Serializable
internal class TitleDto(
    val id: Long,
    val hid: String,
    val slug: String,
    val title: String,
    val type: String? = null,
    val status: String? = null,
    val poster: PosterDto? = null,
    @kotlinx.serialization.SerialName("latestChapter") val latestChapter: Float? = null,
    val year: Int? = null,
    val url: String? = null,
)

@Serializable
internal class PosterDto(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
internal class TitleDetailsResponse(val data: TitleDetailsDto)

@Serializable
internal class TitleDetailsDto(
    val id: Long,
    val hid: String,
    val slug: String,
    val title: String,
    val type: String? = null,
    val status: String? = null,
    @kotlinx.serialization.SerialName("contentRating") val contentRating: String? = null,
    val poster: PosterDto? = null,
    @kotlinx.serialization.SerialName("latestChapter") val latestChapter: Float? = null,
    val year: Int? = null,
    val rank: Int? = null,
    @kotlinx.serialization.SerialName("synopsisHtml") val synopsisHtml: String? = null,
    @kotlinx.serialization.SerialName("altTitles") val altTitles: List<String> = emptyList(),
    val rating: Double? = null,
    @kotlinx.serialization.SerialName("ratingCount") val ratingCount: Int? = null,
    val follows: Int? = null,
    val languages: List<String> = emptyList(),
    val links: LinksDto? = null,
    val genres: List<NamedDto>? = null,
    val themes: List<NamedDto>? = null,
    val demographics: List<NamedDto>? = null,
    val authors: List<NamedDto>? = null,
    val artists: List<NamedDto>? = null,
)

@Serializable
internal class LinksDto(
    val mal: String? = null,
    val al: String? = null,
    val mu: String? = null,
    val md: String? = null,
)

@Serializable
internal class NamedDto(val id: Long? = null, val title: String)

@Serializable
internal class ChaptersPageResponse(
    val items: List<ChapterDto> = emptyList(),
    val meta: MetaDto? = null,
)

@Serializable
internal class ChapterDto(
    val id: Long? = null,
    val number: Float? = null,
    val name: String? = null,
    val language: String? = null,
    val type: String? = null,
    @kotlinx.serialization.SerialName("createdAt") val createdAt: Long? = null,
)

@Serializable
internal class ChapterPagesResponse(val data: ChapterPagesDto)

@Serializable
internal class ChapterPagesDto(
    val id: Long,
    val pages: List<PageImageDto> = emptyList(),
)

@Serializable
internal class PageImageDto(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)
