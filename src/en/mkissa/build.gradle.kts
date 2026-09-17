import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MKissa"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://mkissa.to"
    }

    deeplink {
        host("mkissa.to")
        path("/manga/..*")
    }
}
