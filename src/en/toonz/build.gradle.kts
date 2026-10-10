import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Toonz"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://toonz.to"
    }

    deeplink {
        host("toonz.to")
        path("/manga/..*")
        path("/manhwa/..*")
        path("/adult/..*")
    }
}
