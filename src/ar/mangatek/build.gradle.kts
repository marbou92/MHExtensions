import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaTek"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "ar"
        // v103: aligned with keiyoushi/extensions-source upstream (their vc 10,
        // fad0a54e): the site is an Astro SSR app — /manga-list HTML + astro
        // props payloads + api.mangatek.com unlock — not the axis API the
        // previous rewrite assumed (that shape only ever existed on the
        // mangatuk.com twin).
        baseUrl = "https://mangatek.com"
    }

    deeplink {
        host("mangatek.com")
        host("www.mangatek.com")
        path("/manga/..*")
        path("/reader/..*")
    }
}
