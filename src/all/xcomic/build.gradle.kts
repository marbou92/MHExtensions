import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "XComic"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://xcomic.me"
    }

    deeplink {
        host("xcomic.me")
        host("xcomic.net")
        host("comik.to")
        host("yona.to")
        path("/title/..*")
        path("/chapter/..*")
    }
}
