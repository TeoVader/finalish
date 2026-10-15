package dev.finalish.ui.shares

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.ui.application
import dev.finalish.ui.components.rememberMessageSnackbar
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import dev.finalish.ui.container
import dev.finalish.core.data.FrameioRepository
import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.finalish.R
import dev.finalish.core.data.Remote
import dev.finalish.core.model.Collection
import dev.finalish.core.model.Share
import dev.finalish.core.util.Format
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SharesEvent {
    data class Bind(val accountId: String, val projectId: String) : SharesEvent
    data object Refresh : SharesEvent
    data class SetEnabled(val share: Share, val enabled: Boolean) : SharesEvent
    /** A link changed in the editor. */
    data class Replace(val share: Share) : SharesEvent
    data class Delete(val share: Share) : SharesEvent
    data class ShowMessage(val text: String) : SharesEvent
}

class SharesViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<Remote<List<Share>>>(Remote.loading())
    val state: StateFlow<Remote<List<Share>>> = _state.asStateFlow()
    private val _messages = messageFlow(capacity = 2)
    val messages = _messages.asSharedFlow()
    private var account = ""
    private var project = ""

    fun onEvent(e: SharesEvent) {
        when (e) {
            is SharesEvent.Bind -> bind(e.accountId, e.projectId)
            SharesEvent.Refresh -> refresh()
            is SharesEvent.SetEnabled -> setEnabled(e.share, e.enabled)
            is SharesEvent.Replace -> replace(e.share)
            is SharesEvent.Delete -> delete(e.share)
            is SharesEvent.ShowMessage -> _messages.tryEmit(e.text)
        }
    }

    private fun bind(a: String, p: String) {
        if (account == a && project == p) return
        account = a; project = p
        refresh()
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    /** A new pull replaces the one in flight, so an older answer can't bring back a deleted link. */
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { repo.shares(account, project).collect { _state.value = it } }
    }

    private fun setEnabled(s: Share, enabled: Boolean) {
        _state.update { r -> r.copy(data = r.data?.map { if (it.id == s.id) it.copy(enabled = enabled) else it }) }
        viewModelScope.launch {
            _messages.report { repo.setShareEnabled(account, s, enabled) }
                .onFailure { _state.update { r -> r.copy(data = r.data?.map { x -> if (x.id == s.id) s else x }) } }
        }
    }

    private fun replace(s: Share) = _state.update { r -> r.copy(data = r.data?.map { if (it.id == s.id) s else it }) }

    private fun delete(s: Share) {
        _state.update { r -> r.copy(data = r.data?.filterNot { it.id == s.id }) }
        viewModelScope.launch {
            _messages.report({ getApplication<Application>().getString(R.string.shares_deleted) }) { repo.deleteShare(account, project, s.id) }
                .onFailure { refresh() }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { SharesViewModel(application, container.repository) } }
    }
}

@Composable
fun SharesScreen(accountId: String, projectId: String, projectName: String, onBack: () -> Unit, vm: SharesViewModel = viewModel(factory = SharesViewModel.Factory)) {
    LaunchedEffect(accountId, projectId) { vm.onEvent(SharesEvent.Bind(accountId, projectId)) }
    val remote by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val snackbar = rememberMessageSnackbar(vm.messages)
    var confirm by remember { mutableStateOf<Share?>(null) }
    var editing by remember { mutableStateOf<Share?>(null) }
    var filter by remember { mutableIntStateOf(0) } // 0 active, 1 disabled, 2 all
    var picking by remember { mutableStateOf(false) }
    var newLinkAssets by remember { mutableStateOf<List<dev.finalish.core.model.Asset>?>(null) }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar("", null, onBack)
            PullToRefreshBox(remote.loading && remote.data != null, onRefresh = { vm.onEvent(SharesEvent.Refresh) }, Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    // Room for the floating "New link" button.
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                            Text(projectName, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
                            Text(stringResource(R.string.shares_share_links), style = MaterialTheme.typography.headlineMedium)
                            val all = remote.data.orEmpty()
                            if (all.isNotEmpty()) {
                                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        stringResource(R.string.shares_filter_active, all.count { it.enabled }),
                                        stringResource(R.string.shares_filter_off, all.count { !it.enabled }),
                                        stringResource(R.string.shares_filter_all),
                                    ).forEachIndexed { i, label ->
                                        val on = filter == i
                                        Text(
                                            label, style = MaterialTheme.typography.labelLarge,
                                            color = if (on) Palette.Ink else Palette.TextSecondary,
                                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Text else Palette.Surface2)
                                                .pressable({ filter = i }, scaleTo = 0.94f).padding(horizontal = 13.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    when {
                        remote.isInitialLoading -> items(4) { Box(Modifier.fillMaxWidth().height(96.dp).shimmer(RoundedCornerShape(18.dp))) }
                        remote.isEmptyError -> item { ErrorState(remote.error.orEmpty(), onRetry = { vm.onEvent(SharesEvent.Refresh) }) }
                        remote.data.isNullOrEmpty() -> item {
                            EmptyState(Icons.Rounded.Link, stringResource(R.string.shares_empty_title), stringResource(R.string.shares_empty_body))
                        }
                        else -> items(
                            remote.data.orEmpty().filter { when (filter) { 0 -> it.enabled; 1 -> !it.enabled; else -> true } },
                            key = { it.id },
                        ) { s ->
                            ShareCard(
                                s, accountId,
                                onOpen = { editing = s },
                                onCopy = { s.shortUrl?.let { copyToClipboard(context, it) } },
                                onShare = { s.shortUrl?.let { shareText(context, it, s.name) } },
                                onToggle = { vm.onEvent(SharesEvent.SetEnabled(s, it)) },
                                onDelete = { confirm = s },
                            )
                        }
                    }
                }
            }
        }
        dev.finalish.ui.components.PrimaryButton(
            stringResource(R.string.shares_new_link),
            onClick = { picking = true },
            icon = Icons.Rounded.Add,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }

    if (picking) {
        dev.finalish.ui.browser.AssetPickerSheet(
            accountId = accountId,
            projectId = projectId,
            actionLabel = { n -> if (n == 0) res.getString(R.string.shares_choose_what_to_share) else res.getQuantityString(R.plurals.shares_share_n_items, n, n) },
            onPick = { picking = false; newLinkAssets = it },
            onDismiss = { picking = false },
        )
    }
    newLinkAssets?.let { list ->
        CreateShareSheet(
            accountId, projectId, list,
            onDismiss = { newLinkAssets = null },
            onChangeAssets = { newLinkAssets = null; picking = true },
            onCreated = { vm.onEvent(SharesEvent.Refresh) },
        )
    }

    editing?.let { s ->
        ShareEditorSheet(
            accountId = accountId,
            initial = s,
            onChanged = { vm.onEvent(SharesEvent.Replace(it)) },
            onMessage = { vm.onEvent(SharesEvent.ShowMessage(it)) },
            onDismiss = { editing = null },
            projectId = projectId,
            onDelete = { editing = null; confirm = s },
        )
    }

    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Palette.Surface2,
            title = { Text(stringResource(R.string.shares_delete_title, s.name ?: stringResource(R.string.shares_share_fallback))) },
            text = { Text(stringResource(R.string.shares_delete_body), color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.onEvent(SharesEvent.Delete(s)) }) { Text(stringResource(R.string.shares_delete), color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.shares_cancel)) } },
        )
    }
}

@Composable
private fun ShareCard(s: Share, accountId: String, onOpen: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (s.enabled) 1f else 0.6f }
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Surface2)
            .pressable(onOpen, scaleTo = 0.985f)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShareThumb(accountId, s, Modifier.size(width = 64.dp, height = 42.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name ?: stringResource(R.string.shares_untitled), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        s.layout?.let { l -> layoutLabelRes(l)?.let { stringResource(it) } ?: l.replaceFirstChar { it.uppercase() } },
                        s.createdAt?.let { stringResource(R.string.shares_created, Format.relative(it)) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1,
                )
            }
            Switch(
                checked = s.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
            )
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!s.enabled) Pill(stringResource(R.string.shares_status_turned_off), Palette.TextSecondary, Palette.Surface3)
            else if (s.access == "public") Pill(stringResource(R.string.shares_access_public), Palette.Green, Palette.Green.copy(alpha = 0.16f))
            else Pill(stringResource(R.string.shares_invited_only), Palette.AccentBright, Palette.AccentContainer)
            if (s.commentingEnabled) Pill(stringResource(R.string.shares_pill_comments), Palette.TextSecondary, Palette.Surface3)
            if (s.downloadingEnabled) Pill(stringResource(R.string.shares_pill_download), Palette.TextSecondary, Palette.Surface3)
            if (s.enabled && !s.commentingEnabled && !s.downloadingEnabled) Pill(stringResource(R.string.shares_pill_view_only), Palette.TextSecondary, Palette.Surface3)
            s.expiration?.let { Pill(Format.remaining(it)?.let { left -> stringResource(R.string.shares_expires_in, left) } ?: stringResource(R.string.shares_status_expired), Palette.Amber, Palette.Amber.copy(alpha = 0.16f)) }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.Surface3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.lastViewedAt?.let { stringResource(R.string.shares_viewed, Format.relative(it)) } ?: stringResource(R.string.shares_not_viewed_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.lastViewedAt != null) Palette.Green else Palette.TextTertiary,
                )
            }
            Box {
                Icon(
                    Icons.Rounded.MoreHoriz, stringResource(R.string.shares_more), tint = Palette.TextTertiary,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).pressable({ menu = true }, scaleTo = 0.9f).padding(8.dp),
                )
                androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = dev.finalish.ui.theme.MenuShape) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.shares_send)) }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) }, onClick = { menu = false; onShare() })
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.shares_delete), color = Palette.Red) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Palette.Red) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.shares_edit), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface3).pressable(onOpen, scaleTo = 0.94f).padding(horizontal = 12.dp, vertical = 9.dp),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Text).pressable(onCopy, scaleTo = 0.94f).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ContentCopy, null, tint = Palette.Ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.shares_copy), style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
            }
        }
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = Palette.TextSecondary) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface3).pressable(onClick, scaleTo = 0.92f).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun SimpleTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.shares_back)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        actions()
    }
}

// ---- Collections -----------------------------------------------------------

/** Read-only list: its only events are binding and loading, so plain functions instead of an event type. */
class CollectionsViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<Remote<List<Collection>>>(Remote.loading())
    val state: StateFlow<Remote<List<Collection>>> = _state.asStateFlow()
    private var bound = false
    private var account = ""
    private var project = ""
    private var job: kotlinx.coroutines.Job? = null

    fun bind(a: String, p: String) {
        if (bound) return
        bound = true
        account = a
        project = p
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch { repo.collections(account, project).collect { _state.value = it } }
    }

    companion object {
        val Factory = viewModelFactory { initializer { CollectionsViewModel(application, container.repository) } }
    }
}

@Composable
fun CollectionsScreen(
    accountId: String,
    projectId: String,
    projectName: String,
    onBack: () -> Unit,
    onOpen: (Collection) -> Unit,
    vm: CollectionsViewModel = viewModel(factory = CollectionsViewModel.Factory),
) {
    LaunchedEffect(Unit) { vm.bind(accountId, projectId) }
    val remote by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Palette.Ink)) {
        SimpleTopBar(stringResource(R.string.shares_collections), projectName, onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                remote.isInitialLoading -> items(4) { Box(Modifier.fillMaxWidth().height(72.dp).shimmer(RoundedCornerShape(16.dp))) }
                remote.isEmptyError -> item { ErrorState(remote.error.orEmpty(), onRetry = vm::load) }
                remote.data.isNullOrEmpty() -> item {
                    EmptyState(Icons.Rounded.ViewCarousel, stringResource(R.string.shares_collections_empty_title), stringResource(R.string.shares_collections_empty_body))
                }
                else -> items(remote.data.orEmpty(), key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Palette.Surface2)
                            .pressable({ onOpen(c) }, enabled = c.rootFolderId != null, scaleTo = 0.98f)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ViewCarousel, null, tint = Palette.AccentBright)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name ?: stringResource(R.string.shares_untitled_template), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    if (c.aggregationMode == "dynamic") stringResource(R.string.shares_smart_collection) else stringResource(R.string.shares_collection),
                                    if (c.private) stringResource(R.string.shares_private) else null,
                                    Format.relative(c.updatedAt).ifBlank { null },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                            )
                        }
                        if (c.rootFolderId != null) Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
                    }
                }
            }
            if (!remote.data.isNullOrEmpty()) item(key = "collections-hint") {
                Text(
                    stringResource(R.string.shares_collections_hint),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                )
            }
        }
    }
}
