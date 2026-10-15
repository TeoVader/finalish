package dev.finalish.ui.home

import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Project
import dev.finalish.core.transfer.DownloadItem
import dev.finalish.core.transfer.TransferState
import dev.finalish.ui.SessionState
import dev.finalish.ui.browser.FolderPickerSheet
import dev.finalish.ui.components.ReadableWidth
import dev.finalish.ui.components.WIDE_LAYOUT
import dev.finalish.ui.components.pressable
import dev.finalish.ui.search.SearchTab
import dev.finalish.ui.settings.SettingsTab
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.transfers.TransfersTab

internal enum class HomeTab(@androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector) {
    Projects(R.string.home_tab_projects, Icons.Rounded.Folder),
    Search(R.string.home_tab_search, Icons.Rounded.Search),
    Transfers(R.string.home_tab_transfers, Icons.Rounded.SwapVert),
    Settings(R.string.home_tab_settings, Icons.Rounded.Build),
}

@Composable
fun HomeScreen(
    session: SessionState,
    onSelectAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenShares: (Project) -> Unit,
    onOpenCollections: (Project) -> Unit,
    onOpenAsset: (Asset) -> Unit,
    onOpenOffline: (DownloadItem) -> Unit,
    onOpenUpload: (dev.finalish.core.transfer.UploadItem) -> Unit = {},
    onRetrySession: () -> Unit = {},
    onOpenMembers: (scope: String, id: String, title: String) -> Unit = { _, _, _ -> },
    onOpenTrash: (Project) -> Unit = {},
    onOpenMetadataFields: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    requestedTab: Int? = null,
    onTabShown: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val container = remember { FinalishApp.container(context) }
    // Only the count reaches the screen: progress ticks would otherwise recompose all of Home for a badge.
    val runningCount = remember {
        val moving: (TransferState) -> Boolean = { it == TransferState.Running || it == TransferState.Queued }
        val count = { up: List<dev.finalish.core.transfer.UploadItem>, down: List<DownloadItem> -> up.count { moving(it.state) } + down.count { moving(it.state) } }
        count(container.transfers.uploads.value, container.transfers.downloads.value) to
            kotlinx.coroutines.flow.combine(container.transfers.uploads, container.transfers.downloads, count).distinctUntilChanged()
    }
    val running by runningCount.second.collectAsStateWithLifecycle(runningCount.first)
    val projectsVm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory)
    val prefs by container.settings.settings.collectAsStateWithLifecycle()
    if (!prefs.permissionsAsked) {
        // One-time step right after the first sign-in.
        dev.finalish.ui.onboarding.PermissionsScreen(onDone = { container.settings.update { it.copy(permissionsAsked = true) } })
        return
    }

    // The floating bar steps aside while you scroll down a list and comes back on the way up.
    var navShown by remember { mutableStateOf(true) }
    val navScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) navShown = false else if (available.y > 6f) navShown = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = tab) { HomeTab.entries.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reducedMotion = dev.finalish.ui.theme.LocalReducedMotion.current
    androidx.compose.runtime.LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.currentPage }.collect { tab = it }
    }
    fun go(i: Int) {
        scope.launch { if (reducedMotion) pager.scrollToPage(i) else pager.animateScrollToPage(i) }
    }
    androidx.compose.runtime.LaunchedEffect(requestedTab) {
        requestedTab?.let { tab = it; pager.scrollToPage(it); onTabShown() }
    }
    // Back from Search, Transfers or Settings lands on Projects before leaving the app.
    androidx.activity.compose.BackHandler(tab != 0) { go(0) }
    androidx.compose.runtime.LaunchedEffect(tab) { navShown = true }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ink).nestedScroll(navScroll)) {
        // Tablets and unfolded phones (expanded width): the bar stands on the left and stays put,
        // and text-heavy tabs keep a readable width instead of stretching edge to edge.
        val wide = maxWidth >= WIDE_LAYOUT
        // Phones in landscape (compact height) get the side bar too: a bottom pill would cover a
        // third of the little height there is.
        val rail = wide || maxHeight < COMPACT_HEIGHT
        val padding = if (rail) PaddingValues(bottom = 24.dp) else PaddingValues(bottom = 96.dp)
        // Each tab keeps its scroll position and inputs while you visit the others.
        val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        // Swipe sideways to move between the tabs in the bar (inner sideways lists scroll first).
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            beyondViewportPageCount = 0,
            key = { HomeTab.entries[it].name },
            // With the side bar the tabs start right of it.
            // In landscape the camera hole sits on one side: keep the tabs clear of it.
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))
                .padding(start = if (rail) NAV_RAIL_SPACE else 0.dp),
        ) { i -> val t = HomeTab.entries[i]; tabStates.SaveableStateProvider(t.name) {
            androidx.compose.runtime.CompositionLocalProvider(dev.finalish.ui.components.LocalSideBarWidth provides if (rail) NAV_RAIL_SPACE else 0.dp) {
            when (t) {
                HomeTab.Projects -> ProjectsTab(
                    session, onSelectAccount, onOpenProject, onOpenShares, onOpenCollections, onSignOut, padding, projectsVm,
                    onRetrySession = onRetrySession,
                    onOpenMembers = onOpenMembers,
                    onOpenTrash = onOpenTrash,
                    onOpenNotifications = onOpenNotifications,
                )
                HomeTab.Search -> ReadableWidth(wide) {
                    SearchTab(
                        session.accountId,
                        onOpenAsset = onOpenAsset,
                        onOpenProject = onOpenProject,
                        contentPadding = padding,
                    )
                }
                HomeTab.Transfers -> ReadableWidth(wide) { TransfersTab(onOpenOffline, padding, onOpenUpload) }
                HomeTab.Settings -> ReadableWidth(wide) {
                    SettingsTab(session, onSignOut, padding, onSelectAccount, onOpenMetadataFields = onOpenMetadataFields, onOpenGroups = onOpenGroups, onOpenLicenses = onOpenLicenses)
                }
            }
            }
        } }

        // Same at the top: scrolled content fades out under the status bar instead of colliding with the clock.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars.add(androidx.compose.foundation.layout.WindowInsets(top = 12.dp)))
                .background(Brush.verticalGradient(listOf(Palette.Ink, Palette.Ink.copy(alpha = 0.85f), Color.Transparent))),
        )
        if (rail) {
            FloatingNav(
                current = pager.targetPage,
                badge = running,
                onSelect = ::go,
                vertical = true,
                modifier = Modifier.align(Alignment.CenterStart).navigationBarsPadding()
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Start))
                    .padding(start = 16.dp),
            )
            return@BoxWithConstraints
        }
        // Soft fade under the floating bar so content never collides with it.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.92f)))),
        )
        androidx.compose.animation.AnimatedVisibility(
            navShown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
            exit = androidx.compose.animation.slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
        ) {
            FloatingNav(
                current = pager.targetPage,
                badge = running,
                onSelect = ::go,
                modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp),
            )
        }
    }

}

private val NAV_RAIL_SPACE = 88.dp
private val COMPACT_HEIGHT = 480.dp

/**
 * The home tabs' floating pill: along the bottom on phones; on wide screens [vertical], standing
 * on the left, with only the icons (the selected one in the accent pill).
 */
@Composable
internal fun FloatingNav(current: Int, badge: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) return VerticalNav(current, badge, onSelect, modifier)
    Row(
        modifier
            .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.Surface3.copy(alpha = 0.97f))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(28.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        val density = androidx.compose.ui.platform.LocalDensity.current
        HomeTab.entries.forEachIndexed { i, t ->
            val selected = i == current
            val label = stringResource(t.labelRes)
            // The open pill fits its label: long words (Italian "Trasferimenti") were cut at a fixed width.
            val open = remember(label, labelStyle, density) {
                with(density) { measurer.measure(label, labelStyle, maxLines = 1).size.width.toDp() } + 22.dp + 8.dp + 32.dp
            }.coerceAtLeast(116.dp)
            val bg by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, Motion.snappy(), label = "navbg")
            val fg by animateColorAsState(if (selected) Palette.OnAccent else Palette.TextSecondary, Motion.snappy(), label = "navfg")
            val w by animateDpAsState(if (selected) open else 54.dp, Motion.snappy(), label = "navw")
            Box(
                Modifier
                    .width(w)
                    .height(48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(bg)
                    .pressable({ onSelect(i) }, scaleTo = 0.92f),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Icon(t.icon, label, tint = fg, modifier = Modifier.size(22.dp))
                        if (t == HomeTab.Transfers && badge > 0) {
                            // Amber dot: something is moving (uploads or offline downloads).
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Amber),
                            )
                        }
                    }
                    // The label opens with the pill instead of popping in while it is still growing.
                    androidx.compose.animation.AnimatedVisibility(
                        selected,
                        enter = androidx.compose.animation.expandHorizontally(Motion.snappy()) + fadeIn(Motion.enter()),
                        exit = androidx.compose.animation.shrinkHorizontally(Motion.snappy()) + fadeOut(Motion.exit()),
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalNav(current: Int, badge: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.Surface3.copy(alpha = 0.97f))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(28.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HomeTab.entries.forEachIndexed { i, t ->
            val selected = i == current
            val label = stringResource(t.labelRes)
            val bg by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, Motion.snappy(), label = "railbg")
            val fg by animateColorAsState(if (selected) Palette.OnAccent else Palette.TextSecondary, Motion.snappy(), label = "railfg")
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(bg)
                    .pressable({ onSelect(i) }, scaleTo = 0.92f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, label, tint = fg, modifier = Modifier.size(22.dp))
                if (t == HomeTab.Transfers && badge > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Amber),
                    )
                }
            }
        }
    }
}
