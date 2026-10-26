package eu.kanade.tachiyomi.extension.ar.mangatek

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal val sortOptions = listOf(
    "الأحدث سلاسل" to "latest",
    "الأكثر صلة" to "relevance",
    "الأكثر شعبية" to "popular",
    "الرائج" to "trending",
    "الأعلى تقييماً" to "rating",
    "أبجدي" to "alphabetical",
)

internal val statusOptions = listOf(
    "الكل" to "",
    "مستمرة" to "ongoing",
    "مكتملة" to "completed",
    "متوقفة مؤقتاً" to "hiatus",
    "ملغاة" to "cancelled",
)

internal class SortFilter : Filter.Select<String>("Sort", sortOptions.map { it.first }.toTypedArray()) {
    val selected: String get() = sortOptions[state].second
}

internal class StatusFilter : Filter.Select<String>("الحالة", statusOptions.map { it.first }.toTypedArray()) {
    val selected: String get() = statusOptions[state].second
}

internal class TriStateFilter(name: String, val id: String) : Filter.TriState(name)

internal class GenreFilter(genres: List<Pair<String, String>>) : Filter.Group<TriStateFilter>("التصنيفات", genres.map { (label, id) -> TriStateFilter(label, id) }) {
    val included: List<String>
        get() = state.filter { it.isIncluded() }.map { it.id }
}

internal fun getFilterListInternal(genres: List<Pair<String, String>>?): FilterList = FilterList(
    SortFilter(),
    StatusFilter(),
    if (genres != null && genres.isNotEmpty()) {
        GenreFilter(genres)
    } else {
        Filter.Header("اضغط «إعادة تعيين» لتحميل التصنيفات")
    },
)
