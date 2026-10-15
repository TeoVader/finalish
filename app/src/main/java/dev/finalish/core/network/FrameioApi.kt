package dev.finalish.core.network

import dev.finalish.core.model.FileUploadStatus
import dev.finalish.core.model.Account
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Collection
import dev.finalish.core.model.Comment
import dev.finalish.core.model.DataEnvelope
import dev.finalish.core.model.FileWithUploadUrls
import dev.finalish.core.model.PageEnvelope
import dev.finalish.core.model.Project
import dev.finalish.core.model.SearchHit
import dev.finalish.core.model.Share
import dev.finalish.core.model.User
import dev.finalish.core.model.Workspace
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Frame.io V4 REST API. Base URL: https://api.frame.io/v4/ */
interface FrameioApi {

    @GET("me")
    suspend fun me(): DataEnvelope<User>

    @GET("accounts")
    suspend fun accounts(
        @Query("page_size") pageSize: Int = 100,
    ): PageEnvelope<Account>

    @GET("accounts/{account}/workspaces")
    suspend fun workspaces(
        @Path("account") account: String,
        @Query("sort") sort: String = "name_asc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Workspace>

    @GET("accounts/{account}/workspaces/{workspace}/projects")
    suspend fun projects(
        @Path("account") account: String,
        @Path("workspace") workspace: String,
        @Query("sort") sort: String = "name_asc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Project>

    @POST("accounts/{account}/workspaces")
    suspend fun createWorkspace(
        @Path("account") account: String,
        @Body body: JsonObject,
    ): DataEnvelope<Workspace>

    @POST("accounts/{account}/workspaces/{workspace}/projects")
    suspend fun createProject(
        @Path("account") account: String,
        @Path("workspace") workspace: String,
        @Body body: JsonObject,
    ): DataEnvelope<Project>

    @PATCH("accounts/{account}/projects/{project}")
    suspend fun updateProject(
        @Path("account") account: String,
        @Path("project") project: String,
        @Body body: JsonObject,
    ): DataEnvelope<Project>

    @DELETE("accounts/{account}/projects/{project}")
    suspend fun deleteProject(
        @Path("account") account: String,
        @Path("project") project: String,
    ): Response<Unit>

    @GET("accounts/{account}/projects/{project}")
    suspend fun project(
        @Path("account") account: String,
        @Path("project") project: String,
    ): DataEnvelope<Project>

    @GET("accounts/{account}/folders/{folder}")
    suspend fun folder(
        @Path("account") account: String,
        @Path("folder") folder: String,
    ): DataEnvelope<Asset>

    @GET("accounts/{account}/folders/{folder}/children")
    suspend fun children(
        @Path("account") account: String,
        @Path("folder") folder: String,
        @Query("include") include: String = INCLUDE_BROWSE,
        @Query("sort") sort: String? = null,
        @Query("page_size") pageSize: Int = 60,
        @Query("after") after: String? = null,
    ): PageEnvelope<Asset>

    /** First few children + total count, for folder card previews. */
    @GET("accounts/{account}/folders/{folder}/children")
    suspend fun childrenPreview(
        @Path("account") account: String,
        @Path("folder") folder: String,
        @Query("include") include: String = "media_links.thumbnail,media_links.thumbnail_high_quality,media_links.high_quality",
        @Query("page_size") pageSize: Int = 8,
        @Query("include_total_count") totalCount: Boolean = true,
        @Query("sort") sort: String = "updated_at_desc",
    ): PageEnvelope<Asset>

    @GET("accounts/{account}/files/{file}")
    suspend fun file(
        @Path("account") account: String,
        @Path("file") file: String,
        @Query("include") include: String = INCLUDE_PLAYBACK,
    ): DataEnvelope<Asset>

    @GET("accounts/{account}/version_stacks/{stack}")
    suspend fun versionStack(
        @Path("account") account: String,
        @Path("stack") stack: String,
    ): DataEnvelope<Asset>

    @GET("accounts/{account}/version_stacks/{stack}/children")
    suspend fun versions(
        @Path("account") account: String,
        @Path("stack") stack: String,
        @Query("include") include: String = INCLUDE_BROWSE,
        @Query("page_size") pageSize: Int = 50,
        @Query("after") after: String? = null,
    ): PageEnvelope<Asset>

    /** Just the versions' ids, to count them for the card's "V3" badge. */
    @GET("accounts/{account}/version_stacks/{stack}/children")
    suspend fun versionIds(
        @Path("account") account: String,
        @Path("stack") stack: String,
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Asset>

    /** Whether Frame.io has finished receiving a file (used to follow remote imports). */
    @GET("accounts/{account}/files/{file}/status")
    suspend fun fileStatus(
        @Path("account") account: String,
        @Path("file") file: String,
    ): DataEnvelope<FileUploadStatus>

    @POST("accounts/{account}/folders/{folder}/version_stacks")
    suspend fun createVersionStack(
        @Path("account") account: String,
        @Path("folder") folder: String,
        @Body body: JsonObject,
    ): DataEnvelope<Asset>

    @POST("accounts/{account}/folders/{folder}/folders")
    suspend fun createFolder(
        @Path("account") account: String,
        @Path("folder") folder: String,
        @Body body: JsonObject,
    ): DataEnvelope<Asset>

    @PATCH("accounts/{account}/{kind}/{id}")
    suspend fun rename(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): DataEnvelope<Asset>

    @PATCH("accounts/{account}/{kind}/{id}/move")
    suspend fun move(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): DataEnvelope<Asset>

    @POST("accounts/{account}/files/{id}/copy")
    suspend fun copyFile(
        @Path("account") account: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @DELETE("accounts/{account}/{kind}/{id}")
    suspend fun delete(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Path("id") id: String,
    ): Response<Unit>

    @POST("accounts/{account}/folders/{folder}/files/local_upload")
    suspend fun createLocalUpload(
        @Path("account") account: String,
        @Path("folder") folder: String,
        @Body body: JsonObject,
    ): DataEnvelope<FileWithUploadUrls>

    // ---- Comments ---------------------------------------------------------

    /** Account audit log. Used to name the authors of comments Frame.io returns without an owner. */
    @retrofit2.http.Headers("api-version: 4.0")
    @GET("accounts/{account}/audit_logs")
    suspend fun auditLogs(
        @Path("account") account: String,
        @Query(value = "filters[event_type]", encoded = true) eventType: String? = null,
        @Query(value = "filters[resource_type]", encoded = true) resourceType: String? = null,
        @Query(value = "filters[from_date]", encoded = true) fromDate: String? = null,
        @Query(value = "filters[to_date]", encoded = true) toDate: String? = null,
        @Query("include") include: String = "user",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<dev.finalish.core.model.AuditLog>

    @GET("accounts/{account}/files/{file}/comments")
    suspend fun comments(
        @Path("account") account: String,
        @Path("file") file: String,
        @Query("include") include: String = "owner,replies",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Comment>

    @POST("accounts/{account}/files/{file}/comments")
    suspend fun createComment(
        @Path("account") account: String,
        @Path("file") file: String,
        @Body body: JsonObject,
    ): DataEnvelope<Comment>

    @PATCH("accounts/{account}/comments/{comment}")
    suspend fun updateComment(
        @Path("account") account: String,
        @Path("comment") comment: String,
        @Body body: JsonObject,
    ): DataEnvelope<Comment>

    @DELETE("accounts/{account}/comments/{comment}")
    suspend fun deleteComment(
        @Path("account") account: String,
        @Path("comment") comment: String,
    ): Response<Unit>

    // ---- Metadata ---------------------------------------------------------

    /** All fields, including empty ones (file includes omit them). */
    @GET("accounts/{account}/files/{file}/metadata")
    suspend fun metadata(
        @Path("account") account: String,
        @Path("file") file: String,
        @Query("show_null") showNull: Boolean = true,
    ): DataEnvelope<dev.finalish.core.model.FileMetadata>

    @PATCH("accounts/{account}/projects/{project}/metadata/values")
    suspend fun updateMetadata(
        @Path("account") account: String,
        @Path("project") project: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @GET("accounts/{account}/metadata/field_definitions")
    suspend fun fieldDefinitions(
        @Path("account") account: String,
        @Query("include") include: String = "creator",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<dev.finalish.core.model.FieldDefinition>

    @POST("accounts/{account}/metadata/field_definitions")
    suspend fun createFieldDefinition(
        @Path("account") account: String,
        @Body body: JsonObject,
    ): DataEnvelope<dev.finalish.core.model.FieldDefinition>

    @PATCH("accounts/{account}/metadata/field_definitions/{field}")
    suspend fun updateFieldDefinition(
        @Path("account") account: String,
        @Path("field") field: String,
        @Body body: JsonObject,
    ): DataEnvelope<dev.finalish.core.model.FieldDefinition>

    @DELETE("accounts/{account}/metadata/field_definitions/{field}")
    suspend fun deleteFieldDefinition(
        @Path("account") account: String,
        @Path("field") field: String,
    ): Response<Unit>

    // ---- Shares & collections --------------------------------------------

    @GET("accounts/{account}/projects/{project}/shares")
    suspend fun shares(
        @Path("account") account: String,
        @Path("project") project: String,
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Share>

    @POST("accounts/{account}/projects/{project}/shares")
    suspend fun createShare(
        @Path("account") account: String,
        @Path("project") project: String,
        @Body body: JsonObject,
    ): DataEnvelope<Share>

    @GET("accounts/{account}/shares/{share}")
    suspend fun share(
        @Path("account") account: String,
        @Path("share") share: String,
    ): DataEnvelope<Share>

    @GET("accounts/{account}/shares/{share}/assets")
    suspend fun shareAssets(
        @Path("account") account: String,
        @Path("share") share: String,
        @Query("include") include: String = "media_links.thumbnail",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Asset>

    @DELETE("accounts/{account}/shares/{share}/assets/{asset}")
    suspend fun removeShareAsset(
        @Path("account") account: String,
        @Path("share") share: String,
        @Path("asset") asset: String,
    ): Response<Unit>

    @POST("accounts/{account}/shares/{share}/assets")
    suspend fun addShareAsset(
        @Path("account") account: String,
        @Path("share") share: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @PATCH("accounts/{account}/shares/{share}")
    suspend fun updateShare(
        @Path("account") account: String,
        @Path("share") share: String,
        @Body body: JsonObject,
    ): DataEnvelope<Share>

    /** Experimental API: the same update, plus fields only it accepts (e.g. `watermark_template_id`). */
    @Headers(EXPERIMENTAL)
    @PATCH("accounts/{account}/shares/{share}")
    suspend fun updateShareExperimental(
        @Path("account") account: String,
        @Path("share") share: String,
        @Body body: JsonObject,
    ): DataEnvelope<Share>

    /** Session watermark templates of the account (experimental API). */
    @Headers(EXPERIMENTAL)
    @GET("accounts/{account}/session_watermark_templates")
    suspend fun watermarkTemplates(
        @Path("account") account: String,
        @Query("page_size") pageSize: Int = 100,
        @Query("sort") sort: String = "name_asc",
    ): PageEnvelope<dev.finalish.core.model.WatermarkTemplate>

    @DELETE("accounts/{account}/shares/{share}")
    suspend fun deleteShare(
        @Path("account") account: String,
        @Path("share") share: String,
    ): Response<Unit>

    @GET("accounts/{account}/projects/{project}/collections")
    suspend fun collections(
        @Path("account") account: String,
        @Path("project") project: String,
        @Query("page_size") pageSize: Int = 100,
    ): PageEnvelope<Collection>

    // ---- Search -----------------------------------------------------------

    // ---- Invited projects, members ------------------------------------------

    @GET("accounts/{account}/invited_projects")
    suspend fun invitedProjects(
        @Path("account") account: String,
        @Query("sort") sort: String = "name_asc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Project>

    @GET("accounts/{account}/users")
    @Headers(EXPERIMENTAL)
    suspend fun accountUsers(
        @Path("account") account: String,
        @Query("sort") sort: String = "name_asc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<dev.finalish.core.model.UserRole>

    @GET("accounts/{account}/{scope}/{id}/users")
    suspend fun members(
        @Path("account") account: String,
        @Path("scope") scope: String,
        @Path("id") id: String,
        /** Folders reject `sort` (422 "Unexpected field"); pass null there. */
        @Query("sort") sort: String? = "name_asc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<dev.finalish.core.model.UserRole>

    @PATCH("accounts/{account}/{scope}/{id}/users/{user}")
    suspend fun setMemberRole(
        @Path("account") account: String,
        @Path("scope") scope: String,
        @Path("id") id: String,
        @Path("user") user: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @DELETE("accounts/{account}/{scope}/{id}/users/{user}")
    suspend fun removeMember(
        @Path("account") account: String,
        @Path("scope") scope: String,
        @Path("id") id: String,
        @Path("user") user: String,
    ): Response<Unit>

    // ---- User groups ---------------------------------------------------------

    @GET("accounts/{account}/groups")
    suspend fun groups(
        @Path("account") account: String,
        @Query("sort") sort: String = "name_asc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<dev.finalish.core.model.UserGroup>

    @POST("accounts/{account}/groups")
    suspend fun createGroup(
        @Path("account") account: String,
        @Body body: JsonObject,
    ): DataEnvelope<dev.finalish.core.model.UserGroup>

    @PATCH("accounts/{account}/groups/{group}")
    suspend fun updateGroup(
        @Path("account") account: String,
        @Path("group") group: String,
        @Body body: JsonObject,
    ): DataEnvelope<dev.finalish.core.model.UserGroup>

    @Headers(EXPERIMENTAL)
    @DELETE("accounts/{account}/groups/{group}")
    suspend fun deleteGroup(
        @Path("account") account: String,
        @Path("group") group: String,
    ): Response<Unit>

    @Headers(EXPERIMENTAL)
    @GET("accounts/{account}/groups/{group}/users")
    suspend fun groupUsers(
        @Path("account") account: String,
        @Path("group") group: String,
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<User>

    @Headers(EXPERIMENTAL)
    @POST("accounts/{account}/groups/{group}/users")
    suspend fun addGroupUsers(
        @Path("account") account: String,
        @Path("group") group: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @Headers(EXPERIMENTAL)
    @DELETE("accounts/{account}/groups/{group}/users/{user}")
    suspend fun removeGroupUser(
        @Path("account") account: String,
        @Path("group") group: String,
        @Path("user") user: String,
    ): Response<Unit>

    /** Groups with a role on a project or workspace; [scope] is "projects" or "workspaces". */
    @Headers(EXPERIMENTAL)
    @GET("accounts/{account}/{scope}/{id}/groups")
    suspend fun scopeGroups(
        @Path("account") account: String,
        @Path("scope") scope: String,
        @Path("id") id: String,
        @Query("page_size") pageSize: Int = 100,
    ): PageEnvelope<dev.finalish.core.model.GroupRole>

    @Headers(EXPERIMENTAL)
    @PATCH("accounts/{account}/{scope}/{id}/groups/{group}")
    suspend fun setGroupRole(
        @Path("account") account: String,
        @Path("scope") scope: String,
        @Path("id") id: String,
        @Path("group") group: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @Headers(EXPERIMENTAL)
    @DELETE("accounts/{account}/{scope}/{id}/groups/{group}")
    suspend fun removeGroupRole(
        @Path("account") account: String,
        @Path("scope") scope: String,
        @Path("id") id: String,
        @Path("group") group: String,
    ): Response<Unit>

    @PATCH("accounts/{account}/workspaces/{workspace}")
    suspend fun updateWorkspace(
        @Path("account") account: String,
        @Path("workspace") workspace: String,
        @Body body: JsonObject,
    ): DataEnvelope<Workspace>

    @DELETE("accounts/{account}/workspaces/{workspace}")
    suspend fun deleteWorkspace(
        @Path("account") account: String,
        @Path("workspace") workspace: String,
    ): Response<Unit>

    // ---- Comment attachments --------------------------------------------------

    @POST("accounts/{account}/comments/{comment}/attachments")
    suspend fun createAttachment(
        @Path("account") account: String,
        @Path("comment") comment: String,
        @Body body: JsonObject,
    ): DataEnvelope<dev.finalish.core.model.CommentAttachment>

    @DELETE("accounts/{account}/comments/{comment}/attachments/{attachment}")
    suspend fun deleteAttachment(
        @Path("account") account: String,
        @Path("comment") comment: String,
        @Path("attachment") attachment: String,
    ): Response<Unit>

    // ---- Share reviewers & activity ------------------------------------------

    @GET("accounts/{account}/shares/{share}/reviewers")
    suspend fun shareReviewers(
        @Path("account") account: String,
        @Path("share") share: String,
        @Query("page_size") pageSize: Int = 100,
    ): PageEnvelope<User>

    @POST("accounts/{account}/shares/{share}/reviewers")
    suspend fun addShareReviewers(
        @Path("account") account: String,
        @Path("share") share: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @retrofit2.http.HTTP(method = "DELETE", path = "accounts/{account}/shares/{share}/reviewers", hasBody = true)
    suspend fun removeShareReviewers(
        @Path("account") account: String,
        @Path("share") share: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @GET("accounts/{account}/shares/{share}/activities")
    suspend fun shareActivities(
        @Path("account") account: String,
        @Path("share") share: String,
        @Query("include") include: String = "user",
        @Query("page_size") pageSize: Int = 50,
    ): PageEnvelope<dev.finalish.core.model.ShareActivity>

    // ---- Trash, imports, copies, versions ---------------------------------------

    @GET("accounts/{account}/projects/{project}/recently_deleted")
    suspend fun recentlyDeleted(
        @Path("account") account: String,
        @Path("project") project: String,
        @Query("sort") sort: String = "deleted_at_desc",
        @Query("page_size") pageSize: Int = 100,
        @Query("after") after: String? = null,
    ): PageEnvelope<Asset>

    @PATCH("accounts/{account}/{kind}/{id}/restore")
    @Headers(EXPERIMENTAL)
    suspend fun restore(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Path("id") id: String,
    ): Response<Unit>

    @POST("accounts/{account}/folders/{folder}/files/remote_upload")
    suspend fun remoteUpload(
        @Path("account") account: String,
        @Path("folder") folder: String,
        @Body body: JsonObject,
    ): DataEnvelope<Asset>

    @POST("accounts/{account}/{kind}/{id}/copy")
    suspend fun copyAsset(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @PATCH("accounts/{account}/{kind}/move")
    suspend fun bulkMove(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @POST("accounts/{account}/{kind}/copy")
    suspend fun bulkCopy(
        @Path("account") account: String,
        @Path("kind") kind: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @PATCH("accounts/{account}/version_stacks/{stack}/versions")
    @Headers(EXPERIMENTAL)
    suspend fun reorderVersion(
        @Path("account") account: String,
        @Path("stack") stack: String,
        @Body body: JsonObject,
    ): PageEnvelope<Asset>

    @POST("accounts/{account}/search")
    suspend fun searchPage(
        @Path("account") account: String,
        @Body body: JsonObject,
        @Query("after") after: String?,
        @Query("page_size") pageSize: Int = 40,
    ): PageEnvelope<SearchHit>

    companion object {
        /** Endpoints Frame.io still marks experimental reject requests without this header (404 "no route"). */
        const val EXPERIMENTAL = "api-version: experimental"

        const val BASE_URL = "https://api.frame.io/v4/"

        const val INCLUDE_BROWSE =
            "media_links.thumbnail,media_links.thumbnail_high_quality,media_links.efficient,media_links.scrub_sheet,metadata,creator"
        const val INCLUDE_PLAYBACK =
            "media_links.thumbnail_high_quality,media_links.high_quality,media_links.efficient," +
                "media_links.video_h264_180,media_links.scrub_sheet,media_links.original,metadata,creator"
        const val INCLUDE_PLAYBACK_NO_ORIGINAL =
            "media_links.thumbnail_high_quality,media_links.high_quality,media_links.efficient," +
                "media_links.video_h264_180,media_links.scrub_sheet,metadata,creator"

        fun kindPath(type: String): String = when (type) {
            "folder" -> "folders"
            "version_stack" -> "version_stacks"
            else -> "files"
        }
    }
}
