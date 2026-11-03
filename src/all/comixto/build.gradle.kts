import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Comix"
    versionCode = 53
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://comix.to"
    }

    // v127 (versionCode 53) — FIELD VERDICT on v126: details light up
    // (the SSR island rung) but the chapter list stays dark. Live recon:
    // the title page SSRs the detail into the initial-data JSON island
    // and fires NO chapters XHR on its own — the list mounts from
    // client-side state only after interaction, and the API itself
    // refuses plain clients (403 missing_token; no SSR chapter routes
    // exist; the payload chunk is string-table VM-obfuscated so the
    // legacy bridge's axios discovery can never find it either). The
    // fix makes the SPA fire the calls: the tee session now SPA-DRIVES
    // the title page — real dispatched clicks on the page's own chapter
    // controls (tabs, load-more buttons, the first chapter route link —
    // the SPA's router intercepts every click on the document), paced
    // and bounded — so the site's own signed client produces the
    // chapters exchange and the tee records it verbatim.

    deeplink {
        host("comix.to")
        path("/title/..*")
    }
}
