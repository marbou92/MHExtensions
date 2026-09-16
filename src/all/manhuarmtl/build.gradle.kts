import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhuaRMTL"
    versionCode = 13
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "all"
        baseUrl = "https://manhuarmtl.com"
    }

    deeplink {
        host("manhuarmtl.com")
        path("/manga/..*")
    }
}
