import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 56
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        baseUrl = "https://procomic.net"
    }

    deeplink {
        host("procomic.net")
        host("www.procomic.net")
        // Keep the .pro hosts: older library entries carry them.
        host("procomic.pro")
        host("www.procomic.pro")
        path("/ar/..*")
    }
}

dependencies {
}
