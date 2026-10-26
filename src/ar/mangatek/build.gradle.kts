import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaTek"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://mangatek.com"
    }

    deeplink {
        host("mangatek.com")
        host("www.mangatek.com")
        path("/series/..*")
    }
}
