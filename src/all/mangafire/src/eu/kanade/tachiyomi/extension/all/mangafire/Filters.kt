package eu.kanade.tachiyomi.extension.all.mangafire

import eu.kanade.tachiyomi.source.model.Filter

internal class GenreOption(name: String, val value: String) : Filter.TriState(name)

internal class CheckBoxOption(name: String, val value: String) : Filter.CheckBox(name)

internal class TypeFilter :
    Filter.Group<CheckBoxOption>(
        "Type",
        listOf("Manga", "Manhwa", "Manhua", "Other").map { CheckBoxOption(it, it.lowercase().replace(' ', '_')) },
    ) {
    val checked: List<String>
        get() = state.filter { it.state }.map { it.value }
}

internal class StatusFilter :
    Filter.Group<CheckBoxOption>(
        "Status",
        listOf("Releasing", "Finished", "On Hiatus", "Discontinued", "Not Yet Released")
            .map { CheckBoxOption(it, it.lowercase().replace(' ', '_')) },
    ) {
    val checked: List<String>
        get() = state.filter { it.state }.map { it.value }
}

internal class ContentRatingFilter :
    Filter.Group<CheckBoxOption>(
        "Content rating",
        listOf("Safe", "Suggestive", "Erotica", "Pornographic").map { CheckBoxOption(it, it.lowercase()) },
    ) {
    val checked: List<String>
        get() = state.filter { it.state }.map { it.value }
}

internal class GenreFilter :
    Filter.Group<GenreOption>(
        "Genres (tap twice to exclude)",
        GENRES.map { GenreOption(it.second, it.first) },
    ) {
    fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }
    fun excluded(): List<String> = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }

    companion object {
        // Live values from /api/filter-options (id -> name).
        private val GENRES = listOf(
            "1" to "Action",
            "268929" to "Adult",
            "78" to "Adventure",
            "3" to "Avant Garde",
            "4" to "Boys Love",
            "5" to "Comedy",
            "268930" to "Demons",
            "8" to "Drama",
            "268931" to "Ecchi",
            "10" to "Fantasy",
            "268932" to "Girls Love",
            "11" to "Gore",
            "13" to "Hentai",
            "14" to "Horror",
            "7" to "Isekai",
            "44" to "Iyashikei",
            "17" to "Maho Shoujo",
            "18" to "Mecha",
            "20" to "Music",
            "21" to "Mystery",
            "22" to "Psychological",
            "23" to "Romance",
            "24" to "Sci-Fi",
            "26" to "Slice of Life",
            "27" to "Sports",
            "30" to "Supernatural",
            "32" to "Thriller",
        )
    }
}

internal class ThemeOption(name: String, val value: String) : Filter.TriState(name)

internal class ThemeFilter :
    Filter.Group<ThemeOption>(
        "Themes (tap twice to exclude)",
        THEMES.map { ThemeOption(it.second, it.first) },
    ) {
    fun included(): List<String> = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }

    companion object {
        private val THEMES = listOf(
            "268933" to "Aliens",
            "268934" to "Animals",
            "268935" to "Cooking",
            "268936" to "Crossdressing",
            "268937" to "Delinquents",
            "268938" to "Demons",
            "268939" to "Gore",
            "268940" to "Harem",
            "268941" to "Idols (Female)",
            "268957" to "Idols (Male)",
            "268942" to "Isekai",
            "268943" to "Magic",
            "268944" to "Mahou Shoujo",
            "268945" to "Martial Arts",
            "268946" to "Military",
            "268947" to "Music",
            "268948" to "Office Workers",
            "268949" to "Otaku Culture",
            "268950" to "Police",
            "268951" to "Post-Apocalyptic",
            "268952" to "Reincarnation",
            "268953" to "Reverse Harem",
            "268954" to "Samurai",
            "268955" to "School",
            "268959" to "School Life",
            "268958" to "Survival",
            "268960" to "Super Powers",
            "268961" to "Supernatural",
            "268963" to "Time Travel",
            "268964" to "Vampire",
            "268965" to "Video Games",
            "268966" to "Villainess",
            "268967" to "Workplace",
            "268968" to "Zombies",
        )
    }
}

internal class DemographicFilter :
    Filter.Group<CheckBoxOption>(
        "Demographics",
        listOf("Josei", "Seinen", "Shoujo", "Shounen").map { CheckBoxOption(it, it.lowercase()) },
    ) {
    val checked: List<String>
        get() = state.filter { it.state }.map { it.value }
}

internal class MinChapterFilter : Filter.Text("Minimum chapters")

internal class YearFromFilter : Filter.Text("Year from")

internal class YearToFilter : Filter.Text("Year to")

internal class SortFilter(state: Int = 0) :
    Filter.Select<String>(
        "Sort",
        arrayOf(
            "Best match (search only)",
            "Latest update",
            "Recently added",
            "Title (A-Z)",
            "Title (Z-A)",
            "Year (newest)",
            "Year (oldest)",
            "Score",
            "Trending",
            "Views (7 days)",
            "Views (30 days)",
            "Views (all time)",
            "Follows",
        ),
        state,
    ) {
    val selected: String
        get() = when (state) {
            1 -> "chapter_updated_at:desc"
            2 -> "created_at:desc"
            3 -> "title:asc"
            4 -> "title:desc"
            5 -> "year:desc"
            6 -> "year:asc"
            7 -> "score:desc"
            8 -> "trending:desc"
            9 -> "views_7d:desc"
            10 -> "views_30d:desc"
            11 -> "views_total:desc"
            12 -> "follows_total:desc"
            else -> ""
        }
}
