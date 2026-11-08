package com.example.wastickers

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Access outcome for ONE scanned folder. Empty is never confused with denied. */
enum class FolderProbeResult {
    /** Listed successfully (may still contain zero matching files). */
    OK,
    /** File.exists() was false: the folder is genuinely empty or absent. */
    MISSING,
    /** The folder exists but unreadable: SecurityException / cannot list. */
    DENIED,
    /** listFiles() threw (I/O error, provider issue). */
    ERROR
}

/** One folder that was probed, with the reason it produced what it produced. */
data class FolderDiagnostic(
    val path: String,
    val result: FolderProbeResult,
    val filesSeen: Int = 0,
    val dirsSeen: Int = 0,
    val wastickersSeen: Int = 0,
    val error: String? = null
)

data class AdvancedDiagnostics(
    val startedAt: Long,
    val finishedAt: Long,
    val permissionGranted: Boolean,
    val rootsScanned: List<String>,
    val folders: List<FolderDiagnostic>,
    val filesExamined: Long,
    val filesMatched: Long,
    val dirsExamined: Long,
    val depthLimit: Int,
    val truncated: Boolean,
    val errors: List<String>
) {
    val durationMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)

    /** Folders that could NOT be read — surfaced, never silently dropped. */
    val deniedFolders: List<FolderDiagnostic>
        get() = folders.filter {
            it.result == FolderProbeResult.DENIED || it.result == FolderProbeResult.ERROR
        }
}

data class AdvancedDiscoveryResult(
    val packages: List<DirectWastickersFile>,
    val diagnostics: AdvancedDiagnostics
)

/**
 * A .wastickers file found through DIRECT file access.
 *
 * [path] is a real absolute path, so reading never needs a ContentResolver and
 * no content:// Uri is ever converted into a fake File.
 */
data class DirectWastickersFile(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** Where it was found: "/storage/emulated/0/Download" etc. */
    val parentPath: String
) {
    val file: File get() = File(path)
}

/**
 * Central, direct-file discovery engine used once MANAGE_EXTERNAL_STORAGE is
 * granted.
 *
 * WHY this exists: on a real device (Android 12 / API 31) the SAF-only path
 * cannot see files written by other apps unless the user picks them one by one.
 * With all-files access the app may walk the shared storage directly, which is
 * the reliable channel the task requires.
 *
 * Behaviour:
 *  - discovers the available storage roots dynamically (never one hard-coded
 *    "/storage/emulated/0" assumption);
 *  - walks a bounded, configurable depth, following no symlinks;
 *  - skips heavy noise directories (.thumbnails, caches) to stay fast;
 *  - distinguishes EMPTY vs DENIED vs ERROR for every folder;
 *  - is cancellable and runs entirely on Dispatchers.IO;
 *  - never converts a read error into an empty list without reporting it.
 */
class AdvancedStorageDiscoveryEngine(
    private val context: Context,
    private val permissionManager: StoragePermissionManager = StoragePermissionManager(context)
) {

    /** Walks [roots] and returns every .wastickers archive with diagnostics. */
    suspend fun discover(
        roots: List<File> = availableRoots(),
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxFiles: Long = DEFAULT_MAX_FILES,
        onProgress: (Int) -> Unit = {}
    ): AdvancedDiscoveryResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val scanJob = currentCoroutineContext()
        val snapshot = permissionManager.check()
        val diagnosticsFolders = ArrayList<FolderDiagnostic>()
        val errors = ArrayList<String>()
        val found = ArrayList<DirectWastickersFile>()
        val visited = HashSet<String>()
        var filesExamined = 0L
        var dirsExamined = 0L
        var truncated = false

        val effectiveRoots = if (snapshot.state == StoragePermissionState.GRANTED) {
            roots.filter { it.exists() }
        } else {
            // Without all-files access we still probe the app-visible roots so
            // the UI can explain exactly what is missing.
            roots.filter { runCatching { it.exists() && it.canRead() }.getOrDefault(false) }
        }
        if (snapshot.state != StoragePermissionState.GRANTED) {
            errors.add(
                "MANAGE_EXTERNAL_STORAGE not granted — direct scan limited " +
                    "(state=${snapshot.state})"
            )
        }

        suspend fun walk(dir: File, depth: Int) {
            if (!isScanActive()) return
            if (depth > maxDepth) return
            if (filesExamined >= maxFiles) {
                truncated = true
                return
            }
            val canonical = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            if (!visited.add(canonical)) return

            // --- Probe the folder for real, distinguishing every outcome. ---
            val exists = try {
                dir.exists()
            } catch (e: SecurityException) {
                diagnosticsFolders.add(
                    FolderDiagnostic(dir.absolutePath, FolderProbeResult.DENIED, error = e.message)
                )
                errors.add("SecurityException exists(): ${dir.absolutePath}: ${e.message}")
                return
            }
            if (!exists) {
                diagnosticsFolders.add(
                    FolderDiagnostic(dir.absolutePath, FolderProbeResult.MISSING)
                )
                return
            }
            if (!safeIsDirectory(dir)) {
                diagnosticsFolders.add(
                    FolderDiagnostic(dir.absolutePath, FolderProbeResult.MISSING,
                        error = "not a directory")
                )
                return
            }
            val children: Array<File>? = try {
                dir.listFiles()
            } catch (e: SecurityException) {
                diagnosticsFolders.add(
                    FolderDiagnostic(dir.absolutePath, FolderProbeResult.DENIED, error = e.message)
                )
                errors.add("SecurityException listFiles(): ${dir.absolutePath}: ${e.message}")
                null
            } catch (e: Exception) {
                diagnosticsFolders.add(
                    FolderDiagnostic(dir.absolutePath, FolderProbeResult.ERROR, error = e.message)
                )
                errors.add("listFiles() failed: ${dir.absolutePath}: ${e.message}")
                null
            }
            if (children == null) {
                if (diagnosticsFolders.none { it.path == dir.absolutePath }) {
                    diagnosticsFolders.add(
                        FolderDiagnostic(
                            dir.absolutePath,
                            FolderProbeResult.DENIED,
                            error = "listFiles() returned null"
                        )
                    )
                }
                return
            }

            var seenFiles = 0
            var seenDirs = 0
            var seenWst = 0
            for (child in children) {
                if (!isScanActive()) return
                if (filesExamined >= maxFiles) {
                    truncated = true
                    return
                }
                val isDir = safeIsDirectory(child)
                if (isDir) {
                    seenDirs++
                    dirsExamined++
                    if (!isNoiseDirectory(child.name)) {
                        walk(child, depth + 1)
                    }
                } else {
                    seenFiles++
                    filesExamined++
                    if (WastickersFileOps.isWastickersName(child.name)) {
                        if (safeCanRead(child)) {
                            seenWst++
                            val size = runCatching { child.length() }.getOrDefault(0L)
                            val modified = runCatching { child.lastModified() }.getOrDefault(0L)
                            found.add(
                                DirectWastickersFile(
                                    path = child.absolutePath,
                                    displayName = WastickersFileOps.sanitizeName(child.name),
                                    sizeBytes = size,
                                    lastModified = modified,
                                    parentPath = dir.absolutePath
                                )
                            )
                            onProgress(found.size)
                        } else {
                            // A file we cannot read is NOT a missing file.
                            seenWst++
                            errors.add("Unreadable .wastickers file: ${child.absolutePath}")
                            diagnosticsFolders.add(
                                FolderDiagnostic(
                                    dir.absolutePath,
                                    FolderProbeResult.DENIED,
                                    error = "unreadable file ${child.name}"
                                )
                            )
                        }
                    }
                }
            }
            diagnosticsFolders.add(
                FolderDiagnostic(
                    path = dir.absolutePath,
                    result = FolderProbeResult.OK,
                    filesSeen = seenFiles,
                    dirsSeen = seenDirs,
                    wastickersSeen = seenWst
                )
            )
        }

        for (root in effectiveRoots) {
            if (!scanJob.isActive) break
            walk(root, 0)
        }

        AdvancedDiscoveryResult(
            packages = found.sortedWith(
                compareByDescending<DirectWastickersFile> { it.lastModified }
                    .thenBy { it.displayName.lowercase() }
            ),
            diagnostics = AdvancedDiagnostics(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                permissionGranted = snapshot.state == StoragePermissionState.GRANTED,
                rootsScanned = effectiveRoots.map { it.absolutePath },
                folders = diagnosticsFolders,
                filesExamined = filesExamined,
                filesMatched = found.size.toLong(),
                dirsExamined = dirsExamined,
                depthLimit = maxDepth,
                truncated = truncated,
                errors = errors
            )
        )
    }

    /**
     * Storage roots discovered at runtime.
     *
     * Primary external storage plus every removable/external volume the system
     * reports. Android/media is included because many chat apps drop stickers
     * there. Duplicates are removed so no volume is scanned twice.
     */
    fun availableRoots(): List<File> {
        val roots = LinkedHashSet<String>()
        fun add(path: String?) {
            if (!path.isNullOrBlank()) roots.add(path)
        }

        add(runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            runCatching { context.getExternalFilesDirs(null) }
                .getOrNull()
                ?.forEach { dir ->
                    // /storage/emulated/0/Android/data/<pkg>/files -> /storage/emulated/0
                    dir?.absolutePath?.let { path ->
                        val marker = "/Android/data/"
                        val idx = path.indexOf(marker)
                        if (idx > 0) add(path.substring(0, idx))
                    }
                }
        }
        runCatching { File("/storage") }
            .getOrNull()
            ?.listFiles()
            ?.filter { it.isDirectory && !it.name.equals("emulated", true) &&
                !it.name.equals("self", true) }
            ?.forEach { add(it.absolutePath) }
        add("/storage/emulated/0")

        return roots.map { File(it) }
            .filter { runCatching { it.exists() }.getOrDefault(false) }
    }

    /**
     * Heavy, irrelevant directories are skipped by their LEAF name only.
     * "Android" itself is walked (Android/media is a real sticker location),
     * but the per-package data/obb/cache subtrees are pruned for speed.
     */
    private fun isNoiseDirectory(name: String): Boolean {
        val lower = name.lowercase()
        return lower in NOISE_DIR_NAMES ||
            lower == "cache" ||
            lower == "caches" ||
            lower.startsWith(".thumbnails")
    }

    private fun safeIsDirectory(file: File): Boolean = try {
        file.isDirectory
    } catch (_: Exception) {
        false
    }

    private fun safeCanRead(file: File): Boolean = try {
        file.canRead()
    } catch (_: Exception) {
        false
    }

    /** Suspending cancellation probe. Never swallows a cancellation signal. */
    private suspend fun isScanActive(): Boolean = currentCoroutineContext().isActive

    private companion object {
        const val DEFAULT_MAX_DEPTH = 8
        const val DEFAULT_MAX_FILES = 60000L
        val NOISE_DIR_NAMES = setOf(
            ".thumbnails", "thumbnails", "lost.dir", ".trash", ".trash-1000",
            // Leaf names only. Only names that can NEVER hold a user pack are
            // pruned: skipping a real pack folder is a false negative and must
            // be avoided, so generic names like "files" are NOT excluded.
            "obb", "no_backup", "code_cache"
        )
    }
}
