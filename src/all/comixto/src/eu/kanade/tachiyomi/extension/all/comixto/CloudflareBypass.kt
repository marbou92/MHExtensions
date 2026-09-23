// Neutralized: this file belonged to the temporary tachiyomix-1.6 migration
// (commit 17d546b). The extensions build on the stable 1.4 extension library
// again, so the original content is gone and only this placeholder remains.
// The placeholder keeps ktlint's no-empty-file rule happy; the file is safe
// to delete.
//
// The Comix source now uses plain app-level Cloudflare handling (the app's
// own CloudflareInterceptor inside network.cloudflareClient), matching
// keiyoushi's Comix source for the same site — the custom fingerprint +
// cookie-sync + sleep-retry layer here was slower and more challenge-prone,
// and forced non-browser sec-fetch headers onto image downloads.
package eu.kanade.tachiyomi.extension.all.comixto

internal const val CLOUDFLARE_BYPASS_NEUTRALIZED = true
