package eu.kanade.tachiyomi.extension.ar.procomic

/**
 * Deprecated no-op, kept only for checkout compatibility.
 *
 * v101 (ProComic 1.6.17) moved the source to procomic.net and deleted the
 * whole map-token / plan / signed-proxy pipeline this WebView client drove,
 * rewriting ProtectedPageServer.kt in place as ProComicImageServer. The old
 * client referenced the removed class name, so checkouts that still carried
 * this file failed to compile ("Unresolved reference 'ProtectedPageServer'").
 *
 * Nothing in the extension references this class anymore. This stub is
 * shipped under the same filename so applying the v102 changed-files pack
 * over an older checkout fixes compilation without manual deletions.
 * Deleting this file is safe and recommended.
 */
@Deprecated("Dead code since v101 - safe to delete; kept so old checkouts keep compiling")
class ProComicWebViewPlanClient
