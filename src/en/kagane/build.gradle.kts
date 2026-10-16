import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kagane"
    versionCode = 51
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        // v72: kagane.to is gone (NXDOMAIN). kagane.si is the current primary;
        // cx/moe/net are the site's own mirrors (one shared backend + ID space).
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
