import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// v74: one source per content language — language switching now happens in
// Mihon's own UI (Browse → Languages → pick a language → Swarm), exactly like
// MangaDot. Each generated source strictly filters chapters to its language
// (the same rule the old "Chapter language" setting enforced). The codes are
// the site's own chapter-language set, curated to the languages the
// aggregator actually serves — deliberately NOT every code it has ever
// emitted.
val languages = listOf(
    "en", "fr", "ar", "es", "es-la", "pt-br", "id", "th", "vi", "tr",
    "ru", "de", "it", "ja", "ko", "zh",
)

keiyoushi {
    name = "Swarm"
    versionCode = 6
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
