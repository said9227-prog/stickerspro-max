package com.example.wastickers

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import java.util.Locale

/**
 * WASTICKERS Explorer — a gallery-first screen.
 *
 * Design contract:
 *  - the PACKAGE GALLERY owns the screen; controls are small and secondary;
 *  - every card shows REAL extracted sticker previews, never a placeholder;
 *  - the all-files permission state is shown and re-verified on resume;
 *  - loading / success / empty / permission-required / partial / error states
 *    are all explicit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WastickersImportScreen(
    onNavigateBack: () -> Unit,
    onOpenPack: (Long) -> Unit,
    viewModel: WastickersImportViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isRtl = Locale.getDefault().language == "ar"
    val context = LocalContext.current

    // --- Pickers -------------------------------------------------------
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri == null) viewModel.onPermissionDenied() else viewModel.onTreeUriGranted(uri) }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.onDocumentsSelected(uris) }

    // Re-check the real permission when returning from system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(Unit) {
        viewModel.openPackEvents.collect { packId -> onOpenPack(packId) }
    }
    DisposableEffect(Unit) { onDispose { viewModel.clearSelectionOnLeave() } }

    var showFilters by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                ExplorerHeader(
                    state = state,
                    onBack = onNavigateBack,
                    onRefresh = { viewModel.refresh() },
                    onToggleFilters = { showFilters = !showFilters },
                    filtersOpen = showFilters
                )
            },
            bottomBar = {
                ExplorerBottomBar(
                    state = state,
                    onImport = { viewModel.importSelected() },
                    onCancel = { viewModel.cancelImport() },
                    onClear = { viewModel.clearSelection() }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // ---- compact search row ----
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.wst_search_hint)) },
                    shape = RoundedCornerShape(12.dp)
                )

                if (showFilters) {
                    FilterRow(
                        state = state,
                        onFilter = { viewModel.setFilter(it) },
                        onSort = { viewModel.setSort(it) },
                        onShareDiagnostics = { viewModel.shareDiagnosticReport() }
                    )
                }

                // ---- permission / partial / error banners (compact) ----
                StatusBanners(state = state)

                // ---- gallery ----
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.isLoading && state.packages.isEmpty() -> LoadingState(
                            scanned = state.scannedCount
                        )

                        state.storagePermission != StoragePermissionState.GRANTED &&
                            state.packages.isEmpty() -> PermissionState(
                            detail = state.storagePermissionDetail,
                            onOpenSettings = {
                                viewModel.manageStorageIntent()?.let {
                                    runCatching { context.startActivity(it) }
                                }
                            },
                            onPickFolder = { treeLauncher.launch(null) },
                            onPickFiles = { documentLauncher.launch(WastickersMimeTypes.PICKER) }
                        )

                        state.packages.isEmpty() && state.error != null -> ErrorState(
                            message = state.error ?: "",
                            onRetry = { viewModel.refresh() }
                        )

                        state.visiblePackages.isEmpty() && state.query.isNotBlank() ->
                            SimpleMessage(
                                title = stringResource(R.string.wst_no_results),
                                hint = stringResource(R.string.wst_no_results_hint)
                            )

                        state.visiblePackages.isEmpty() && state.packages.isNotEmpty() ->
                            SimpleMessage(
                                title = stringResource(R.string.exp_filter_empty),
                                hint = stringResource(R.string.exp_filter_empty_hint)
                            )

                        else -> PackageGallery(
                            packages = state.visiblePackages,
                            selectedIds = state.selectedIds,
                            importProgress = state.import,
                            isParsing = state.isParsing,
                            previewResolver = { key -> viewModel.previewFileUri(key) },
                            onToggle = { viewModel.toggleSelection(it) },
                            onImport = { viewModel.importSingle(it) }
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Header: small, information-dense, never dominating the screen       */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerHeader(
    state: WastickersUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFilters: () -> Unit,
    filtersOpen: Boolean
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.wst_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.exp_header_count, state.packages.size) +
                        " · " + scanStateText(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.wst_back)
                )
            }
        },
        actions = {
            PermissionDot(state.storagePermission)
            IconButton(onClick = onToggleFilters) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.exp_filters))
            }
            IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.wst_refresh))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (filtersOpen) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    )
}

@Composable
private fun PermissionDot(permission: StoragePermissionState) {
    val (color, label) = when (permission) {
        StoragePermissionState.GRANTED ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.exp_perm_ok)
        StoragePermissionState.DENIED ->
            MaterialTheme.colorScheme.error to stringResource(R.string.exp_perm_missing)
        StoragePermissionState.UNSUPPORTED ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.exp_perm_unsupported)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun scanStateText(state: WastickersUiState): String = when {
    state.isLoading -> stringResource(R.string.exp_scanning)
    state.isParsing -> stringResource(R.string.exp_parsing)
    state.storagePermission != StoragePermissionState.GRANTED ->
        stringResource(R.string.exp_need_permission)
    else -> stringResource(R.string.exp_ready)
}

/* ------------------------------------------------------------------ */
/* Filters + sorting: one compact, scrollable row                      */
/* ------------------------------------------------------------------ */

@Composable
private fun FilterRow(
    state: WastickersUiState,
    onFilter: (ExplorerFilter) -> Unit,
    onSort: (ExplorerSort) -> Unit,
    onShareDiagnostics: () -> Unit
) {
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ExplorerFilter.values().size) { index ->
                val filter = ExplorerFilter.values()[index]
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilter(filter) },
                    label = {
                        Text(filterLabel(filter), style = MaterialTheme.typography.labelSmall)
                    }
                )
            }
            item {
                TextButton(onClick = onShareDiagnostics) {
                    Text(
                        stringResource(R.string.wst_diagnostic_report),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ExplorerSort.values().size) { index ->
                val sort = ExplorerSort.values()[index]
                AssistChip(
                    onClick = { onSort(sort) },
                    label = {
                        Text(sortLabel(sort), style = MaterialTheme.typography.labelSmall)
                    },
                    colors = if (state.sort == sort) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    }
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun filterLabel(filter: ExplorerFilter): String = when (filter) {
    ExplorerFilter.ALL -> stringResource(R.string.exp_filter_all)
    ExplorerFilter.STATIC -> stringResource(R.string.exp_filter_static)
    ExplorerFilter.ANIMATED -> stringResource(R.string.exp_filter_animated)
    ExplorerFilter.READY -> stringResource(R.string.exp_filter_ready)
}

@Composable
private fun sortLabel(sort: ExplorerSort): String = when (sort) {
    ExplorerSort.NEWEST -> stringResource(R.string.exp_sort_newest)
    ExplorerSort.NAME -> stringResource(R.string.exp_sort_name)
    ExplorerSort.SIZE -> stringResource(R.string.exp_sort_size)
    ExplorerSort.STICKER_COUNT -> stringResource(R.string.exp_sort_count)
}

/* ------------------------------------------------------------------ */
/* Banners: partial access + errors, compact                           */
/* ------------------------------------------------------------------ */

@Composable
private fun StatusBanners(state: WastickersUiState) {
    val denied = state.directDiagnostics?.deniedFolders?.size ?: 0
    if (denied > 0) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.exp_partial_access, denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    val running = state.import?.isRunning == true
    if (state.isLoading && state.packages.isNotEmpty() && !running) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .height(2.dp)
        )
    }
}

/* ------------------------------------------------------------------ */
/* Gallery: 2-column grid of large, real previews                      */
/* ------------------------------------------------------------------ */

@Composable
private fun PackageGallery(
    packages: List<ExplorerPackage>,
    selectedIds: Set<String>,
    importProgress: ImportProgress?,
    isParsing: Boolean,
    previewResolver: (String) -> String?,
    onToggle: (String) -> Unit,
    onImport: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 158.dp),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (isParsing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.exp_reading_packages),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        items(packages, key = { it.id }) { pkg ->
            PackageCard(
                pkg = pkg,
                selected = pkg.id in selectedIds,
                progress = importProgress?.perFile?.get(pkg.id),
                previewResolver = previewResolver,
                onToggle = { onToggle(pkg.id) },
                onImport = { onImport(pkg.id) }
            )
        }
    }
}

@Composable
private fun PackageCard(
    pkg: ExplorerPackage,
    selected: Boolean,
    progress: FileImportProgress?,
    previewResolver: (String) -> String?,
    onToggle: () -> Unit,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // --- PREVIEW AREA: the visual priority of the card ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val uris = pkg.previewKeys.mapNotNull(previewResolver).take(4)
                if (uris.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = stringResource(R.string.wst_no_preview),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else if (uris.size == 1) {
                    AsyncImage(
                        model = uris.first(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 2x2 mosaic of REAL stickers from this pack.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f)) {
                            PreviewCell(uris[0], Modifier.weight(1f))
                            PreviewCell(uris.getOrNull(1), Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.weight(1f)) {
                            PreviewCell(uris.getOrNull(2), Modifier.weight(1f))
                            PreviewCell(uris.getOrNull(3), Modifier.weight(1f))
                        }
                    }
                }

                if (pkg.isNew) {
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            stringResource(R.string.wst_new_badge),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            // --- TEXT: name, counts, meta ---
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = pkg.packName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (pkg.stickerCount > 0) {
                            stringResource(R.string.wst_sticker_count, pkg.stickerCount)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = WastickersFileOps.formatSize(pkg.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(pkg)
                    Spacer(modifier = Modifier.width(4.dp))
                    if (pkg.kind != PackageKind.VALID) {
                        Text(
                            text = when (pkg.kind) {
                                PackageKind.NEEDS_PARTS ->
                                    stringResource(R.string.wst_status_needs_parts)
                                else -> stringResource(R.string.wst_invalid_archive)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (progress != null && progress.status == WastickersStatus.IMPORTING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                // Small, secondary import action — never larger than preview.
                Button(
                    onClick = onImport,
                    enabled = pkg.importable &&
                        progress?.status != WastickersStatus.IMPORTING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.wst_import_one),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCell(uri: String?, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(0.5.dp)) {
        if (uri == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun TypeBadge(pkg: ExplorerPackage) {
    val label = when (pkg.stickerType) {
        PackageStickerType.STATIC -> stringResource(R.string.wst_type_static)
        PackageStickerType.ANIMATED -> stringResource(R.string.wst_type_animated)
        PackageStickerType.MIXED -> stringResource(R.string.exp_type_mixed)
        PackageStickerType.UNKNOWN -> "—"
    }
    val color = when (pkg.stickerType) {
        PackageStickerType.ANIMATED, PackageStickerType.MIXED ->
            MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}

/* ------------------------------------------------------------------ */
/* Bottom bar: appears only when something is selected / importing      */
/* ------------------------------------------------------------------ */

@Composable
private fun ExplorerBottomBar(
    state: WastickersUiState,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit
) {
    val progress = state.import
    val running = progress?.isRunning == true
    val selection = state.selectedIds

    if (selection.isEmpty() && progress == null) {
        // Nothing selected: no bottom bar at all, gallery gets full height.
        return
    }

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.wst_overall_progress,
                            (progress.overall * 100).toInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${progress.completed}/${progress.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.overall },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
                if (!running) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (progress.isCancelled) {
                            stringResource(R.string.wst_import_cancelled)
                        } else {
                            stringResource(
                                R.string.wst_import_done,
                                progress.succeeded,
                                progress.failed,
                                progress.skipped
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.wst_selected_format, selection.size, state.packages.size),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear, enabled = selection.isNotEmpty()) {
                    Text(stringResource(R.string.wst_clear_selection), style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onImport,
                    enabled = selection.isNotEmpty() && !running,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${stringResource(R.string.wst_import_selected)} (${selection.size})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (running) {
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.wst_cancel_import), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Full-screen states                                                  */
/* ------------------------------------------------------------------ */

@Composable
private fun LoadingState(scanned: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            stringResource(R.string.wst_loading),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.exp_scanned_count, scanned),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionState(
    detail: String?,
    onOpenSettings: () -> Unit,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.exp_permission_required),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            detail ?: stringResource(R.string.exp_permission_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.exp_open_settings))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wst_grant_folder))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onPickFiles) {
            Text(stringResource(R.string.wst_pick_files_direct))
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.wst_error),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.wst_retry)) }
    }
}

@Composable
private fun SimpleMessage(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Archive,
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
