package com.example.wastickers

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real diagnostics for WASTICKERS discovery.
 *
 * Every scan records the Android version, each source with its Uri and access
 * state, the number of folders/nodes/files examined, unreadable files, error
 * count and exception details. Nothing is invented: the values come from
 * [WastickersScanResult] produced by the actual scan.
 *
 * The report is written to the app's cache as
 * WASTICKERS_DIAGNOSTIC_REPORT.txt and can be shared by the user.
 *
 * Privacy: only storage Uris and file names the user picked are included.
 * No contacts, no accounts, no message contents and no device identifiers.
 */
class WastickersDiagnosticManager(private val context: Context) {

    /** Last scan result, kept for the report even after the UI refreshes. */
    @Volatile
    var lastScan: WastickersScanResult? = null
        private set

    @Volatile
    var lastImportSummary: String? = null
        private set

    fun recordScan(result: WastickersScanResult) {
        lastScan = result
    }

    fun recordImport(summary: String) {
        lastImportSummary = summary
    }

    /** Builds the report text. Pure formatting, no storage access. */
    fun buildReport(): String {
        val scan = lastScan
        val builder = StringBuilder()
        builder.appendLine("================================================================")
        builder.appendLine("WASTICKERS_DIAGNOSTIC_REPORT")
        builder.appendLine("Generated: ${formatNow()}")
        builder.appendLine("================================================================")
        builder.appendLine()
        builder.appendLine("[Environment]")
        builder.appendLine("Android SDK       : ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        builder.appendLine("Device            : ${Build.MANUFACTURER} ${Build.MODEL}")
        builder.appendLine("App package       : ${context.packageName}")
        builder.appendLine("Legacy read perm  : " +
            (if (Build.VERSION.SDK_INT <= 32) "applicable (API <= 32)" else "not applicable (API 33+)"))
        builder.appendLine()

        if (scan == null) {
            builder.appendLine("[Scan]")
            builder.appendLine("No scan has been performed in this session yet.")
            builder.appendLine("Open the WASTICKERS screen and press Refresh to populate this report.")
            builder.appendLine()
        } else {
            val d = scan.diagnostics
            builder.appendLine("[Scan summary]")
            builder.appendLine("Duration              : ${d.durationMs} ms")
            builder.appendLine("Download queried      : ${d.downloadQueried}")
            builder.appendLine("MediaStore queried    : ${d.mediaStoreQueried}")
            builder.appendLine("Folders scanned       : ${d.foldersScanned}")
            builder.appendLine("Nodes scanned         : ${d.nodesScanned}")
            builder.appendLine("WASTICKERS found      : ${d.wastickersFound}")
            builder.appendLine("Unreadable files      : ${d.unreadableFiles}")
            builder.appendLine("Errors                : ${d.errors}")
            builder.appendLine("Invalid (non-wst)     : ${scan.outcome.invalidFiles}")
            builder.appendLine("Inaccessible folders  : ${scan.outcome.inaccessibleFolders}")
            builder.appendLine("Probed (unknown) dirs : ${scan.outcome.probedFolders}")
            if (d.downloadError != null) {
                builder.appendLine("Download error        : ${d.downloadError}")
            }
            if (d.mediaStoreError != null) {
                builder.appendLine("MediaStore error      : ${d.mediaStoreError}")
            }
            builder.appendLine()

            builder.appendLine("[Sources]")
            if (scan.sources.isEmpty()) {
                builder.appendLine("(no source was probed)")
            }
            for (source in scan.sources) {
                builder.appendLine("Source     : ${source.kind}")
                builder.appendLine("  Label    : ${source.label}")
                builder.appendLine("  URI      : ${source.uri ?: "(none)"}")
                builder.appendLine("  State    : ${source.state}")
                builder.appendLine("  Files    : ${source.filesFound}")
                builder.appendLine("  Default  : ${source.isDefault}")
                if (source.detail != null) {
                    builder.appendLine("  Detail   : ${source.detail}")
                }
                builder.appendLine()
            }

            builder.appendLine("[Access verification]")
            for (source in scan.sources) {
                val persisted = source.uri?.let { describePersisted(it) } ?: "n/a (not a SAF Uri)"
                builder.appendLine("${source.kind}: persisted=${persisted}")
            }
            builder.appendLine()

            if (d.errorDetails.isNotEmpty()) {
                builder.appendLine("[Exception details]")
                d.errorDetails.forEach { builder.appendLine(" - $it") }
                builder.appendLine()
            }
        }

        builder.appendLine("[Last import]")
        builder.appendLine(lastImportSummary ?: "(no import in this session)")
        builder.appendLine()
        builder.appendLine("================================================================")
        builder.appendLine("End of report")
        builder.appendLine("================================================================")
        return builder.toString()
    }

    /** Writes the report file and returns it, or null when writing failed. */
    suspend fun writeReport(): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, REPORT_FILE_NAME)
            file.writeText(buildReport())
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes the report and launches a system share sheet. Returns false when
     * the report could not be produced so the UI can show a real error.
     */
    suspend fun shareReport(): Boolean = withContext(Dispatchers.IO) {
        val file = writeReport() ?: return@withContext false
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, REPORT_FILE_NAME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, REPORT_FILE_NAME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun describePersisted(uriString: String): String {
        return try {
            val uri = android.net.Uri.parse(uriString)
            val grant = context.contentResolver.persistedUriPermissions
                .any { it.isReadPermission && it.uri.toString() == uriString }
            if (grant) "yes" else "NO (not persisted)"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun formatNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        const val REPORT_FILE_NAME = "WASTICKERS_DIAGNOSTIC_REPORT.txt"
    }
}
