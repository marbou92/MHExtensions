import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 13
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

dependencies {
    // Software AVIF decode (libavif + dav1d, native libs for all 4 ABIs):
    // the site's page pieces are AVIF tiles and the platform AVIF codec only
    // ships on Android 12+ (and is missing on some OEM builds after that) —
    // without this, every protected page 502s on the devices that need it.
    implementation("org.aomedia.avif.android:avif:1.1.1.14d8e3c4")
}
