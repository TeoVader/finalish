package dev.finalish.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.model.Workspace
import dev.finalish.ui.components.PrimaryButton
import dev.finalish.ui.theme.Palette

/** Workspace target for a new project: an existing one, or a new one to create first. */
sealed interface WorkspaceTarget {
    data class Existing(val id: String) : WorkspaceTarget
    data class New(val name: String) : WorkspaceTarget
}

@Composable
fun NewProjectSheet(
    workspaces: List<Workspace>,
    preselected: String?,
    busy: Boolean,
    onCreate: (target: WorkspaceTarget, name: String, restricted: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Plan features the account lacks (PlanLimits): no restricted switch or new workspace on Free. */
    missing: Set<String> = emptySet(),
    /** Set when the plan's project limit is reached: explained, and nothing can be created. */
    limitNote: String? = null,
) {
    var name by remember { mutableStateOf("") }
    var restricted by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(preselected ?: workspaces.firstOrNull()?.id) }
    var newWorkspace by remember { mutableStateOf(workspaces.isEmpty()) }
    var workspaceName by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(

        modifier = Modifier.offset(x = dev.finalish.ui.components.LocalSideBarWidth.current / 2),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface1,
    ) {
        Column(
            Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_new_project), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel), color = Palette.TextSecondary) }
            }
            Field(name, { name = it }, stringResource(R.string.home_project_name), Modifier.focusRequester(focus))

            Text(stringResource(R.string.home_workspace_header), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(workspaces, key = { it.id }) { ws ->
                    Chip(ws.name, !newWorkspace && selected == ws.id) { newWorkspace = false; selected = ws.id }
                }
                if ("multiple_workspaces" !in missing || workspaces.isEmpty()) {
                    item(key = "new") { Chip(stringResource(R.string.home_new_workspace_chip), newWorkspace) { newWorkspace = true } }
                }
            }
            AnimatedVisibility(newWorkspace) {
                Field(workspaceName, { workspaceName = it }, stringResource(R.string.home_workspace_name))
            }

            if ("restricted_projects" !in missing) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_restricted), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.home_restricted_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                    )
                }
                Switch(
                    checked = restricted, onCheckedChange = { restricted = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
                )
            }

            limitNote?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = Palette.Amber,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Amber.copy(alpha = 0.12f)).padding(12.dp),
                )
            }
            val ready = limitNote == null && name.isNotBlank() && (if (newWorkspace) workspaceName.isNotBlank() else selected != null)
            PrimaryButton(
                if (busy) stringResource(R.string.home_creating) else stringResource(R.string.home_create_project),
                icon = Icons.Rounded.Add,
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = if (newWorkspace) WorkspaceTarget.New(workspaceName.trim()) else WorkspaceTarget.Existing(selected!!)
                    onCreate(target, name.trim(), restricted)
                },
            )
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            focusedContainerColor = Palette.Surface2,
            unfocusedContainerColor = Palette.Surface2,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
