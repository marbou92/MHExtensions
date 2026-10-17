import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// v75: one source per content language — language switching happens in
// Mihon's own UI (Browse → Languages → pick a language → Swarm), exactly like
// MangaDot. Each generated source strictly filters chapters to its language.
// The list is the site's own chapter-language ground truth, sampled across
// the popular catalog's chapter rows (2026-10-17): en 40/40, id 33, pt-br 33,
// es 31, vi 30, fr 27, es-la 23, zh 20, th 18, pl 16, it 16, ru 9, ar 3.
// ja/ko have ZERO chapter rows on the entire popular sample (Korean rows are
// coded `kr`) — dropped; sparse-but-real ar stays.
val languages = listOf(
    "en", "fr", "ar", "es", "es-la", "pt-br", "id", "th", "vi", "tr",
    "ru", "de", "it", "zh", "pl",
)

keiyoushi {
    name = "Swarm"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    languages.forEach { langCode ->
        source {
            lang = langCode
            baseUrl = "https://swarm.ws"
        }
    }

    deeplink {
        host("swarm.ws")
        path("/comic/..*")
    }
}
