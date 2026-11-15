import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

val languages = listOf(
    "en", "fr", "ar", "es", "es-la", "pt-br", "id", "th", "vi", "tr",
    "ru", "de", "it", "zh", "pl",
)

keiyoushi {
    name = "Swarm"
    versionCode = 8
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
