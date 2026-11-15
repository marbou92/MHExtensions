import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

val languages = listOf(
    "en", "es", "pt-BR", "fr", "de", "it", "ru", "tr", "ar",
    "id", "th", "vi", "hi", "tl", "ko", "zh", "pl",
    "nl", "uk",
)

keiyoushi {
    name = "MangaBall"
    versionCode = 12
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    languages.forEach { langCode ->
        source {
            lang = langCode
            // The old mangaball.net is gone (301 → .com). The id is derived
            // from name/lang/versionId. NOT baseUrl.
            baseUrl = "https://mangaball.com"
        }
    }

    deeplink {
        host("mangaball.com")
        host("mangaball.net")
        path("/manga/..*")
        path("/title-detail/..*")
    }
}
