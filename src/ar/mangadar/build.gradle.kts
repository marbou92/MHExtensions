import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaDar"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://mangadar.com"
    }

    deeplink {
        host("mangadar.com")
        host("www.mangadar.com")
        path("/manga/..*")
        path("/series/..*")
    }
}
