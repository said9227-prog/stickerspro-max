package com.example.wastickers

import java.util.Locale

/**
 * One package shown in the WASTICKERS Explorer gallery.
 *
 * This is the UI-facing model: it merges the discovered file with the parsed
 * package metadata and the REAL extracted previews. A card is never rendered
 * with a placeholder icon when previews exist.
 */
data class ExplorerPackage(
    /** Stable identity: prefixed Uri (SAF/MediaStore) or direct absolute path. */
    val id: String,
    val displayName: String,
    val packName: String,
    val author: String? = null,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: WastickersSource,
    val location: String,
    val stickerCount: Int = 0,
    val stickerType: PackageStickerType = PackageStickerType.UNKNOWN,
    val kind: PackageKind = PackageKind.VALID,
    /** Cache keys for the extracted previews (already written to disk). */
    val previewKeys: List<String> = emptyList(),
    val reason: String? = null,
    val isNew: Boolean = false,
    val status: WastickersStatus = WastickersStatus.READY,
    /** The underlying discovery record, needed by the existing importer. */
    val file: WastickersFile
) {
    /** Ready-to-import: structurally valid and not already failing. */
    val importable: Boolean
        get() = kind == PackageKind.VALID &&
            status != WastickersStatus.IMPORTING &&
            status != WastickersStatus.CORRUPT

    val isAnimated: Boolean
        get() = stickerType == PackageStickerType.ANIMATED ||
            stickerType == PackageStickerType.MIXED

    val typeLabelType: PackageStickerType get() = stickerType
}

/** Filter chips in the Explorer. */
enum class ExplorerFilter {
    ALL,
    STATIC,
    ANIMATED,
    READY
}

/** Sort order for the gallery. */
enum class ExplorerSort {
    NEWEST,
    NAME,
    SIZE,
    STICKER_COUNT
}

/**
 * Pure, JVM-testable query logic for the Explorer: search + filter + sort.
 * Kept free of Android types so it is covered by fast unit tests and can be
 * reused by any future screen.
 */
object ExplorerQuery {

    fun apply(
        packages: List<ExplorerPackage>,
        query: String,
        filter: ExplorerFilter,
        sort: ExplorerSort
    ): List<ExplorerPackage> {
        val needle = normalize(query)
        val filtered = packages.asSequence()
            .filter { p ->
                when (filter) {
                    ExplorerFilter.ALL -> true
                    ExplorerFilter.STATIC -> p.stickerType == PackageStickerType.STATIC
                    ExplorerFilter.ANIMATED ->
                        p.stickerType == PackageStickerType.ANIMATED ||
                            p.stickerType == PackageStickerType.MIXED
                    ExplorerFilter.READY -> p.importable
                }
            }
            .filter { p ->
                if (needle.isEmpty()) {
                    true
                } else {
                    normalize(p.packName).contains(needle) ||
                        normalize(p.displayName).contains(needle) ||
                        normalize(p.author.orEmpty()).contains(needle)
                }
            }
            .toList()

        return when (sort) {
            ExplorerSort.NEWEST -> filtered.sortedWith(
                compareByDescending<ExplorerPackage> { it.isNew }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.packName.lowercase(Locale.ROOT) }
            )
            ExplorerSort.NAME -> filtered.sortedWith(
                compareByDescending<ExplorerPackage> { it.isNew }
                    .thenBy { it.packName.lowercase(Locale.ROOT) }
            )
            ExplorerSort.SIZE -> filtered.sortedWith(
                compareByDescending<ExplorerPackage> { it.isNew }
                    .thenByDescending { it.sizeBytes }
            )
            ExplorerSort.STICKER_COUNT -> filtered.sortedWith(
                compareByDescending<ExplorerPackage> { it.isNew }
                    .thenByDescending { it.stickerCount }
            )
        }
    }

    /**
     * Arabic-aware normalisation: lowercases, strips diacritics/tatweel and
     * unifies alef/yaa/taa-marbuta so "ملصقاتي" matches "ملصقاتى" and
     * "مَلصقاتي". Straightforward and allocation-bounded.
     */
    fun normalize(value: String): String {
        if (value.isEmpty()) return ""
        val builder = StringBuilder(value.length)
        for (ch in value) {
            val mapped = when (ch) {
                '\u0623', '\u0625', '\u0622' -> '\u0627' // alef variants
                '\u0649' -> '\u064A'                     // alef maqsura -> yaa
                '\u0629' -> '\u0647'                     // taa marbuta -> haa
                '\u0640' -> continue                     // tatweel
                in '\u064B'..'\u065F' -> continue        // harakat/diacritics
                in '\u0670'..'\u0670' -> continue
                else -> ch
            }
            builder.append(mapped.lowercaseChar())
        }
        return builder.toString().trim()
    }
}
