package eu.kanade.tachiyomi.extension.ar.procomic

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * ProComic's browse filters (reconstructed for v1.6.56 — behavior-faithful
 * to the shipped v1.6.55 build). The search endpoint takes
 * `sort` / `type` / `year` / `includeGenres`; the values below were
 * live-probed against /api/public/series/search (all answer 200).
 */
class SortFilter :
    Filter.Select<String>(
        "الترتيب",
        SORT_ENTRIES.map { it.first }.toTypedArray(),
    ) {
    /** The API value for the currently selected entry ("latest" default). */
    val selected: String
        get() = SORT_ENTRIES[state].second
}

private val SORT_ENTRIES = listOf(
    "الأحدث" to "latest",
    "الأكثر شعبية" to "popular",
    "الأعلى تقييماً" to "rating",
    "الرائج الآن" to "trending",
    "الأكثر مشاهدة" to "views",
    "الأفضل" to "top",
    "النقاط" to "score",
    "الجديد" to "newest",
    "أبجدياً" to "title",
)

class TypeFilter :
    Filter.Select<String>(
        "النوع",
        TYPE_ENTRIES.map { it.first }.toTypedArray(),
    ) {
    /** The API value for the currently selected entry ("" = any). */
    val selected: String
        get() = TYPE_ENTRIES[state].second
}

private val TYPE_ENTRIES = listOf(
    "الكل" to "",
    "مانجا" to "manga",
    "مانهوا" to "manhwa",
    "مانهوا صينية" to "manhua",
)

class GenreFilter(genres: List<String>?) :
    Filter.Group<GenreFilter.Entry>(
        "التصنيفات",
        (genres ?: emptyList()).map { Entry(it) },
    ) {
    class Entry(name: String) : Filter.CheckBox(name)

    /** The checked genre names (comma-joined by the caller). */
    val included: List<String>
        get() = state.filter { it.state }.map { it.name }
}

class YearFilter : Filter.Text("السنة")

/** The browse filter list; the genre entries come from the cached filter
 * data (empty group until the first successful fetch). */
internal fun getFilterListInternal(genres: List<String>?): FilterList = FilterList(
    SortFilter(),
    TypeFilter(),
    GenreFilter(genres),
    YearFilter(),
)
