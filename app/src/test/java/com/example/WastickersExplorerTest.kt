package com.example

import com.example.wastickers.ExplorerFilter
import com.example.wastickers.ExplorerPackage
import com.example.wastickers.ExplorerQuery
import com.example.wastickers.ExplorerSort
import com.example.wastickers.PackageKind
import com.example.wastickers.PackageStickerType
import com.example.wastickers.WastickersFile
import com.example.wastickers.WastickersPackageParser
import com.example.wastickers.WastickersSource
import com.example.wastickers.WastickersStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure JVM tests for the WASTICKERS Explorer redesign and the package parser.
 * The Android-only parts (MANAGE_EXTERNAL_STORAGE, real filesystem walk) are
 * covered by instrumentation tests and are NOT claimed as PASS here.
 */
class WastickersExplorerTest {

    private lateinit var tempDir: File
    private val created = ArrayList<File>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("wst-explorer").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        created.forEach { runCatching { it.delete() } }
    }

    /* ---------------- fixtures ---------------- */

    private fun webp(animated: Boolean = false): ByteArray {
        val riff = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        )
        return if (animated) {
            riff + byteArrayOf(
                'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'X'.code.toByte()
            ) + byteArrayOf(
                'A'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()
            ) + ByteArray(32) { 7 }
        } else {
            riff + ByteArray(32) { 3 }
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = File(tempDir, "p_${System.nanoTime()}.wastickers")
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }
        created.add(f)
        return f
    }

    private fun pkg(
        id: String = "a",
        name: String = "Pack A",
        file: String = "a.wastickers",
        count: Int = 5,
        size: Long = 1000L,
        type: PackageStickerType = PackageStickerType.STATIC,
        kind: PackageKind = PackageKind.VALID,
        modified: Long = 1_700_000_000_000L,
        isNew: Boolean = false,
        status: WastickersStatus = WastickersStatus.READY
    ) = ExplorerPackage(
        id = id,
        displayName = file,
        packName = name,
        sizeBytes = size,
        lastModified = modified,
        source = WastickersSource.DIRECT_FILE,
        location = "/storage/emulated/0/Download",
        stickerCount = count,
        stickerType = type,
        kind = kind,
        isNew = isNew,
        status = status,
        file = WastickersFile(
            id = id,
            uri = "file:///tmp/$file",
            displayName = file,
            sizeBytes = size,
            lastModified = modified,
            source = WastickersSource.DIRECT_FILE
        )
    )

    /* ---------------- parser: real ZIP validation ---------------- */

    @Test
    fun `parser accepts a valid pack and extracts real previews`() {
        val f = zip(
            "title.txt" to "My Arabic Pack".toByteArray(),
            "author.txt" to "Someone".toByteArray(),
            "1.webp" to webp(),
            "2.webp" to webp(),
            "3.webp" to webp()
        )
        val info = WastickersPackageParser.parseFile(f, "x.wastickers")
        assertEquals(PackageKind.VALID, info.kind)
        assertEquals(3, info.stickerCount)
        assertEquals("My Arabic Pack", info.packName)
        assertEquals("Someone", info.author)
        // REAL previews, not placeholders.
        assertTrue("expected real previews", info.previews.isNotEmpty())
        assertTrue(info.previews.size <= WastickersPackageParser.MAX_PREVIEWS)
        assertTrue(info.previews.first().bytes.isNotEmpty())
        assertEquals(PackageStickerType.STATIC, info.stickerType)
    }

    @Test
    fun `parser detects animated packs from bytes`() {
        val f = zip("a.webp" to webp(animated = true), "b.webp" to webp(animated = true))
        val info = WastickersPackageParser.parseFile(f, "anim.wastickers")
        assertEquals(PackageKind.VALID, info.kind)
        assertEquals(PackageStickerType.ANIMATED, info.stickerType)
    }

    @Test
    fun `parser reports mixed when static and animated coexist`() {
        val f = zip("a.webp" to webp(true), "b.webp" to webp(false))
        val info = WastickersPackageParser.parseFile(f, "mix.wastickers")
        assertEquals(PackageStickerType.MIXED, info.stickerType)
    }

    @Test
    fun `parser rejects a non zip file`() {
        val f = File(tempDir, "bad.wastickers")
        f.writeBytes(ByteArray(64) { 9 })
        created.add(f)
        val info = WastickersPackageParser.parseFile(f, "bad.wastickers")
        assertEquals(PackageKind.INVALID, info.kind)
        assertTrue(info.reason != null)
    }

    @Test
    fun `parser rejects a zip with no stickers`() {
        val f = zip("readme.txt" to "hello".toByteArray())
        val info = WastickersPackageParser.parseFile(f, "empty.wastickers")
        assertEquals(PackageKind.INVALID, info.kind)
        assertTrue(info.reason!!.isNotBlank())
    }

    @Test
    fun `parser rejects zip slip entry names`() {
        val f = zip("../../evil.webp" to webp())
        val info = WastickersPackageParser.parseFile(f, "slip.wastickers")
        assertEquals(PackageKind.INVALID, info.kind)
    }

    @Test
    fun `parser marks a split segment as needing other parts`() {
        val f = File(tempDir, "big.part1.wastickers")
        f.writeBytes(ByteArray(32))
        created.add(f)
        val info = WastickersPackageParser.parseFile(f, f.name)
        assertEquals(PackageKind.NEEDS_PARTS, info.kind)
    }

    /* ---------------- explorer query: search / filter / sort ---------------- */

    private val sample = listOf(
        pkg(id = "1", name = "ملصقاتي", file = "a.wastickers", count = 10, size = 500,
            type = PackageStickerType.STATIC, modified = 100),
        pkg(id = "2", name = "Animated Cats", file = "b.wastickers", count = 3, size = 9000,
            type = PackageStickerType.ANIMATED, modified = 300, isNew = true),
        pkg(id = "3", name = "Mixed Pack", file = "c.wastickers", count = 7, size = 4000,
            type = PackageStickerType.MIXED, modified = 200),
        pkg(id = "4", name = "Broken", file = "d.wastickers", count = 0, size = 10,
            type = PackageStickerType.UNKNOWN, kind = PackageKind.INVALID)
    )

    @Test
    fun `filter all returns every package`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.ALL, ExplorerSort.NEWEST)
        assertEquals(4, out.size)
    }

    @Test
    fun `filter static returns only static packs`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.STATIC, ExplorerSort.NEWEST)
        assertEquals(1, out.size)
        assertEquals("ملصقاتي", out.first().packName)
    }

    @Test
    fun `filter animated includes mixed packs`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.ANIMATED, ExplorerSort.NEWEST)
        assertEquals(2, out.size)
        assertTrue(out.any { it.stickerType == PackageStickerType.MIXED })
    }

    @Test
    fun `filter ready excludes invalid packages`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.READY, ExplorerSort.NEWEST)
        assertFalse(out.any { it.kind == PackageKind.INVALID })
    }

    @Test
    fun `sort by size is descending`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.ALL, ExplorerSort.SIZE)
        // NEW packs are pinned first by design.
        val rest = out.filterNot { it.isNew }.map { it.sizeBytes }
        assertEquals(rest.sortedDescending(), rest)
    }

    @Test
    fun `sort by sticker count is descending`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.ALL, ExplorerSort.STICKER_COUNT)
        // NEW packs are pinned first by design; the remaining list is ordered
        // by sticker count descending.
        val rest = out.filterNot { it.isNew }.map { it.stickerCount }
        assertEquals(rest.sortedDescending(), rest)
    }

    @Test
    fun `sort by name is alphabetical`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.ALL, ExplorerSort.NAME)
        // NEW packs stay first by design, the rest are alphabetical.
        val rest = out.filterNot { it.isNew }.map { it.packName.lowercase() }
        assertEquals(rest.sorted(), rest)
    }

    @Test
    fun `new packages are placed first`() {
        val out = ExplorerQuery.apply(sample, "", ExplorerFilter.ALL, ExplorerSort.NEWEST)
        assertTrue(out.first().isNew)
    }

    @Test
    fun `search matches arabic name ignoring diacritics`() {
        val out = ExplorerQuery.apply(sample, "ملصقاتي", ExplorerFilter.ALL, ExplorerSort.NEWEST)
        assertEquals(1, out.size)
        // Diacritics and alef variants must not break matching.
        val withDiacritics = ExplorerQuery.apply(
            sample, "مَلصَقاتى", ExplorerFilter.ALL, ExplorerSort.NEWEST
        )
        assertEquals(1, withDiacritics.size)
    }

    @Test
    fun `search is case insensitive for english`() {
        val out = ExplorerQuery.apply(sample, "animated", ExplorerFilter.ALL, ExplorerSort.NEWEST)
        assertEquals(1, out.size)
    }

    @Test
    fun `search with no match returns empty`() {
        val out = ExplorerQuery.apply(sample, "zzz-does-not-exist", ExplorerFilter.ALL, ExplorerSort.NEWEST)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `normalize strips diacritics and unifies alef`() {
        assertEquals(ExplorerQuery.normalize("أحمد"), ExplorerQuery.normalize("احمد"))
        assertEquals(ExplorerQuery.normalize("ملصقـات"), ExplorerQuery.normalize("ملصقات"))
    }
}
