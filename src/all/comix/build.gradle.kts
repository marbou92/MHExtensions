import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Comix"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://comix.to"
    }

    deeplink {
        host("comix.to")
        path("/title/..*")
    }
}
