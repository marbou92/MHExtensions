import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 20
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ar"
        // v101: the site moved to procomic.net — .pro now serves "moved"
        // notices for reader/series routes and its image-signing stack is
        // decommissioned (403 forbidden), which is where the device-side
        // HTTP 502 wave came from.
        // v105 correction: the site is DUAL-HOMED — most series migrated to
        // .net but a share of the catalog still lives on .pro (their .net
        // reader route 308s to the series page). The reader fetch falls
        // back across both apexes; covers resolve across the app-CDN pool
        // (app.procomic.net / app.procomic.pro / app.prochan.net /
        // cdn{1-4}.procomic.net) and ride the loopback JPEG transcoder.
        baseUrl = "https://procomic.net"
    }

    // v104 note: versionCode 19 = the Android 10/11 decode fix — the bundled
    // libavif_android.so is now extracted from the extension's own installed
    // APK and System.load-ed explicitly (extension classloaders don't expose
    // native lib paths, so AvifDecoder's static loadLibrary never found it).
    // v105 note: versionCode 20 = the dual-homed reader fallback + the cover
    // pipeline (resolver + loopback proxy + fixed preferred port).

    deeplink {
        host("procomic.net")
        host("www.procomic.net")
        // Keep the .pro hosts: older library entries carry them.
        host("procomic.pro")
        host("www.procomic.pro")
        path("/ar/..*")
    }
}

android {
    packaging {
        // AGP 9 removed ndk.abiFilters — the packaging excludes strip the
        // emulator-only ABIs of the software AVIF decoder (libavif + dav1d
        // below) at packaging time. The x86/x86_64 builds only matter to
        // emulators (which run Android 12+ with the platform codec anyway)
        // and were 3.1 MB of the APK; real devices are ARM. Cuts the APK
        // from ~4.8 MB to ~1.7 MB.
        excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/mips/**", "lib/mips64/**")
    }
}

dependencies {
    // Software AVIF decode (libavif + dav1d, native libs for the ARM ABIs):
    // the site's page files are AVIF (tall long-strip pages) and the
    // platform codec only ships on Android 12+ (and is missing on some OEM
    // builds after that). The image proxy transcodes every AVIF page to
    // JPEG with it, so pages render on every device and every host app.
    implementation("org.aomedia.avif.android:avif:1.1.1.14d8e3c4")
}
