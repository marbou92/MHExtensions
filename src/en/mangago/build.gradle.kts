import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaGo"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://www.mangago.me"
    }

    deeplink {
        host("www.mangago.me")
        host("mangago.me")
        path("/read-manga/..*")
    }
}
