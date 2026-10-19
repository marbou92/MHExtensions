package eu.kanade.tachiyomi.extension.ar.mangatek

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class TriStateFilter(name: String, val value: String = name, state: Int = STATE_IGNORE) : Filter.TriState(name, state)

class SortFilter :
    Filter.Select<String>(
        name = "Sort",
        values = sortOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = sortOptions[state].second
}

private val sortOptions = listOf(
    "الأحدث سلاسل" to "latest",
    "الأكثر صلة" to "relevance",
    "الأكثر شعبية" to "popular",
    "الرائج" to "trending",
    "الأعلى تقييماً" to "rating",
    "أبجدي" to "alphabetical",
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

/**
 * Genres are fetched from `/api/catalog/genres` (fetchFilterData); the values
 * carried here are the gateway genre IDs (`wpgen_...`) that `includeGenres`
 * expects, while the labels shown to the user are the Arabic names.
 */
class GenreFilter(genres: List<Pair<String, String>>) :
    Filter.Group<TriStateFilter>(
        name = "التصنيفات",
        state = genres.map { (label, id) -> TriStateFilter(label, id) },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
}

internal fun getFilterListInternal(genres: List<Pair<String, String>>?): FilterList = FilterList(
    SortFilter(),
    StatusFilter(),
    if (genres != null) {
        GenreFilter(genres)
    } else {
        Filter.Header("اضغط «إعادة تعيين» لتحميل التصنيفات")
    },
)
