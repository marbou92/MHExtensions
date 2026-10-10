import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaTaro"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://mangataro.org"
    }

    deeplink {
        host("mangataro.org")
        path("/manga/..*")
        path("/read/..*")
    }
}
