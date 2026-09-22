package eu.kanade.tachiyomi.extension.en.kagane

/**
 * v15: the extension-level Cloudflare bypass (v14) is RETIRED.
 *
 * The solver added browser-fingerprint headers (fetch-like sec-fetch-* on
 * document navigations — itself a bot signal), re-synced cookies okhttp
 * already attaches, and spun an off-screen WebView per challenged request.
 * Net effect on the user's device: slower than doing nothing, and more
 * challenge-prone than keiyoushi's own kagane source, which has ZERO custom
 * Cloudflare handling and relies on the app-level CloudflareInterceptor
 * (KeiSource runs it after source interceptors) plus the shared WebView
 * cookie jar. configureClient is now byte-equivalent to upstream.
 *
 * This file is kept only because GitHub web upload cannot delete files.
 */
object CloudflareBypass {
    const val NOTE = "custom Cloudflare bypass retired in v15 (keiyoushi-parity)"
}
