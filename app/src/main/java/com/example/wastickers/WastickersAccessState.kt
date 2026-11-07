package com.example.wastickers

/**
 * Access state of ONE discovery source, decided by actually verifying it.
 *
 * A grant stored in SharedPreferences is NOT proof of access. Each source is
 * probed independently (persisted permission check + real read) and mapped to
 * exactly one of these states so the UI can explain WHY files are missing.
 */
enum class WastickersAccessState {
    /** takePersistableUriPermission succeeded AND a real read succeeded. */
    GRANTED_AND_READABLE,
    /** The Uri permission exists but reading the provider failed. */
    GRANTED_BUT_UNREADABLE,
    /** The picker returned a grant that could not be persisted. */
    TEMPORARY_ACCESS,
    /** The persisted grant no longer exists (revoked in system settings). */
    PERMISSION_REVOKED,
    /** The documents provider is missing or throws on every query. */
    PROVIDER_UNAVAILABLE,
    /** The user has not selected this source yet. */
    NOT_SELECTED
}

/** Kind of a discovery source shown on the WASTICKERS screen. */
enum class WastickersSourceKind {
    /** MediaStore.Downloads / MediaStore.Files — the default source. */
    DOWNLOAD,
    /** A SAF directory tree chosen with ACTION_OPEN_DOCUMENT_TREE. */
    SAF_TREE,
    /** One or more documents chosen with ACTION_OPEN_DOCUMENT. */
    USER_DOCUMENTS
}

/**
 * Live status of one source. [files] is the number of .wastickers archives
 * found through THIS source only, so one failing source never hides another.
 */
data class WastickersSourceStatus(
    val kind: WastickersSourceKind,
    val label: String,
    val state: WastickersAccessState,
    val uri: String? = null,
    val filesFound: Int = 0,
    /** Human-readable, specific reason shown instead of a generic message. */
    val detail: String? = null,
    /** True when this is the persisted default source. */
    val isDefault: Boolean = false
) {
    /** Whether this source contributed at least one archive to the list. */
    val isReadable: Boolean get() = state == WastickersAccessState.GRANTED_AND_READABLE
}

/**
 * WHY the discovery list is empty. Each case maps to a different remedy, so the
 * UI never shows a single generic "no files" message.
 */
enum class WastickersEmptyReason {
    /** The scan is still running. */
    NONE,
    /** Every readable source was scanned and genuinely contains no archives. */
    NO_MATCHING_FILES,
    /** No source has readable access yet. */
    NO_READ_PERMISSION,
    /** A source is known but its folder cannot be opened right now. */
    FOLDER_UNAVAILABLE,
    /** The documents provider itself is unavailable. */
    PROVIDER_UNAVAILABLE,
    /** All discovered archives are invalid (not real ZIP packs). */
    ONLY_INVALID_FILES,
    /** The scan itself failed. */
    SCAN_FAILED
}

/** Optional extension filters offered when picking documents. */
object WastickersMimeTypes {
    /**
     * .wastickers has NO registered MIME type anywhere. Restricting the picker
     * to "application/zip" or any specific type would HIDE exactly the files
     * the user needs, so every picker uses */* and the extension rule decides.
     */
    const val ANY = "*/*"

    val PICKER = arrayOf(ANY)
}
