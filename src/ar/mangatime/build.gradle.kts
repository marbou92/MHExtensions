import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaTime"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://mangatime.org"
    }

    deeplink {
        host("mangatime.org")
        host("www.mangatime.org")
        path("/manga/..*")
        path("/series/..*")
    }
}
