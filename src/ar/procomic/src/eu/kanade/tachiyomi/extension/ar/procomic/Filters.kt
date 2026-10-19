package eu.kanade.tachiyomi.extension.ar.procomic

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

private val sortOptions = listOf(
    "الأحدث" to "latest",
    "الأكثر شعبية" to "popular",
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
    "مانجا (يابانية)" to "manga",
    "مانهوا (كورية)" to "manhwa",
    "مانها (صينية)" to "manhua",
    "روايات" to "novel",
)

/**
 * The search API only applies genre filters reliably when a search term is
 * present (site-side quirk), so the genre filter rides along with the query
 * the source always sends. Values are the English genre names the API
 * expects.
 */
class GenreFilter(genreValues: List<String>) :
    Filter.Group<TriStateFilter>(
        name = "التصنيفات",
        state = genreValues.map { TriStateFilter(it) },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
}

class YearFilter : Filter.Text("السنة")

internal fun getFilterListInternal(genres: List<String>?): FilterList = FilterList(
    SortFilter(),
    TypeFilter(),
    if (genres != null) {
        GenreFilter(genres)
    } else {
        Filter.Header("اضغط «إعادة تعيين» لتحميل التصنيفات")
    },
)
