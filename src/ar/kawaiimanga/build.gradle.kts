import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kawaiimanga"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://kawaiimanga.org"
    }

    deeplink {
        host("kawaiimanga.org")
        host("www.kawaiimanga.org")
        path("/manga/..*")
    }
}
