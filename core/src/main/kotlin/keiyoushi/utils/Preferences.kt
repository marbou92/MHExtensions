package keiyoushi.utils

import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * Returns the [SharedPreferences] associated with current source id
 */
inline fun HttpSource.getPreferences(
    migration: SharedPreferences.() -> Unit = { },
): SharedPreferences = getPreferences(id).also(migration)

/**
 * Lazily returns the [SharedPreferences] associated with current source id
 */
inline fun HttpSource.getPreferencesLazy(
    crossinline migration: SharedPreferences.() -> Unit = { },
) = lazy { getPreferences(migration) }

/**
 * Returns the [SharedPreferences] associated with passed source id
 */
fun getPreferences(sourceId: Long): SharedPreferences = applicationContext.getSharedPreferences("source_$sourceId", 0x0000)

// ---------------------------------------------------------------------------
// Type-safe reads
//
// Per-source preference files (`source_<id>.xml`) survive extension updates.
// When an older version stored a value under a different type (e.g. a
// StringSet written by a MultiSelectListPreference that a newer version
// reads back as a String), the raw getters throw ClassCastException and crash
// the app on the spot. These wrappers degrade to the default instead.
// ---------------------------------------------------------------------------

fun SharedPreferences.getStringSafe(key: String, defValue: String?): String? = try {
    getString(key, defValue)
} catch (_: ClassCastException) {
    defValue
}

fun SharedPreferences.getStringSetSafe(key: String, defValue: Set<String>): Set<String> = try {
    getStringSet(key, defValue) ?: defValue
} catch (_: ClassCastException) {
    defValue
}
