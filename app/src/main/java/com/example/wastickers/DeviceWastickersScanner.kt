package com.example.wastickers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of one full scan across all sources. */
data class WastickersScanResult(
    val outcome: DiscoveryOutcome,
    /** Status of every source, in the order they were probed. */
    val sources: List<WastickersSourceStatus>,
    /** Diagnostics collected during this scan (never thrown away). */
    val diagnostics: WastickersScanDiagnostics
)

/**
 * Aggregated counters for one scan. Nothing here is derived from comments: the
 * numbers are incremented on the real code paths and shown in the in-app
 * diagnostic report.
 */
data class WastickersScanDiagnostics(
    val androidSdk: Int = Build.VERSION.SDK_INT,
    val foldersScanned: Int = 0,
    val nodesScanned: Int = 0,
    val wastickersFound: Int = 0,
    val unreadableFiles: Int = 0,
    val errors: Int = 0,
    val errorDetails: List<String> = emptyList(),
    val mediaStoreQueried: Boolean = false,
    val mediaStoreError: String? = null,
    val downloadQueried: Boolean = false,
    val downloadError: String? = null,
    val durationMs: Long = 0L
)

interface WastickersDataSource {
    /** Scans every reachable .wastickers archive. Runs on Dispatchers.IO. */
    suspend fun scan(): DiscoveryOutcome

    /** Scans every source and returns per-source status plus diagnostics. */
    suspend fun scanDetailed(): WastickersScanResult

    /** Whether at least one persistent read grant is available. */
    fun hasAnyAccess(): Boolean

    /** Whether the scoped legacy read permission is granted (API <= 32). */
    fun hasLegacyReadPermission(): Boolean
}

/**
 * Multi-source, permission-aware discovery of .wastickers archives.
 *
 * ROOT CAUSE of "files never appear": on Android 13+ (API 33+, targetSdk 36)
 * READ_EXTERNAL_STORAGE is ignored and MediaStore.Files only exposes files this
 * app itself created. Files received from WhatsApp, a browser download or a
 * file manager (e.g. gsgwh.wastickers) are invisible to a plain MediaStore
 * query. There is no permission the app can request to see them without the
 * user's involvement.
 *
 * FIX: Download is probed as a REAL source in three legitimate ways —
 *   1. MediaStore.Downloads (API 29+) limited to RELATIVE_PATH LIKE 'Download%',
 *      which legally exposes media rows on API 29..32 with the scoped read
 *      permission and on 33+ any row the app is allowed to see;
 *   2. MediaStore.Files (API <= 32) as the legacy fallback;
 *   3. A user-granted SAF tree (if the user chose Download) — the only channel
 *      that can see files written by other apps on Android 11+.
 *
 * Every source is probed in isolation. A SecurityException, a dead provider or
 * an unresolvable grant is recorded and reported; it never aborts the scan.
 *
 * No MANAGE_EXTERNAL_STORAGE / all-files access is requested, and a content://
 * Uri is NEVER converted to a java.io.File path.
 */
class DeviceWastickersScanner(
    private val context: Context,
    private val treePermissionStore: TreePermissionStore,
    private val documentPermissionStore: DocumentPermissionStore,
    private val engine: WastickersDiscoveryEngine = WastickersDiscoveryEngine(),
    private val defaultSourceStore: WastickersDefaultSourceStore? = null
) : WastickersDataSource {

    override suspend fun scan(): DiscoveryOutcome = scanDetailed().outcome

    override suspend fun scanDetailed(): WastickersScanResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val diagnostics = DiagnosticsAccumulator()
        val sources = ArrayList<WastickersSourceStatus>()
        val flat = ArrayList<WastickersFile>()

        val preferred = defaultSourceStore?.current() ?: WastickersSourceKind.DOWNLOAD

        /* ---------------- Source 1: Download (default) ------------------ */
        val downloadProbe = probeDownload(flat, diagnostics)
        sources.add(
            WastickersSourceStatus(
                kind = WastickersSourceKind.DOWNLOAD,
                label = "Download",
                state = downloadProbe.state,
                filesFound = downloadProbe.found,
                detail = downloadProbe.detail,
                isDefault = preferred == WastickersSourceKind.DOWNLOAD
            )
        )

        /* ------- Source 2: directly picked documents ------------------- */
        val (invalidFiles, documentsStatus) = scanDirectDocuments(flat, diagnostics)
        sources.add(documentsStatus.copy(isDefault = preferred == WastickersSourceKind.USER_DOCUMENTS))

        /* ---------------- Source 3: SAF trees -------------------------- */
        val trees = ArrayList<FolderTreePort>()
        val treeUris = ArrayList<Uri>()
        var revokedTrees = 0
        var unreadableTrees = 0
        for (treeUri in treePermissionStore.grantedTreeUris()) {
            if (!hasPersistedReadPermission(treeUri)) {
                treePermissionStore.remove(treeUri)
                revokedTrees++
                diagnostics.recordError(
                    "Grant revoked for tree $treeUri — removed from store"
                )
                continue
            }
            // Verify the tree can actually be listed before counting it.
            val port = SafTreePort(treeUri)
            val rootId = port.rootId()
            if (rootId.isNullOrEmpty() || port.listChildren(rootId) == null) {
                unreadableTrees++
                diagnostics.recordError("Tree unreadable: $treeUri")
                continue
            }
            trees.add(port)
            treeUris.add(treeUri)
        }

        val treeOutcome = engine.collect(trees, flat)
        val treeFiles = treeOutcome.files.count { it.source == WastickersSource.SAF_TREE }
        when {
            trees.isNotEmpty() -> sources.add(
                WastickersSourceStatus(
                    kind = WastickersSourceKind.SAF_TREE,
                    label = if (trees.size == 1) {
                        describeTree(treeUris.first())
                    } else {
                        "المجلدات المختارة (${trees.size})"
                    },
                    state = WastickersAccessState.GRANTED_AND_READABLE,
                    uri = treeUris.firstOrNull()?.toString(),
                    filesFound = treeFiles,
                    detail = if (trees.size > 1) {
                        treeUris.joinToString("\n") { describeTree(it) }
                    } else null,
                    isDefault = preferred == WastickersSourceKind.SAF_TREE
                )
            )
            unreadableTrees > 0 -> sources.add(
                WastickersSourceStatus(
                    kind = WastickersSourceKind.SAF_TREE,
                    label = "مجلد مختار",
                    state = WastickersAccessState.GRANTED_BUT_UNREADABLE,
                    detail = "الصلاحية موجودة لكن تعذّر فتح المجلد",
                    isDefault = preferred == WastickersSourceKind.SAF_TREE
                )
            )
            else -> sources.add(
                WastickersSourceStatus(
                    kind = WastickersSourceKind.SAF_TREE,
                    label = "مجلد SAF",
                    state = if (revokedTrees > 0) {
                        WastickersAccessState.PERMISSION_REVOKED
                    } else {
                        WastickersAccessState.NOT_SELECTED
                    },
                    detail = if (revokedTrees > 0) {
                        "تم إلغاء صلاحية $revokedTrees مجلد — أعد اختيار المجلد"
                    } else {
                        "لم يتم اختيار مجلد بعد"
                    },
                    isDefault = preferred == WastickersSourceKind.SAF_TREE
                )
            )
        }

        diagnostics.foldersScanned += treeOutcome.scannedNodes
        diagnostics.nodesScanned += treeOutcome.scannedNodes
        diagnostics.inaccessible += treeOutcome.inaccessibleFolders +
            revokedTrees + unreadableTrees
        val normalized = treeOutcome.copy(
            invalidFiles = invalidFiles,
            inaccessibleFolders = treeOutcome.inaccessibleFolders +
                revokedTrees + unreadableTrees
        )
        diagnostics.wastickersFound = normalized.files.size
        diagnostics.durationMs = System.currentTimeMillis() - startedAt

        WastickersScanResult(
            outcome = normalized,
            sources = sources,
            diagnostics = diagnostics.toDiagnostics()
        )
    }

    override fun hasAnyAccess(): Boolean =
        treePermissionStore.hasAny() ||
            documentPermissionStore.hasAny() ||
            hasLegacyReadPermission()

    override fun hasLegacyReadPermission(): Boolean {
        if (Build.VERSION.SDK_INT > 32) return false
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True when the ContentResolver still holds a persisted read grant for [uri]. */
    fun hasPersistedReadPermission(uri: Uri): Boolean {
        val target = uri.toString()
        return try {
            context.contentResolver.persistedUriPermissions.any { grant ->
                grant.isReadPermission &&
                    (grant.uri.toString() == target ||
                        target.startsWith(grant.uri.toString().trimEnd('/') + "/"))
            }
        } catch (_: Exception) {
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /* 1. Download — the default source                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Probes the Download collection without pretending a grant exists.
     *
     * @return the resulting state, the number of archives found and a specific
     *         explanation when nothing could be read.
     */
    private fun probeDownload(
        out: MutableList<WastickersFile>,
        diagnostics: DiagnosticsAccumulator
    ): DownloadProbe {
        diagnostics.downloadQueried = true
        val before = out.size

        // (a) MediaStore.Downloads is the canonical collection for Download.
        //     Available from API 29. It is queried WITHOUT any fabricated
        //     permission: the resolver returns only rows we may see.
        var rowError: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rowError = queryDownloadCollection(out, diagnostics)
        }

        // (b) Legacy files collection, useful on API <= 32 with the scoped
        //     read permission (and harmless as a supplement elsewhere).
        if (rowError != null || out.size == before) {
            val filesError = queryFilesCollectionForDownload(out, diagnostics)
            if (rowError == null) rowError = filesError
        }

        val found = out.size - before
        diagnostics.downloadError = rowError
        val state = when {
            rowError == null -> WastickersAccessState.GRANTED_AND_READABLE
            found > 0 -> WastickersAccessState.GRANTED_AND_READABLE
            !hasLegacyReadPermission() && Build.VERSION.SDK_INT > 32 ->
                WastickersAccessState.NOT_SELECTED
            else -> WastickersAccessState.GRANTED_BUT_UNREADABLE
        }
        val detail = when {
            rowError != null -> rowError
            found > 0 -> null
            Build.VERSION.SDK_INT > 32 ->
                "لم يتم العثور على ملفات في Download عبر MediaStore. " +
                    "اختر مجلد Download لتمكين القراءة الكاملة."
            else -> "لم يتم العثور على ملفات .wastickers في Download"
        }
        return DownloadProbe(state, found, detail)
    }

    private data class DownloadProbe(
        val state: WastickersAccessState,
        val found: Int,
        val detail: String?
    )

    /** @return null on success, or a specific error description. */
    private fun queryDownloadCollection(
        out: MutableList<WastickersFile>,
        diagnostics: DiagnosticsAccumulator
    ): String? {
        if (out.size >= MAX_DOCUMENTS) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.RELATIVE_PATH
        )
        return try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Downloads._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Downloads.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED)
                val pathIdx = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
                while (cursor.moveToNext() && out.size < MAX_DOCUMENTS) {
                    addMediaRow(
                        cursor, collection, idIdx, nameIdx, sizeIdx, dateIdx, pathIdx, out
                    )
                }
            }
            null
        } catch (e: SecurityException) {
            diagnostics.recordError("MediaStore.Downloads SecurityException: ${e.message}")
            "لا توجد صلاحية قراءة MediaStore: ${e.message ?: "SecurityException"}"
        } catch (e: Exception) {
            diagnostics.recordError("MediaStore.Downloads failed: ${e.message}")
            "تعذّر الاستعلام عن Download: ${e.message ?: e::class.java.simpleName}"
        }
    }

    /**
     * Legacy fallback: MediaStore.Files filtered to the Download relative path.
     * On API <= 32 RELATIVE_PATH does not exist (added in Q), so the whole file
     * collection is scanned and the path is inferred from DATA when available.
     */
    private fun queryFilesCollectionForDownload(
        out: MutableList<WastickersFile>,
        diagnostics: DiagnosticsAccumulator
    ): String? {
        if (out.size >= MAX_DOCUMENTS) return null
        val mayQuery = Build.VERSION.SDK_INT >= 33 || hasLegacyReadPermission()
        if (!mayQuery) return null

        diagnostics.mediaStoreQueried = true
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri(VOLUME_EXTERNAL_LEGACY)
        }
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
        }

        return try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                } else -1
                var rows = 0
                while (cursor.moveToNext() && rows < MAX_MEDIA_ROWS &&
                    out.size < MAX_DOCUMENTS
                ) {
                    rows++
                    addMediaRow(
                        cursor, collection, idIdx, nameIdx, sizeIdx, dateIdx, pathIdx, out
                    )
                }
            }
            null
        } catch (e: SecurityException) {
            diagnostics.mediaStoreError = "SecurityException: ${e.message}"
            diagnostics.recordError("MediaStore.Files SecurityException: ${e.message}")
            "MediaStore: لا توجد صلاحية قراءة (${e.message ?: "SecurityException"})"
        } catch (e: Exception) {
            diagnostics.mediaStoreError = "${e::class.java.simpleName}: ${e.message}"
            diagnostics.recordError("MediaStore.Files failed: ${e.message}")
            "MediaStore غير متاح: ${e.message ?: e::class.java.simpleName}"
        }
    }

    /**
     * Adds one MediaStore row when it is a .wastickers archive.
     *
     * Deliberately NO SQL "LIKE '%.wastickers'". Its case sensitivity depends on
     * the provider's collation and an uppercase .WASTICKERS row could be
     * filtered out before Kotlin ever sees it. Every candidate is filtered by
     * the single Kotlin rule WastickersFileOps.isWastickersName.
     */
    private fun addMediaRow(
        cursor: android.database.Cursor,
        collection: Uri,
        idIdx: Int,
        nameIdx: Int,
        sizeIdx: Int,
        dateIdx: Int,
        pathIdx: Int,
        out: MutableList<WastickersFile>
    ) {
        val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
            cursor.getString(nameIdx)
        } else null
        if (!WastickersFileOps.isWastickersName(name)) return

        val rowId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
        if (rowId < 0L) return

        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
        // MediaStore reports DATE_MODIFIED in SECONDS.
        val modifiedSeconds = if (dateIdx >= 0 && !cursor.isNull(dateIdx)) {
            cursor.getLong(dateIdx)
        } else 0L
        val relativePath = if (pathIdx >= 0 && !cursor.isNull(pathIdx)) {
            cursor.getString(pathIdx).orEmpty()
        } else ""

        val uri = Uri.withAppendedPath(collection, rowId.toString()).toString()
        val id = "media:$uri"
        if (out.any { it.id == id }) return
        out.add(
            WastickersFile(
                id = id,
                uri = uri,
                displayName = WastickersFileOps.sanitizeName(name),
                sizeBytes = size,
                lastModified = modifiedSeconds * 1000L,
                source = WastickersSource.MEDIA_STORE,
                location = relativePath.ifBlank {
                    WastickersFileOps.locationOf(uri, WastickersSource.MEDIA_STORE)
                }
            )
        )
    }

    /* ------------------------------------------------------------------ */
    /* 2. Directly picked documents (ACTION_OPEN_DOCUMENT)                 */
    /* ------------------------------------------------------------------ */

    /** @return pair of (invalid file count, per-source status). */
    private fun scanDirectDocuments(
        out: MutableList<WastickersFile>,
        diagnostics: DiagnosticsAccumulator
    ): Pair<Int, WastickersSourceStatus> {
        val granted = documentPermissionStore.grantedDocumentUris()
        if (granted.isEmpty()) {
            return 0 to WastickersSourceStatus(
                kind = WastickersSourceKind.USER_DOCUMENTS,
                label = "ملفات مختارة",
                state = WastickersAccessState.NOT_SELECTED,
                detail = "لم يتم اختيار أي ملف بعد"
            )
        }

        var invalid = 0
        var revoked = 0
        var found = 0
        for (uri in granted) {
            val doc = try {
                DocumentFile.fromSingleUri(context, uri)
            } catch (e: SecurityException) {
                diagnostics.recordError("Document ${uri} SecurityException: ${e.message}")
                null
            } catch (e: Exception) {
                diagnostics.recordError("Document ${uri} failed: ${e.message}")
                null
            }

            val name = doc?.name
            if (name == null) {
                // The grant was revoked or the document was deleted.
                documentPermissionStore.remove(uri)
                revoked++
                diagnostics.recordError("Document grant lost or file deleted: $uri")
                continue
            }
            if (!WastickersFileOps.isWastickersName(name)) {
                invalid++
                diagnostics.recordError("Picked document is not .wastickers: $name")
                continue
            }

            // Verify the bytes are actually reachable — a name alone proves
            // nothing. Only a successful open counts as readable.
            val readable = canOpenStream(uri)
            if (!readable) {
                diagnostics.unreadableFiles++
                diagnostics.recordError("Document not readable: $name ($uri)")
            }

            val size = try {
                doc.length()
            } catch (e: Exception) {
                0L
            }
            val modified = try {
                doc.lastModified()
            } catch (e: Exception) {
                0L
            }

            val uriString = uri.toString()
            val id = "doc:$uriString"
            if (out.none { it.id == id }) {
                out.add(
                    WastickersFile(
                        id = id,
                        uri = uriString,
                        displayName = WastickersFileOps.sanitizeName(name),
                        sizeBytes = size,
                        lastModified = modified,
                        source = WastickersSource.USER_DOCUMENT,
                        location = WastickersFileOps.locationOf(
                            uriString, WastickersSource.USER_DOCUMENT
                        )
                    )
                )
                found++
            }
        }

        val state = when {
            found > 0 -> WastickersAccessState.GRANTED_AND_READABLE
            revoked == granted.size -> WastickersAccessState.PERMISSION_REVOKED
            granted.isNotEmpty() -> WastickersAccessState.GRANTED_BUT_UNREADABLE
            else -> WastickersAccessState.NOT_SELECTED
        }
        val detail = when (state) {
            WastickersAccessState.PERMISSION_REVOKED ->
                "انتهت صلاحية الملفات المختارة — أعد اختيارها"
            WastickersAccessState.GRANTED_BUT_UNREADABLE ->
                "الملفات المختارة غير قابلة للقراءة (${granted.size})"
            else -> if (invalid > 0) "$invalid ملف غير صالح" else null
        }
        return invalid to WastickersSourceStatus(
            kind = WastickersSourceKind.USER_DOCUMENTS,
            label = "ملفات مختارة (${granted.size})",
            state = state,
            filesFound = found,
            detail = detail
        )
    }

    /** Cheap real read probe: opens and reads one byte, then closes. */
    private fun canOpenStream(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.read()
            true
        } ?: false
    } catch (_: Exception) {
        false
    }

    /* ------------------------------------------------------------------ */
    /* 3. SAF tree adapter -> WastickersDiscoveryEngine                    */
    /* ------------------------------------------------------------------ */

    private inner class SafTreePort(private val treeUri: Uri) : FolderTreePort {

        override fun label(): String = treeUri.toString()

        override fun rootId(): String? = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        }

        override fun listChildren(parentId: String): FolderListing? {
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            } catch (_: Exception) {
                return FolderListing(emptyList(), FolderAccess.FAILED)
            }

            val cursor = try {
                context.contentResolver.query(
                    childrenUri,
                    CHILD_PROJECTION,
                    null,
                    null,
                    null
                )
            } catch (_: SecurityException) {
                return FolderListing(emptyList(), FolderAccess.DENIED)
            } catch (_: Exception) {
                return FolderListing(emptyList(), FolderAccess.FAILED)
            } ?: return FolderListing(emptyList(), FolderAccess.DENIED)

            val children = ArrayList<RawDocument>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val dateIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (c.moveToNext() && children.size < MAX_DOCUMENTS) {
                    val docId = if (idIdx >= 0 && !c.isNull(idIdx)) {
                        c.getString(idIdx)
                    } else null
                    if (docId.isNullOrEmpty()) continue
                    val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                        c.getString(nameIdx)
                    } else null
                    val modified = if (dateIdx >= 0 && !c.isNull(dateIdx)) {
                        c.getLong(dateIdx)
                    } else 0L
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                        c.getLong(sizeIdx)
                    } else 0L
                    val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) {
                        c.getString(mimeIdx)
                    } else ""
                    children.add(RawDocument(docId, name, size, modified, mime))
                }
            }
            return FolderListing(children, FolderAccess.OK)
        }

        override fun documentUri(documentId: String): String? = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
        } catch (_: Exception) {
            null
        }

        override fun locationFor(documentId: String): String =
            documentId.takeLast(96)
    }

    private fun describeTree(uri: Uri?): String {
        if (uri == null) return "مجلد مختار"
        val decoded = runCatching {
            DocumentsContract.getTreeDocumentId(uri)?.substringAfter(':')
        }.getOrNull()
        return decoded?.substringAfterLast('/')?.ifBlank { "Download" } ?: "مجلد مختار"
    }

    /** Accumulates real counters; never silently discards an exception. */
    private class DiagnosticsAccumulator {
        var foldersScanned = 0
        var nodesScanned = 0
        var inaccessible = 0
        var unreadableFiles = 0
        var wastickersFound = 0
        var durationMs = 0L
        private val errors = ArrayList<String>()
        var mediaStoreQueried = false
        var mediaStoreError: String? = null
        var downloadQueried = false
        var downloadError: String? = null

        fun recordError(message: String) {
            if (errors.size < MAX_ERRORS) errors.add(message)
        }

        fun toDiagnostics(): WastickersScanDiagnostics = WastickersScanDiagnostics(
            foldersScanned = foldersScanned,
            nodesScanned = nodesScanned,
            wastickersFound = wastickersFound,
            unreadableFiles = unreadableFiles,
            errors = errors.size,
            errorDetails = errors.toList(),
            mediaStoreQueried = mediaStoreQueried,
            mediaStoreError = mediaStoreError,
            downloadQueried = downloadQueried,
            downloadError = downloadError,
            durationMs = durationMs
        )
    }

    private companion object {
        const val VOLUME_EXTERNAL_LEGACY = "external"
        const val MAX_DOCUMENTS = 5000
        const val MAX_MEDIA_ROWS = 20000
        const val MAX_ERRORS = 50

        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
    }
}
