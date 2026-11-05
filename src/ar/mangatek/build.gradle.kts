import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaTek"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "ar"
        // v103: aligned with keiyoushi/extensions-source upstream (their vc 10,
        // fad0a54e): the site is an Astro SSR app — /manga-list HTML + astro
        // props payloads + api.mangatek.com unlock — not the axis API the
        // previous rewrite assumed (that shape only ever existed on the
        // mangatuk.com twin).
        // v104 (upstream vc 11, hazptr 8bd9246d): the reader's unlock seeds
        // rotate with each build of its bundle — the baked proof salt and
        // reader seed went stale, so the unlock proof and the overlay
        // decryption failed. The seeds are now extracted from the site's own
        // obfuscated /_astro/ChapterImageViewer.<hash>.js (BundleSeeds:
        // string array + decoder + rotation run in QuickJS, the WASM hash
        // pins the algorithm) and cached per bundle URL.
        // v105 (upstream vc 12, hazptr 02cab9a2): the JSON decode failure the
        // field report named ("a json something failure" on chapter open) —
        // the site moved the reader embed's imageUrls from a list of strings
        // to a list of MIXED entries (plain url | pending marker | off-screen
        // decoy). The parser now reads them all, filters the decoys, and when
        // any page is marked pending the whole chapter is fetched from the
        // reader's own pages API (POST api.mangatek.com/api/reader/pages,
        // proof signed with the extracted seed).
        baseUrl = "https://mangatek.com"
    }

    deeplink {
        host("mangatek.com")
        host("www.mangatek.com")
        path("/manga/..*")
        path("/reader/..*")
    }
}
