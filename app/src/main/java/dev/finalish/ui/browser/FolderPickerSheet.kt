package dev.finalish.ui.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.finalish.FinalishApp
import dev.finalish.R
import dev.finalish.core.data.SortOrder
import dev.finalish.core.model.Asset
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette

private data class Crumb(val id: String, val name: String)

/**
 * Bottom sheet for choosing a destination folder inside a project
 * (used by move, copy and "share to Final(ish)" uploads).
 */
@Composable
fun FolderPickerSheet(
    accountId: String,
    rootFolderId: String,
    rootName: String,
    actionLabel: String,
    excludeIds: Set<String> = emptySet(),
    projectId: String? = null,
    onPick: (folderId: String, folderName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { FinalishApp.container(context).repository }
    val stack = remember { mutableStateListOf(Crumb(rootFolderId, rootName)) }
    var folders by remember { mutableStateOf<List<Asset>?>(null) }
    val current = stack.last()

    // Show the real project name for the root crumb instead of a generic label.
    LaunchedEffect(projectId) {
        if (projectId != null) runCatching { repo.project(accountId, projectId).name }
            .onSuccess { name -> if (stack.isNotEmpty()) stack[0] = stack[0].copy(name = name) }
    }

    LaunchedEffect(current.id) {
        folders = repo.cachedChildren(accountId, current.id, SortOrder.NameAsc)?.filter { it.isFolder }
        repo.children(accountId, current.id, SortOrder.NameAsc).collect { r ->
            r.data?.let { list -> folders = list.filter { it.isFolder && it.id !in excludeIds } }
            if (!r.loading && r.data == null) folders = emptyList()
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
                    Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stack.joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            androidx.activity.compose.BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
            AnimatedContent(
                targetState = Triple(current.id, stack.size, folders),
                // Into a subfolder slides forward, back up slides back.
                transitionSpec = { dev.finalish.ui.theme.sheetPage(forward = targetState.second >= initialState.second) },
                contentKey = { it.first },
                label = "picker",
            ) { (_, _, list) ->
                Box(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp)) {
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                        list.isEmpty() -> Text(
                            stringResource(R.string.browser_no_subfolders),
                            color = Palette.TextTertiary,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        else -> LazyColumn {
                            items(list, key = { it.id }) { f ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .pressable({ stack.add(Crumb(f.id, f.name)) }, scaleTo = 0.98f)
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface3),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Folder, null, tint = Palette.AccentBright, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                                    Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary)
                                }
                            }
                        }
                    }
                }
            }
            PrimaryButton(
                stringResource(R.string.browser_picker_action, actionLabel, current.name),
                onClick = { onPick(current.id, current.name) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}
