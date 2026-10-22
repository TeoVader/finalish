package dev.finalish.ui.browser

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.data.BrowseLayout
import dev.finalish.core.data.SortOrder
import dev.finalish.core.model.Asset
import dev.finalish.core.transfer.TransferState
import dev.finalish.ui.components.AutoRefresh
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.pressable
import dev.finalish.ui.shares.CreateShareSheet
import dev.finalish.ui.theme.Palette

@Composable
fun FolderScreen(
    accountId: String,
    projectId: String,
    rootFolderId: String,
    folderId: String,
    title: String,
    onBack: () -> Unit,
    onOpenFolder: (Asset) -> Unit,
    onOpenAsset: (Asset) -> Unit,
    isProjectRoot: Boolean = false,
    projectName: String = "",
    onOpenShares: (projectName: String) -> Unit = {},
    onOpenMembers: (projectName: String) -> Unit = {},
    onOpenTrash: (projectName: String) -> Unit = {},
    onOpenFolderMembers: (folderId: String, folderName: String) -> Unit = { _, _ -> },
    onOpenTransfers: () -> Unit = {},
    focusId: String? = null,
    vm: FolderViewModel = viewModel(factory = FolderViewModel.Factory, key = "folder-$folderId"),
) {
    // Bound during composition (it's idempotent) so cached items are there on the first frame.
    remember(accountId, folderId) { vm.bind(accountId, folderId); true }
    remember(isProjectRoot, accountId) { if (isProjectRoot) vm.loadShares(projectId); true }
    // Subfolders show their project above the title (mockup); look it up when the route didn't carry it.
    val context = androidx.compose.ui.platform.LocalContext.current
    var project by rememberSaveable(projectId) { mutableStateOf(projectName) }
    LaunchedEffect(projectId, accountId) {
        if (project.isBlank()) runCatching { dev.finalish.FinalishApp.container(context).repository.project(accountId, projectId).name }
            .onSuccess { project = it }
    }

    val remote by vm.remote.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    // Collected here but read only inside each cell, so a new count or preview recomposes that cell alone.
    val versionCounts = vm.versionCounts.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val kind by vm.kind.collectAsStateWithLifecycle()
    val kindCounts by vm.kindCounts.collectAsStateWithLifecycle()
    val fieldFilters by vm.filters.collectAsStateWithLifecycle()
    val flattening by vm.flattening.collectAsStateWithLifecycle()
    var filtersOpen by remember { mutableStateOf(false) }
    val downloads = vm.downloads.collectAsStateWithLifecycle()
    val hasUploads by vm.hasActiveUploads.collectAsStateWithLifecycle()
    val userRefreshing by vm.userRefreshing.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val previews = vm.previews.collectAsStateWithLifecycle()
    val projectShares by vm.shares.collectAsStateWithLifecycle()
    var editingShare by remember { mutableStateOf<dev.finalish.core.model.Share?>(null) }
    var appearance by remember { mutableStateOf(false) }
    var foldersOpen by rememberSaveable { mutableStateOf(true) }
    var filesOpen by rememberSaveable { mutableStateOf(true) }
    AutoRefresh(folderId, intervalMs = { if (vm.hasProcessing) 5_000 else 15_000 }) {
        vm.refresh()
        if (isProjectRoot) vm.loadShares(projectId)
    }
    val online = dev.finalish.ui.components.rememberIsOnline()
    // Download progress ticks don't reach the grid: only a change in the set of finished files does.
    val offlineIds by remember { derivedStateOf { downloads.value.filter { it.state == TransferState.Done }.map { it.fileId }.toSet() } }

    val snackbar = dev.finalish.ui.components.rememberMessageSnackbar(vm.messages)

    val selecting = selection.isNotEmpty()
    BackHandler(selecting) { vm.clearSelection() }

    var searching by rememberSaveable { mutableStateOf(false) }
    // Set when the search icon opens the field; the field takes the focus (and the keyboard) once shown.
    var focusFilter by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var newFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Asset?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Asset>?>(null) }
    var moveTargets by remember { mutableStateOf<Pair<List<Asset>, Boolean>?>(null) } // (assets, isCopy)
    var shareTargets by remember { mutableStateOf<List<Asset>?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // The system file picker, not the photo picker: the photo picker hides the original file names
    // (it hands out "1000000034.jpg"), and uploads must keep the names you gave your files.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.upload(uris, title)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.upload(uris, title)
    }

    val gridState = rememberLazyGridState()
    // "Show in project": once the item is listed, scroll it into view and flash it (once per visit).
    var focusDone by rememberSaveable(focusId) { mutableStateOf(focusId == null) }
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    val uploadMediaLabel = stringResource(R.string.browser_upload_photos_videos)
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 24 } }

    // Built once, not on every pass over the grid's content.
    val currentOpenFolderMembers by rememberUpdatedState(onOpenFolderMembers)
    val onFileAction: (Asset, FileAction) -> Unit = remember(vm) {
        { a, action ->
            when (action) {
                FileAction.Share -> shareTargets = listOf(a)
                FileAction.Download -> vm.download(listOf(a))
                FileAction.SaveToDevice -> vm.saveToDevice(a)
                FileAction.Members -> currentOpenFolderMembers(a.id, a.name)
                FileAction.Rename -> renameTarget = a
                FileAction.Move -> moveTargets = listOf(a) to false
                FileAction.Copy -> moveTargets = listOf(a) to true
                FileAction.Delete -> confirmDelete = listOf(a)
            }
        }
    }
    val accent = Palette.Accent
    val focusTint: (Asset) -> Modifier = remember(focusId, accent) {
        { asset ->
            if (asset.id == focusId) Modifier.drawBehind {
                drawRoundRect(accent.copy(alpha = 0.28f * flash.value), cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
            } else Modifier
        }
    }

    Scaffold(
        containerColor = Palette.Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedContent(selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { sel ->
                if (sel) {
                    SelectionTopBar(
                        count = selection.size,
                        onClose = vm::clearSelection,
                        onSelectAll = vm::selectAll,
                    )
                } else {
                    BrowserTopBar(
                        title = title,
                        showTitle = scrolled,
                        onBack = onBack,
                        layout = layout,
                        onToggleLayout = { vm.setLayout(if (layout == BrowseLayout.Grid) BrowseLayout.List else BrowseLayout.Grid) },
                        sort = sort,
                        onSort = vm::setSort,
                        onSearch = {
                            searching = !searching
                            if (!searching) vm.setFilter("")
                            else {
                                focusFilter = true
                                // The field sits in the header: bring it into view when scrolled down.
                                if (scrolled) scope.launch { gridState.animateScrollToItem(0) }
                            }
                        },
                        onAppearance = { appearance = true },
                        filterCount = fieldFilters.size,
                        onFilters = { filtersOpen = true },
                        projectActions = ProjectActions(
                            { onOpenMembers(project.ifBlank { title }) }, { onOpenTrash(project.ifBlank { title }) }, { onOpenShares(project.ifBlank { title }) },
                            folderMembers = if (isProjectRoot) null else ({ onOpenFolderMembers(folderId, title) }),
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Box {
                    Row(
                        Modifier
                            .height(54.dp)
                            .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = Palette.Accent, spotColor = Palette.Accent)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Palette.Accent)
                            // Long-press: straight to the gallery (mockup's "+" shortcut).
                            .pressable({ addMenu = true }, onLongClick = { pickMedia.launch(arrayOf("image/*", "video/*")) }, scaleTo = 0.94f)
                            .semantics { onLongClick(label = uploadMediaLabel) { false } }
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Palette.OnAccent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.browser_upload), style = MaterialTheme.typography.titleSmall, color = Palette.OnAccent)
                    }
                    if (addMenu) AddSheet(
                        folderName = title,
                        cellular = appSettings.cellularTransfers,
                        onCellular = { v -> vm.updateSettings { it.copy(cellularTransfers = v) } },
                        onMedia = { addMenu = false; pickMedia.launch(arrayOf("image/*", "video/*")) },
                        onFiles = { addMenu = false; pickFiles.launch(arrayOf("*/*")) },
                        onImport = { addMenu = false; importing = true },
                        onNewFolder = { addMenu = false; newFolder = true },
                        onDismiss = { addMenu = false },
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                selecting,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                val selected = vm.selected()
                SelectionActions(
                    selected = selected,
                    onDownload = { vm.download(selected) },
                    onShare = { shareTargets = selected },
                    onMove = { moveTargets = selected to false },
                    onCopy = { moveTargets = selected to true },
                    onRename = { renameTarget = selected.firstOrNull() },
                    onStack = { vm.stackAsVersions(selected) },
                    onDelete = { confirmDelete = selected },
                    onDone = vm::clearSelection,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = userRefreshing,
            onRefresh = { vm.refresh(user = true) },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            // The list goes two columns when wide (landscape), so rows don't stretch under the Upload button.
            LaunchedEffect(focusId) {
                if (focusDone || focusId == null) return@LaunchedEffect
                androidx.compose.runtime.snapshotFlow { items.any { it.id == focusId } }.first { it }
                // A frame for the grid to lay the new items out.
                androidx.compose.runtime.withFrameNanos { }
                revealItem(gridState, focusId)
                focusDone = true
                repeat(2) {
                    flash.animateTo(1f, androidx.compose.animation.core.tween(280))
                    flash.animateTo(0f, androidx.compose.animation.core.tween(520))
                }
            }
            val columns = if (layout == BrowseLayout.Grid) GridCells.Adaptive(appSettings.cardSize.minWidthDp.dp) else GridCells.Adaptive(360.dp)
            LazyVerticalGrid(
                state = gridState,
                columns = columns,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (layout == BrowseLayout.Grid) 18.dp else 2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                    Column {
                        if (!isProjectRoot && project.isNotBlank()) {
                            Text(project, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            LayoutToggle(layout) { vm.setLayout(it) }
                        }
                        val folders = items.count { it.isFolder }
                        val files = items.size - folders
                        if (remote.data != null) {
                            Text(
                                listOfNotNull(
                                    folders.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.browser_folder_count, it, it) },
                                    files.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.browser_item_count, it, it) },
                                ).joinToString(" · ").ifBlank { stringResource(R.string.browser_empty) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextTertiary,
                            )
                        }
                        dev.finalish.ui.components.OfflineBanner(Modifier.padding(top = 12.dp))
                        AnimatedVisibility(searching) {
                            FilterField(filter, vm::setFilter, Modifier.padding(top = 14.dp), focus = focusFilter, onFocused = { focusFilter = false })
                        }
                        val chips = KindFilter.entries.filter { it == KindFilter.All || (kindCounts[it] ?: 0) > 0 }
                        if (chips.size > 2) {
                            androidx.compose.foundation.lazy.LazyRow(
                                Modifier.padding(top = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(chips, key = { it.name }) { c ->
                                    val n = kindCounts[c] ?: 0
                                    val on = kind == c
                                    Text(
                                        if (c == KindFilter.Review) stringResource(c.labelRes) else "${stringResource(c.labelRes)} · $n",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (on) Palette.Ink else Palette.TextSecondary,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(if (on) Palette.Text else Palette.Surface2)
                                            .pressable({ vm.setKind(c) }, scaleTo = 0.94f)
                                            .padding(horizontal = 13.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        if (fieldFilters.isNotEmpty()) {
                            androidx.compose.foundation.lazy.LazyRow(
                                Modifier.padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(fieldFilters.size) { i ->
                                    val f = fieldFilters[i]
                                    Row(
                                        Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Palette.AccentContainer)
                                            .pressable({ vm.removeFilter(f) }, scaleTo = 0.94f)
                                            .padding(start = 13.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(f.label(), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright, maxLines = 1)
                                        Icon(Icons.Rounded.Close, stringResource(R.string.browser_remove_filter), tint = Palette.AccentBright, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                                    }
                                }
                            }
                        }
                        if (appSettings.flattenFolders) {
                            Text(
                                stringResource(if (flattening) R.string.browser_loading_subfolders else R.string.browser_showing_subfolders),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (isProjectRoot && filter.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "shares") {
                        dev.finalish.ui.shares.ProjectSharesStrip(
                            shares = projectShares,
                            accountId = accountId,
                            onOpen = { editingShare = it },
                            onSeeAll = { onOpenShares(project.ifBlank { title }) },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                if (hasUploads) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "uploads") {
                        FolderUploadStrip(vm, onOpenTransfers)
                    }
                }

                when {
                    remote.isInitialLoading -> items(8, key = { "sk$it" }) { FolderSkeletonCard() }
                    remote.isEmptyError -> item(span = { GridItemSpan(maxLineSpan) }, key = "err") {
                        ErrorState(remote.error.orEmpty(), onRetry = { vm.refresh(user = true) }, retrying = true)
                    }
                    items.isEmpty() && !remote.loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        if (filter.isNotBlank()) {
                            EmptyState(Icons.Rounded.Search, stringResource(R.string.browser_no_matches), stringResource(R.string.browser_no_matches_message, filter))
                        } else {
                            EmptyState(
                                Icons.Rounded.FolderOpen, stringResource(R.string.browser_empty_title),
                                stringResource(R.string.browser_empty_message),
                                actionLabel = stringResource(R.string.browser_upload),
                                brand = true,
                                // Same menu as the + button: the text promises audio and PDF too.
                                onAction = { addMenu = true },
                            )
                        }
                    }
                    else -> {
                        val cell: @Composable androidx.compose.foundation.lazy.grid.LazyGridItemScope.(Asset) -> Unit = { asset ->
                            val onClick = {
                                if (selecting) vm.toggleSelect(asset.id)
                                else if (asset.isFolder) onOpenFolder(asset) else onOpenAsset(asset)
                            }
                            if (asset.isVersionStack) LaunchedEffect(asset.id, asset.updatedAt) { vm.requestVersionCount(asset) }
                            // Read here, per cell: a count or preview arriving recomposes only its own card.
                            val versionCount by remember(asset.id) { derivedStateOf { versionCounts.value[asset.id] } }
                            if (layout == BrowseLayout.Grid) {
                                if (asset.isFolder) LaunchedEffect(asset.id) { vm.requestPreview(asset) }
                                if (asset.isFolder) {
                                    val preview by remember(asset.id) { derivedStateOf { previews.value[asset.id] } }
                                    FolderGridCard(
                                        asset, preview,
                                        selected = asset.id in selection, selecting = selecting,
                                        onClick = onClick, onLongClick = { vm.toggleSelect(asset.id) },
                                        modifier = Modifier.animateItem(),
                                        aspect = appSettings.cardAspect.ratio,
                                        titleLines = appSettings.titleLines,
                                        showInfo = appSettings.showCardInfo,
                                        onAction = { onFileAction(asset, it) },
                                    )
                                } else AssetGridCard(
                                    asset, selected = asset.id in selection, selecting = selecting,
                                    offline = asset.media.id in offlineIds,
                                    onClick = onClick, onLongClick = { vm.toggleSelect(asset.id) },
                                    modifier = Modifier.animateItem().then(focusTint(asset)),
                                    aspect = appSettings.cardAspect.ratio,
                                    fit = appSettings.thumbFit,
                                    titleLines = appSettings.titleLines,
                                    showInfo = appSettings.showCardInfo,
                                    compact = appSettings.cardSize == dev.finalish.core.data.CardSize.S,
                                    fieldsLine = cardFieldsLine(asset, appSettings.cardFields),
                                    onAction = { onFileAction(asset, it) },
                                    online = online,
                                    versionCount = versionCount,
                                )
                            } else {
                                AssetListRow(
                                    asset, selected = asset.id in selection, selecting = selecting,
                                    offline = asset.media.id in offlineIds,
                                    onClick = onClick, onLongClick = { vm.toggleSelect(asset.id) },
                                    modifier = Modifier.animateItem().then(focusTint(asset)),
                                    onAction = { onFileAction(asset, it) },
                                    online = online,
                                    versionCount = versionCount,
                                    fieldsLine = cardFieldsLine(asset, appSettings.cardFields),
                                )
                            }
                        }
                        val folderItems = items.filter { it.isFolder }
                        val fileItems = items.filterNot { it.isFolder }
                        val grouped = appSettings.groupSections && folderItems.isNotEmpty() && fileItems.isNotEmpty()
                        if (!grouped) {
                            items(items, key = { it.id }, contentType = { if (it.isFolder) 0 else 1 }) { cell(it) }
                        } else {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h-folders") {
                                SectionBar(
                                    pluralStringResource(R.plurals.browser_folder_count, folderItems.size, folderItems.size),
                                    foldersOpen, Modifier.animateItem(),
                                ) { foldersOpen = !foldersOpen }
                            }
                            if (foldersOpen) items(folderItems, key = { it.id }, contentType = { 0 }) { cell(it) }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h-files") {
                                val bytes = fileItems.sumOf { it.media.fileSize }
                                SectionBar(
                                    pluralStringResource(R.plurals.browser_asset_count, fileItems.size, fileItems.size) +
                                        (if (bytes > 0) " · ${dev.finalish.core.util.Format.bytes(bytes)}" else ""),
                                    filesOpen, Modifier.animateItem(),
                                ) { filesOpen = !filesOpen }
                            }
                            if (filesOpen) items(fileItems, key = { it.id }, contentType = { 1 }) { cell(it) }
                        }
                    }
                }
            }
        }
    }

    editingShare?.let { s ->
        dev.finalish.ui.shares.ShareEditorSheet(
            accountId = accountId,
            initial = s,
            onChanged = vm::replaceShare,
            onMessage = { snackbarMessage -> vm.toast(snackbarMessage) },
            onDismiss = { editingShare = null },
            projectId = projectId,
        )
    }

    if (importing) ImportFromLinkDialog(onDismiss = { importing = false }) { url, name ->
        importing = false
        vm.importFromUrl(url, name, title)
    }

    if (appearance) {
        val fields by vm.fields.collectAsStateWithLifecycle()
        AppearanceSheet(appSettings, sort, vm::setSort, onChange = vm::updateSettings, onDismiss = { appearance = false }, fields = fields)
    }

    if (filtersOpen) {
        val fields by vm.fields.collectAsStateWithLifecycle()
        FilterSheet(fields, fieldFilters, onAdd = vm::addFilter, onRemove = vm::removeFilter, onClear = vm::clearFilters, onDismiss = { filtersOpen = false })
    }

    if (newFolder) {
        TextPromptDialog(stringResource(R.string.browser_new_folder), stringResource(R.string.browser_folder_name), "", stringResource(R.string.browser_create), onDismiss = { newFolder = false }) {
            newFolder = false
            vm.createFolder(it)
        }
    }
    renameTarget?.let { target ->
        TextPromptDialog(stringResource(R.string.browser_action_rename), stringResource(R.string.browser_name), target.name, stringResource(R.string.browser_action_rename), onDismiss = { renameTarget = null }) {
            renameTarget = null
            vm.rename(target, it)
        }
    }
    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Palette.Surface2,
            title = { Text(if (list.size == 1) stringResource(R.string.browser_delete_one, list.first().name) else pluralStringResource(R.plurals.browser_delete_many, list.size, list.size)) },
            text = { Text(stringResource(R.string.browser_delete_message), color = Palette.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(list) }) { Text(stringResource(R.string.browser_action_delete), color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
    moveTargets?.let { (list, isCopy) ->
        FolderPickerSheet(
            accountId = accountId,
            rootFolderId = rootFolderId,
            rootName = stringResource(R.string.browser_project),
            projectId = projectId,
            actionLabel = stringResource(if (isCopy) R.string.browser_copy else R.string.browser_move),
            excludeIds = list.map { it.id }.toSet(),
            onPick = { dest, _ ->
                moveTargets = null
                if (isCopy) vm.copy(list, dest) else vm.move(list, dest)
            },
            onDismiss = { moveTargets = null },
        )
    }
    shareTargets?.let { list ->
        CreateShareSheet(accountId, projectId, list, onDismiss = { shareTargets = null; vm.clearSelection() })
    }
}

class ProjectActions(val members: () -> Unit, val trash: () -> Unit, val shares: () -> Unit, val folderMembers: (() -> Unit)? = null)

@Composable
private fun ImportFromLinkDialog(onDismiss: () -> Unit, onImport: (url: String, name: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val suggested = remember(url) {
        runCatching { url.trim().toUri().lastPathSegment?.substringBefore('?') }.getOrNull().orEmpty()
    }
    val valid = url.trim().startsWith("http://") || url.trim().startsWith("https://")
    val importedFile = stringResource(R.string.browser_imported_file)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        icon = { Icon(Icons.Rounded.AddLink, null) },
        title = { Text(stringResource(R.string.browser_import_from_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.browser_import_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.browser_file_url)) }, singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.browser_file_name)) },
                    placeholder = { Text(suggested.ifBlank { "clip.mp4" }) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onImport(url.trim(), name.trim().ifBlank { suggested.ifBlank { importedFile } }) }) { Text(stringResource(R.string.browser_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

@Composable
private fun SectionBar(label: String, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 0f else -90f, label = "chev")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .pressable(onToggle, scaleTo = 0.99f)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
        Icon(
            Icons.Rounded.KeyboardArrowDown, stringResource(if (expanded) R.string.browser_collapse else R.string.browser_expand),
            tint = Palette.TextTertiary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun BrowserTopBar(
    title: String,
    showTitle: Boolean,
    onBack: () -> Unit,
    layout: BrowseLayout,
    onToggleLayout: () -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onSearch: () -> Unit,
    onAppearance: () -> Unit,
    filterCount: Int = 0,
    onFilters: () -> Unit = {},
    projectActions: ProjectActions? = null,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Ink)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_back)) }
        // One fixed-weight slot, so the icons don't jump while the title fades.
        Box(Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(showTitle, enter = fadeIn(dev.finalish.ui.theme.Motion.enter()), exit = fadeOut(dev.finalish.ui.theme.Motion.exit())) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.browser_filter)) }
        IconButton(onClick = onFilters) {
            Box {
                Icon(Icons.Rounded.FilterList, if (filterCount > 0) stringResource(R.string.browser_filters_on, filterCount) else stringResource(R.string.browser_filters), tint = if (filterCount > 0) Palette.AccentBright else androidx.compose.material3.LocalContentColor.current)
                if (filterCount > 0) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Accent))
            }
        }
        IconButton(onClick = onAppearance) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.browser_sort_and_view)) }
        if (projectActions != null) Box {
            IconButton(onClick = { projectMenu = true }) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.browser_project_options)) }
            DropdownMenu(projectMenu, onDismissRequest = { projectMenu = false }, shape = dev.finalish.ui.theme.MenuShape) {
                projectActions.folderMembers?.let { fm ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.browser_folder_members)) }, leadingIcon = { Icon(Icons.Rounded.FolderShared, null) }, onClick = { projectMenu = false; fm() })
                }
                DropdownMenuItem(text = { Text(stringResource(if (projectActions.folderMembers != null) R.string.browser_project_members else R.string.browser_action_members)) }, leadingIcon = { Icon(Icons.Rounded.Group, null) }, onClick = { projectMenu = false; projectActions.members() })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_share_links)) }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, onClick = { projectMenu = false; projectActions.shares() })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_recently_deleted)) }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = { projectMenu = false; projectActions.trash() })
            }
        }
    }
}

@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface1)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_cancel)) }
        AnimatedContent(count, label = "count") { Text(pluralStringResource(R.plurals.browser_selected_count, it, it), style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.browser_select_all)) }
    }
}

@Composable
private fun SelectionActions(
    selected: List<Asset>,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onStack: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit = {},
) {
    var more by remember { mutableStateOf(false) }
    val files = selected.filter { it.isFile }
    // Floating pill (mockup): count, the three most used actions, ⋯ for the rest, Done.
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
    Row(
        Modifier
            .shadow(24.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Surface3.copy(alpha = 0.97f))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pluralStringResource(R.plurals.browser_selected_count, selected.size, selected.size), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        BarIcon(Icons.Rounded.Link, stringResource(R.string.browser_share), onShare)
        BarIcon(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.browser_move), onMove)
        BarIcon(Icons.Rounded.Delete, stringResource(R.string.browser_action_delete), onDelete, tint = Palette.Red)
        Box {
            BarIcon(Icons.Rounded.MoreHoriz, stringResource(R.string.browser_more), { more = true })
            DropdownMenu(more, onDismissRequest = { more = false }, shape = dev.finalish.ui.theme.MenuShape) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_action_save_offline)) }, leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, null) },
                    enabled = selected.any { !it.isFolder }, onClick = { more = false; onDownload() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_action_rename)) }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                    enabled = selected.size == 1, onClick = { more = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_action_copy_to)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    enabled = selected.isNotEmpty(), onClick = { more = false; onCopy() },
                )
                HorizontalDivider(color = Palette.Hairline)
                // Files make a new stack; files plus one existing stack go into that stack.
                val stacks = selected.filter { it.isVersionStack }
                val stackable = files.size + stacks.size == selected.size && when (stacks.size) {
                    0 -> files.size in 2..10
                    1 -> files.size in 1..10
                    else -> false
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(if (stacks.size == 1) R.string.browser_add_to_stack else R.string.browser_stack_as_versions))
                            if (!stackable) Text(stringResource(R.string.browser_stack_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                        }
                    },
                    leadingIcon = { Icon(Icons.Rounded.Layers, null) },
                    enabled = stackable,
                    onClick = { more = false; onStack() },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.browser_done), style = MaterialTheme.typography.labelLarge, color = Palette.Ink,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Text).pressable(onDone, scaleTo = 0.94f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Palette.TextSecondary) {
    Box(
        Modifier.size(40.dp, 44.dp).clip(RoundedCornerShape(12.dp)).pressable(onClick, scaleTo = 0.9f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Palette.Text,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .pressable(onClick, enabled = enabled, scaleTo = 0.9f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, focus: Boolean = false, onFocused: () -> Unit = {}) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Opened from the search icon: type straight away, with the keyboard up (tablets don't raise it by themselves).
    LaunchedEffect(focus) {
        if (!focus) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        onFocused()
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface2)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Palette.TextTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.browser_filter_this_folder), color = Palette.TextTertiary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Rounded.Close, stringResource(R.string.browser_clear), tint = Palette.TextTertiary, modifier = Modifier.size(18.dp).pressable({ onChange("") }))
        }
    }
}

/** The "Uploading N files" strip; collects the uploads itself so their progress redraws only this strip. */
@Composable
private fun FolderUploadStrip(vm: FolderViewModel, onClick: () -> Unit) {
    val uploads by vm.activeUploads.collectAsStateWithLifecycle()
    if (uploads.isEmpty()) return
    UploadStrip(uploads.size, uploads.sumOf { it.doneBytes }.toFloat() / uploads.sumOf { it.totalBytes }.coerceAtLeast(1), onClick)
}

@Composable
private fun UploadStrip(count: Int, progress: Float, onClick: () -> Unit) {
    // Tap: the Transfers tab, where each upload can be followed, paused or cancelled.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.AccentContainer)
            .pressable(onClick, scaleTo = 0.98f)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.UploadFile, null, tint = Palette.AccentBright)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.browser_uploading_files, count, count), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            dev.finalish.ui.components.TransferProgress(progress)
        }
        Spacer(Modifier.width(12.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.browser_open_transfers), tint = Palette.TextSecondary, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** "Status: Approved · Duration: 1:23" for the fields picked in Sort & view. */
@Composable
private fun cardFieldsLine(asset: Asset, fields: List<String>): String? {
    if (asset.isFolder || fields.isEmpty()) return null
    val res = LocalResources.current
    val people: (Int) -> String = { res.getQuantityString(R.plurals.browser_people_count, it, it) }
    return fields.mapNotNull { f -> asset.fieldText(f, people)?.let { "$f: $it" } }.joinToString(" · ").ifBlank { null }
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length)))
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        icon = { Icon(Icons.Rounded.Edit, null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = text.text.isNotBlank(), onClick = { onConfirm(text.text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

/** Grid / list as a small two-icon toggle next to the title. */
@Composable
private fun LayoutToggle(layout: BrowseLayout, onChange: (BrowseLayout) -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface2).padding(3.dp),
    ) {
        listOf(BrowseLayout.Grid to Icons.Rounded.GridView, BrowseLayout.List to Icons.AutoMirrored.Rounded.ViewList).forEach { (l, icon) ->
            val on = layout == l
            Box(
                Modifier.size(width = 36.dp, height = 30.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) Palette.Surface4 else androidx.compose.ui.graphics.Color.Transparent)
                    .pressable({ onChange(l) }, scaleTo = 0.9f),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, stringResource(if (l == BrowseLayout.Grid) R.string.browser_layout_grid else R.string.browser_layout_list), tint = if (on) Palette.Text else Palette.TextTertiary, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** "Add to <folder>" sheet (mockup): two big tiles for the common cases, then import and new folder. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    folderName: String,
    cellular: Boolean,
    onCellular: (Boolean) -> Unit,
    onMedia: () -> Unit,
    onFiles: () -> Unit,
    onImport: () -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 18.dp)) {
            Text(stringResource(R.string.browser_add_to, folderName), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.browser_uploads_keep_going), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple(stringResource(R.string.browser_photos_videos), Icons.Rounded.PhotoLibrary, onMedia), Triple(stringResource(R.string.browser_files), Icons.Rounded.UploadFile, onFiles)).forEachIndexed { i, (label, icon, go) ->
                    Column(
                        Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Surface3)
                            .pressable(go, scaleTo = 0.96f).padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(icon, null, tint = if (i == 0) Palette.Accent else Palette.Blue, modifier = Modifier.size(24.dp))
                        Text(label, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Surface2)) {
                listOf(Triple(stringResource(R.string.browser_import_from_link), Icons.Rounded.AddLink, onImport), Triple(stringResource(R.string.browser_new_folder), Icons.Rounded.CreateNewFolder, onNewFolder)).forEachIndexed { i, (label, icon, go) ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = Palette.Surface3)
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).pressable(go, scaleTo = 0.98f).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.browser_use_mobile_data), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    cellular, onCellular,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
                )
            }
        }
    }
}

/**
 * Scrolls [state] until the item keyed [key] is in view, then centres it a little below the top.
 * A grid can't map a key to an index before it is laid out, so this pages down until it shows up.
 */
private suspend fun revealItem(state: androidx.compose.foundation.lazy.grid.LazyGridState, key: Any) {
    repeat(40) {
        val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        if (hit != null) {
            state.animateScrollToItem(hit.index, scrollOffset = -state.layoutInfo.viewportSize.height / 4)
            return
        }
        val before = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        state.scrollBy(state.layoutInfo.viewportSize.height * 0.8f)
        if (before == (state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)) return
    }
}
