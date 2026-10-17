import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// v75: one source per content language — language switching happens in
// Mihon's own UI (Browse → Languages → pick a language → MangaBall), exactly
// like MangaDot. Each generated source strictly filters chapters to its
// language. The list is the site's own ground truth (2026-10-17): chapter
// rows are coded es (never es-419), cn (never zh/zh-Hant — see the zh→cn
// mapping in MangaBall.kt) and ko/kr; ja has no rows at all. Dead codes
// es-419, ja and zh-Hant are dropped.
//
// NOTE for the maintainer: source ids are derived from name/lang/versionId,
// so switching from the old single "all" source to per-language sources gives
// every language its own id — existing library entries point at the old id
// and need to be re-added under the new per-language sources (Mihon's source
// migration can't carry them across a lang change).
val languages = listOf(
    "en", "es", "pt-BR", "fr", "de", "it", "ru", "tr", "ar",
    "id", "th", "vi", "hi", "tl", "ko", "zh", "pl",
    "nl", "uk",
)

keiyoushi {
    name = "MangaBall"
    versionCode = 11
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
