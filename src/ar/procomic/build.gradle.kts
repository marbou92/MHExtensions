import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://procomic.pro"
    }

    deeplink {
        host("procomic.pro")
        host("www.procomic.pro")
        path("/ar/..*")
    }
}
