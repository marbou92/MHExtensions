package eu.kanade.tachiyomi.extension.ar.mangaswat

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.Locale
import kotlin.math.roundToInt

/**
 * MangaSwat (meshmanga.com) — custom Next.js site over the JSON v2 API,
 * Arabic (مانجا سواط).
 *
 * Beyond the stock parse, this source ships the family's Comix/MangaDot
 * details block — the same options ProComic, MangaTime and Kawaiimanga carry:
 *
 *   - an organized description: the API's 0..5 `rating` rendered as the
 *     familiar ★★★★☆ row, a Type/Status/extra-info line, the synopsis, then
 *     Genres / Links / Alternative Names sections
 *   - "Show extra info" / "Blocked genres" / "Score display position"
 *     preferences driving that description
 *   - "Combine duplicate chapters" folding repeated chapter numbers into one
 *     entry (newest upload wins)
 */
@Source
abstract class MangaSwat :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(1)
    }

    private val apiUrl get() = "$baseUrl/v2/api/v2"

    private fun String.getMangaId(): String = this.removePrefix("/chapters/").substringBefore("/")

    private fun Response.parseMangaList(): MangasPage {
        val data = this.parseAs<MangaListDto>()
        val entries = data.results.map { it.toSManga() }
        return MangasPage(entries, data.hasNext())
    }

    // ========================= Popular =========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$apiUrl/series/?order_by=-followers_count&page=$page")

        return response.parseMangaList()
    }

    // ========================= Latest =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$apiUrl/series/releases/?page=$page")

        return response.parseMangaList()
    }

    //  ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/series/".toHttpUrl().newBuilder().apply {
            addQueryParameter("search", query)
            addQueryParameter("page", page.toString())
        }.build()

        return client.get(url).parseMangaList()
    }

    // ==================== Details & Chapters ====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaDeferred = async {
            if (!fetchDetails) return@async manga

            client.get("$apiUrl/series/${manga.url}/")
                .parseAs<MangaDetailsDto>()
                .let { dto ->
                    dto.toSManga().apply {
                        genre = genreField(dto.genres.map { it.name })
                        description = buildDescription(
                            rating = dto.rating?.toDoubleOrNull(),
                            ratingCount = dto.ratingsCount ?: 0,
                            type = dto.type?.name,
                            statusText = dto.status?.name?.toStatusLabel(),
                            chapterCount = dto.chaptersCount,
                            viewCount = dto.viewsCount,
                            synopsis = dto.story,
                            genres = dto.genres.map { it.name },
                            altNames = dto.alternative
                                ?.split(',', '،')
                                ?.map(String::trim)
                                ?.filter(String::isNotEmpty)
                                .orEmpty(),
                            sourceUrl = dto.slug?.let { "$baseUrl/series/$it" }.orEmpty(),
                        )
                    }
                }
        }

        val chaptersDeferred = async {
            if (fetchChapters) combineDuplicateChapters(getChapterList(manga)) else chapters
        }

        SMangaUpdate(
            manga = mangaDeferred.await(),
            chapters = chaptersDeferred.await(),
        )
    }

    // ========================= MangaDot description =========================

    private fun String.toStatusLabel(): String? = when (lowercase(Locale.US)) {
        "ongoing" -> "Ongoing"
        "completed" -> "Completed"
        "hiatus" -> "Hiatus"
        "cancelled", "canceled" -> "Cancelled"
        else -> null
    }

    /** Genre chips minus the blocked list. */
    private fun genreField(genres: List<String>): String {
        val blocked = blockedGenres()
        return genres
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .filter { it.lowercase() !in blocked }
            .joinToString(", ")
    }

    private fun blockedGenres(): Set<String> = preferences.getString(PREF_BLOCKED_GENRES, "")
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter(String::isNotBlank)
        ?.toSet()
        ?: emptySet()

    private fun buildDescription(
        rating: Double?,
        ratingCount: Int,
        type: String?,
        statusText: String?,
        chapterCount: Int?,
        viewCount: Long?,
        synopsis: String?,
        genres: List<String>,
        altNames: List<String>,
        sourceUrl: String,
    ): String {
        val showExtraInfo = preferences.getBoolean(PREF_SHOW_EXTRA_INFO, true)
        val scorePosition = preferences.getString(PREF_SCORE_POSITION, "end") ?: "end"

        // The site rates 0..5; the description shows the familiar 5-star row.
        val stars = rating?.let {
            if (it <= 0.0) return@let null
            val normalized = (it / RATING_SCALE) * 5.0
            val full = normalized.roundToInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " " +
                String.format(Locale.US, "%.2f", normalized)
        }

        val infoLine = if (showExtraInfo) {
            buildString {
                type?.takeIf(String::isNotBlank)?.let {
                    append("**Type:** ${it.replaceFirstChar { c -> c.uppercase() }}")
                }
                statusText?.takeIf(String::isNotBlank)?.let { appendSep().append("**Status:** ").append(it) }
                chapterCount?.takeIf { it > 0 }?.let { appendSep().append("**Chapters:** $it") }
                viewCount?.takeIf { it > 0 }?.let { appendSep().append(formatCount(it)) }
                ratingCount.takeIf { it > 0 }?.let { appendSep().append("**$it ratings**") }
            }.toString().ifBlank { null }
        } else {
            null
        }

        return buildString {
            if (scorePosition == "top" && stars != null) {
                append(stars).append("\n")
                if (infoLine != null) {
                    append(infoLine).append("\n\n")
                } else {
                    append("\n\n")
                }
            }

            synopsis?.let {
                append(
                    it.replace("\r\n", "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim(),
                    "\n\n",
                )
            }

            if (scorePosition == "end" && stars != null) {
                append(stars).append("\n")
                if (infoLine != null) {
                    append(infoLine).append("\n\n")
                }
            }

            if (isNotEmpty()) {
                append("---\n\n")
            }

            val genreLabels = genres.map(String::trim).filter(String::isNotEmpty)
            if (genreLabels.isNotEmpty()) {
                append("\n**Genres:**\n")
                genreLabels.forEach { label -> append("- ", label, "\n") }
            }

            if (sourceUrl.isNotBlank()) {
                append("\n**Links:**\n- [Source]($sourceUrl)\n")
            }

            if (altNames.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                altNames.forEach { altTitle -> append("- ", altTitle, "\n") }
            }
        }.trim()
    }

    private fun StringBuilder.appendSep(): StringBuilder = apply {
        if (isNotEmpty()) append(" · ")
    }

    private fun formatCount(count: Long): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM views", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fk views", count / 1_000.0)
        else -> "$count views"
    }

    // ============================= Chapters ==============================

    private suspend fun getChapterList(manga: SManga): List<SChapter> {
        val url = "$apiUrl/chapters/".toHttpUrl().newBuilder().apply {
            addQueryParameter("serie", manga.url)
            addQueryParameter("order_by", "-order")
            addQueryParameter("page_size", "200")
        }.build()

        return buildList {
            var nextPage: String? = url.toString()

            while (nextPage != null) {
                val data = client.get(nextPage).parseAs<ChapterListDto>()
                addAll(data.results.map { it.toSChapter() })
                nextPage = data.next
            }
        }
    }

    /**
     * Optional "combine duplicate chapters" — the same option ProComic and
     * MangaTime ship. When enabled, one entry per non-zero chapter number
     * survives — the newest upload wins (the list is newest-first).
     */
    private fun combineDuplicateChapters(chapters: List<SChapter>): List<SChapter> = if (preferences.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)) {
        mutableListOf<SChapter>().apply {
            for ((_, group) in chapters.groupBy { it.chapter_number }) {
                if (group.first().chapter_number <= 0f || group.size == 1) {
                    addAll(group)
                } else {
                    // group preserves the newest-first input order
                    add(group.first())
                }
            }
        }
    } else {
        chapters
    }

    // ========================= Pages =========================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url.getMangaId()}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$apiUrl/chapters/${chapter.url.getMangaId()}/")

        val chapter = response.parseAs<PageListDto>()
        return chapter.images.mapIndexed { i, page ->
            Page(i, imageUrl = page.image)
        }
    }

    // ========================= Preference screen =========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ---- Description (the Comix-style options) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status, chapter count, views and ratings"
            setDefaultValue(true)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Comma-separated genre names to hide from the genre field"
            setDefaultValue("")
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)

        // ---- Chapter list (the duplicate-combining option) ----
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Combine duplicate chapters"
            summary = "Keep one entry per chapter number — newest upload wins"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private companion object {
        const val RATING_SCALE = 5.0

        const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        const val PREF_SCORE_POSITION = "pref_score_position"
        const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
    }
}
