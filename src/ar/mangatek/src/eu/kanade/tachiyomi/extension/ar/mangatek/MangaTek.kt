package eu.kanade.tachiyomi.extension.ar.mangatek

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * MangaTek (mangatek.com) — Arabic "axis" platform site.
 *
 * The web app is a Next.js shell; everything data-related goes through a
 * gateway API on the `api.` subdomain:
 *   - /api/catalog/genres                     (dynamic filter genres)
 *   - /api/catalog/search?...                 (browse + search + filters)
 *   - /api/catalog/series/by-slug/{slug}      (details)
 *   - /api/catalog/series/{id}/chapters       (chapter list)
 *   - /api/catalog/chapters/by-slug/{...}     (reader pages, direct URLs)
 *
 * The platform is deployed twice under the same brand (مانجاتك): the primary
 * mangatek.com and the twin mangatuk.com. The primary site sits behind an
 * aggressive Cloudflare configuration that hard-blocks some networks —
 * including, at recon time, datacenter IPs. Because both deployments speak
 * the exact same API, a gateway-failover interceptor (Kagane multi-mirror
 * pattern) transparently retries blocked requests on the twin and persists
 * whichever gateway answered. The failover can be forced from the source
 * settings.
 *
 * Covers and reader pages live on the content.* subdomains, which sit
 * behind the same WAF but are NOT covered by the gateway rewrite — they
 * get their own identical failover, and cover/page URLs are normalized
 * onto the active content host when mapping API data. Without it a
 * blocked network browses fine but every image stays dead (403 / reset
 * connections on content.mangatek.com while content.mangatuk.com serves
 * the same paths).
 *
 * Details follow the MangaDot description layout: star rating header, a meta
 * info line, the synopsis, a Links section and the alternative names.
 */
@Source
abstract class MangaTek :
    KeiSource(),
    ConfigurableSource {

    // =============================== Setup ===============================

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
        addInterceptor(::gatewayFailoverInterceptor)
        addInterceptor(::contentFailoverInterceptor)
        addInterceptor(CloudflareSolverInterceptor(knownSiteHosts))
        rateLimit(3)
    }

    // ============================== Gateways =============================
    //
    // Primary: mangatek.com (the site the user asked for). Twin:
    // mangatuk.com — same brand, same platform, same API paths, separate
    // catalog. "Auto" (default) starts on the primary and fails over on
    // Cloudflare hard blocks / origin errors, then sticks to whichever
    // gateway answered. The forced modes never rewrite.

    private val primarySite = "https://mangatek.com"
    private val primaryGateway = "https://api.mangatek.com"
    private val primaryContent = "https://content.mangatek.com"
    private val twinSite = "https://mangatuk.com"
    private val twinGateway = "https://api.mangatuk.com"
    private val twinContent = "https://content.mangatuk.com"

    private val knownSiteHosts = setOf(
        "mangatek.com",
        "www.mangatek.com",
        "mangatuk.com",
        "www.mangatuk.com",
        "api.mangatek.com",
        "api.mangatuk.com",
        "content.mangatek.com",
        "content.mangatuk.com",
    )

    private val siteHosts = setOf(primarySite, twinSite)
    private val gatewayHosts = setOf(primaryGateway, twinGateway)
    private val contentHosts = setOf(primaryContent, twinContent)

    private fun gatewayMode(): String = preferences.getString(PREF_GATEWAY, PREF_GATEWAY_AUTO) ?: PREF_GATEWAY_AUTO

    /** Current site origin used to build web links (deeplinks cover both). */
    private fun currentSite(): String = when (gatewayMode()) {
        PREF_GATEWAY_TWIN -> twinSite
        PREF_GATEWAY_PRIMARY -> primarySite
        else -> {
            val pinned = preferences.getString(PREF_ACTIVE_SITE, null)
            if (pinned != null && pinned in siteHosts) pinned else primarySite
        }
    }

    /** Gateway origin used for API calls. */
    private fun currentGateway(): String = when (gatewayMode()) {
        PREF_GATEWAY_PRIMARY -> primaryGateway
        PREF_GATEWAY_TWIN -> twinGateway
        else -> {
            val pinned = preferences.getString(PREF_ACTIVE_GATEWAY, null)
            if (pinned != null && pinned in gatewayHosts) pinned else primaryGateway
        }
    }

    private val gatewayLock = Any()

    private val contentProbeLock = Any()

    private var contentProbedAtMs = 0L

    private fun persistActive(gateway: String) {
        synchronized(gatewayLock) {
            preferences.edit()
                .putString(PREF_ACTIVE_GATEWAY, gateway)
                .putString(PREF_ACTIVE_SITE, if (gateway == twinGateway) twinSite else primarySite)
                .apply()
        }
    }

    /**
     * Rewrites requests between the two gateway hosts and retries on
     * Cloudflare hard-block responses (403 "Attention Required" pages),
     * stale-edge text/html 404s and origin-dead 5xx (502/504/521/522/523),
     * exactly like Kagane's mirror failover. A 504 was the live "HTTP 504
     * on every request" report: the edge is up but the origin behind it is
     * dead, and the twin's origin is a different machine. Only requests to
     * the known gateway hosts are touched. In auto mode the winning gateway
     * is persisted so later requests start there.
     */
    private fun gatewayFailoverInterceptor(chain: Interceptor.Chain): Response {
        val originalUrl = chain.request().url
        val gatewayHostStrings = gatewayHosts.map { it.toHttpUrl().host }
        if (originalUrl.host !in gatewayHostStrings || gatewayMode() != PREF_GATEWAY_AUTO) {
            return chain.proceed(chain.request())
        }

        var request = chain.request()
        val tried = mutableSetOf(originalUrl.host)

        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                val next = nextGateway(tried) ?: throw e
                request = request.withGatewayHost(next)
                tried += next.toHttpUrl().host
                continue
            }

            val hardBlocked = response.code == 403 &&
                response.header("content-type")?.contains("text/html") == true
            // A text/html 404 on an API host is a misrouted or stale edge
            // (the gateway root answers exactly like that); real API errors
            // are JSON. The 5xx set is "edge up, origin dead".
            val staleEdge404 = response.code == 404 &&
                response.header("content-type")?.contains("text/html") == true
            if (hardBlocked || staleEdge404 || response.code in ORIGIN_DEAD_CODES) {
                response.close()
                val next = nextGateway(tried)
                    ?: throw IOException("MangaTek: both gateways are unreachable")
                Log.i(TAG, "Gateway ${request.url.host} dead (${response.code}), failing over to ${next.toHttpUrl().host}")
                request = request.withGatewayHost(next)
                tried += next.toHttpUrl().host
                continue
            }

            if (request.url.host != originalUrl.host) {
                persistActive("https://" + request.url.host)
            }
            return response
        }
    }

    private fun nextGateway(excludeHosts: Set<String>): String? = gatewayHosts
        .firstOrNull { it.toHttpUrl().host !in excludeHosts }

    private fun Request.withGatewayHost(gatewayUrl: String): Request {
        val newHost = gatewayUrl.toHttpUrl().host
        val builder = newBuilder().url(
            url.newBuilder().scheme("https").host(newHost).build(),
        )
        if (!header("Referer").isNullOrBlank()) {
            builder.header("Referer", "https://$newHost/")
        }
        return builder.build()
    }

    // ============================ Content hosts ===========================
    //
    // Covers and reader pages live on the content.* subdomains — separate
    // hosts the gateway failover never rewrites. They sit behind the same
    // aggressive Cloudflare WAF as the primary site, so a network that is
    // hard-blocked there keeps getting dead covers and dead pages even
    // while browsing works on the twin. This mirrors the gateway failover
    // for the two content hosts: WAF hard blocks, stale-edge text/html
    // 404s and origin-dead 5xx (502/504/521/522/523) are retried on the
    // twin content host with the same path, and in auto mode the winner is
    // persisted. Forced modes pin the matching content host and never
    // rewrite.

    private fun currentContentHost(): String = when (gatewayMode()) {
        PREF_GATEWAY_PRIMARY -> primaryContent
        PREF_GATEWAY_TWIN -> twinContent
        else -> {
            val pinned = preferences.getString(PREF_ACTIVE_CONTENT, null)
            // The primary content host is gone entirely (NXDOMAIN since
            // 2026-10): the unpinned default must not hand out URLs on it.
            // Host apps that fetch images with their own client (Kotatsu's
            // Mihon-compat layer) never run the failover interceptor, and
            // Cloudflare answers requests for a removed hostname with
            // HTTP 404 (error 1001) when stale DNS still resolves it —
            // exactly the "HTTP 404 on every cover and page" report.
            if (pinned != null && pinned in contentHosts) pinned else twinContent
        }
    }

    private fun contentFailoverInterceptor(chain: Interceptor.Chain): Response {
        val originalUrl = chain.request().url
        val contentHostStrings = contentHosts.map { it.toHttpUrl().host }
        if (originalUrl.host !in contentHostStrings || gatewayMode() != PREF_GATEWAY_AUTO) {
            return chain.proceed(chain.request())
        }

        var request = chain.request()
        val tried = mutableSetOf(originalUrl.host)

        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                val next = nextContentHost(tried) ?: throw e
                request = request.withContentHost(next)
                tried += next.toHttpUrl().host
                continue
            }

            val hardBlocked = response.code == 403 &&
                response.header("content-type")?.contains("text/html") == true
            // Cloudflare's error-1001 page (stale DNS for a removed host)
            // is a text/html 404 — a real missing file never looks like
            // that, and failing over once costs a single request when it
            // is a genuine miss. Origin-dead 5xx (a 504 was the live
            // "HTTP 504" report) means the edge is up but the origin is
            // gone; the twin's origin is a different machine.
            val staleEdge404 = response.code == 404 &&
                response.header("content-type")?.contains("text/html") == true
            if (hardBlocked || staleEdge404 || response.code in ORIGIN_DEAD_CODES) {
                response.close()
                val next = nextContentHost(tried)
                    ?: throw IOException("MangaTek: both content hosts are unreachable")
                Log.i(TAG, "Content host ${request.url.host} dead (${response.code}), failing over to ${next.toHttpUrl().host}")
                request = request.withContentHost(next)
                tried += next.toHttpUrl().host
                continue
            }

            if (request.url.host != originalUrl.host) {
                synchronized(gatewayLock) {
                    preferences.edit()
                        .putString(PREF_ACTIVE_CONTENT, "https://" + request.url.host)
                        .apply()
                }
            }
            return response
        }
    }

    private fun nextContentHost(excludeHosts: Set<String>): String? = contentHosts
        .firstOrNull { it.toHttpUrl().host !in excludeHosts }

    /**
     * Verifies the active content host before any content URL is handed to
     * the host app, and re-pins the liveness winner when the pinned host is
     * dead. This replaced the 2026-10 probe that went through the source
     * client: it could NEVER heal a pin, because the rewritten request hit
     * the twin's WAF root 403 (text/html), the interceptor classified that
     * as "both content hosts unreachable" and threw — so covers and pages
     * kept pointing at whichever host the pin (or the NXDOMAIN default)
     * named. The probe now runs on a bare client against BOTH hosts and
     * counts any NON-ORIGIN-ERROR answer as alive — including the WAF's
     * root 403, which proves the host exists — while a 5xx (502/504/521…)
     * means the edge is up but the origin behind it is dead, exactly the
     * state that handed out "HTTP 504" covers and pages. A dead host
     * (NXDOMAIN) answers nothing at all.
     */
    private fun ensureActiveContentHostLive() {
        if (gatewayMode() != PREF_GATEWAY_AUTO) return
        synchronized(contentProbeLock) {
            val now = System.currentTimeMillis()
            if (now - contentProbedAtMs < CONTENT_PROBE_INTERVAL_MS) return
            contentProbedAtMs = now
        }
        val active = currentContentHost()
        if (probeHostAlive(active)) return
        val fallback = contentHosts.firstOrNull { it != active } ?: return
        if (probeHostAlive(fallback)) {
            Log.i(TAG, "content host $active dead, pinning $fallback")
            preferences.edit().putString(PREF_ACTIVE_CONTENT, fallback).apply()
        }
    }

    /** True when the host answers with a status an origin can actually be
     * behind: any 2xx-4xx (a WAF 403 still proves the host exists), never
     * a 5xx (edge up, origin dead) and never a connect failure. */
    private fun probeHostAlive(host: String): Boolean = runCatching {
        probeClient.newCall(Request.Builder().url(host).head().build()).execute().use { response ->
            response.code < 500
        }
    }.getOrDefault(false)

    /**
     * Bare client for the liveness probe: deliberately WITHOUT the source
     * interceptors, so a dead host surfaces as a connect failure instead of
     * being silently rewritten (and then misjudged) by the failover loop.
     */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun Request.withContentHost(contentUrl: String): Request {
        val newHost = contentUrl.toHttpUrl().host
        val builder = newBuilder().url(
            url.newBuilder().scheme("https").host(newHost).build(),
        )
        if (!header("Referer").isNullOrBlank()) {
            builder.header("Referer", "https://$newHost/")
        }
        return builder.build()
    }

    /**
     * Rewrites known content hosts onto the currently active one, so cover
     * and page URLs recorded against a dead content host (library entries
     * from before a failover, or twin API responses that still reference
     * primary storage) are served by whichever host answers. Unknown hosts
     * pass through untouched.
     */
    private fun withActiveContentHost(url: String): String {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return url
        val contentHostStrings = contentHosts.map { it.toHttpUrl().host }
        if (parsed.host !in contentHostStrings) return url
        val active = currentContentHost().toHttpUrl().host
        if (parsed.host == active) return url
        return parsed.newBuilder().host(active).build().toString()
    }

    // ============================ Formatting =============================

    private val apiDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = true
        }
    }

    private fun parseDate(raw: String?): Long = apiDateFormat.tryParse(raw?.substringBefore("."))

    // ========================= Popular & Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = getCatalogPage(sort = "popular", page = page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getCatalogPage(sort = "latest", page = page)

    private fun catalogRequest(sort: String, page: Int, filters: FilterList? = null): Request {
        val url = "${currentGateway()}/api/catalog/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("offset", ((page - 1) * PAGE_LIMIT).toString())
            addQueryParameter("sort", sort)
            addQueryParameter("order", "desc")
            addQueryParameter("mature", "include")
        }.build()
        return GET(url, headers)
    }

    private suspend fun getCatalogPage(sort: String, page: Int): MangasPage {
        val response = client.newCall(catalogRequest(sort, page)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while browsing MangaTek")
            val data = it.parseAs<SearchResponseDto>().data
            return MangasPage(
                data.map { series -> series.toSManga() },
                data.size >= PAGE_LIMIT,
            )
        }
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "relevance"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected.orEmpty()
        val genres = filters.firstInstanceOrNull<GenreFilter>()
            ?.included
            .orEmpty()
            .joinToString(",")

        val url = "${currentGateway()}/api/catalog/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("offset", ((page - 1) * PAGE_LIMIT).toString())
            addQueryParameter("sort", sort)
            addQueryParameter("order", "desc")
            addQueryParameter("mature", "include")
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            if (status.isNotEmpty()) addQueryParameter("status", status)
            if (genres.isNotEmpty()) {
                addQueryParameter("includeGenres", genres)
                addQueryParameter("genreMode", "any")
            }
        }.build()

        val response = client.newCall(GET(url, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while searching MangaTek")
            val data = it.parseAs<SearchResponseDto>().data
            return MangasPage(
                data.map { series -> series.toSManga() },
                data.size >= PAGE_LIMIT,
            )
        }
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching get() = true

    @Serializable
    private data class FilterDataDto(
        val genres: List<Pair<String, String>>? = null,
    )

    override suspend fun fetchFilterData(): kotlinx.serialization.json.JsonElement {
        val url = "${currentGateway()}/api/catalog/genres".toHttpUrl()
        val genres = client.get(url, headers = headers).use { response ->
            if (!response.isSuccessful) return@use emptyList<Pair<String, String>>()
            response.parseAs<List<GenreDto>>()
                .map { genre -> genre.name to genre.id }
        }
        return FilterDataDto(genres).toJsonElement()
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList {
        val genres = data
            ?.let { runCatching { it.parseAs<FilterDataDto>() }.getOrNull() }
            ?.genres
        return getFilterListInternal(genres)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "${currentSite()}/series/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val host = url.host
        if (host !in knownSiteHosts) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "series") return null

        val slug = segments[1]
        val tmpManga = SManga.create().apply { this.url = slug }
        return fetchMangaUpdate(tmpManga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) fetchSeriesDetails(manga) else manga
        }
        val chaptersDeferred = async {
            if (fetchChapters) fetchChapterListInternal(manga) else chapters
        }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun fetchSeriesDetails(manga: SManga): SManga {
        ensureActiveContentHostLive()
        val url = "${currentGateway()}/api/catalog/series/by-slug/${manga.url}".toHttpUrl()
        val response = client.newCall(GET(url, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading series details")
            return it.parseAs<SeriesDto>().toSManga()
        }
    }

    // ============================= Chapters ==============================

    private suspend fun fetchChapterListInternal(manga: SManga): List<SChapter> {
        val seriesId = fetchSeriesId(manga.url)
        val url = "${currentGateway()}/api/catalog/series/$seriesId/chapters".toHttpUrl()
        val response = client.newCall(GET(url, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading chapters")
            val data = it.parseAs<ChapterListDto>().data
            return data
                .map { chapter ->
                    chapter.toSChapter(manga.url).apply {
                        date_upload = parseDate(chapter.publishedAt ?: chapter.updatedAt)
                    }
                }
                // Mihon expects newest-first; the gateway returns oldest-first.
                .sortedByDescending { it.chapter_number }
        }
    }

    private suspend fun fetchSeriesId(slug: String): String {
        val url = "${currentGateway()}/api/catalog/series/by-slug/$slug".toHttpUrl()
        val response = client.newCall(GET(url, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while resolving series id")
            return it.parseAs<SeriesDto>().id
        }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String {
        val parsed = chapter.url.parseAs<ChapterRef>()
        return "${currentSite()}/series/${parsed.seriesSlug}/${parsed.chapterSlug}"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        ensureActiveContentHostLive()
        val ref = chapter.url.parseAs<ChapterRef>()
        val url = "${currentGateway()}/api/catalog/chapters/by-slug/${ref.seriesSlug}/${ref.chapterSlug}".toHttpUrl()
        val response = client.newCall(GET(url, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} while loading chapter pages")
            val detail = it.parseAs<ChapterDetailDto>()
            return detail.pages
                .mapNotNull { page -> page.imageUrl?.takeIf(String::isNotBlank) }
                .map(::withActiveContentHost)
                .mapIndexed { index, imageUrl ->
                    Page(index = index, url = getChapterUrl(chapter), imageUrl = imageUrl)
                }
        }
    }

    // ======================== MangaDot description =======================

    /**
     * MangaDot-style description block:
     *   ★★★★☆ 8.00
     *   **Type:** Manhwa · **Status:** Ongoing · **Chapters:** 19 · 12 ratings
     *   ---
     *   <synopsis>
     *   **Genres:** (pref-controlled)
     *   **Links:** ...
     *   **Alternative Names:** ...
     */
    private fun SeriesDto.toSManga() = SManga.create().apply {
        url = this@toSManga.slug
        title = this@toSManga.title
        thumbnail_url = this@toSManga.coverImage
            ?.takeIf { it.startsWith("http") }
            ?.let(::withActiveContentHost)
        author = this@toSManga.authorText()
        artist = this@toSManga.artistText()
        status = when (this@toSManga.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = this@toSManga.genreLabels().joinToString(",") { genresLabel(it) }

        val showGenres = preferences.getBoolean(PREF_SHOW_GENRES, true)
        description = buildString {
            val rating = this@toSManga.ratingAvgValue()
            val ratingCount = this@toSManga.ratingCount ?: 0
            if (rating != null && rating > 0f) {
                val stars = (rating / 2).roundToInt().coerceIn(0, 5)
                append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $rating\n\n")
            }

            val metaInfo = buildList {
                this@toSManga.type?.let { add("**Type:** ${it.replaceFirstChar { c -> c.uppercase() }}") }
                this@toSManga.format?.let { add("**Format:** ${it.replaceFirstChar { c -> c.uppercase() }}") }
                when (this@toSManga.status?.lowercase()) {
                    "ongoing" -> add("**Status:** Ongoing")
                    "completed" -> add("**Status:** Completed")
                    "hiatus" -> add("**Status:** Hiatus")
                    "cancelled" -> add("**Status:** Cancelled")
                }
                this@toSManga.chapterCountText()?.let {
                    add("**Chapters:** $it")
                }
                this@toSManga.viewCount?.takeIf { it > 0 }?.let { add("${it.formatCount()} views") }
                ratingCount.takeIf { it > 0 }?.let { add("$it ratings") }
            }
            if (metaInfo.isNotEmpty()) {
                append(metaInfo.joinToString(" · "), "\n\n")
            }

            if (isNotEmpty()) {
                append("---\n\n")
            }

            this@toSManga.description?.let {
                append(
                    it.replace("\r\n", "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim(),
                    "\n\n",
                )
            }

            val labelList = this@toSManga.genreLabels()
            if (showGenres && labelList.isNotEmpty()) {
                append("\n**Genres:**\n")
                labelList.forEach { label ->
                    append("- ", genresLabel(label), "\n")
                }
            }

            listOfNotNull(
                "[Source](${currentSite()}/series/${this@toSManga.slug})",
            ).also { links ->
                if (links.isNotEmpty()) {
                    append("\n**Links:**\n")
                    links.forEach { link -> append("- ", link, "\n") }
                }
            }

            val altNames = this@toSManga.associatedNames
                ?.split("\n")
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .orEmpty()
            if (altNames.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                altNames.forEach { altTitle ->
                    append("- ", altTitle, "\n")
                }
            }
        }.trim()
        initialized = true
    }

    /** Genre slugs are lowercase english (e.g. "school-life") - present them
     * with capitalized words for the genre chips. */
    private fun genresLabel(slug: String): String = slug
        .split("-")
        .joinToString(" ") { word ->
            word.replaceFirstChar { c -> c.uppercase() }
        }

    private fun Int.formatCount(): String = when {
        this >= 1_000_000 -> String.format(Locale.US, "%.1fM", this / 1_000_000f)
        this >= 1_000 -> String.format(Locale.US, "%.1fk", this / 1_000f)
        else -> toString()
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_GATEWAY
            title = "النطاق (Gateway)"
            summary = "الأساسي mangatek.com محمي بـ Cloudflare وقد يحجب بعض الشبكات، والنسخة المطابقة mangatuk.com تستخدم نفس الواجهة البرمجية"
            entries = arrayOf("تلقائي (تجاوز الحجب تلقائياً)", "الأساسي فقط (mangatek.com)", "النسخة المطابقة فقط (mangatuk.com)")
            entryValues = arrayOf(PREF_GATEWAY_AUTO, PREF_GATEWAY_PRIMARY, PREF_GATEWAY_TWIN)
            setDefaultValue(PREF_GATEWAY_AUTO)
        }.let(screen::addPreference)

        // ---- Details screen ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_GENRES
            title = "Show genres in details"
            summary = "List the title's genres in its description"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private companion object {
        const val TAG = "MangaTek"
        const val PAGE_LIMIT = 24

        const val PREF_GATEWAY = "pref_gateway"
        const val PREF_GATEWAY_AUTO = "auto"
        const val PREF_GATEWAY_PRIMARY = "primary"
        const val PREF_GATEWAY_TWIN = "twin"
        const val PREF_ACTIVE_GATEWAY = "pref_active_gateway"
        const val PREF_ACTIVE_SITE = "pref_active_site"
        const val PREF_ACTIVE_CONTENT = "pref_active_content"
        const val PREF_SHOW_GENRES = "pref_show_genres"

        private const val CONTENT_PROBE_INTERVAL_MS = 10 * 60 * 1000L

        /** Edge-up-origin-dead 5xx codes: Cloudflare answers these itself
         * when the origin behind it is down (504 gateway timeout was the
         * live report), so a response with one of them is retryable on the
         * twin host whose origin is a different machine. */
        private val ORIGIN_DEAD_CODES = setOf(502, 504, 521, 522, 523)
    }
}
