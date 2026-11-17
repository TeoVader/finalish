package dev.finalish.ui.shares

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.finalish.R
import dev.finalish.core.model.WatermarkBlock
import dev.finalish.core.model.WatermarkDataPoint
import dev.finalish.core.model.WatermarkTemplate
import dev.finalish.core.network.userMessage
import dev.finalish.ui.components.pressable
import dev.finalish.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * What the editor changes: the template's first block. Any other blocks are kept as they are.
 * [position], [fontSize] and [alpha] are null when the template doesn't set them; they're only
 * written once the user picks one, so a template saved unchanged is sent back as it was.
 */
internal data class WatermarkDraft(
    val name: String = "",
    /** Data point types in the order they're drawn. */
    val lines: List<String> = listOf("email", "timestamp"),
    val customText: String = "",
    val position: String? = "bottom_right",
    val fontSize: String? = "medium",
    val alpha: Double? = 0.6,
) {
    val valid: Boolean get() = name.isNotBlank() && lines.isNotEmpty() && ("custom_text" !in lines || customText.isNotBlank())

    /** [base] with this draft as its first block; the first block's other settings (color, rotation…) stay. */
    fun applyTo(base: WatermarkTemplate?): WatermarkTemplate {
        val first = base?.blocks?.firstOrNull() ?: WatermarkBlock()
        val unchangedLines = base != null && of(base).let { it.lines == lines && it.customText.trim() == customText.trim() }
        val movedToSlot = position != null && position != first.position
        val block = first.copy(
            position = position ?: first.position,
            // A named slot replaces free-form coordinates, which would otherwise win.
            positionX = if (movedToSlot) null else first.positionX,
            positionY = if (movedToSlot) null else first.positionY,
            positionReferencePoint = if (movedToSlot) null else first.positionReferencePoint,
            fontSize = fontSize ?: first.fontSize,
            alpha = alpha ?: first.alpha,
            dataPoints = if (unchangedLines) first.dataPoints else lines.mapIndexed { i, type ->
                WatermarkDataPoint(type, value = customText.trim().takeIf { type == "custom_text" }, order = (i + 1).toDouble())
            },
        )
        return WatermarkTemplate(id = base?.id.orEmpty(), name = name.trim(), blocks = listOf(block) + base?.blocks.orEmpty().drop(1))
    }

    companion object {
        fun of(t: WatermarkTemplate): WatermarkDraft {
            val b = t.blocks.firstOrNull() ?: return WatermarkDraft(name = t.name, position = null, fontSize = null, alpha = null)
            val points = b.dataPoints.sortedBy { it.order ?: 0.0 }
            return WatermarkDraft(
                name = t.name,
                lines = points.map { it.type }.filter { it in LINE_TYPES }.distinct(),
                customText = points.firstOrNull { it.type == "custom_text" }?.value.orEmpty(),
                position = b.position,
                fontSize = b.fontSize,
                alpha = b.alpha,
            )
        }

        val LINE_TYPES = listOf("email", "username", "timestamp", "ip_address", "user_input", "custom_text")
        val POSITIONS = listOf(
            "top_left", "top_center", "top_right",
            "middle_left", "middle_center", "middle_right",
            "bottom_left", "bottom_center", "bottom_right",
        )
        val SIZES = listOf("small", "medium", "large", "huge")
    }
}

/** Create a session watermark template ([template] null) or edit one; [onSaved] gets what the server kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatermarkTemplateEditorSheet(
    accountId: String,
    template: WatermarkTemplate?,
    onSaved: (WatermarkTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { dev.finalish.FinalishApp.container(context).repository }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(template?.let(WatermarkDraft::of) ?: WatermarkDraft()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface1) {
        WatermarkTemplateEditorContent(
            draft = draft,
            isNew = template == null,
            otherBlocks = (template?.blocks?.size ?: 0) - 1,
            saving = saving,
            error = error,
            onChange = { draft = it; error = null },
            onSave = {
                saving = true
                scope.launch {
                    runCatching { repo.saveWatermarkTemplate(accountId, draft.applyTo(template)) }
                        .onSuccess { onSaved(it) }
                        .onFailure { error = it.userMessage(context) }
                    saving = false
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatermarkTemplateEditorContent(
    draft: WatermarkDraft,
    isNew: Boolean,
    otherBlocks: Int,
    saving: Boolean,
    error: String?,
    onChange: (WatermarkDraft) -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(if (isNew) R.string.shares_new_template else R.string.shares_edit_template), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            draft.name, { onChange(draft.copy(name = it)) },
            label = { Text(stringResource(R.string.shares_template_name)) },
            singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        )

        Section(stringResource(R.string.shares_template_lines)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WatermarkDraft.LINE_TYPES.forEach { type ->
                    val on = type in draft.lines
                    Chip(WatermarkDataPoint(type).label(context), on) { onChange(draft.copy(lines = if (on) draft.lines - type else draft.lines + type)) }
                }
            }
            if ("custom_text" in draft.lines) OutlinedTextField(
                draft.customText, { onChange(draft.copy(customText = it)) },
                label = { Text(stringResource(R.string.shares_template_custom_text)) },
                singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Section(stringResource(R.string.shares_template_position), Modifier.width(132.dp)) {
                PositionGrid(draft.position) { onChange(draft.copy(position = it)) }
            }
            Section(stringResource(R.string.shares_template_size), Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatermarkDraft.SIZES.forEach { s -> Chip(sizeLabel(s), draft.fontSize == s) { onChange(draft.copy(fontSize = s)) } }
                }
            }
        }

        // Not set by the template: the slider sits at 100% and nothing is sent until it moves.
        Section(stringResource(R.string.shares_template_opacity, ((draft.alpha ?: 1.0) * 100).toInt())) {
            Slider(
                (draft.alpha ?: 1.0).toFloat(), { onChange(draft.copy(alpha = (Math.round(it * 20) / 20.0).coerceIn(0.1, 1.0))) },
                valueRange = 0.1f..1f,
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent),
            )
        }

        if (otherBlocks > 0) Text(
            pluralStringResource(R.plurals.shares_template_other_blocks, otherBlocks, otherBlocks),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Red) }
        Button(
            onSave, enabled = draft.valid && !saving, modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.OnAccent, strokeWidth = 2.dp)
            else Text(stringResource(R.string.shares_template_save))
        }
    }
}

@Composable
private fun sizeLabel(size: String): String = stringResource(
    when (size) {
        "small" -> R.string.shares_template_size_small
        "large" -> R.string.shares_template_size_large
        "huge" -> R.string.shares_template_size_huge
        else -> R.string.shares_template_size_medium
    },
)

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge,
        color = if (on) Palette.OnAccent else Palette.TextSecondary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Palette.Surface3)
            .semantics { selected = on }
            .pressable(onClick, scaleTo = 0.94f).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** The frame as a 3×3 grid: tap where the text goes. */
@Composable
private fun PositionGrid(selected: String?, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(Palette.Surface3).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WatermarkDraft.POSITIONS.chunked(3).forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { p ->
                    val on = p == selected
                    val label = p.replace('_', ' ')
                    Box(
                        Modifier.weight(1f).fillMaxWidth().aspectRatio(1.6f, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) Palette.Accent else Palette.Surface2)
                            .border(1.dp, if (on) Palette.AccentBright else Palette.Hairline, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = label; this.selected = on }
                            .pressable({ onPick(p) }, scaleTo = 0.9f),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
