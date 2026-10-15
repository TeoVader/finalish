package dev.finalish.ui.viewer

import android.app.Application
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Comment
import dev.finalish.core.util.Format
import dev.finalish.ui.messageFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the viewer's parts (comments, metadata, file actions) share with [ViewerViewModel]:
 * the asset it was opened on, its state, the player and the snackbar channel.
 */
internal class ViewerSession(
    val app: Application,
    val scope: CoroutineScope,
    val repo: FrameioRepository,
    val player: PlayerController,
) {
    var account = ""
    /** The asset the viewer was opened on (a version stack's id for a stack). */
    var boundId = ""
    var boundType = "file"
    val state = MutableStateFlow(ViewerState())
    val messages = messageFlow()

    fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    /** Frames per second: decoder info first, then the file's metadata, then 24. */
    fun fps(): Double {
        player.frameRate.takeIf { it > 1f }?.let { return it.toDouble() }
        val meta = state.value.file?.metadata?.firstOrNull { it.name.contains("frame rate", true) || it.name.equals("fps", true) }
        return meta?.doubleValue ?: meta?.textValue?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull() ?: 24.0
    }

    fun commentMs(c: Comment): Long? {
        val fps = fps()
        val frame = c.frame ?: c.timecode?.let { Format.timecodeToFrame(it, fps) } ?: return null
        return Format.framesToMs(frame, fps)
    }

    /** The asset as the user sees it in the folder (the stack for version stacks). */
    fun browsedAsset(): Asset? {
        val f = state.value.file ?: return null
        return if (boundType == "version_stack") f.copy(id = boundId, type = "version_stack") else f
    }
}
