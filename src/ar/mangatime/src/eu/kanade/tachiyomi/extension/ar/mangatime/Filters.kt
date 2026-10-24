package eu.kanade.tachiyomi.extension.ar.mangatime

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class TriStateFilter(name: String, val value: String = name, state: Int = STATE_IGNORE) : Filter.TriState(name, state)

class SortFilter :
    Filter.Select<String>(
        name = "الترتيب",
        values = sortOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = sortOptions[state].second
}

// searchSeries sortBy enum: relevance | popularity | rating
private val sortOptions = listOf(
    "الأكثر صلة" to "relevance",
    "الأكثر شعبية" to "popularity",
    "الأعلى تقييماً" to "rating",
)

class StatusFilter :
    Filter.Select<String>(
        name = "الحالة",
        values = statusOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = statusOptions[state].second
}

private val statusOptions = listOf(
    "الكل" to "",
    "مستمرة" to "ongoing",
    "مكتملة" to "completed",
    "متوقفة مؤقتاً" to "hiatus",
    "ملغاة" to "cancelled",
)

class TypeFilter :
    Filter.Select<String>(
        name = "النوع",
        values = typeOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = typeOptions[state].second
}

private val typeOptions = listOf(
    "الكل" to "",
    "مانجا" to "manga",
    "مانهوا" to "manhwa",
    "مانهوا صينية" to "manhua",
    "ويب تون" to "webtoon",
    "كوميك" to "comic",
)

/**
 * Genres are fetched from `content.getGenres` (fetchFilterData), curated to
 * the entries that actually tag series (the raw table carries ~450 rows,
 * many scraped junk with zero series). The values carried here are the
 * Arabic slugs the `filters.genres` array expects — ids do NOT filter.
 */
class GenreFilter(genres: List<Pair<String, String>>) :
    Filter.Group<TriStateFilter>(
        name = "التصنيفات",
        state = genres.map { (label, slug) -> TriStateFilter(label, slug) },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
}

internal fun getFilterListInternal(genres: List<Pair<String, String>>?): FilterList = FilterList(
    SortFilter(),
    StatusFilter(),
    TypeFilter(),
    if (genres != null) {
        GenreFilter(genres)
    } else {
        Filter.Header("اضغط «إعادة تعيين» لتحميل التصنيفات")
    },
)
