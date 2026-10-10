import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// One source per content language — language switching happens in Mihon's own
// UI (Browse → Languages → pick a language → MangaFire), exactly like the
// MangaDot extension does for mangadot.net. The site's editions:
// en, fr, es, es-la (Latin America), pt, pt-br, ja.
val languages = listOf(
    "en",
    "fr",
    "es",
    "es-la",
    "pt",
    "pt-br",
    "ja",
)

keiyoushi {
    name = "MangaFire"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    languages.forEach { langCode ->
        source {
            lang = langCode
            baseUrl = "https://mangafire.to"
        }
    }

    deeplink {
        host("mangafire.to")
        path("/title/..*")
        path("/read/..*")
    }
}
