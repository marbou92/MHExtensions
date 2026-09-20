import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhuaRMTL"
    versionCode = 23
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
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
