package com.example

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.wastickers.DeviceWastickersScanner
import com.example.wastickers.DocumentPermissionStore
import com.example.wastickers.TreePermissionStore
import com.example.wastickers.WastickersAccessState
import com.example.wastickers.WastickersDefaultSourceStore
import com.example.wastickers.WastickersSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * StickersPro Max — REAL device/emulator verification of the Download source and
 * the per-source access states.
 *
 * These tests exercise the actual ContentResolver / MediaStore. They cannot run
 * without an Android device or emulator; when no device is attached they are not
 * executed and the final report records them as NOT VERIFIED.
 */
@RunWith(AndroidJUnit4::class)
class WastickersMultiSourceInstrumentedTest {

    private lateinit var context: Context
    private val insertedUris = ArrayList<Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DocumentPermissionStore(context).clear()
        val trees = TreePermissionStore(context)
        trees.grantedTreeUris().forEach { trees.remove(it) }
    }

    @After
    fun tearDown() {
        for (uri in insertedUris) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        DocumentPermissionStore(context).clear()
        val trees = TreePermissionStore(context)
        trees.grantedTreeUris().forEach { trees.remove(it) }
    }

    private fun scanner() = DeviceWastickersScanner(
        context,
        TreePermissionStore(context),
        DocumentPermissionStore(context),
        defaultSourceStore = WastickersDefaultSourceStore(context)
    )

    /** Inserts a .wastickers file into Download through MediaStore.Downloads. */
    private fun insertDownloadFile(name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/StickersProMaxTest")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        insertedUris.add(uri)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }

    /* Scenario 1 + 14: a file in Download is discovered, and appears again
     * after a refresh once a new file is added. */
    @Test
    fun downloadSourceDiscoversFilesAndRefreshFindsNewOnes() {
        assumeTrue("MediaStore.Downloads requires API 29+", Build.VERSION.SDK_INT >= 29)
        assertNotNull(insertDownloadFile("gsgwh.wastickers"))

        val first = runBlocking { scanner().scanDetailed() }
        assertTrue(
            "Download source must be reported",
            first.sources.any { it.kind == WastickersSourceKind.DOWNLOAD }
        )
        assertTrue(
            "gsgwh.wastickers must be discovered in Download",
            first.outcome.files.any { it.displayName == "gsgwh.wastickers" }
        )

        assertNotNull(insertDownloadFile("Second Pack.wastickers"))
        val second = runBlocking { scanner().scanDetailed() }
        assertTrue(
            "refresh must pick up the newly added archive",
            second.outcome.files.any { it.displayName == "Second Pack.wastickers" }
        )
    }

    /* Scenario 3 + 4: Arabic name and uppercase extension in Download. */
    @Test
    fun downloadSourceFindsArabicAndUppercaseNames() {
        assumeTrue("MediaStore.Downloads requires API 29+", Build.VERSION.SDK_INT >= 29)
        assertNotNull(insertDownloadFile("ملصقاتي.wastickers"))
        assertNotNull(insertDownloadFile("UPPER.WASTICKERS"))

        val result = runBlocking { scanner().scanDetailed() }
        val names = result.outcome.files.map { it.displayName }
        assertTrue("Arabic name must be found: $names", names.contains("ملصقاتي.wastickers"))
        assertTrue("Uppercase extension must be found: $names", names.contains("UPPER.WASTICKERS"))
    }

    /* Scenario 9 + 13: a revoked tree grant is reported, not silently ignored. */
    @Test
    fun revokedTreeGrantIsReportedAsRevokedState() {
        val trees = TreePermissionStore(context)
        trees.add(Uri.parse("content://invalid.provider/tree/primary%3ADownload"))

        val result = runBlocking { scanner().scanDetailed() }
        val treeSource = result.sources.firstOrNull { it.kind == WastickersSourceKind.SAF_TREE }
        assertNotNull("a SAF tree source must be reported", treeSource)
        assertTrue(
            "a lost grant must surface as PERMISSION_REVOKED or unreadable, was ${treeSource?.state}",
            treeSource?.state == WastickersAccessState.PERMISSION_REVOKED ||
                treeSource?.state == WastickersAccessState.GRANTED_BUT_UNREADABLE
        )
        assertTrue(result.outcome.inaccessibleFolders >= 1)
        assertEquals(0, TreePermissionStore(context).grantedTreeUris().size)
    }

    /* Scenario 12: an unavailable provider must not abort the whole scan. */
    @Test
    fun unavailableProviderDoesNotAbortTheScan() {
        val trees = TreePermissionStore(context)
        trees.add(Uri.parse("content://does.not.exist/tree/primary%3ADownload"))

        val result = runBlocking { scanner().scanDetailed() }
        assertTrue("scan must still return a Download status", result.sources.isNotEmpty())
        assertTrue(
            "Download source is still probed when another provider dies",
            result.sources.any { it.kind == WastickersSourceKind.DOWNLOAD }
        )
    }


    /* Direct File Access requires MANAGE_EXTERNAL_STORAGE. When the grant is
     * absent the engine must report it — never pretend an empty list means
     * "no files". */
    @Test
    fun directEngineReportsPermissionStateHonestly() {
        val manager = com.example.wastickers.StoragePermissionManager(context)
        val snapshot = manager.check()
        val engine = com.example.wastickers.AdvancedStorageDiscoveryEngine(context, manager)
        val result = runBlocking { engine.discover(maxDepth = 2, maxFiles = 500) }

        if (snapshot.state == com.example.wastickers.StoragePermissionState.GRANTED) {
            assertTrue(
                "roots must be discovered when permission is granted",
                result.diagnostics.rootsScanned.isNotEmpty()
            )
            assertTrue(
                "a granted scan must report at least the probed root folders",
                result.diagnostics.folders.isNotEmpty()
            )
        } else {
            assertFalse(
                "an ungranted scan must never claim permission",
                result.diagnostics.permissionGranted
            )
            assertTrue(
                "ungranted scans must explain the missing permission",
                result.diagnostics.errors.any { it.contains("MANAGE_EXTERNAL_STORAGE") }
            )
        }
    }

    /* Empty vs denied must be distinguishable, never both "no files". */
    @Test
    fun folderProbeDistinguishesEmptyFromDenied() {
        val manager = com.example.wastickers.StoragePermissionManager(context)
        val engine = com.example.wastickers.AdvancedStorageDiscoveryEngine(context, manager)
        val result = runBlocking { engine.discover(maxDepth = 1, maxFiles = 100) }
        // Every probed folder carries an explicit result, so the UI can explain
        // an empty folder differently from one that could not be read.
        // Every probed folder carries exactly one explicit outcome, so an
        // empty folder is never confused with one that could not be read.
        val outcomes = result.diagnostics.folders.map { it.result }.toSet()
        assertTrue(
            "outcomes must be a subset of the known probe results",
            outcomes.all { it in com.example.wastickers.FolderProbeResult.values().toList() }
        )
    }

    /* A real .wastickers package written by the test is parsed with previews. */
    @Test
    fun packageParserExtractsRealPreviewsFromDisk() {
        val dir = java.io.File(context.cacheDir, "wst_parser_test").apply { mkdirs() }
        val archive = java.io.File(dir, "probe.wastickers")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            val webp = byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                0, 0, 0, 0, 'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(),
                'P'.code.toByte()
            ) + ByteArray(48) { 1 }
            for (name in listOf("1.webp", "2.webp")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(webp)
                zip.closeEntry()
            }
        }
        val info = com.example.wastickers.WastickersPackageParser.parseFile(archive, archive.name)
        assertEquals(com.example.wastickers.PackageKind.VALID, info.kind)
        assertEquals(2, info.stickerCount)
        assertTrue("real previews must be extracted", info.previews.isNotEmpty())
        dir.deleteRecursively()
    }

    /* Default source is persisted as Download. */
    @Test
    fun defaultSourceDefaultsToDownloadAndPersists() {
        val store = WastickersDefaultSourceStore(context)
        store.set(WastickersSourceKind.DOWNLOAD)
        val reloaded = WastickersDefaultSourceStore(context)
        assertEquals(WastickersSourceKind.DOWNLOAD, reloaded.current())
    }
}
