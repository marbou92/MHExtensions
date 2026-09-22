package eu.kanade.tachiyomi.extension.en.mkissa

/**
 * v15: the extension-level Cloudflare bypass (v14) is RETIRED.
 *
 * The solver forced fetch-like sec-fetch-* headers onto every request
 * (including image loads) — a bot signal that made Cloudflare challenges
 * MORE likely — and its cookie-sync duplicated what okhttp's cookie jar
 * already does. Reading happens inside the site's own reader WebView
 * (which solves Cloudflare + Turnstile + AA crypto itself), browsing hits
 * the open API, and page images use browser-like image headers via
 * imageRequest. Plain client, exactly like keiyoushi sources.
 *
 * This file is kept only because GitHub web upload cannot delete files.
 */
object CloudflareBypass {
    const val NOTE = "custom Cloudflare bypass retired in v15 (keiyoushi-parity)"
}
