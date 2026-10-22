import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaTek"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://mangatek.com"
    }

    deeplink {
        host("mangatek.com")
        host("www.mangatek.com")
        host("mangatuk.com")
        host("www.mangatuk.com")
        path("/series/..*")
    }
}
