package eu.kanade.tachiyomi.extension.all.manhuarmtl

// The custom Cloudflare bypass (cookie sync + browser fingerprint headers +
// root priming + smart retry) was REMOVED from this extension.
//
// Why: it added multiple serial round-trips (WebView cookie lookups, a site
// root "priming" GET, backoff sleeps) in front of every request, which made
// the source feel slow even when Cloudflare was not challenging anything —
// and it could still fail outright on the filter-data fetch.
//
// What replaced it: nothing — deliberately. Like keiyoushi's mangadotnet,
// the source now sends plain requests and the HOST APP's own Cloudflare
// interceptor solves any challenge in its WebView. Same approach as Kagane
// in this round.

/** Removal marker — this file intentionally holds no bypass code anymore. */
internal const val CLOUDFLARE_BYPASS_REMOVED: Boolean = true
