package com.example.wastickers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Result of a REAL permission check.
 *
 * The value is derived from Environment.isExternalStorageManager() and a real
 * read attempt — never from "the settings screen was opened", which proves
 * nothing about the actual grant.
 */
enum class StoragePermissionState {
    /** MANAGE_EXTERNAL_STORAGE is granted on API 30+ (or legacy on API < 30). */
    GRANTED,
    /** Not granted: the app must send the user to the system settings screen. */
    DENIED,
    /** The OS does not expose all-files access (always reported as such). */
    UNSUPPORTED
}

data class StoragePermissionSnapshot(
    val state: StoragePermissionState,
    val sdkInt: Int,
    /** Root of the shared storage that was probed, when known. */
    val probedRoot: String?,
    /** True when a real directory listing succeeded (proof, not assumption). */
    val readVerified: Boolean,
    val detail: String? = null
)

/**
 * Central source of truth for the all-files permission.
 *
 * Uses Environment.isExternalStorageManager() on API 30+ and the classic
 * READ/WRITE_EXTERNAL_STORAGE permission below that. After the user returns
 * from system settings the state MUST be re-checked; [check] is cheap and safe
 * to call from onResume.
 *
 * No Shizuku, no Root: only the sanctioned Android APIs are used.
 */
class StoragePermissionManager(private val context: Context) {

    @SuppressLint("SdCardPath")
    fun check(): StoragePermissionSnapshot {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.R) {
            // Legacy model: storage is a normal runtime permission.
            val granted = hasLegacyStoragePermission()
            return StoragePermissionSnapshot(
                state = if (granted) {
                    StoragePermissionState.GRANTED
                } else {
                    StoragePermissionState.DENIED
                },
                sdkInt = sdk,
                probedRoot = primaryRootPath(),
                readVerified = granted && canList(primaryRootPath()),
                detail = if (granted) null else "لم تُمنح صلاحية التخزين"
            )
        }

        val managerGranted = try {
            Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
        val root = primaryRootPath()
        val readVerified = managerGranted && canList(root)
        return StoragePermissionSnapshot(
            state = if (managerGranted) {
                StoragePermissionState.GRANTED
            } else {
                StoragePermissionState.DENIED
            },
            sdkInt = sdk,
            probedRoot = root,
            readVerified = readVerified,
            detail = when {
                !managerGranted ->
                    "صلاحية الوصول إلى كل الملفات غير ممنوحة — افتح الإعدادات وامنحها"
                !readVerified ->
                    "الصلاحية ممنوحة لكن تعذّرت قراءة جذر التخزين"
                else -> null
            }
        )
    }

    fun isGranted(): Boolean = check().state == StoragePermissionState.GRANTED

    /**
     * Intent that opens the system "All files access" screen for this app.
     * Returns null when the platform has no such screen, so the caller can fall
     * back instead of silently doing nothing.
     */
    fun settingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Exception) {
            try {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun hasLegacyStoragePermission(): Boolean {
        val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        return read == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun primaryRootPath(): String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    /** Real read probe: the directory must exist and list at least zero items. */
    private fun canList(path: String?): Boolean {
        if (path == null) return false
        return try {
            val dir = File(path)
            dir.exists() && dir.isDirectory && dir.canRead() && dir.listFiles() != null
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
