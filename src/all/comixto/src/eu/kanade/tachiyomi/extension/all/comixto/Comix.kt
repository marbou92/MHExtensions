package eu.kanade.tachiyomi.extension.all.comixto

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.cloudflare.CloudflareSolverDiagnostics
import keiyoushi.cloudflare.CloudflareSolverInterceptor
import keiyoushi.cloudflare.isCloudflareChallenge
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import rx.Observable
import rx.schedulers.Schedulers
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Source
abstract class Comix :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .apply {
            // v45: comix.to raised managed Cloudflare challenges on the API
            // (chapter-list calls answered 403 "Just a moment…" while the
            // site itself kept loading in a browser). Header hardening of
            // plain OkHttp can never pass a managed challenge — the shared
            // solver does: window-attached WebView solve, Turnstile tap,
            // self-heal verify, single-flight for parallel calls. Installed
            // FIRST so its solve-retry re-enters the hardening + signing
            // interceptors below with the fresh clearance.
            addInterceptor(CloudflareSolverInterceptor(setOf("comix.to")))

            // v45: browser fingerprint headers + WebView cookie sync (the
            // old hard block/retry loop was folded into the solver — see
            // CloudflareBypass for the split).
            CloudflareBypass(setOf("comix.to")).install(this)
        }
        // v46: comix.to's origin answers flaky 502/503/522/523 behind Cloudflare
        // (the official extension added the identical retry on Sep 25 after the
        // same field reports). Single-shot calls surface those as "HTTP 5xx" in
        // the app; this retries with an r= cache-buster, then walks the image
        // CDN's scramble-path variants. Managed challenges are skipped — the
        // solver above owns them.
        .addInterceptor(::retryInterceptor)
        .addInterceptor(::signRequestInterceptor)
        .addInterceptor(::decryptResponseInterceptor)
        .addNetworkInterceptor(::descrambleImageInterceptor)
        .build()

    // Default headers: only Referer (safe for both API and image requests)
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // API-specific headers (JSON + XHR) — used only for /api/v1/ calls
    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // ========================================================================
    // WebView fallback (v46) — the site's own bundle signs + decrypts
    // ========================================================================

    private fun webViewChapterList(manga: SManga): List<SChapter> {
        val hid = manga.url
        CloudflareSolverDiagnostics.record("COMIX native chapter list failed → WebView fallback (hid=$hid)")
        val capture = ComixWebView.capture(client, baseUrl, headers["User-Agent"] ?: FALLBACK_UA) { mainScriptUrl, passName, rejectName ->
            webViewChapterListCaptureScript(hid, mainScriptUrl, passName, rejectName)
        }
        capture.material?.takeIf { it.isValid() }?.let { material ->
            applyCipherMaterial(material)
        }
        val items = capture.payload.parseAs<List<ComixChapterDto>>()
        CloudflareSolverDiagnostics.record("COMIX WebView fallback chapter list OK (${items.size} items)")
        return items.map { it.toSChapter() }
    }

    private fun webViewPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url
        CloudflareSolverDiagnostics.record("COMIX native page list failed → WebView fallback (chapter=$chapterId)")
        val capture = ComixWebView.capture(client, baseUrl, headers["User-Agent"] ?: FALLBACK_UA) { mainScriptUrl, passName, rejectName ->
            webViewPageListCaptureScript(chapterId, mainScriptUrl, passName, rejectName)
        }
        capture.material?.takeIf { it.isValid() }?.let { material ->
            applyCipherMaterial(material)
        }
        val payload = capture.payload.parseAs<WebViewPagesPayload>()
        val container = payload.container()
            ?: throw IOException("Comix WebView fallback returned no page container")
        CloudflareSolverDiagnostics.record("COMIX WebView fallback page list OK (${container.items.size} pages)")
        return buildPageList(container)
    }

    /**
     * Imports the site's env bundle in the WebView and drives the site's OWN
     * manga API (`mangaApi.chapters(...)`) — the exact call shape the official
     * keiyoushi Comix extension uses, so signing + decryption happen in-JS.
     */
    private fun webViewChapterListCaptureScript(
        hid: String,
        mainScriptUrl: String,
        passPayloadName: String,
        rejectName: String,
    ): String = $$"""
        (function () {
            const payloadKey = '__comixChapterPayload';
            const mangaId = $${JSONObject.quote(hid)};
            const mainScriptUrl = $${JSONObject.quote(mainScriptUrl)};
            if (window[payloadKey]) return null;
            window[payloadKey] = true;

            (async () => {
                try {
                    if (!mainScriptUrl) throw new Error('Could not find main bundle');
                    const mainResponse = await fetch(mainScriptUrl);
                    if (!mainResponse.ok) throw new Error('Could not load main bundle');
                    const mainJavaScript = await mainResponse.text();
                    const environmentFile = mainJavaScript.match(/from\s*["']\.\/(env-[^"']+\.js)["']/)?.[1];
                    if (!environmentFile) throw new Error('Could not find environment bundle');

                    const importBundle = new Function('url', 'return import(url)');
                    const environment = await importBundle(new URL(environmentFile, mainScriptUrl).href);
                    const mangaApi = Object.values(environment).find(value =>
                        value && typeof value === 'object' && typeof value.chapters === 'function');
                    if (!mangaApi) throw new Error('Could not find manga API');

                    const items = [];
                    let page = 1;
                    while (page <= $${ComixWebView.MAX_CHAPTER_PAGES}) {
                        const response = await mangaApi.chapters(mangaId, {
                            page,
                            limit: 100,
                            order: { number: 'desc' }
                        });
                        const pageItems = response?.items;
                        if (!Array.isArray(pageItems) || pageItems.length === 0) break;
                        items.push(...pageItems);
                        const meta = response.meta || response.pagination || {};
                        const lastPage = meta.lastPage || meta.last_page || page;
                        if (!(meta.hasNext || page < lastPage)) break;
                        page++;
                    }
                    window.$${passPayloadName}(JSON.stringify(items));
                } catch (error) {
                    window.$${rejectName}(error);
                }
            })();
            return null;
        })();
    """.trimIndent()

    /** Same env-bundle import, but drives the site's generic API client with the chapter-pages path. */
    private fun webViewPageListCaptureScript(
        chapterId: String,
        mainScriptUrl: String,
        passPayloadName: String,
        rejectName: String,
    ): String = $$"""
        (function () {
            const payloadKey = '__comixPagePayload';
            const chapterId = $${JSONObject.quote(chapterId)};
            const mainScriptUrl = $${JSONObject.quote(mainScriptUrl)};
            if (window[payloadKey]) return null;
            window[payloadKey] = true;

            (async () => {
                try {
                    if (!mainScriptUrl) throw new Error('Could not find main bundle');
                    const mainResponse = await fetch(mainScriptUrl);
                    if (!mainResponse.ok) throw new Error('Could not load main bundle');
                    const mainJavaScript = await mainResponse.text();
                    const environmentFile = mainJavaScript.match(/from\s*["']\.\/(env-[^"']+\.js)["']/)?.[1];
                    if (!environmentFile) throw new Error('Could not find environment bundle');

                    const importBundle = new Function('url', 'return import(url)');
                    const environment = await importBundle(new URL(environmentFile, mainScriptUrl).href);
                    const apiClient = Object.values(environment).find(value =>
                        value && typeof value === 'object' && typeof value.get === 'function');
                    if (!apiClient) throw new Error('Could not find API client');

                    const data = await apiClient.get('/chapters/' + chapterId);
                    const container = data && (data.pages || (data.result && data.result.pages));
                    if (!container || !Array.isArray(container.items)) {
                        throw new Error('Unexpected chapter payload from API client');
                    }
                    window.$${passPayloadName}(JSON.stringify(data));
                } catch (error) {
                    window.$${rejectName}(error);
                }
            })();
            return null;
        })();
    """.trimIndent()

    // ========================================================================
    // API
    // ========================================================================

    private val apiBaseUrl = "$baseUrl/api/v1"

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        // "Most followed" as popular
        return mangaListRequest(page, sortBy = "follows_total", query = null)
    }

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = mangaListRequest(page, sortBy = "chapter_updated_at", query = null)

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    // =============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortFilter = filters.firstInstance<SortFilter>()
        val sortIndex = sortFilter?.state?.index ?: 0
        val sortAscending = sortFilter?.state?.ascending ?: false
        val sortBy = sortOptions[sortIndex] ?: "relevance"
        val sortDir = if (sortAscending) "asc" else "desc"

        // Use defaults from preferences, override with filter selections if any
        val defaultTypes = preferences.getDefaultTypes()
        val defaultDemos = preferences.getDefaultDemographics()
        val defaultContentRatings = preferences.getContentRatings()

        val types = filters.firstInstance<TypeFilter>()?.state?.filter { it.state }?.map { it.value }
            ?.ifEmpty { defaultTypes }
        val statuses = filters.firstInstance<StatusFilter>()?.state?.filter { it.state }?.map { it.value } ?: emptyList()
        val demographics = filters.firstInstance<DemographicFilter>()?.state?.filter { it.state }?.map { it.id }
            ?.ifEmpty { defaultDemos.mapNotNull { it.toIntOrNull() } }
        val genresIncl = filters.firstInstance<GenreFilter>()?.state?.filter { it.state }?.map { it.id } ?: emptyList()
        val contentRatings = filters.firstInstance<ContentRatingFilter>()?.state?.filter { it.state }?.map { it.value }
            ?.ifEmpty { defaultContentRatings }
        val minChapters = filters.firstInstance<MinChaptersFilter>()?.state?.toIntOrNull()?.toString() ?: ""
        val yearFrom = filters.firstInstance<YearFromFilter>()?.state?.toIntOrNull()?.toString() ?: ""
        val yearTo = filters.firstInstance<YearToFilter>()?.state?.toIntOrNull()?.toString() ?: ""

        return mangaListRequest(
            page = page,
            sortBy = sortBy,
            sortDir = sortDir,
            query = query.takeIf { it.isNotBlank() },
            types = types ?: emptyList(),
            statuses = statuses,
            contentRatings = contentRatings ?: emptyList(),
            demographics = demographics ?: emptyList(),
            genresIncl = genresIncl,
            minChapters = minChapters,
            yearFrom = yearFrom,
            yearTo = yearTo,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val hid = manga.url
        return GET("$apiBaseUrl/manga/$hid", apiHeaders)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = response.parseAs<ComixMangaDetailDto>()
        return detail.toSManga()
    }

    // ============================= Chapters =============================

    // v46: if the native signed path fails for ANY reason (flaky origin after
    // 10 retries, rotated cipher material, handed-back challenge), rerun the
    // fetch through the WebView where the site's OWN bundle signs + decrypts
    // the calls — the same environment the user sees working when they open
    // the site. A successful run also recaptures the current cipher material,
    // so the fast native path self-heals afterwards.
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = super.fetchChapterList(manga).onErrorResumeNext { error ->
        Observable.fromCallable { webViewChapterList(manga) }
            .subscribeOn(Schedulers.io())
            .onErrorResumeNext { Observable.error(error) }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val hid = manga.url
        return GET("$apiBaseUrl/manga/$hid/chapters?page=1&limit=100", apiHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ComixChapterListDto>()
        val items = data.items.toMutableList()
        val hid = response.request.url.encodedPath
            .substringAfter("/manga/")
            .substringBefore("/chapters")

        // Remaining chapter-list pages are fetched IN PARALLEL (bounded pool)
        // instead of one-by-one: a 900-chapter manga used to pay 8 serial
        // signed+decrypted round-trips before the list even opened.
        if (data.meta?.hasNext == true) {
            val maxPage = minOf(data.meta?.lastPage ?: 2, 20)
            val pages = (2..maxPage).toList()
            if (pages.isNotEmpty()) {
                val pool = Executors.newFixedThreadPool(minOf(4, pages.size)) { runnable ->
                    Thread(runnable, "Comix-ChapterList").apply { isDaemon = true }
                }
                try {
                    val futures = pages.map { page ->
                        pool.submit(
                            Callable {
                                val nextReq = GET("$apiBaseUrl/manga/$hid/chapters?page=$page&limit=100", apiHeaders)
                                client.newCall(nextReq).execute().use { nextResp ->
                                    nextResp.parseAs<ComixChapterListDto>().items
                                }
                            },
                        )
                    }
                    // Page order matters (site order), so collect in page order.
                    futures.forEach { future -> items.addAll(future.get()) }
                } finally {
                    pool.shutdown()
                }
            }
        }

        var chapters = items.map { it.toSChapter() }

        // Deduplicate chapters by number — keep the best version of each
        if (preferences.deduplicateChapters()) {
            // Build a map of chapter_number -> best DTO, then preserve original order
            val bestByKey = mutableMapOf<Float, ComixChapterDto>()
            for (dto in items) {
                val key = dto.number ?: -1f
                val existing = bestByKey[key]
                if (existing == null || isBetterChapter(dto, existing)) {
                    bestByKey[key] = dto
                }
            }
            val bestIds = bestByKey.values.map { it.id }.toSet()
            chapters = items.filter { it.id in bestIds }.map { it.toSChapter() }
        }

        // Filter by scanlator preference
        val scanlatorPref = preferences.getScanlatorFilter()
        if (scanlatorPref.isNotBlank()) {
            chapters = chapters.filter { ch ->
                scanlatorPref.split(",").any { s ->
                    ch.scanlator?.contains(s.trim(), ignoreCase = true) == true
                }
            }
        }

        return chapters
    }

    /**
     * Returns true if [a] is a better chapter than [b] for deduplication.
     * Priority: official > more votes > more recent.
     */
    private fun isBetterChapter(a: ComixChapterDto, b: ComixChapterDto): Boolean {
        // Official chapters always win
        if (a.isOfficial == true && b.isOfficial != true) return true
        if (b.isOfficial == true && a.isOfficial != true) return false
        // Then higher votes
        val aVotes = a.votes ?: 0
        val bVotes = b.votes ?: 0
        if (aVotes != bVotes) return aVotes > bVotes
        // Then more recent (by relative date — can't parse exact, so keep original order)
        return false
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
        return GET("$apiBaseUrl/chapters/$chapterId", apiHeaders)
    }

    // v46: same fallback shape as the chapter list — the site's own client
    // signs + decrypts the /chapters/{id} call inside the WebView.
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = super.fetchPageList(chapter).onErrorResumeNext { error ->
        Observable.fromCallable { webViewPageList(chapter) }
            .subscribeOn(Schedulers.io())
            .onErrorResumeNext { Observable.error(error) }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url is the numeric chapter ID; we cannot reconstruct the full web URL without
        // the manga slug, so we point back at the site root where the reader lives.
        return "$baseUrl/title/${chapter.url}"
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ComixChapterPagesDto>()
        return buildPageList(data.pages)
    }

    /**
     * v46: mirrors the official Comix extension's page-URL handling. The old
     * code stripped the ENTIRE query string from image URLs — fine when the
     * site put nothing there, but today's URLs carry parameters and the grid
     * scramble is gated on a `v3` flag (without it the server does not send
     * the x-scramble-* headers our descrambler needs). Legacy byte-XOR pages
     * (every 4th, no `s` flag) keep a `#scrambled` marker like upstream.
     */
    private fun buildPageList(container: ComixPagesContainerDto?): List<Page> {
        val base = container?.baseUrl.orEmpty()
        return container?.items.orEmpty().mapIndexed { index, pageDto ->
            val full = if (pageDto.url.startsWith("http")) pageDto.url else "$base/${pageDto.url.trimStart('/')}"
            val isV3 = pageDto.s == 1 || full.contains("?v3")
            val isLegacyScramble = !isV3 && (index + 1) % 4 == 0
            val url = when {
                isV3 -> {
                    val httpUrl = full.toHttpUrl()
                    if (httpUrl.queryParameterNames.contains("v3")) {
                        full
                    } else {
                        httpUrl.newBuilder().addQueryParameter("v3", null).build().toString()
                    }
                }
                isLegacyScramble -> "$full#scrambled"
                else -> full
            }
            Page(index, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        // v46 (mirrors the official extension, Oct 3): comix image domains
        // BLOCK any request carrying Referer or Origin — and the default
        // source headers attach `Referer: $baseUrl/` to everything. Strip
        // both (and any fragment marker) for image requests only.
        val requestHeaders = headersBuilder()
            .removeAll("Origin")
            .removeAll("Referer")
            .build()
        return GET(imageUrl.substringBefore('#'), requestHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun mangaListRequest(
        page: Int,
        sortBy: String,
        sortDir: String = "desc",
        query: String?,
        types: List<String> = emptyList(),
        statuses: List<String> = emptyList(),
        contentRatings: List<String> = listOf("safe", "suggestive"),
        demographics: List<Int> = emptyList(),
        genresIncl: List<Int> = emptyList(),
        minChapters: String = "",
        yearFrom: String = "",
        yearTo: String = "",
    ): Request {
        val url = "$apiBaseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "28")
            .addQueryParameter("order[$sortBy]", sortDir)
            .apply {
                query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("keyword", it) }
                types.forEachIndexed { i, v -> addQueryParameter("types[$i]", v) }
                statuses.forEachIndexed { i, v -> addQueryParameter("statuses[$i]", v) }
                contentRatings.forEachIndexed { i, v -> addQueryParameter("content_rating[$i]", v) }
                demographics.forEachIndexed { i, v -> addQueryParameter("demographics[$i]", v.toString()) }
                genresIncl.forEachIndexed { i, v -> addQueryParameter("genres_in[$i]", v.toString()) }
                if (minChapters.isNotBlank()) addQueryParameter("min_chap", minChapters)
                if (yearFrom.isNotBlank()) addQueryParameter("year_from", yearFrom)
                if (yearTo.isNotBlank()) addQueryParameter("year_to", yearTo)
            }
            .build()

        return GET(url, apiHeaders)
    }

    private fun mangaListParse(response: Response): MangasPage {
        val data = response.parseAs<ComixMangaListDto>()
        val mangas = data.items.map { it.toSManga() }
        val hasNext = data.meta?.hasNext == true
        return MangasPage(mangas, hasNext)
    }

    // ========================================================================
    // Filters
    // ========================================================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        DemographicFilter(),
        GenreFilter(),
        ContentRatingFilter(),
        MinChaptersFilter(),
        YearFromFilter(),
        YearToFilter(),
    )

    private val sortOptions = mapOf(
        0 to "relevance",
        1 to "chapter_updated_at",
        2 to "created_at",
        3 to "title",
        4 to "year",
        5 to "score",
        6 to "views_7d",
        7 to "views_30d",
        8 to "views_90d",
        9 to "views_total",
        10 to "follows_total",
    )

    private class SortFilter :
        Filter.Sort(
            "Sort by",
            arrayOf(
                "Relevance",
                "Latest update",
                "Recently added",
                "Title",
                "Year",
                "Highest rated",
                "Most viewed (7 days)",
                "Most viewed (30 days)",
                "Most viewed (90 days)",
                "Most viewed (all time)",
                "Most followed",
            ),
            Selection(0, false),
        )

    private class TypeFilter :
        Filter.Group<CheckboxFilter>(
            "Type",
            listOf(
                CheckboxFilter("Manga", "manga"),
                CheckboxFilter("Manhwa", "manhwa"),
                CheckboxFilter("Manhua", "manhua"),
                CheckboxFilter("Other", "other"),
            ),
        )

    private class StatusFilter :
        Filter.Group<CheckboxFilter>(
            "Status",
            listOf(
                CheckboxFilter("Releasing", "releasing"),
                CheckboxFilter("Finished", "finished"),
                CheckboxFilter("On hiatus", "on_hiatus"),
                CheckboxFilter("Discontinued", "discontinued"),
            ),
        )

    private class DemographicFilter :
        Filter.Group<IdCheckboxFilter>(
            "Demographic",
            listOf(
                IdCheckboxFilter("Shounen", 2),
                IdCheckboxFilter("Seinen", 4),
                IdCheckboxFilter("Shoujo", 1),
                IdCheckboxFilter("Josei", 3),
            ),
        )

    private class GenreFilter :
        Filter.Group<IdCheckboxFilter>(
            "Genres (include)",
            listOf(
                IdCheckboxFilter("Action", 6),
                IdCheckboxFilter("Adventure", 7),
                IdCheckboxFilter("Boys Love", 8),
                IdCheckboxFilter("Comedy", 9),
                IdCheckboxFilter("Crime", 10),
                IdCheckboxFilter("Drama", 11),
                IdCheckboxFilter("Fantasy", 12),
                IdCheckboxFilter("Girls Love", 13),
                IdCheckboxFilter("Harem", 40),
                IdCheckboxFilter("Historical", 14),
                IdCheckboxFilter("Horror", 15),
                IdCheckboxFilter("Isekai", 16),
                IdCheckboxFilter("Magical Girls", 17),
                IdCheckboxFilter("Mecha", 18),
                IdCheckboxFilter("Medical", 19),
                IdCheckboxFilter("Mystery", 20),
                IdCheckboxFilter("Philosophical", 21),
                IdCheckboxFilter("Psychological", 22),
                IdCheckboxFilter("Romance", 23),
                IdCheckboxFilter("Sci-Fi", 24),
                IdCheckboxFilter("Slice of Life", 25),
                IdCheckboxFilter("Sports", 26),
                IdCheckboxFilter("Superhero", 27),
                IdCheckboxFilter("Thriller", 28),
                IdCheckboxFilter("Tragedy", 29),
                IdCheckboxFilter("Wuxia", 30),
                IdCheckboxFilter("Adult", 87264),
                IdCheckboxFilter("Ecchi", 87265),
                IdCheckboxFilter("Hentai", 87266),
                IdCheckboxFilter("Mature", 87267),
                IdCheckboxFilter("Smut", 87268),
            ),
        )

    private class ContentRatingFilter :
        Filter.Group<CheckboxFilter>(
            "Content rating",
            listOf(
                CheckboxFilter("Safe", "safe", true),
                CheckboxFilter("Suggestive", "suggestive", true),
                CheckboxFilter("Erotica", "erotica"),
                CheckboxFilter("Pornographic", "pornographic"),
            ),
        )

    private class MinChaptersFilter : Filter.Text("Min chapters", "")

    private class YearFromFilter : Filter.Text("Year from", "")

    private class YearToFilter : Filter.Text("Year to", "")

    private class CheckboxFilter(name: String, val value: String, default: Boolean = false) : Filter.CheckBox(name, default)

    private class IdCheckboxFilter(name: String, val id: Int, default: Boolean = false) : Filter.CheckBox(name, default)

    // ========================================================================
    // Sign + Decrypt (reverse-engineered API protection)
    // ========================================================================

    /**
     * v46: comix.to's origin answers flaky 502/503/522/523 (and intermittent
     * 404s on image paths) behind Cloudflare — the site's own SPA retried
     * them transparently, which is exactly why "the site works" while
     * single-shot extension calls died with "HTTP 5xx". Port of the official
     * extension's proceedWithRetry (Sep 25): up to 10 attempts, 1.5 s apart,
     * each with an r= cache-buster so a poisoned CDN/edge entry can't serve
     * the same failure twice. The origin excludes r from signature
     * validation (the official extension adds r AFTER signing too).
     *
     * Managed challenges are skipped here — they flow up to the solver.
     *
     * After the retry budget: walk the image CDN's scramble-path variants
     * (/hi/, /fcf/, /i5/, ...) the official extension maintains for image
     * URLs that 404 on their current path segment.
     */
    private fun retryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = proceedWithRetry(chain, request)
        if (response.isSuccessful) return response

        val url = request.url.toString()
        val fallbacks = listOf("/hi/", "/fcf/", "/i5/", "/si/", "/i/", "/sii/", "/ii/")
            .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
            .filter { it != url }

        if (fallbacks.isEmpty()) return response

        for (fallbackUrl in fallbacks) {
            response.close()
            response = proceedWithRetry(chain, request.newBuilder().url(fallbackUrl).build())
            if (response.isSuccessful) break
        }
        return response
    }

    private fun proceedWithRetry(chain: Interceptor.Chain, request: Request): Response {
        var response = chain.proceed(request)
        if (response.isSuccessful) return response

        // A managed challenge must reach the solver above untouched — retry
        // rounds against "Just a moment…" pages are pure dead air.
        if (response.isCloudflareChallenge()) return response

        for (attempt in 1..10) {
            if (response.code !in SERVER_ERROR_CODES && response.code != 404) break

            response.close()
            runCatching { Thread.sleep(1500) }

            val retryUrl = request.url.newBuilder()
                .setQueryParameter("r", attempt.toString())
                .build()
            response = chain.proceed(request.newBuilder().url(retryUrl).build())
            if (response.isSuccessful) break
            if (response.isCloudflareChallenge()) return response
        }

        return response
    }

    private fun signRequestInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)

        val path = request.url.encodedPath
        // Only sign the protected endpoints
        if (!path.startsWith("/api/v1/manga") && !path.matches(Regex("^/api/v1/chapters/[^/]+$"))) {
            return chain.proceed(request)
        }

        // Build the "normalized" path+query that the server expects for signing:
        //  - strip the /api/v1 prefix
        //  - strip the existing _ param (if any) and the retry cache-buster r
        //    (v46: the origin validates signatures WITHOUT those two — the
        //    official extension adds r after signing as well)
        //  - serialize remaining params as raw "key=value" with sorted keys, arrays as key[0], key[1]...
        val normalizedPath = path.removePrefix("/api/v1")
        val paramsToSign = request.url.queryParameterNames
            .filter { it != "_" && it != "r" }
            .sorted()

        val queryParts = mutableListOf<String>()
        for (name in paramsToSign) {
            val values = request.url.queryParameterValues(name)
            if (values.size == 1 && !name.endsWith("[]")) {
                queryParts.add("$name=${values[0]}")
            } else {
                values.forEachIndexed { i, v ->
                    val baseName = name.removeSuffix("[]")
                    queryParts.add("$baseName[$i]=$v")
                }
            }
        }

        val toSign = if (queryParts.isEmpty()) {
            normalizedPath
        } else {
            "$normalizedPath?${queryParts.joinToString("&")}"
        }

        val signature = sign(toSign)

        val newUrl = request.url.newBuilder()
            .removeAllQueryParameters("_")
            .addQueryParameter("_", signature)
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }

    /**
     * Intercepts responses: decrypts `x-enc: 1` bodies, then unwraps the
     * `{"status":"ok","result":...}` envelope so parseAs gets the inner data.
     */
    private fun decryptResponseInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Only process API responses — never touch image downloads
        val path = response.request.url.encodedPath
        if (!path.startsWith("/api/v1/")) return response

        val body = response.body
        var content = body.string()

        // Step 1: Decrypt if x-enc: 1
        if (response.headers["x-enc"] == "1") {
            content = try {
                val encrypted = content.parseAs<ComixEncryptedDto>()
                decrypt(encrypted.e)
            } catch (_: Exception) {
                content
            }
        }

        // Step 2: Unwrap {"status":"ok","result":...} envelope
        content = try {
            val json = org.json.JSONObject(content)
            if (json.optString("status") == "ok" && json.has("result")) {
                json.get("result").toString()
            } else {
                content
            }
        } catch (_: Exception) {
            content
        }

        return response.newBuilder()
            .body(content.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Descrambles images with x-scramble-* headers (5x5 grid) and de-XORs
     * byte-encrypted pages carrying x-enc-seed/x-enc-len/x-enc-algo (v46 —
     * the official extension handles both layers; the byte layer was added
     * in late Sept when the site started XOR-encoding page bodies).
     * Grid: xorshift(13,17,5) / LCG + Fisher-Yates with inverse permutation.
     * XOR: LCG keystream (or xorshift candidates when algo=2), top byte.
     */
    private fun descrambleImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val rawScrambleSeed = response.header("x-scramble-seed")
        val rawScrambleGrid = response.header("x-scramble-grid")
        val rawScrambleAlgo = response.header("x-scramble-algo")
        val rawScrambleHash = response.header("x-scramble-hash")
        val rawEncSeed = response.header("x-enc-seed")
        val rawEncAlgo = response.header("x-enc-algo")
        val encLen = response.header("x-enc-len")?.toIntOrNull()

        val scrambleSeed = rawScrambleSeed?.toLongOrNull()?.toInt()
        val scrambleHash = when (rawScrambleHash?.trim()) {
            "03632" -> 58414
            "02900" -> 117532
            else -> 0
        }
        val encSeed = rawEncSeed?.toLongOrNull()?.toInt()

        val needsXor = encSeed != null && encSeed != 0 && encLen != null
        val shouldDescrambleGrid = rawScrambleGrid == "5x5" &&
            (rawScrambleAlgo == null || rawScrambleAlgo == "1" || rawScrambleAlgo == "2" || rawScrambleAlgo == "3") &&
            scrambleSeed != null && scrambleSeed != 0

        if (!needsXor && !shouldDescrambleGrid) return response

        val bodyMediaType = response.body.contentType()
        val imageBytes = response.body.bytes()
        val bytes = if (needsXor) decodeEncryptedBytes(imageBytes, encSeed!!, encLen!!, rawEncAlgo) else imageBytes

        if (shouldDescrambleGrid) {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // A scrambled page whose body doesn't decode is a transient bad fetch
            // (CDN hiccup / interrupted stream). Handing the undecodable bytes to
            // the reader is the "page only loads after refreshing twice" bug: the
            // broken response counts as delivered, so Mihon shows a blank/error
            // page until the user re-requests it manually. Retry the same request
            // a couple of times right here instead — a fresh download decodes and
            // the reader never sees the bad bytes.
            var decoded: android.graphics.Bitmap? = bitmap
            var currentResponse = response
            var retries = 0
            while (decoded == null && retries < 2) {
                retries++
                currentResponse.close()
                currentResponse = chain.proceed(request)
                if (!currentResponse.isSuccessful) return currentResponse
                val retryBytes = currentResponse.body.bytes()
                val retryDecoded = if (needsXor) {
                    decodeEncryptedBytes(retryBytes, encSeed!!, encLen!!, rawEncAlgo)
                } else {
                    retryBytes
                }
                decoded = android.graphics.BitmapFactory.decodeByteArray(retryDecoded, 0, retryDecoded.size)
            }

            if (decoded == null) {
                currentResponse.close()
                throw IOException("Comix page image didn't download correctly (tried ${retries + 1} times) — tap the page to retry")
            }

            return finishDescramble(currentResponse, decoded, rawScrambleAlgo, scrambleSeed!!, scrambleHash)
        }

        // XOR-only page: re-encode the clean bytes as JPEG (mirrors upstream);
        // if they still don't decode, hand the raw bytes back untouched.
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) {
            val output = Buffer()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output.outputStream())
            bitmap.recycle()

            return response.newBuilder()
                .removeHeader("Content-Encoding")
                .header("Content-Type", "image/jpeg".toMediaType().toString())
                .header("Content-Length", output.size.toString())
                .body(output.asResponseBody("image/jpeg".toMediaType(), output.size))
                .build()
        }

        return response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .removeHeader("Content-Type")
            .body(bytes.toResponseBody(bodyMediaType))
            .build()
    }

    /** Byte-XOR layer: algo 2 = xorshift candidates with image-signature picking; default LCG. */
    private fun decodeEncryptedBytes(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo == "2") {
            val candidates = listOf(
                decodeWithXorshiftBytes(bytes, seed or 1, length, false),
                decodeWithXorshiftBytes(bytes, seed, length, false),
                decodeWithXorshiftBytes(bytes, seed or 1, length, true),
                decodeWithLcgBytes(bytes, seed, length),
            )
            return candidates.firstOrNull { it.hasImageSignature() } ?: candidates.first()
        }
        return decodeWithLcgBytes(bytes, seed, length)
    }

    private fun decodeWithXorshiftBytes(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = initialState
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[i] = (result[i].toInt() xor key).toByte()
        }
        return result
    }

    private fun decodeWithLcgBytes(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state * ENC_MULTIPLIER + ENC_INCREMENT
            result[i] = (result[i].toInt() xor (state ushr 24)).toByte()
        }
        return result
    }

    private fun ByteArray.hasImageSignature(): Boolean = size >= 12 && (
        (
            this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() &&
                this[3] == 'F'.code.toByte() && this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
                this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
            ) ||
            (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) ||
            (
                this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() &&
                    this[3] == 'G'.code.toByte()
                )
        )

    /**
     * Descrambles [bitmap] (from [imageBytes] of [response]) using the
     * xorshift(13,17,5) / LCG + Fisher-Yates inverse permutation and returns
     * the rewritten response.
     */
    private fun finishDescramble(
        response: Response,
        bitmap: android.graphics.Bitmap,
        rawScrambleAlgo: String?,
        scrambleSeed: Int,
        scrambleHash: Int,
    ): Response {
        val cols = 5
        val rows = 5
        val numTiles = cols * rows
        val tileW = bitmap.width / cols
        val tileH = bitmap.height / rows

        val seed = scrambleSeed xor scrambleHash
        val order = if (rawScrambleAlgo == "3") buildOrderXorshift(seed, numTiles) else buildOrderLcg(seed, numTiles)

        val output = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        for (dstIdx in 0 until numTiles) {
            val srcIdx = order[dstIdx]
            val srcCol = srcIdx % cols
            val srcRow = srcIdx / cols
            val dstCol = dstIdx % cols
            val dstRow = dstIdx / cols
            val srcRect = android.graphics.Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = android.graphics.Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        }

        bitmap.recycle()

        val jpegMedia = "image/jpeg".toMediaType()
        val buffer = Buffer()
        output.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())
        output.recycle()

        return response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Type")
            .body(buffer.asResponseBody(jpegMedia, buffer.size))
            .build()
    }

    private fun buildOrderXorshift(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed or 1
        for (i in n - 1 downTo 1) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return IntArray(n).also { inverse ->
            for (i in arr.indices) {
                inverse[arr[i]] = i
            }
        }
    }

    private fun buildOrderLcg(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed
        for (i in n - 1 downTo 1) {
            state = state * 1664525 + 1013904223
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return IntArray(n).also { inverse ->
            for (i in arr.indices) {
                inverse[arr[i]] = i
            }
        }
    }

    // --- Cipher material ---------------------------------------------------
    // v46: the S-box/key material is no longer frozen at the constants we
    // extracted at recon time. The site ROTATES this material — a rotation
    // used to invalidate every native signature until a new extension release
    // shipped. The WebView fallback now recaptures the live material (atob
    // hook, see WebViewFallback.kt) and swaps it in here, so the fast native
    // path self-heals after one fallback run. Defaults = the recon constants.

    @Volatile
    private var cipherMaterial: ComixCipherMaterial = ComixCipherMaterial.fromDefaults()

    @Synchronized
    private fun applyCipherMaterial(material: WebViewCipherMaterial) {
        if (!material.isValid()) return
        runCatching {
            cipherMaterial = ComixCipherMaterial(
                sboxes = material.sboxes.map { list -> ByteArray(256) { i -> (list[i] and 0xFF).toByte() } }.toTypedArray(),
                keys = material.keys.map { list -> ByteArray(list.size) { i -> (list[i] and 0xFF).toByte() } }.toTypedArray(),
            )
        }
        CloudflareSolverDiagnostics.record("COMIX cipher material refreshed from site bundle")
    }

    /** One substitution round's material + its precomputed inverse. */
    private class ComixCipherMaterial(
        val sboxes: Array<ByteArray>,
        val keys: Array<ByteArray>,
    ) {
        val inverseSboxes: Array<IntArray> = Array(3) { round ->
            IntArray(256).also { inv ->
                sboxes[round].forEachIndexed { i, v -> inv[v.toInt() and 0xFF] = i }
            }
        }

        companion object {
            fun fromDefaults(): ComixCipherMaterial = ComixCipherMaterial(
                sboxes = arrayOf(
                    Base64.decode(SBOX1_B64, Base64.DEFAULT),
                    Base64.decode(SBOX2_B64, Base64.DEFAULT),
                    Base64.decode(SBOX3_B64, Base64.DEFAULT),
                ),
                keys = arrayOf(
                    Base64.decode(KEY1_B64, Base64.DEFAULT),
                    Base64.decode(KEY2_B64, Base64.DEFAULT),
                    Base64.decode(KEY3_B64, Base64.DEFAULT),
                ),
            )
        }
    }

    private fun sboxTransform(data: ByteArray, sbox: ByteArray, key: ByteArray, seed: Int): ByteArray {
        val out = ByteArray(data.size)
        var u = seed
        for (a in data.indices) {
            val f = sbox[255 and (data[a].toInt() and 0xFF xor (key[a % key.size].toInt() and 0xFF) xor u)]
            out[a] = f
            u = f.toInt() and 0xFF
        }
        return out
    }

    private fun invSboxTransform(data: ByteArray, invSbox: IntArray, key: ByteArray, seed: Int): ByteArray {
        val out = ByteArray(data.size)
        var u = seed
        for (a in data.indices) {
            val orig = invSbox[data[a].toInt() and 0xFF] xor (key[a % key.size].toInt() and 0xFF) xor u
            out[a] = orig.toByte()
            u = data[a].toInt() and 0xFF
        }
        return out
    }

    private fun sign(input: String): String {
        val material = cipherMaterial
        var bytes = input.toByteArray(Charsets.UTF_8)
        bytes = sboxTransform(bytes, material.sboxes[0], material.keys[0], 189)
        bytes = sboxTransform(bytes, material.sboxes[1], material.keys[1], 133)
        bytes = sboxTransform(bytes, material.sboxes[2], material.keys[2], 32)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun decrypt(eField: String): String {
        val material = cipherMaterial
        val raw = Base64.decode(eField, Base64.URL_SAFE)
        var t = raw
        // Reverse order: undo stage 3, then 2, then 1
        t = invSboxTransform(t, material.inverseSboxes[2], material.keys[2], 32)
        t = invSboxTransform(t, material.inverseSboxes[1], material.keys[1], 133)
        t = invSboxTransform(t, material.inverseSboxes[0], material.keys[0], 189)
        return String(t, Charsets.UTF_8)
    }

    // ========================================================================
    // DTO -> SManga / SChapter conversions
    // ========================================================================

    private fun ComixMangaListItemDto.toSManga(): SManga = SManga.create().apply {
        url = hid
        title = this@toSManga.title
        thumbnail_url = poster?.large ?: poster?.medium
    }

    private fun ComixMangaDetailDto.toSManga(): SManga {
        val showAltNames = preferences.showAltNames()
        val showExtraInfo = preferences.showExtraInfo()
        val showTagsInGenre = preferences.showTagsInGenre()
        val blockedGenres = preferences.getBlockedGenres()
        val scorePosition = preferences.getScorePosition()

        // Build genre chips
        val genreChips = buildList {
            if (showTagsInGenre) {
                addAll(genres.map { it.title })
                addAll(demographics.map { it.title })
                addAll(formats.map { it.title })
                addAll(tags.map { it.title })
            } else {
                addAll(genres.map { it.title })
                addAll(demographics.map { it.title })
            }
        }.distinct()
            .filterNot { it.lowercase() in blockedGenres }
            .joinToString(", ")

        // Build score stars
        val hasScore = ratedAvg != null && ratedCount != null && ratedCount > 0
        val stars = if (hasScore) {
            val score = ratedAvg
            val fullStars = score.div(2).toInt().coerceIn(0, 5)
            "★".repeat(fullStars) + "☆".repeat(5 - fullStars) + " $score"
        } else {
            null
        }

        // Build info line (bold labels)
        val infoLine = if (showExtraInfo) {
            buildString {
                if (year != null) append("**Year:** $year")
                if (latestChapter != null && latestChapter > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Chapters:** ${latestChapter.toString().removeSuffix(".0")}")
                }
                if (followsTotal != null && followsTotal > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("**Tracked:** $followsTotal")
                }
                if (contentRating != null) {
                    if (isNotEmpty()) append(" · ")
                    append("**Content Rating:** ${formatContentRating(contentRating)}")
                }
                if (hasScore) {
                    if (isNotEmpty()) append(" · ")
                    append("**$ratedCount ratings**")
                }
            }.ifBlank { null }
        } else {
            null
        }

        // Build description
        val desc = buildString {
            // Score at top
            if (scorePosition == "top" && stars != null) {
                append(stars)
                append("\n")
                if (infoLine != null) {
                    append(infoLine)
                    append("\n\n")
                }
            }

            synopsis?.let { append(it) }

            if (showAltNames && altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }

            // Score at end
            if (scorePosition == "end" && stars != null) {
                if (isNotEmpty()) append("\n\n")
                append(stars)
                if (infoLine != null) {
                    append("\n")
                    append(infoLine)
                }
            }

            // No score
            if (scorePosition == "none" && infoLine != null) {
                if (isNotEmpty()) append("\n\n")
                append(infoLine)
            }
        }.trim()

        return SManga.create().apply {
            url = hid
            title = this@toSManga.title
            author = authors.joinToString(", ") { it.title }.ifBlank { null }
            artist = artists.joinToString(", ") { it.title }.ifBlank { null }
            genre = genreChips.ifBlank { null }
            description = desc.ifBlank { synopsis }
            status = when (this@toSManga.status) {
                "releasing" -> SManga.ONGOING
                "finished" -> SManga.COMPLETED
                "cancelled" -> SManga.CANCELLED
                "on_hiatus" -> SManga.ON_HIATUS
                "discontinued" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = poster?.large ?: poster?.medium
            initialized = true
        }
    }

    private fun formatStatus(status: String?): String = when (status) {
        "releasing" -> "Releasing"
        "finished" -> "Finished"
        "on_hiatus" -> "On hiatus"
        "discontinued" -> "Discontinued"
        "cancelled" -> "Cancelled"
        else -> "Unknown"
    }

    private fun formatContentRating(rating: String?): String = when (rating) {
        "safe" -> "Safe"
        "suggestive" -> "Suggestive"
        "erotica" -> "Erotica"
        "pornographic" -> "Pornographic"
        else -> "Unknown"
    }

    private fun formatLanguage(lang: String?): String = when (lang) {
        "ko" -> "Korean"
        "ja" -> "Japanese"
        "zh" -> "Chinese"
        "en" -> "English"
        else -> lang ?: "Unknown"
    }

    private fun ComixChapterDto.toSChapter(): SChapter {
        val chNum = number
        val chName = name
        return SChapter.create().apply {
            // Store the numeric chapter ID as the URL (used for pageListRequest)
            url = id.toString()
            name = buildString {
                if (chNum != null && chNum > 0) {
                    append("Ch. ")
                    append(chNum.toString().removeSuffix(".0"))
                }
                if (!chName.isNullOrBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(chName)
                }
                if (isEmpty()) append("Chapter ${chNum ?: id}")
            }
            chapter_number = chNum ?: -1f
            date_upload = parseRelativeDate(createdAtFormatted)
            scanlator = group?.name
        }
    }

    /**
     * Parses relative date strings like "4d ago", "1mo ago", "39s ago", "9mos ago".
     * Returns an approximate epoch-millis timestamp.
     */
    private fun parseRelativeDate(relative: String?): Long {
        if (relative.isNullOrBlank()) return 0L
        val now = System.currentTimeMillis()
        val regex = Regex("""(\d+)\s*(s|sec|m|min|h|hr|d|day|w|wk|mo|mos|y|yr)s?\s*ago""")
        val match = regex.find(relative) ?: return 0L
        val (numStr, unit) = match.destructured
        val num = numStr.toLongOrNull() ?: return 0L
        val millis = when (unit) {
            "s", "sec" -> num * 1000
            "m", "min" -> num * 60 * 1000
            "h", "hr" -> num * 60 * 60 * 1000
            "d", "day" -> num * 24 * 60 * 60 * 1000
            "w", "wk" -> num * 7 * 24 * 60 * 60 * 1000
            "mo", "mos" -> num * 30 * 24 * 60 * 60 * 1000
            "y", "yr" -> num * 365 * 24 * 60 * 60 * 1000
            else -> 0
        }
        return now - millis
    }

    private inline fun <reified T : Filter<*>> FilterList.firstInstance(): T? = filterIsInstance<T>().firstOrNull()

    // ========================================================================
    // Settings / Preferences
    // ========================================================================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Content rating
        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Default content rating"
            summary = "Content ratings to show by default in browse/search"
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf("safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(setOf("safe", "suggestive"))
        }.let(screen::addPreference)

        // Default type
        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_TYPES
            title = "Default type filter"
            summary = "Manga types to show by default (empty = all)"
            entries = arrayOf("Manga", "Manhwa", "Manhua", "Other")
            entryValues = arrayOf("manga", "manhwa", "manhua", "other")
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        // Default demographics
        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_DEMOGRAPHICS
            title = "Default demographic filter"
            summary = "Demographics to show by default (empty = all)"
            entries = arrayOf("Shounen", "Seinen", "Shoujo", "Josei")
            entryValues = arrayOf("shounen", "seinen", "shoujo", "josei")
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        // Blocked genres
        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from genre chips"
            setDefaultValue("")
        }.let(screen::addPreference)

        // Deduplicate chapters
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "Keep only one chapter per number (useful when multiple scanlators upload the same chapter)"
            setDefaultValue(false)
        }.let(screen::addPreference)

        // Scanlator filter
        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_FILTER
            title = "Scanlator filter"
            summary = "Comma-separated scanlator names to show (empty = show all)"
            setDefaultValue("")
        }.let(screen::addPreference)

        // Show alt names
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show extra info
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status, year, content rating, follows, rating, latest chapter"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show tags in genre chips
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRE
            title = "Show tags in genre chips"
            summary = "Include format tags (Long Strip, Full Color, etc.) in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Score display position
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.getDefaultTypes(): List<String> = getStringSet(PREF_DEFAULT_TYPES, emptySet())?.toList() ?: emptyList()

    private fun android.content.SharedPreferences.getDefaultDemographics(): List<String> = getStringSet(PREF_DEFAULT_DEMOGRAPHICS, emptySet())?.toList() ?: emptyList()

    private fun android.content.SharedPreferences.getContentRatings(): List<String> = getStringSet(PREF_CONTENT_RATING, setOf("safe", "suggestive"))?.toList() ?: listOf("safe", "suggestive")

    private fun android.content.SharedPreferences.getBlockedGenres(): List<String> = getString(PREF_BLOCKED_GENRES, "")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun android.content.SharedPreferences.deduplicateChapters(): Boolean = getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)

    private fun android.content.SharedPreferences.getScanlatorFilter(): String = getString(PREF_SCANLATOR_FILTER, "") ?: ""

    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)

    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun android.content.SharedPreferences.showTagsInGenre(): Boolean = getBoolean(PREF_SHOW_TAGS_IN_GENRE, true)

    private fun android.content.SharedPreferences.getScorePosition(): String = getString(PREF_SCORE_POSITION, "end") ?: "end"

    companion object {

        // v46: origin flakiness + image CDN path variants + byte-XOR keystream
        // constants (mirror of the official extension's handling).
        private val SERVER_ERROR_CODES = setOf(502, 503, 522, 523)
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("/(?:i5|s?i+)/")
        private const val ENC_MULTIPLIER = 1000005
        private const val ENC_INCREMENT = 1234567891

        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_DEFAULT_TYPES = "pref_default_types"
        private const val PREF_DEFAULT_DEMOGRAPHICS = "pref_default_demographics"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SCANLATOR_FILTER = "pref_scanlator_filter"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRE = "pref_show_tags_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"

        private const val SBOX1_B64 = "gbicCvAMzfcXEtGAyjvvhmb2yCWzWhjqcxXZ7ZhpzANOzoQLo3nuPZ2vK9dkb9hJExC0Vni/hdQBceI+mw611gkhQFjBuf4bJg1TxYqM+SL4YDqtwjxiGSdeH7so7Fn1HiRo37Z+RNvl44twXWVhomtMjw+8bemfmv9XEXr7mS82MxaCOJZRR0oHd9PLI5O+gyBGT6hcLoduNa7yCObVVCk3bFWsoD+xcqTrBcP6dNJN/NB1Br2QGhSN2snHAqeRNKVFQiyeAFLPSKGwY8aq9EPgsi17qd4ywPMxiH8w6N1qX1tLKtzhOeemHWeJQfFQ5H23q7qSlJUcjgTEl3x2/Q=="
        private const val KEY1_B64 = "rafYl4oSAKQX+GYoic9oW4iGwiYpZzs0"
        private const val SBOX2_B64 = "2lQehmgyYFAoWUi0haazZqHy5zZ34NN+VzlfsoB2Y1yY0IuMLjgVcV2xt8t4moH+AP0NMJ5qekW7DFIHEWKkOgIBIMhDdA8lbM6iHKjDlq6IChpb3CnA9NmsvQW/afdt1SfJjTdwcvpKqunCJLxBFmXX9hecm6tGb+HRxD7BC3njoxPxgnX5pdKP1IMSkd4/O3NRfZSE6DVLG2s9uexaipA05cpJzE8Qkv/z5jzHAwlEWOLd3yxA+0cvVbpOoJPFGc8f1lb4vu2HUxjuuEwEQk0GsPCVnyKvfOoh9TG2YYmZLV4I67UU2NsrrakqZ47k/O+ne25/DjPGZCMdnZcmzQ=="
        private const val KEY2_B64 = "2USAq+VTo5ht4bQn+K9DUcpUQRTtrB56"
        private const val SBOX3_B64 = "+mhJSFwzaV+PQPDyKp2scO/S9SdFsy/7e56UWT8XHbK3E2+19nEPwfwOgE9uVCaDtOAWTobCZX+cBCXlIbBqyDyQB1beKLspW6kGPhBCV9x0jf0KUeFhHjmlMf7qMFIB41PfDFprZ3bJiK4YxrZDv+K6dcwJmggVO8f5ktrXTM0cZL4fer0SpnkbvNajPbHxfuTz5lVEBarOI4rdc+2V6zTsjpfQYjgN1MMr6EvA6eehN6dQ1bgUogt9rZOBbQBeNnLYY00uZqSoJBnFi5gthCJsWF33ykosn9v/9KB8udMCz0YRYImrA4VHr5mMgpH4xDXLeEHRd5vZOiAalofuMg=="
        private const val KEY3_B64 = "yNHlokVEnuecesDrB/lDhVuUNiheWc3a47VtkwZ2ENg="
    }
}
