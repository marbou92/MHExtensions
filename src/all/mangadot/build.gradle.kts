import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

// One source per content language — language switching happens in Mihon's own
// UI (Browse → Languages → pick a language → MangaDot), exactly like the
// upstream keiyoushi "mangadotnet" extension does for this site. Chapter and
// volume requests carry the source language via the site's `lang` parameter.
val languages = listOf(
    // Languages the site's catalog covers (mirrors upstream's list)
    "ar", "bn", "bg", "my", "zh", "zh-Hant", "cs", "da", "nl", "en", "tl",
    "fi", "fr", "ka", "de", "el", "he", "hi", "hu", "id", "it", "ja", "ko",
    "la", "lt", "ms", "mn", "no", "fa", "pl", "pt", "pt-BR", "ro", "ru",
    "es", "es-419", "sv", "th", "tr", "uk", "vi",
    "zu", "zh-tw", "yo", "uz", "ur", "tk", "to", "ti", "te", "ta", "tg",
    "ss", "sw", "so", "sl", "sk", "si", "sd", "sn", "st", "sh", "sr", "sm",
    "rm", "ps", "ny", "ne", "mo", "mr", "mi", "mt", "ml", "mg", "mk", "lb",
    "lv", "lo", "ky", "ku", "kk", "kn", "jv", "ga", "ig", "is", "ha", "ht",
    "gu", "gn", "gl", "fo", "et", "eo", "hr", "cv", "ceb", "ca", "km", "bs",
    "be", "eu", "az", "hy", "am", "sq", "af", "ab",
)

keiyoushi {
    name = "MangaDot"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    languages.forEach { langCode ->
        source {
            lang = langCode
            baseUrl = "https://mangadot.net"
        }
    }

    deeplink {
        host("mangadot.net")
        path("/manga/..*")
        path("/chapter/..*")
        path("/volume/..*")
    }
}
