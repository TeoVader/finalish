package dev.finalish.ui.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.core.model.User
import dev.finalish.ui.theme.Palette

enum class CommentFilter(@androidx.annotation.StringRes val labelRes: Int) {
    All(dev.finalish.R.string.viewer_filter_all),
    Open(dev.finalish.R.string.viewer_filter_open),
    Completed(dev.finalish.R.string.viewer_filter_done),
}
enum class PanelTab(@androidx.annotation.StringRes val labelRes: Int) {
    Comments(dev.finalish.R.string.viewer_tab_comments),
    Info(dev.finalish.R.string.viewer_tab_info),
    Versions(dev.finalish.R.string.viewer_tab_versions),
}

@Immutable
data class PendingAttachment(val uri: String, val name: String, val size: Long, val mediaType: String)

@Immutable
data class ComposerState(
    val text: String = "",
    val attachTime: Boolean = true,
    val rangeStartMs: Long? = null,
    val drawing: Boolean = false,
    val shapes: List<Shape> = emptyList(),
    val tool: DrawTool = DrawTool.Pen,
    val color: Color = Palette.Annotation[0],
    val anchor: Offset? = null,
    val placingAnchor: Boolean = false,
    val sending: Boolean = false,
    val editing: Comment? = null,
    val attachments: List<PendingAttachment> = emptyList(),
    /** While editing: move the comment to the current playhead on save. */
    val retime: Boolean = false,
    /** Playhead captured when you started writing while the video kept playing. */
    val capturedMs: Long? = null,
    /** Bumped to put the cursor in the comment field (e.g. "Comment at 00:23"). */
    val focusRequest: Int = 0,
)

@Immutable
data class ViewerState(
    val versions: List<Asset> = emptyList(),
    val file: Asset? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val comments: List<Comment> = emptyList(),
    val commentsLoading: Boolean = true,
    val filter: CommentFilter = CommentFilter.All,
    val selectedCommentId: String? = null,
    val offlinePath: String? = null,
    val me: User? = null,
    val tab: PanelTab = PanelTab.Comments,
    val pdfPage: Int = 1,
    /** Frame.io is still transcoding: no playable link yet (polled until there is one). */
    val preparing: Boolean = false,
) {
    val kind: MediaKind get() = file?.kind ?: MediaKind.Other
    val visibleComments: List<Comment>
        get() = when (filter) {
            CommentFilter.All -> comments
            CommentFilter.Open -> comments.filter { !it.isCompleted }
            CommentFilter.Completed -> comments.filter { it.isCompleted }
        }
    val versionNumber: Int get() = versions.indexOfFirst { it.id == file?.id }.let { if (it < 0) 0 else it + 1 }
}
