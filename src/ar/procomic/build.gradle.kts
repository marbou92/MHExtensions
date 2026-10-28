import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 28
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
    // v110 FIELD VERDICT on v109: the app STILL died on every image — the
    // WebView tier had three structural crash vectors. (1) No concurrency
    // gate: covers + pages + the preload window each built their OWN
    // offscreen WebView (parallel Chromium boots, stacked renderer
    // processes). (2) Full-size draws: the WebView measured at the image's
    // natural size, so a 4000x6000 "originals" cover allocated a ~96 MB
    // ARGB bitmap per decode — two concurrent (the transcode stage allows
    // two) OOM-killed the host. (3) The probe-after-destroy race: destroy()
    // posted at verdict/timeout while JS probe retries were still queued —
    // evaluateJavascript on a destroyed WebView throws on the MAIN thread
    // with nothing to catch it: process death. versionCode 25 = ONE shared
    // offscreen WebView reused across decodes behind a process-wide permit
    // (never destroyed between decodes), ?e=-bounded draws (covers render
    // at <=1280 long edge, ~9 MB, never 96 MB), every callback
    // identity-checked against the current decode job and Throwable-
    // guarded, enableSlowWholeDocumentDraw for tall-page captures, and a
    // webview.attempt/webview.crashes marker breaker (same pattern as the
    // native tier) that disables the tier for a session after a process
    // death inside it and permanently after two — the worst case is now
    // two deaths per install, then legible error cards, never a loop.

    // v111 FIELD VERDICT on v110: the app STILL died on every cover and
    // page. Four on-device AVIF decoders (bundled libavif JNI, framework
    // MediaCodec, the offscreen WebView twice) all found a way to kill the
    // host process on the affected Android 10 device — every one of them
    // is a native-crash/OOM surface no Java guard can catch. versionCode
    // 26 = THE NEW WAY: the device no longer runs ANY AVIF decoder. The
    // loopback server re-fetches each AVIF URL through the weserv image
    // proxy (images.weserv.nl, then the wsrv.nl alias) with output=jpg so
    // the decode happens SERVER-SIDE, and this process only ever runs
    // BitmapFactory on plain JPEG — the one decoder that exists and is
    // safe on every Android version. Covers ride their ?e= box as
    // w/h/fit=inside/we params (the long edge is capped before the bytes
    // ever cross the network); pages get a server-side box only when the
    // container declares more than the 25 MP budget (parsed in pure
    // Kotlin from the already-downloaded bytes). Ladder:
    // BitmapFactory (12+ native AVIF) -> weserv JPEG -> legible error
    // card. The avif-android dependency (libavif + dav1d, 1.6 MB of
    // native libs) is REMOVED — the APK drops from ~1.7 MB to ~0.1 MB.
    // Debug-only MediaCodec hook retained for the harness pins; the
    // production ladder never reaches it.
    // v112 (versionCode 27): the site capped the SSR page embed at the
    // first `publicImageCount` pages (observed live: 3) and moved the
    // rest behind Cloudflare Turnstile — its browser reader solves the
    // challenge in-page, so the field sees "only the first three pages
    // of a chapter". The source now replays the site's own deferred-
    // media credential chain: parse the flight's deferredMedia JWT +
    // pages-scope imagePass, GET /chapter-deferred-media/{id}?token=…
    // with x-chapter-pass, renew either credential via the open POST
    // refresh / image-pass endpoints when the answer names them
    // expired, and merge the returned images[] after the public
    // pages. Verified live: with the gate enforced the answer is
    // `Turnstile token required` — the source logs a legible trail and
    // ships the public pages unchanged; if the site ever relaxes the
    // gate, the full chapter appears with no update. No WebView, no
    // decoder changes — the v111 no-crash guarantee is untouched.
    // v113 (versionCode 28): the gate does not relax, so the source
    // mints the missing credential itself. A throwaway offscreen
    // WebView (TurnstileSolver) loads a ~1 KB page whose ORIGIN is the
    // site apex and runs the site's EXACT widget config (sitekey
    // 0x4AAAAAACk1d7qWJi_of2qU, appearance interaction-only, retry
    // auto — lifted from the live reader chunk), hands the minted token
    // back over a JS bridge, and is destroyed; the deferred exchange
    // then retries with the reader's exact shape (x-turnstile-token +
    // x-turnstile-widget + split). Crash safety = the v111 contract
    // extended: blockNetworkImage is set BEFORE anything loads, the
    // page contains no image/stylesheet/font resource at all (the only
    // remote fetch is Cloudflare's challenge JS — no decode surface can
    // exist), onRenderProcessGone consumes a sandboxed-renderer death
    // so the app survives, and the whole solve is timeout-bounded and
    // always torn down. Solver failure = v112 behavior (3 public pages
    // + a legible trail); solver success = the FULL chapter.

    deeplink {
        host("procomic.net")
        host("www.procomic.net")
        // Keep the .pro hosts: older library entries carry them.
        host("procomic.pro")
        host("www.procomic.pro")
        path("/ar/..*")
    }
}

dependencies {
    // v111: NO native decoder dependency. The software AVIF transcode
    // moved server-side (the weserv image proxy) — the avif-android
    // artifact (libavif + dav1d, ~1.6 MB of ARM .so files) is gone from
    // the APK, and with it the last piece of on-device AVIF decode
    // machinery that ever killed a host process.
}
