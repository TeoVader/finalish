package dev.finalish.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object LoginRoute
@Serializable data object HomeRoute

@Serializable
data class FolderRoute(
    val projectId: String,
    val rootFolderId: String,
    val folderId: String,
    val title: String,
    val isProjectRoot: Boolean = false,
    /** Shown above a subfolder's title; looked up when empty. */
    val projectName: String = "",
    /** An item to scroll to and flash once loaded ("Show in project"). */
    val focusId: String? = null,
)

@Serializable
data class ViewerRoute(
    val assetId: String,
    val type: String = "file",
    val offline: Boolean = false,
    /** Optional siblings (file ids) for swiping between assets in a folder. */
    val folderId: String? = null,
)

@Serializable
data class CompareRoute(val leftId: String, val rightId: String)

@Serializable
data class SharesRoute(val projectId: String, val projectName: String)

@Serializable
data class MembersRoute(val scope: String, val id: String, val title: String)

@Serializable data object MetadataFieldsRoute
@Serializable data object LicensesRoute

@Serializable data object GroupsRoute

@Serializable data object NotificationsRoute

@Serializable
data class GroupRoute(val id: String, val name: String, val emoji: String, val memberCount: Int, val source: String)

@Serializable
data class TrashRoute(val projectId: String, val projectName: String)

@Serializable
data class FeedbackRoute(val kind: String = "Bug")

@Serializable
data class CollectionsRoute(val projectId: String, val projectName: String)
