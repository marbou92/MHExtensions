package eu.kanade.tachiyomi.extension.en.kagane

// The custom Cloudflare bypass (cookie sync + browser fingerprint headers +
// root priming + smart retry) was REMOVED from this extension.
//
// Why: it added extra serial round-trips (WebView cookie lookups, a site
// root "priming" GET before the integrity token call, backoff sleeps) in
// front of every request, which made the source slow even when Cloudflare
// was not challenging anything.
//
// What replaced it: nothing — deliberately. Like keiyoushi's mangadotnet,
// the source now sends plain requests and the HOST APP's own Cloudflare
// interceptor solves any challenge in its WebView. Same approach as
// ManhuaRMTL in this round.

/** Removal marker — this file intentionally holds no bypass code anymore. */
internal const val CLOUDFLARE_BYPASS_REMOVED: Boolean = true
