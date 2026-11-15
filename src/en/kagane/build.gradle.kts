import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kagane"
    versionCode = 52
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://kagane.si"
    }

    deeplink {
        host("kagane.si")
        host("kagane.cx")
        host("kagane.moe")
        host("kagane.net")
        path("/series/..*")
    }
}
