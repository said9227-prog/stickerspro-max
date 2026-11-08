package com.example

import com.example.wastickers.DiscoveryOutcome
import com.example.wastickers.WastickersAccessState
import com.example.wastickers.WastickersEmptyReason
import com.example.wastickers.WastickersFile
import com.example.wastickers.WastickersFileOps
import com.example.wastickers.WastickersMimeTypes
import com.example.wastickers.WastickersScanDiagnostics
import com.example.wastickers.WastickersScanResult
import com.example.wastickers.WastickersSource
import com.example.wastickers.WastickersSourceKind
import com.example.wastickers.WastickersSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StickersPro Max — multi-source discovery contract.
 *
 * These tests cover the PURE logic of the new storage layer:
 *  - the six access states and their readable/not-readable semantics,
 *  - a source failing without hiding the files of another source,
 *  - Download being the default source kind,
 *  - the empty-list reason being specific, never generic,
 *  - the picker MIME filter staying * / * so .wastickers is never hidden.
 *
 * The Android-dependent behaviour (real ContentResolver, MediaStore, SAF
 * grants) is NOT provable here and is covered by the instrumentation tests.
 */
class WastickersSourceStatusTest {

    private fun status(
        kind: WastickersSourceKind = WastickersSourceKind.DOWNLOAD,
        state: WastickersAccessState = WastickersAccessState.GRANTED_AND_READABLE,
        files: Int = 0,
        isDefault: Boolean = false
    ) = WastickersSourceStatus(
        kind = kind,
        label = kind.name,
        state = state,
        filesFound = files,
        isDefault = isDefault
    )

    /* 1. All states exist and ONLY GRANTED_AND_READABLE counts as readable. */
    @Test
    fun `every access state maps to the right readability`() {
        val all = WastickersAccessState.values()
        // 6 original states + MANAGE_EXTERNAL_STORAGE-missing state.
        assertEquals(7, all.size)
        assertTrue(status(state = WastickersAccessState.GRANTED_AND_READABLE).isReadable)
        assertFalse(status(state = WastickersAccessState.GRANTED_BUT_UNREADABLE).isReadable)
        assertFalse(status(state = WastickersAccessState.TEMPORARY_ACCESS).isReadable)
        assertFalse(status(state = WastickersAccessState.PERMISSION_REVOKED).isReadable)
        assertFalse(status(state = WastickersAccessState.PROVIDER_UNAVAILABLE).isReadable)
        assertFalse(status(state = WastickersAccessState.NOT_SELECTED).isReadable)
        assertFalse(status(state = WastickersAccessState.NO_PERMISSION_MANAGE).isReadable)
    }

    /* 2. All source kinds exist, including Direct File Access + Download. */
    @Test
    fun `source kinds include direct file and download`() {
        val kinds = WastickersSourceKind.values().map { it.name }
        assertEquals(4, kinds.size)
        assertTrue(kinds.contains("DIRECT_FILE"))
        assertTrue(kinds.contains("DOWNLOAD"))
        assertTrue(kinds.contains("SAF_TREE"))
        assertTrue(kinds.contains("USER_DOCUMENTS"))
    }

    /* 3. A failing source does not hide another source's files. */
    @Test
    fun `one failing source does not hide files from another`() {
        val sources = listOf(
            status(WastickersSourceKind.DOWNLOAD, WastickersAccessState.GRANTED_BUT_UNREADABLE, 0),
            status(WastickersSourceKind.SAF_TREE, WastickersAccessState.GRANTED_AND_READABLE, 3)
        )
        val totalFiles = sources.sumOf { it.filesFound }
        assertEquals(3, totalFiles)
        assertTrue(sources.any { it.isReadable })
    }

    /* 4. The empty-state reasons are all distinct and cover the required set. */
    @Test
    fun `empty reasons cover every required failure cause`() {
        val reasons = WastickersEmptyReason.values().map { it.name }.toSet()
        for (required in listOf(
            "NO_MATCHING_FILES",
            "NO_READ_PERMISSION",
            "FOLDER_UNAVAILABLE",
            "PROVIDER_UNAVAILABLE",
            "ONLY_INVALID_FILES",
            "SCAN_FAILED"
        )) {
            assertTrue("missing reason $required", reasons.contains(required))
        }
    }

    /* 5. Picking is never restricted by MIME: .wastickers has no registered type. */
    @Test
    fun `picker mime filter is any type`() {
        assertEquals("*/*", WastickersMimeTypes.ANY)
        assertTrue(WastickersMimeTypes.PICKER.toList() == listOf("*/*"))
    }

    /* 6. Diagnostics expose every counter required by the report. */
    @Test
    fun `scan diagnostics expose required counters`() {
        val diag = WastickersScanDiagnostics(
            foldersScanned = 12,
            nodesScanned = 40,
            wastickersFound = 5,
            unreadableFiles = 2,
            errors = 1,
            errorDetails = listOf("boom")
        )
        assertEquals(12, diag.foldersScanned)
        assertEquals(40, diag.nodesScanned)
        assertEquals(5, diag.wastickersFound)
        assertEquals(2, diag.unreadableFiles)
        assertEquals(1, diag.errors)
        assertEquals(listOf("boom"), diag.errorDetails)
    }

    /* 7. A successful scan result carries files + per-source status together. */
    @Test
    fun `scan result carries files and per source status`() {
        val file = WastickersFile(
            id = "media:content://x/1",
            uri = "content://x/1",
            displayName = "gsgwh.wastickers",
            sizeBytes = 100,
            lastModified = 1_700_000_000_000L,
            source = WastickersSource.MEDIA_STORE
        )
        val result = WastickersScanResult(
            outcome = DiscoveryOutcome(files = listOf(file)),
            sources = listOf(status(files = 1, isDefault = true)),
            diagnostics = WastickersScanDiagnostics(wastickersFound = 1)
        )
        assertEquals(1, result.outcome.files.size)
        assertEquals(1, result.sources.size)
        assertTrue(result.sources.first().isDefault)
        assertEquals(1, result.diagnostics.wastickersFound)
    }

    /* 8. The extension rule (single source of truth) accepts every real case. */
    @Test
    fun `extension rule accepts all required variants regardless of source`() {
        for (name in listOf(
            "gsgwh.wastickers",
            "GSGWH.WASTICKERS",
            "My Stickers.wastickers",
            "ملصقاتي.wastickers",
            "  spaced  .wastickers  "
        )) {
            assertTrue("should accept $name", WastickersFileOps.isWastickersName(name))
        }
        assertFalse(WastickersFileOps.isWastickersName("pack.zip"))
        assertFalse(WastickersFileOps.isWastickersName(null))
    }
}
