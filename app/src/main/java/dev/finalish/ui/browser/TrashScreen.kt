package dev.finalish.ui.browser

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.finalish.core.data.FrameioRepository
import dev.finalish.ui.application
import dev.finalish.ui.components.rememberMessageSnackbar
import dev.finalish.ui.messageFlow
import dev.finalish.ui.report
import dev.finalish.ui.container
import dev.finalish.R
import dev.finalish.core.model.Asset
import dev.finalish.core.network.userMessage
import dev.finalish.core.util.Format
import dev.finalish.ui.components.EmptyState
import dev.finalish.ui.components.ErrorState
import dev.finalish.ui.components.KindPlaceholder
import dev.finalish.ui.components.pressable
import dev.finalish.ui.components.shimmer
import dev.finalish.ui.shares.SimpleTopBar
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    /** Null until the first load. */
    val items: List<Asset>? = null,
    val error: String? = null,
    /** Restore is an experimental endpoint; some accounts don't have it (404). */
    val restoreUnavailable: Boolean = false,
    val webUrl: String? = null,
)

sealed interface TrashEvent {
    data class Bind(val accountId: String, val projectId: String) : TrashEvent
    data object Load : TrashEvent
    data class Restore(val asset: Asset) : TrashEvent
}

class TrashViewModel(app: Application, private val repo: FrameioRepository) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()
    private val _messages = messageFlow()
    val messages = _messages.asSharedFlow()
    private var account = ""
    private var project = ""

    fun onEvent(e: TrashEvent) {
        when (e) {
            is TrashEvent.Bind -> bind(e.accountId, e.projectId)
            TrashEvent.Load -> load()
            is TrashEvent.Restore -> restore(e.asset)
        }
    }

    private fun bind(a: String, p: String) {
        if (a == account && p == project) return
        account = a; project = p
        load()
    }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(error = null) }
        runCatching { repo.project(account, project).viewUrl }.onSuccess { url -> _state.update { it.copy(webUrl = url) } }
        runCatching { repo.recentlyDeleted(account, project) }
            .onSuccess { list -> _state.update { it.copy(items = list) } }
            .onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
    }

    private fun restore(a: Asset) {
        val before = _state.value.items
        _state.update { s -> s.copy(items = s.items?.filterNot { it.id == a.id }) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val unavailable = { e: Throwable -> e is retrofit2.HttpException && e.code() == 404 }
            _messages.report(
                success = { app.getString(R.string.browser_trash_restored, a.name) },
                error = { if (unavailable(it)) app.getString(R.string.browser_trash_restore_unavailable) else it.userMessage() },
            ) { repo.restore(account, a) }.onFailure { e ->
                _state.update { s ->
                    s.copy(
                        items = s.items?.let { l -> dev.finalish.core.util.reinsert(l, before.orEmpty(), a) { x -> x.id } },
                        restoreUnavailable = s.restoreUnavailable || unavailable(e),
                    )
                }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { TrashViewModel(application, container.repository) } }
    }
}

/** Project "Recently deleted": restore files, folders and version stacks. */
@Composable
fun TrashScreen(accountId: String, projectId: String, projectName: String, onBack: () -> Unit, vm: TrashViewModel = viewModel(factory = TrashViewModel.Factory, key = "trash-$projectId")) {
    LaunchedEffect(accountId, projectId) { vm.onEvent(TrashEvent.Bind(accountId, projectId)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val items = state.items
    val error = state.error
    val unavailable = state.restoreUnavailable
    val webUrl = state.webUrl
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = rememberMessageSnackbar(vm.messages)

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(stringResource(R.string.browser_recently_deleted), projectName, onBack)
            val list0 = items
            val stage = when {
                list0 == null && error != null -> 0
                list0 == null -> 1
                list0.isEmpty() -> 2
                else -> 3
            }
            androidx.compose.animation.Crossfade(stage, animationSpec = dev.finalish.ui.theme.Motion.enter(), label = "trash") { st ->
                val list = list0.orEmpty()
                when (st) {
                    0 -> ErrorState(error.orEmpty(), onRetry = { vm.onEvent(TrashEvent.Load) })
                    1 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(5) { Box(Modifier.fillMaxWidth().height(64.dp).shimmer(RoundedCornerShape(14.dp))) }
                    }
                    2 -> EmptyState(Icons.Rounded.DeleteSweep, stringResource(R.string.browser_trash_empty_title), stringResource(R.string.browser_trash_empty_message))
                    else -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                        if (unavailable) item {
                            Column(
                                Modifier.fillMaxWidth().padding(4.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface2).padding(14.dp),
                            ) {
                                Text(stringResource(R.string.browser_trash_restore_web), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.browser_trash_restore_web_message),
                                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                                )
                                webUrl?.let { url ->
                                    Text(
                                        stringResource(R.string.browser_trash_open_web),
                                        style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright,
                                        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                                            .pressable({ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) })
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        items(list, key = { it.id }) { a ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KindPlaceholder(a.kind, Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            when { a.isFolder -> stringResource(R.string.browser_folder); a.isVersionStack -> stringResource(R.string.browser_version_stack); else -> Format.bytes(a.fileSize) },
                                            a.deletedAt?.let { stringResource(R.string.browser_trash_deleted_ago, Format.relative(it)) },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                                    )
                                }
                                if (!unavailable) Row(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Palette.AccentContainer)
                                        .pressable({ vm.onEvent(TrashEvent.Restore(a)) }, scaleTo = 0.92f)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.RestoreFromTrash, null, tint = Palette.AccentBright, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(stringResource(R.string.browser_trash_restore), style = MaterialTheme.typography.labelLarge, color = Palette.AccentBright)
                                }
                            }
                        }
                        item(key = "retention") {
                            Text(
                                stringResource(R.string.browser_trash_retention),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                            )
                        }
                    }
            
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
