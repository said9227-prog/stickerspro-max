package com.example.wastickers

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** What a package contains, decided from its BYTES, never from its name. */
enum class PackageKind {
    /** Complete ZIP with at least one valid sticker. */
    VALID,
    /** Split/multi-volume archive segment; the other parts are required. */
    NEEDS_PARTS,
    /** Not a usable archive (corrupt, empty, Zip-Slip, Zip-Bomb, no stickers). */
    INVALID
}

/** Sticker flavour detected inside the archive. */
enum class PackageStickerType {
    STATIC,
    ANIMATED,
    MIXED,
    UNKNOWN
}

/**
 * One real preview extracted from the archive.
 * [entryName] identifies WHICH sticker it is, so the UI can show several.
 */
data class PackagePreview(
    val entryName: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is PackagePreview && other.entryName == entryName && other.bytes contentEquals bytes

    override fun hashCode(): Int = entryName.hashCode() * 31 + bytes.contentHashCode()
}

/** Everything the UI needs about one .wastickers package. */
data class WastickersPackageInfo(
    val kind: PackageKind,
    /** Pack name derived from title.txt/author, else the file name. */
    val packName: String,
    val author: String? = null,
    val stickerCount: Int = 0,
    val stickerType: PackageStickerType = PackageStickerType.UNKNOWN,
    val totalEntries: Int = 0,
    /** Uncompressed size of the sticker entries that were examined. */
    val contentBytes: Long = 0L,
    val previews: List<PackagePreview> = emptyList(),
    val reason: String? = null
) {
    val isValid: Boolean get() = kind == PackageKind.VALID
    val animatedCount: Int = if (stickerType == PackageStickerType.MIXED ||
        stickerType == PackageStickerType.ANIMATED
    ) stickerCount else 0

    /** Distinct identity for de-duplication: name + size + mtime is NOT used here. */
    fun previewNames(): List<String> = previews.map { it.entryName }
}

/**
 * Parses a .wastickers package safely and extracts real previews.
 *
 * Design rules enforced here:
 *  - A ZIP is only VALID when it opens, contains at least one sticker and
 *    passes the Zip-Slip / Zip-Bomb limits from WastickersArchiveValidator.
 *  - Up to [maxPreviews] previews are extracted (first N stickers) so the
 *    gallery can show several thumbnails — never a placeholder icon.
 *  - Preview bytes are bounded so a huge sticker cannot exhaust memory.
 *  - Reading is done with ZipFile (random access) when a real File is given,
 *    and falls back to ZipInputStream for ContentResolver streams.
 */
object WastickersPackageParser {

    const val MAX_PREVIEWS = 4
    const val MAX_PREVIEW_BYTES = 512 * 1024

    /** Parses a real file on disk (direct file access path). */
    fun parseFile(file: File, displayName: String = file.name): WastickersPackageInfo {
        if (!file.isFile || file.length() == 0L) {
            return WastickersPackageInfo(
                kind = PackageKind.INVALID,
                packName = WastickersFileOps.packNameOf(displayName),
                reason = "الملف فارغ أو غير موجود"
            )
        }
        val report = WastickersArchiveValidator.validate(file, displayName)
        if (!report.ok) {
            return WastickersPackageInfo(
                kind = if (report.needsParts) PackageKind.NEEDS_PARTS else PackageKind.INVALID,
                packName = WastickersFileOps.packNameOf(displayName),
                stickerCount = report.stickerCount,
                reason = report.reason
            )
        }
        return try {
            ZipFile(file).use { zip -> parseZipFile(zip, displayName) }
        } catch (e: Exception) {
            WastickersPackageInfo(
                kind = if (WastickersFileOps.hasPartMarker(displayName)) {
                    PackageKind.NEEDS_PARTS
                } else {
                    PackageKind.INVALID
                },
                packName = WastickersFileOps.packNameOf(displayName),
                reason = "تعذّر قراءة الأرشيف: ${e.message ?: "خطأ غير معروف"}"
            )
        }
    }

    /** Parses a stream (ContentResolver / SAF path). */
    fun parseStream(
        displayName: String,
        opener: () -> InputStream?
    ): WastickersPackageInfo {
        val stream = try {
            opener()
        } catch (e: Exception) {
            return WastickersPackageInfo(
                kind = PackageKind.INVALID,
                packName = WastickersFileOps.packNameOf(displayName),
                reason = "تعذّر فتح الملف: ${e.message ?: "SecurityException"}"
            )
        } ?: return WastickersPackageInfo(
            kind = PackageKind.INVALID,
            packName = WastickersFileOps.packNameOf(displayName),
            reason = "تعذّر فتح الملف عبر ContentResolver"
        )

        return try {
            stream.use { parseZipStream(it, displayName) }
        } catch (e: Exception) {
            WastickersPackageInfo(
                kind = if (WastickersFileOps.hasPartMarker(displayName)) {
                    PackageKind.NEEDS_PARTS
                } else {
                    PackageKind.INVALID
                },
                packName = WastickersFileOps.packNameOf(displayName),
                reason = "تعذّر قراءة الأرشيف: ${e.message ?: "خطأ غير معروف"}"
            )
        }
    }

    private fun parseZipFile(zip: ZipFile, displayName: String): WastickersPackageInfo {
        var title: String? = null
        var author: String? = null
        var stickerCount = 0
        var animated = 0
        var static = 0
        var totalEntries = 0
        var contentBytes = 0L
        val previews = ArrayList<PackagePreview>()

        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!WastickersArchiveValidator.isSafeEntryName(entry.name)) {
                return WastickersPackageInfo(
                    kind = PackageKind.INVALID,
                    packName = WastickersFileOps.packNameOf(displayName),
                    reason = "اسم ملف غير آمن (Zip Slip): ${entry.name}"
                )
            }
            totalEntries++
            if (entry.isDirectory) continue
            val leaf = entry.name.substringAfterLast('/')

            if (leaf.equals("title.txt", ignoreCase = true) && entry.size in 1..4096) {
                title = runCatching {
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }.trim()
                }.getOrNull()
                continue
            }
            if (leaf.equals("author.txt", ignoreCase = true) && entry.size in 1..4096) {
                author = runCatching {
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }.trim()
                }.getOrNull()
                continue
            }

            if (!WastickersArchiveValidator.isStickerName(leaf)) continue
            stickerCount++
            contentBytes += entry.size.coerceAtLeast(0L)

            val isAnimatedEntry = leaf.endsWith(".webm", ignoreCase = true)
            // Only the first N stickers are inflated, and each one bounded.
            if (previews.size < MAX_PREVIEWS || isAnimatedEntry) {
                val payload = runCatching {
                    zip.getInputStream(entry).use { readBounded(it, MAX_PREVIEW_BYTES) }
                }.getOrDefault(ByteArray(0))
                if (isAnimatedEntry || looksAnimatedWebp(payload)) animated++ else static++
                if (payload.isNotEmpty() && previews.size < MAX_PREVIEWS) {
                    previews.add(PackagePreview(leaf, payload))
                }
            } else {
                // Do not inflate: infer animation from the extension only.
                if (isAnimatedEntry) animated++ else static++
            }
        }

        val type = when {
            stickerCount == 0 -> PackageStickerType.UNKNOWN
            animated > 0 && static > 0 -> PackageStickerType.MIXED
            animated > 0 -> PackageStickerType.ANIMATED
            else -> PackageStickerType.STATIC
        }
        val name = title?.takeIf { it.isNotBlank() }
            ?: WastickersFileOps.packNameOf(displayName)

        return WastickersPackageInfo(
            kind = if (stickerCount > 0) PackageKind.VALID else PackageKind.INVALID,
            packName = name,
            author = author?.takeIf { it.isNotBlank() },
            stickerCount = stickerCount,
            stickerType = type,
            totalEntries = totalEntries,
            contentBytes = contentBytes,
            previews = previews,
            reason = if (stickerCount == 0) "لا يحتوي الأرشيف على ملصقات صالحة" else null
        )
    }

    private fun parseZipStream(stream: InputStream, displayName: String): WastickersPackageInfo {
        var title: String? = null
        var author: String? = null
        var stickerCount = 0
        var animated = 0
        var static = 0
        var totalEntries = 0
        var contentBytes = 0L
        val previews = ArrayList<PackagePreview>()
        var sawSticker = false

        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (totalEntries++ > WastickersArchiveValidator.MAX_ENTRIES) {
                    return WastickersPackageInfo(
                        kind = PackageKind.INVALID,
                        packName = WastickersFileOps.packNameOf(displayName),
                        reason = "عدد الملفات داخل الأرشيف يتجاوز الحد المسموح"
                    )
                }
                if (!WastickersArchiveValidator.isSafeEntryName(entry.name)) {
                    return WastickersPackageInfo(
                        kind = PackageKind.INVALID,
                        packName = WastickersFileOps.packNameOf(displayName),
                        reason = "اسم ملف غير آمن (Zip Slip): ${entry.name}"
                    )
                }
                val leaf = entry.name.substringAfterLast('/')
                if (!entry.isDirectory) {
                    when {
                        leaf.equals("title.txt", ignoreCase = true) -> {
                            title = readBounded(zip, 4096)
                                .toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
                        }
                        leaf.equals("author.txt", ignoreCase = true) -> {
                            author = readBounded(zip, 4096)
                                .toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
                        }
                        WastickersArchiveValidator.isStickerName(leaf) -> {
                            sawSticker = true
                            stickerCount++
                            val isAnimatedEntry = leaf.endsWith(".webm", ignoreCase = true)
                            if (previews.size < MAX_PREVIEWS) {
                                val payload = readBounded(zip, MAX_PREVIEW_BYTES)
                                contentBytes += payload.size
                                if (isAnimatedEntry || looksAnimatedWebp(payload)) {
                                    animated++
                                } else {
                                    static++
                                }
                                if (payload.isNotEmpty()) {
                                    previews.add(PackagePreview(leaf, payload))
                                }
                            } else {
                                if (isAnimatedEntry) animated++ else static++
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (!sawSticker) {
            return WastickersPackageInfo(
                kind = PackageKind.INVALID,
                packName = WastickersFileOps.packNameOf(displayName),
                reason = "لا يحتوي الأرشيف على ملصقات صالحة"
            )
        }
        val type = when {
            animated > 0 && static > 0 -> PackageStickerType.MIXED
            animated > 0 -> PackageStickerType.ANIMATED
            else -> PackageStickerType.STATIC
        }
        return WastickersPackageInfo(
            kind = PackageKind.VALID,
            packName = title ?: WastickersFileOps.packNameOf(displayName),
            author = author,
            stickerCount = stickerCount,
            stickerType = type,
            totalEntries = totalEntries,
            contentBytes = contentBytes,
            previews = previews
        )
    }

    /** Bounded read: never allocates more than [limit] bytes. */
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer)
            if (read <= 0) break
            val take = minOf(read, limit - total)
            out.write(buffer, 0, take)
            total += take
        }
        return out.toByteArray()
    }

    /** Real animation detection: look for the WebP ANIM chunk, not the name. */
    private fun looksAnimatedWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val limit = minOf(bytes.size - 4, 512)
        var i = 0
        while (i <= limit) {
            if (bytes[i] == 'A'.code.toByte() && bytes[i + 1] == 'N'.code.toByte() &&
                bytes[i + 2] == 'I'.code.toByte() && bytes[i + 3] == 'M'.code.toByte()
            ) {
                return true
            }
            i++
        }
        return false
    }
}
