import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaBall"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://mangaball.net"
    }

    deeplink {
        host("mangaball.net")
        path("/manga/..*")
        path("/title-detail/..*")
    }
}
