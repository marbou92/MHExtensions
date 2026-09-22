package eu.kanade.tachiyomi.extension.all.manhuarmtl

/**
 * v15: the extension-level Cloudflare bypass (v14) is RETIRED.
 *
 * The solver added browser-fingerprint headers (fetch-like sec-fetch-* on
 * document navigations — itself a bot signal), re-synced cookies okhttp
 * already attaches, and spun an off-screen WebView per challenged request.
 * Net effect on the user's device: slower than doing nothing, and more
 * challenge-prone than keiyoushi's own manhuarm source for this same site,
 * which ships only a one-shot CloudflareWarmupInterceptor and otherwise
 * relies on the app-level CloudflareInterceptor plus the shared WebView
 * cookie jar. That warm-up interceptor is now ported verbatim
 * (CloudflareWarmupInterceptor.kt).
 *
 * This file is kept only because GitHub web upload cannot delete files.
 */
object CloudflareBypass {
    const val NOTE = "custom Cloudflare bypass retired in v15 (keiyoushi-parity)"
}
