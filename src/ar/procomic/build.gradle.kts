import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 24
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
    // v106 note: versionCode 21 = the host-app crash fix — the native decoder
    // is crash-loop-gated (attempt marker + session/permanent disable),
    // ARGB_8888-only output, a 25 MP allocation budget, covers request a
    // downscaled ?e=1280 variant, the result cache is byte-budgeted, and
    // the connection handler swallows Throwable so no Error can kill the
    // host app process.
    // v107 note: versionCode 22 = the loader ROOT-CAUSE fix. v104–v106
    // resolved "our own installed APK" from the HOST application context
    // (keiyoushi.utils.applicationContext IS Injekt's Application — hosted
    // extensions have no Context of their own), so the .so scan always ran
    // against the host apk and the native decoder never loaded on ANY
    // device (field report: "avif native: unavailable (no
    // libavif_android.so entry)" on every Android 10 page and cover). The
    // path now comes from the package manager (our own package); the
    // loader verifies the .so entry in every candidate zip (pm path +
    // classloader dex paths) and names each scanned zip in the diag trail.
    // v108 note: versionCode 23 = the framework-only decode tier. The edge
    // serves the AVIF files AS-IS regardless of the Accept header (the
    // file name pins the format — verified live), so the format cannot be
    // negotiated away, and the field screenshot from 1.6.22 ("no loadable
    // libavif_android.so scanned [base.apk: no entry]") proved the native
    // tier is still unreachable on the affected host app even after v107:
    // the dex-walk used Class.getDeclaredField (exact-class only), which
    // never sees pathList on BaseDexClassLoader subclasses, and even a
    // correct zip would end in System.load of a file from the host's cache
    // dir — which the W^X SELinux policy of modern targetSdk hosts may
    // refuse. New ladder: BitmapFactory (12+) -> MediaCodec "video/av1"
    // (the framework's c2.android.av1 software decoder, Android 10+, ISOBMFF
    // parsed in pure Kotlin incl. grid AVIF) -> libavif (fixed loader) ->
    // ImageDecoder. No .so, no PackageManager, no System.load — the only
    // tier whose availability the OS itself guarantees.
    // v109 FIELD VERDICT on v108: the host app CRASHED on every cover and
    // page load — MediaCodec's c2.av1 (Android 10's brand-new software AV1
    // codec) configured at a still image's full size is a native-crash/
    // OOM-kill surface no Java guard can catch, and the marker breaker only
    // gates after two process deaths. versionCode 24 = the MediaCodec tier
    // is REMOVED from the ladder and replaced with a WebView decode tier:
    // the host's Chromium engine (AVIF since Chrome 85; Play auto-updates
    // the WebView package even on Android 10) decodes the AVIF offscreen
    // (data: URL on the main looper, JS natural-size probe, draw into a
    // software ARGB bitmap) and every failure mode is survivable — caught
    // Java exceptions, bounded timeouts, or a sandboxed-renderer death that
    // leaves the app alive. Ladder: BitmapFactory (12+) -> libavif (loader)
    // -> WebView (24+) -> ImageDecoder (28+).

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
