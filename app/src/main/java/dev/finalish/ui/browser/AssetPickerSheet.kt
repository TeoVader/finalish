package dev.finalish.ui.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.data.SortOrder
import dev.finalish.core.model.Asset
import dev.finalish.core.util.Format
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

private data class PickCrumb(val id: String, val name: String)

/**
 * Pick files and folders of a project (browse folders, tick items in any of them).
 * Used to choose what goes into a share link.
 */
@Composable
fun AssetPickerSheet(
    accountId: String,
    projectId: String,
    actionLabel: (count: Int) -> String,
    onPick: (List<Asset>) -> Unit,
    onDismiss: () -> Unit,
    excludeIds: Set<String> = emptySet(),
    /** Start inside this folder (id to name) instead of the project root. */
    startFolder: Pair<String, String>? = null,
    /** Pick exactly one file: folders only open, and ticking replaces the choice. */
    single: Boolean = false,
    /** Which files to offer (folders always show, to browse into). */
    accept: (Asset) -> Boolean = { true },
) {
    val context = LocalContext.current
    val repo = remember { FinalishApp.container(context).repository }
    val stack = remember { mutableStateListOf<PickCrumb>() }
    val picked = remember { mutableStateMapOf<String, Asset>() }
    var items by remember { mutableStateOf<List<Asset>?>(null) }
    val current = stack.lastOrNull()

    LaunchedEffect(projectId) {
        if (startFolder != null) {
            stack.add(PickCrumb(startFolder.first, startFolder.second))
            return@LaunchedEffect
        }
        runCatching { repo.project(accountId, projectId) }
            .onSuccess { stack.add(PickCrumb(it.rootFolderId, it.name)) }
            .onFailure { items = emptyList() }
    }
    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        items = repo.cachedChildren(accountId, id, SortOrder.NameAsc)
        repo.children(accountId, id, SortOrder.NameAsc).collect { r ->
            r.data?.let { list -> items = list.filter { it.id !in excludeIds && (it.isFolder || accept(it)) } }
            if (!r.loading && r.data == null) items = emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(current?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            single -> picked.values.firstOrNull()?.name ?: stringResource(R.string.browser_pick_one_file)
                            picked.isEmpty() -> stringResource(R.string.browser_tick_files)
                            else -> pluralStringResource(R.plurals.browser_selected_count, picked.size, picked.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                    )
                }
            }
            androidx.activity.compose.BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
            AnimatedContent(
                targetState = Triple(current?.id, stack.size, items),
                transitionSpec = { dev.finalish.ui.theme.sheetPage(forward = targetState.second >= initialState.second) },
                contentKey = { it.first },
                label = "assetPicker",
            ) { (_, _, list) ->
                Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 460.dp)) {
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                        list.isEmpty() -> Text(stringResource(R.string.browser_nothing_here), color = Palette.TextTertiary, modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn {
                            items(list, key = { it.id }) { a ->
                                PickRow(
                                    a,
                                    selectable = !(single && a.isFolder),
                                    selected = a.id in picked,
                                    onToggle = if (single && a.isFolder) ({ stack.add(PickCrumb(a.id, a.name)) }) else ({
                                        if (a.id in picked) picked.remove(a.id) else {
                                            if (single) picked.clear()
                                            picked[a.id] = a
                                        }
                                    }),
                                    onOpen = if (a.isFolder) ({ stack.add(PickCrumb(a.id, a.name)) }) else null,
                                )
                            }
                        }
                    }
                }
            }
            PrimaryButton(
                actionLabel(picked.size),
                enabled = picked.isNotEmpty(),
                onClick = { onPick(picked.values.toList()) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PickRow(a: Asset, selected: Boolean, onToggle: () -> Unit, onOpen: (() -> Unit)?, selectable: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onOpen ?: onToggle, scaleTo = 0.98f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(if (selected) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Palette.Outline, CircleShape))
                .pressable(onToggle, scaleTo = 0.85f),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.browser_selected), tint = Palette.OnAccent, modifier = Modifier.size(16.dp))
        } else Spacer(Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(60.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(Palette.Surface3)) {
            AssetThumbnail(a, Modifier.fillMaxSize(), scrubbable = false)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (a.isFolder) stringResource(R.string.browser_folder) else Format.bytes(a.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
            )
        }
        if (onOpen != null) Icon(Icons.Rounded.ChevronRight, stringResource(R.string.browser_open_folder), tint = Palette.TextTertiary)
    }
}
