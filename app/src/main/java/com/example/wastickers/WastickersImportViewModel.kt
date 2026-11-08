package com.example.wastickers

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.StickerItem
import com.example.core.model.StickerPack
import com.example.data.db.AppDatabase
import com.example.data.repository.PackRepository
import com.example.data.repository.StickerRepository
import com.example.storage.TempFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class WastickersPermission {
    GRANTED,
    NEEDS_ACCESS,
    DENIED
}

data class WastickersUiState(
    val isLoading: Boolean = false,
    val files: List<WastickersFile> = emptyList(),
    val query: String = "",
    val selectedIds: Set<String> = emptySet(),
    val permission: WastickersPermission = WastickersPermission.NEEDS_ACCESS,
    val error: String? = null,
    /** Folder nodes that could not be read (revoked grant / broken provider). */
    val inaccessibleFolders: Int = 0,
    /** Directly picked documents that are not .wastickers archives. */
    val invalidFiles: Int = 0,
    val import: ImportProgress? = null,
    /** Content inspection of each archive, decided from its BYTES, not name. */
    val inspections: Map<String, ArchiveInspection> = emptyMap(),
    /** Versioned preview file Uris; never keyed by image name alone. */
    val previewUris: Map<String, String> = emptyMap(),
    /** End-of-batch summary derived from the real per-file progress. */
    val importReport: ImportReport? = null,
    /** Pack currently being imported, shown in the progress bar. */
    val currentImportName: String? = null,
    /** Status of EVERY source, probed independently. */
    val sources: List<WastickersSourceStatus> = emptyList(),
    /** Why the list is empty, so the UI never shows a generic message. */
    val emptyReason: WastickersEmptyReason = WastickersEmptyReason.NONE,
    /** The persisted default source kind. */
    val defaultSource: WastickersSourceKind = WastickersSourceKind.DOWNLOAD,
    /** True while a permission/scan toast should be shown. */
    val notice: String? = null,

    /* ---------------- WASTICKERS Explorer state ---------------- */
    /** Every discovered package with real previews + parsed metadata. */
    val packages: List<ExplorerPackage> = emptyList(),
    /** Packages after search/filter/sort (this is what the gallery renders). */
    val visiblePackages: List<ExplorerPackage> = emptyList(),
    val filter: ExplorerFilter = ExplorerFilter.ALL,
    val sort: ExplorerSort = ExplorerSort.NEWEST,
    /** REAL all-files permission snapshot (never assumed). */
    val storagePermission: StoragePermissionState = StoragePermissionState.DENIED,
    val storagePermissionDetail: String? = null,
    /** Per-folder diagnostics from Direct File Access. */
    val directDiagnostics: AdvancedDiagnostics? = null,
    /** Files discovered so far during the running scan (live progress). */
    val scannedCount: Int = 0,
    /** True while packages are still being parsed/previewed. */
    val isParsing: Boolean = false
)

@OptIn(FlowPreview::class) // Flow.debounce is still a FlowPreview API
class WastickersImportViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val db = AppDatabase.getInstance(app)
    private val packRepository = PackRepository(db.stickerDao())
    private val stickerRepository = StickerRepository(db.stickerDao())
    private val tempFileManager = TempFileManager(app)

    private val history = DiscoveryHistoryStore(app)
    private val hashLog: ImportedHashLog = SharedPrefsImportedHashLog(app)
    private val treeStore = TreePermissionStore(app)
    private val documentStore = DocumentPermissionStore(app)
    private val defaultSourceStore = WastickersDefaultSourceStore(app)
    val diagnostics = WastickersDiagnosticManager(app)
    private val permissionManager = StoragePermissionManager(app)
    val storagePermissionManager: StoragePermissionManager get() = permissionManager
    private val scanner: DeviceWastickersScanner =
        DeviceWastickersScanner(app, treeStore, documentStore, defaultSourceStore = defaultSourceStore)

    private val _uiState = MutableStateFlow(WastickersUiState())
    val uiState: StateFlow<WastickersUiState> = _uiState.asStateFlow()

    private val _openPackEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val openPackEvents: SharedFlow<Long> = _openPackEvents.asSharedFlow()

    /* --- Instant, debounced, index-based search -------------------------- */
    private val _query = MutableStateFlow("")
    private val _files = MutableStateFlow<List<WastickersFile>>(emptyList())
    private val _visibleFiles = MutableStateFlow<List<WastickersFile>>(emptyList())
    val visibleFiles: StateFlow<List<WastickersFile>> = _visibleFiles.asStateFlow()

    /** Bounded LRU of archive inspections, shared with preview generation. */
    private val inspectionCache = WastickersInspectionCache(maxEntries = 120)
    private val previewDir: File by lazy {
        File(app.cacheDir, "wastickers_previews").apply { mkdirs() }
    }
    private var inspectionJob: Job? = null
    private var parseJob: Job? = null

    init {
        // The normalised index is built ONCE per list change (on a background
        // dispatcher). Each debounced keystroke then reuses that SAME index and
        // only does substring matching: no storage reads, no re-normalisation
        // of thousands of haystacks per character.
        combine(
            _query.debounce(SEARCH_DEBOUNCE_MS),
            _files.map { files -> WastickersSearchIndex(files) }
        ) { query, index -> index.search(query) }
            .flowOn(Dispatchers.Default)
            .onEach { _visibleFiles.value = it }
            .launchIn(viewModelScope)
    }

    private val importer: WastickersImporter = WastickersImporter(
        opener = ArchiveOpener { file ->
            try {
                app.contentResolver.openInputStream(Uri.parse(file.uri))
            } catch (_: Exception) {
                null
            }
        },
        writer = PackWriter { pack -> persistPack(pack) },
        hashLog = hashLog,
        tempDirProvider = { File(app.cacheDir, "wastickers_import").apply { mkdirs() } }
    )

    private var importJob: Job? = null

    /**
     * Lazily inspects the CURRENTLY VISIBLE archives on Dispatchers.IO.
     *
     * This is the root-cause fix for "content decides, not the extension":
     * a file named pack.part2.wastickers may be a complete standalone archive,
     * while a file named pack.wastickers may be a split segment. Results are
     * cached by archive VERSION (id + size + modification time), so a refresh
     * never re-reads unchanged files and a changed file never shows a stale
     * preview from another pack.
     */
    private fun inspectVisible(files: List<WastickersFile>) {
        inspectionJob?.cancel()
        inspectionJob = viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val versions = files.map { WastickersPreviewKeys.archiveVersion(it) }
                inspectionCache.retainVersions(versions)
                val results = LinkedHashMap<String, ArchiveInspection>()
                val previews = LinkedHashMap<String, String>()

                for (file in files.take(MAX_INSPECTIONS_PER_SCAN)) {
                    if (!isActive) break
                    val version = WastickersPreviewKeys.archiveVersion(file)
                    val cacheKey = "$version|inspection"
                    val cached = inspectionCache.get(cacheKey)
                    val inspection = cached ?: WastickersArchiveInspector.inspect(file.displayName) {
                        try {
                            app.contentResolver.openInputStream(Uri.parse(file.uri))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (cached == null) inspectionCache.put(cacheKey, inspection)
                    results[file.id] = inspection

                    val bytes = inspection.previewBytes
                    val entry = inspection.previewEntryName
                    if (bytes != null && entry != null && bytes.isNotEmpty()) {
                        val preview = writeVersionedPreview(version, entry, bytes)
                        if (preview != null) previews[file.id] = Uri.fromFile(preview).toString()
                    }
                }

                // Delete preview files belonging to archives that disappeared
                // or changed, so no stale image can ever be shown.
                val expected = previews.values.mapNotNull { raw ->
                    runCatching { File(Uri.parse(raw).path.orEmpty()).name }.getOrNull()
                }.toHashSet()
                previewDir.listFiles()?.forEach { candidate ->
                    if (candidate.name !in expected) runCatching { candidate.delete() }
                }

                results to previews
            }
            _uiState.update { state ->
                state.copy(inspections = snapshot.first, previewUris = snapshot.second)
            }
        }
    }

    /** Preview file name is derived from the FULL archive identity, never "1.webp". */
    private fun writeVersionedPreview(
        version: String,
        entryName: String,
        bytes: ByteArray
    ): File? = try {
        val key = WastickersPreviewKeys.archivePreviewKey(version, entryName)
        val extension = entryName.substringAfterLast('.', "webp")
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(6).ifBlank { "webp" }
        val target = File(previewDir, "p_${Integer.toHexString(key.hashCode())}.$extension")
        if (!target.exists() || target.length() != bytes.size.toLong()) {
            target.writeBytes(bytes)
        }
        target.takeIf { it.exists() && it.length() > 0L }
    } catch (_: Exception) {
        null
    }

    /** Persists one extracted pack using the existing Room + managed storage. */
    private suspend fun persistPack(pack: ImportedPack): Long {
        val normalized = pack.name.replace("[^\\w]".toRegex(), "_").lowercase(Locale.US)
        val shortName = "pack_${normalized.take(24)}_${System.currentTimeMillis() % 100000}"
        val packId = packRepository.createPack(
            StickerPack(
                name = pack.name,
                shortName = shortName,
                author = pack.author,
                defaultEmoji = "✨"
            )
        )

        val items = pack.stickers.mapIndexed { index, sticker ->
            val preferredName = "${sticker.displayName}.${sticker.file.extension}"
            val (stableFile, stableUri) =
                tempFileManager.saveToPackOriginals(packId, sticker.file, preferredName)
            val mime = when (sticker.type) {
                com.example.core.model.StickerType.WEBM -> "video/webm"
                com.example.core.model.StickerType.PNG,
                com.example.core.model.StickerType.JPG -> "image/png"
                else -> "image/webp"
            }
            StickerItem(
                packId = packId,
                originalUri = stableUri.toString(),
                originalName = stableFile.name,
                displayName = sticker.displayName,
                emoji = "✨",
                type = sticker.type,
                originalSize = stableFile.length(),
                width = sticker.width,
                height = sticker.height,
                durationMs = sticker.durationMs,
                sortOrder = index,
                sourceUri = "wastickers://${pack.sourceId}",
                sourceMimeType = mime,
                sourceDisplayName = sticker.displayName
            )
        }
        stickerRepository.addStickers(items)
        _openPackEvents.tryEmit(packId)
        return packId
    }

    /** Re-scans every source and merges the result with the persistent history. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val result = scanner.scanDetailed()
                val outcome = result.outcome
                diagnostics.recordScan(result)

                val now = System.currentTimeMillis()
                val previous = withContext(Dispatchers.IO) { history.load() }
                val merge = withContext(Dispatchers.Default) {
                    WastickersFileOps.mergeDiscovery(previous, outcome.files, now)
                }
                // SMART REFRESH: newly discovered archives go to the top,
                // everything else is preserved (no fabricated dates), and known
                // files are never flagged NEW again.
                val ordered = withContext(Dispatchers.Default) {
                    WastickersFileOps.orderForRefresh(merge.files, merge.newIds)
                }
                withContext(Dispatchers.IO) { history.save(merge.records) }
                withContext(Dispatchers.IO) { defaultSourceStore.markFirstScanCompleted() }

                val permission = when {
                    scanner.hasAnyAccess() -> WastickersPermission.GRANTED
                    result.sources.any { it.isReadable } -> WastickersPermission.GRANTED
                    _uiState.value.permission == WastickersPermission.DENIED ->
                        WastickersPermission.DENIED
                    else -> WastickersPermission.NEEDS_ACCESS
                }
                val availableIds = ordered.map { it.id }.toSet()
                _files.value = ordered
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        files = ordered,
                        selectedIds = state.selectedIds.intersect(availableIds),
                        permission = permission,
                        error = null,
                        inaccessibleFolders = outcome.inaccessibleFolders,
                        invalidFiles = outcome.invalidFiles,
                        sources = result.sources,
                        emptyReason = computeEmptyReason(result, ordered),
                        defaultSource = defaultSourceStore.current(),
                        storagePermission = result.permission?.state
                            ?: permissionManager.check().state,
                        storagePermissionDetail = result.permission?.detail,
                        directDiagnostics = result.directDiagnostics,
                        scannedCount = ordered.size,
                        notice = result.sources
                            .firstOrNull { it.state == WastickersAccessState.PERMISSION_REVOKED }
                            ?.detail
                    )
                }
                inspectVisible(ordered)
                buildPackages(ordered)
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        permission = WastickersPermission.DENIED,
                        emptyReason = WastickersEmptyReason.NO_READ_PERMISSION,
                        error = "تم رفض صلاحية الوصول إلى الملفات"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        emptyReason = WastickersEmptyReason.SCAN_FAILED,
                        error = e.message ?: "تعذر فحص الملفات"
                    )
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* WASTICKERS Explorer: parsing, previews, filtering, permission       */
    /* ------------------------------------------------------------------ */

    /**
     * Parses each discovered archive ONCE and writes its real previews to the
     * cache, then publishes [ExplorerPackage] cards.
     *
     * Runs on Dispatchers.IO, is cancellable, and never blocks the UI thread.
     * Preview images are bounded (MAX_PREVIEWS per package, 512 KB each) so a
     * large gallery cannot exhaust memory.
     */
    private fun buildPackages(files: List<WastickersFile>) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _uiState.update { it.copy(isParsing = files.isNotEmpty()) }
            val built = withContext(Dispatchers.IO) {
                val result = ArrayList<ExplorerPackage>(files.size)
                for (file in files) {
                    if (!isActive) break
                    val info = parsePackage(file)
                    val keys = writePreviews(file, info)
                    val existing = _uiState.value.files.firstOrNull { it.id == file.id }
                    result.add(
                        ExplorerPackage(
                            id = file.id,
                            displayName = file.displayName,
                            packName = info.packName,
                            author = info.author,
                            sizeBytes = file.sizeBytes,
                            lastModified = file.lastModified,
                            source = file.source,
                            location = file.location,
                            stickerCount = info.stickerCount,
                            stickerType = info.stickerType,
                            kind = info.kind,
                            previewKeys = keys,
                            reason = info.reason,
                            isNew = file.isNew,
                            status = existing?.status ?: file.status,
                            file = file
                        )
                    )
                }
                result
            }
            _uiState.update { state ->
                state.copy(
                    packages = built,
                    isParsing = false,
                    visiblePackages = ExplorerQuery.apply(
                        built, state.query, state.filter, state.sort
                    )
                )
            }
        }
    }

    /** Parses via direct File when possible, else via the ContentResolver. */
    private fun parsePackage(file: WastickersFile): WastickersPackageInfo {
        val direct = directPathOf(file)
        if (direct != null) {
            val parsed = WastickersPackageParser.parseFile(direct, file.displayName)
            if (parsed.kind != PackageKind.INVALID || !isContentUri(file.uri)) return parsed
        }
        return WastickersPackageParser.parseStream(file.displayName) {
            try {
                app.contentResolver.openInputStream(Uri.parse(file.uri))
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Absolute path for DIRECT_FILE sources; null for content:// sources. */
    private fun directPathOf(file: WastickersFile): File? {
        if (file.source != WastickersSource.DIRECT_FILE) return null
        if (file.uri.startsWith("file:")) {
            return runCatching { File(java.net.URI(file.uri)) }.getOrNull()
        }
        return null
    }

    private fun isContentUri(uri: String): Boolean = uri.startsWith("content://")

    /**
     * Writes each real preview to the cache with a VERSIONED name so a reused
     * card can never show another pack's image. Returns the cache keys.
     */
    private fun writePreviews(file: WastickersFile, info: WastickersPackageInfo): List<String> {
        if (info.previews.isEmpty()) return emptyList()
        val keys = ArrayList<String>(info.previews.size)
        for (preview in info.previews) {
            if (preview.bytes.isEmpty()) continue
            val key = WastickersPreviewKeys.archivePreview(file, preview.entryName)
            val ext = preview.entryName.substringAfterLast('.', "webp")
                .lowercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() }
                .take(6)
                .ifBlank { "webp" }
            val target = File(previewDir, "p_${Integer.toHexString(key.hashCode())}.$ext")
            try {
                if (!target.exists() || target.length() != preview.bytes.size.toLong()) {
                    target.writeBytes(preview.bytes)
                }
                if (target.exists() && target.length() > 0) {
                    keys.add(key)
                }
            } catch (_: Exception) {
                // A preview that cannot be written is simply skipped; the card
                // still renders with its metadata and the real count.
            }
        }
        return keys
    }

    /** Resolves a preview key to a displayable file Uri (Coil-compatible). */
    fun previewFileUri(key: String): String? {
        val dir = previewDir.listFiles() ?: return null
        val prefix = "p_${Integer.toHexString(key.hashCode())}."
        return dir.firstOrNull { it.name.startsWith(prefix) }
            ?.let { "file://${it.absolutePath}" }
    }

    fun setFilter(filter: ExplorerFilter) {
        _uiState.update { state ->
            state.copy(
                filter = filter,
                visiblePackages = ExplorerQuery.apply(
                    state.packages, state.query, filter, state.sort
                )
            )
        }
    }

    fun setSort(sort: ExplorerSort) {
        _uiState.update { state ->
            state.copy(
                sort = sort,
                visiblePackages = ExplorerQuery.apply(
                    state.packages, state.query, state.filter, sort
                )
            )
        }
    }

    /**
     * Re-checks the all-files permission. MUST be called from onResume: opening
     * the settings screen proves nothing, only this snapshot does.
     */
    fun refreshStoragePermission() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { permissionManager.check() }
            _uiState.update {
                it.copy(
                    storagePermission = snapshot.state,
                    storagePermissionDetail = snapshot.detail
                )
            }
            if (snapshot.state == StoragePermissionState.GRANTED &&
                _uiState.value.packages.isEmpty() && !_uiState.value.isLoading
            ) {
                refresh()
            }
        }
    }

    /** True when the platform has an "all files access" settings screen. */
    fun manageStorageIntent(): android.content.Intent? = permissionManager.settingsIntent()

    /**
     * Explains WHY nothing is shown. Each cause maps to a different remedy, so
     * the UI never falls back to a single generic "no files" message.
     */
    private fun computeEmptyReason(
        result: WastickersScanResult,
        files: List<WastickersFile>
    ): WastickersEmptyReason {
        if (files.isNotEmpty()) return WastickersEmptyReason.NONE
        val sources = result.sources
        val anyReadable = sources.any { it.isReadable }
        return when {
            !anyReadable && sources.none { it.state == WastickersAccessState.NOT_SELECTED } ->
                WastickersEmptyReason.NO_READ_PERMISSION
            !anyReadable && sources.all { it.state == WastickersAccessState.NOT_SELECTED } ->
                WastickersEmptyReason.NO_READ_PERMISSION
            sources.any { it.state == WastickersAccessState.PROVIDER_UNAVAILABLE } ->
                WastickersEmptyReason.PROVIDER_UNAVAILABLE
            sources.any {
                it.state == WastickersAccessState.GRANTED_BUT_UNREADABLE ||
                    it.state == WastickersAccessState.PERMISSION_REVOKED
            } && !anyReadable -> WastickersEmptyReason.FOLDER_UNAVAILABLE
            result.outcome.invalidFiles > 0 -> WastickersEmptyReason.ONLY_INVALID_FILES
            else -> WastickersEmptyReason.NO_MATCHING_FILES
        }
    }

    /**
     * Persists a SAF tree grant ONLY when it is proven usable.
     *
     * Order matters and is the root-cause fix for silently broken sources:
     *  1. takePersistableUriPermission with the flags the picker actually gave;
     *  2. verify the grant is really present in persistedUriPermissions;
     *  3. verify a real read of the tree succeeds;
     *  4. only then store the Uri as a source.
     */
    fun onTreeUriGranted(uri: Uri?, takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        if (uri == null) {
            onPermissionDenied()
            return
        }
        viewModelScope.launch {
            val readFlag = if (takeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            } else {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            val persisted = withContext(Dispatchers.IO) {
                tryPersist(uri, readFlag)
            }
            if (!persisted) {
                _uiState.update {
                    it.copy(
                        permission = WastickersPermission.NEEDS_ACCESS,
                        emptyReason = WastickersEmptyReason.NO_READ_PERMISSION,
                        notice = "تعذّر تثبيت صلاحية المجلد — حاول مرة أخرى",
                        error = null
                    )
                }
                return@launch
            }
            val readable = withContext(Dispatchers.IO) { canListTree(uri) }
            if (readable) {
                withContext(Dispatchers.IO) { treeStore.add(uri) }
                withContext(Dispatchers.IO) { defaultSourceStore.set(WastickersSourceKind.SAF_TREE) }
                refresh()
            } else {
                // Grant is real but the provider cannot list it right now.
                _uiState.update {
                    it.copy(
                        notice = "الصلاحية مثبتة لكن المجلد غير قابل للقراءة حالياً",
                        emptyReason = WastickersEmptyReason.FOLDER_UNAVAILABLE
                    )
                }
                refresh()
            }
        }
    }

    /**
     * Handles ACTION_OPEN_DOCUMENT results. Each document must pass the same
     * three-step proof (persist, verify, read) before it is stored.
     */
    fun onDocumentsSelected(uris: List<Uri>, takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        if (uris.isEmpty()) {
            onPermissionDenied()
            return
        }
        viewModelScope.launch {
            val accepted = ArrayList<Uri>(uris.size)
            var unreadable = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    if (!tryPersist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                        continue
                    }
                    if (canReadDocument(uri)) {
                        accepted.add(uri)
                    } else {
                        unreadable++
                    }
                }
            }
            if (accepted.isNotEmpty()) {
                withContext(Dispatchers.IO) { documentStore.addAll(accepted) }
                withContext(Dispatchers.IO) {
                    defaultSourceStore.set(WastickersSourceKind.USER_DOCUMENTS)
                }
            }
            val notice = when {
                accepted.isEmpty() && unreadable > 0 ->
                    "تعذّرت قراءة $unreadable ملف — تحقق من الصلاحية"
                unreadable > 0 ->
                    "تم قبول ${accepted.size} وتخطّي $unreadable ملف غير قابل للقراءة"
                else -> null
            }
            _uiState.update { it.copy(notice = notice) }
            refresh()
        }
    }

    /** @return true when the persisted read grant is confirmed for [uri]. */
    private fun tryPersist(uri: Uri, flags: Int): Boolean {
        return try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
            hasPersistedGrant(uri)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "takePersistableUriPermission denied for $uri", e)
            false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "takePersistableUriPermission failed for $uri", e)
            false
        }
    }

    /** Reads persistedUriPermissions instead of assuming the grant worked. */
    private fun hasPersistedGrant(uri: Uri): Boolean = try {
        val target = uri.toString()
        app.contentResolver.persistedUriPermissions.any { grant ->
            grant.isReadPermission && grant.uri.toString() == target
        }
    } catch (_: Exception) {
        false
    }

    /** Verifies a tree can really be listed through DocumentsContract. */
    private fun canListTree(uri: Uri): Boolean = try {
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return false
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            uri, rootId
        )
        app.contentResolver.query(
            children,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null
        )?.use { it.moveToFirst() }
        true
    } catch (_: Exception) {
        false
    }

    /** Verifies a picked document is really readable through the ContentResolver. */
    private fun canReadDocument(uri: Uri): Boolean = try {
        app.contentResolver.openInputStream(uri)?.use { stream ->
            stream.read()
            true
        } ?: false
    } catch (e: Exception) {
        android.util.Log.w(TAG, "document not readable: $uri", e)
        false
    }

    /** Sets the persisted default source and refreshes. */
    fun setDefaultSource(kind: WastickersSourceKind) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { defaultSourceStore.set(kind) }
            _uiState.update { it.copy(defaultSource = kind) }
            refresh()
        }
    }

    /** Writes and shares WASTICKERS_DIAGNOSTIC_REPORT.txt. */
    fun shareDiagnosticReport() {
        viewModelScope.launch {
            val ok = diagnostics.shareReport()
            if (!ok) {
                _uiState.update {
                    it.copy(notice = "تعذّر إنشاء تقرير التشخيص")
                }
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun onLegacyPermissionResult(granted: Boolean) {
        if (!granted) {
            onPermissionDenied()
        } else {
            refresh()
        }
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(
                permission = WastickersPermission.DENIED,
                error = "تم رفض الصلاحية — يمكنك اختيار مجلد أو ملف يدوياً"
            )
        }
    }

    fun setQuery(query: String) {
        // The text field updates immediately; the debounced pipeline rebuilds
        // the visible list after SEARCH_DEBOUNCE_MS without touching storage.
        _query.value = query
        _uiState.update { state ->
            state.copy(
                query = query,
                visiblePackages = ExplorerQuery.apply(
                    state.packages, query, state.filter, state.sort
                )
            )
        }
    }

    /**
     * Called from the screen's DisposableEffect when the composable leaves the
     * composition (not only on ViewModel.onCleared). Navigating away and back
     * therefore never restores a stale selection and never imports packs the
     * user selected during a previous visit.
     */
    fun clearSelectionOnLeave() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun toggleSelection(id: String) {
        _uiState.update {
            it.copy(selectedIds = WastickersFileOps.toggleSelection(it.selectedIds, id))
        }
    }

    /** Selects all currently visible (filtered) files. */
    fun selectAll(ids: Collection<String>) {
        _uiState.update {
            it.copy(selectedIds = WastickersFileOps.selectAll(it.selectedIds, ids))
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    /** Clears the NEW badge but keeps the discovery date in history. */
    fun markNewAsSeen() {
        viewModelScope.launch {
            val newIds = _uiState.value.files.filter { it.isNew }.map { it.id }
            withContext(Dispatchers.IO) { history.markSeen(newIds) }
            _uiState.update { state ->
                state.copy(files = state.files.map { if (it.isNew) it.copy(isNew = false) else it })
            }
        }
    }

    /** Imports every selected archive in one bounded, cancellable operation. */
    fun importSelected() {
        val selected = _uiState.value.files.filter { it.id in _uiState.value.selectedIds }
        startImport(selected)
    }

    /** Per-file import button: imports just this archive, without altering the
     *  user's multi-selection. Duplicate protection is handled by the hash log. */
    fun importSingle(id: String) {
        val file = _uiState.value.files.firstOrNull { it.id == id } ?: return
        startImport(listOf(file))
    }

    private fun startImport(files: List<WastickersFile>) {
        if (files.isEmpty()) return
        importJob?.cancel()
        importJob = importer.import(files)
            .onEach { progress ->
                _uiState.update { state ->
                    val updated = state.files.map { file ->
                        progress.perFile[file.id]?.let { file.copy(status = it.status) } ?: file
                    }
                    state.copy(
                        files = updated,
                        import = progress,
                        importReport = WastickersFileOps.buildImportReport(progress),
                        currentImportName = WastickersFileOps.currentImportLabel(updated, progress)
                    )
                }
            }
            .onCompletion { cause ->
                _uiState.update { state ->
                    val progress = state.import
                    if (progress == null) {
                        state
                    } else {
                        val terminal = if (cause is CancellationException) {
                            progress.copy(isRunning = false, isCancelled = true)
                        } else {
                            progress.copy(isRunning = false)
                        }
                        state.copy(
                            import = terminal,
                            importReport = WastickersFileOps.buildImportReport(terminal),
                            currentImportName = null
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _uiState.update { state ->
            val terminal = state.import?.copy(isRunning = false, isCancelled = true)
            state.copy(
                import = terminal,
                importReport = terminal?.let { WastickersFileOps.buildImportReport(it) },
                currentImportName = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        importJob?.cancel()
        inspectionJob?.cancel()
        parseJob?.cancel()
    }

    private companion object {
        const val TAG = "WastickersImportVM"
        const val SEARCH_DEBOUNCE_MS = 180L
        /** Only the visible top of the list is inspected lazily per refresh. */
        const val MAX_INSPECTIONS_PER_SCAN = 60
    }
}
