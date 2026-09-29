import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaBall"
    versionCode = 9
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "all"
        // The old mangaball.net is gone (301 → .com). Source id is derived
        // from name/lang/versionId — NOT baseUrl — so this migration keeps
        // every existing library entry and bookmark intact.
        baseUrl = "https://mangaball.com"
    }

    deeplink {
        host("mangaball.com")
        host("mangaball.net")
        path("/manga/..*")
        path("/title-detail/..*")
    }
}
