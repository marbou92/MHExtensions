import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// v74: one source per content language — language switching now happens in
// Mihon's own UI (Browse → Languages → pick a language → MangaBall), exactly
// like MangaDot. Each generated source strictly filters chapters to its
// language. The codes are the site's own translation set, curated to the
// languages the catalog actually serves — deliberately NOT every code it has
// ever emitted.
//
// NOTE for the maintainer: source ids are derived from name/lang/versionId,
// so switching from the old single "all" source to per-language sources gives
// every language its own id — existing library entries point at the old id
// and need to be re-added under the new per-language sources (Mihon's source
// migration can't carry them across a lang change).
val languages = listOf(
    "en", "es", "es-419", "pt-BR", "fr", "de", "it", "ru", "tr", "ar",
    "id", "th", "vi", "hi", "tl", "ja", "ko", "zh", "zh-Hant", "pl",
    "nl", "uk",
)

keiyoushi {
    name = "MangaBall"
    versionCode = 10
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    languages.forEach { langCode ->
        source {
            lang = langCode
            // The old mangaball.net is gone (301 → .com). The id is derived
            // from name/lang/versionId — NOT baseUrl.
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
