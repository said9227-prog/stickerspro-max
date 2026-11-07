package com.example.wastickers

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers which discovery source is the user's default.
 *
 * DataStore is not currently on the classpath (the dependency is commented out
 * in app/build.gradle.kts), so the preference is persisted through
 * SharedPreferences with the same synchronous-commit discipline used by the
 * other WASTICKERS stores. The public surface is a plain value, so switching to
 * DataStore later requires no call-site change.
 */
class WastickersDefaultSourceStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted default source kind. Download is the default on first run. */
    @Synchronized
    fun current(): WastickersSourceKind {
        val raw = prefs.getString(KEY_KIND, null) ?: return WastickersSourceKind.DOWNLOAD
        return runCatching { WastickersSourceKind.valueOf(raw) }
            .getOrDefault(WastickersSourceKind.DOWNLOAD)
    }

    @Synchronized
    fun set(kind: WastickersSourceKind) {
        prefs.edit().putString(KEY_KIND, kind.name).commit()
    }

    /** True once the user has completed at least one scan. */
    @Synchronized
    fun hasCompletedFirstScan(): Boolean = prefs.getBoolean(KEY_FIRST_SCAN, false)

    @Synchronized
    fun markFirstScanCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_SCAN, true).commit()
    }

    private companion object {
        const val PREFS_NAME = "wastickers_default_source"
        const val KEY_KIND = "default_source_kind_v1"
        const val KEY_FIRST_SCAN = "first_scan_done_v1"
    }
}
