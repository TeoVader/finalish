package dev.finalish.core.data

import dev.finalish.core.util.runCatchingCancellable
import dev.finalish.core.model.Account
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Collection
import dev.finalish.core.model.Comment
import dev.finalish.core.model.MediaKind
import dev.finalish.core.model.Project
import dev.finalish.core.model.SearchHit
import dev.finalish.core.model.Share
import dev.finalish.core.model.User
import dev.finalish.core.model.Workspace
import dev.finalish.core.network.AppJson
import dev.finalish.core.network.FrameioApi
import dev.finalish.core.network.afterCursor
import dev.finalish.core.network.allPages
import dev.finalish.core.network.userMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class WorkspaceProjects(val workspace: Workspace, val projects: List<Project>)

/** Pseudo-workspace grouping projects shared with the user from outside their workspaces. */
const val INVITED_WORKSPACE = "invited"

/** A workspace's name to show: the projects others shared get theirs in the app's language. */
fun Workspace.displayName(context: android.content.Context): String =
    if (id == INVITED_WORKSPACE) context.getString(dev.finalish.R.string.core_workspace_shared_with_you) else name

class FrameioRepository(
    private val api: FrameioApi,
    private val cache: JsonCache,
) {

    // ---- Generic stale-while-revalidate -----------------------------------

    private fun <T> swr(
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend (partial: suspend (T) -> Unit) -> T,
    ): Flow<Remote<T>> = flow {
        val cached = cache.get(key, serializer)
        emit(Remote.loading(cached))
        try {
            val fresh = fetch { partial -> emit(Remote.loading(partial)) }
            cache.put(key, serializer, fresh)
            emit(Remote.ready(fresh))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emit(Remote(data = cached, loading = false, error = t.userMessage()))
        }
    }

    // ---- Accounts / workspaces / projects -----------------------------------

    suspend fun me(): User {
        cache.peek<User>("me")?.let { return it }
        return runCatchingCancellable { api.me().data.also { cache.put("me", User.serializer(), it) } }
            .getOrElse { cache.get("me", User.serializer()) ?: throw it }
    }

    suspend fun accounts(): List<Account> = runCatchingCancellable {
        api.accounts().data.also { list ->
            cache.put("accounts", ListSerializer(Account.serializer()), list)
            list.forEach { dev.finalish.core.network.PlanLimits.seed(it.id, it.storageLimit) }
        }
    }.getOrElse { cache.get("accounts", ListSerializer(Account.serializer())) ?: throw it }

    fun workspacesWithProjects(account: String): Flow<Remote<List<WorkspaceProjects>>> =
        swr("ws:$account", ListSerializer(WorkspaceProjects.serializer())) { _ ->
            val previous = cache.get("ws:$account", ListSerializer(WorkspaceProjects.serializer())).orEmpty()
            val workspaces = allPages(first = { api.workspaces(account) }, next = { api.workspaces(account, after = it) })
            val gate = Semaphore(4)
            coroutineScope {
                workspaces.map { ws ->
                    async {
                        gate.withPermit {
                            // One workspace failing keeps its last known projects instead of looking empty.
                            val projects = try {
                                allPages(first = { api.projects(account, ws.id) }, next = { api.projects(account, ws.id, after = it) })
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                previous.firstOrNull { it.workspace.id == ws.id }?.projects.orEmpty()
                            }
                            WorkspaceProjects(ws, projects)
                        }
                    }
                }.awaitAll()
            }.let { groups ->
                // Projects you were invited to without workspace access.
                val known = groups.flatMap { g -> g.projects.map { it.id } }.toSet()
                // A failed call keeps the last known invited projects instead of emptying the group.
                val invited = try {
                    allPages(first = { api.invitedProjects(account) }, next = { api.invitedProjects(account, after = it) })
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    previous.firstOrNull { it.workspace.id == INVITED_WORKSPACE }?.projects.orEmpty()
                }.filter { it.id !in known }
                if (invited.isEmpty()) groups
                else groups + WorkspaceProjects(Workspace(INVITED_WORKSPACE, account, "Shared with you"), invited)
            }
        }

    suspend fun project(account: String, projectId: String): Project = api.project(account, projectId).data

    /** Project names from the last projects load, without a network call (Notifications). */
    suspend fun cachedProjectNames(account: String): Map<String, String> =
        cache.get("ws:$account", ListSerializer(WorkspaceProjects.serializer())).orEmpty()
            .flatMap { it.projects }.associate { it.id to it.name }

    suspend fun createWorkspace(account: String, name: String): Workspace =
        api.createWorkspace(account, body { putJsonObject("data") { put("name", name) } }).data
            .also { cache.invalidate("ws:$account") }

    suspend fun createProject(account: String, workspaceId: String, name: String, restricted: Boolean): Project =
        api.createProject(account, workspaceId, body {
            putJsonObject("data") {
                put("name", name)
                put("restricted", restricted)
            }
        }).data.also { cache.invalidate("ws:$account") }

    suspend fun updateProject(account: String, projectId: String, fields: Map<String, JsonElement>): Project =
        api.updateProject(account, projectId, body { put("data", JsonObject(fields)) }).data
            .also { cache.invalidate("ws:$account") }

    suspend fun deleteProject(account: String, projectId: String) {
        val r = api.deleteProject(account, projectId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        cache.invalidate("ws:$account")
    }

    // ---- Folders / files ---------------------------------------------------

    fun children(account: String, folderId: String, sort: SortOrder): Flow<Remote<List<Asset>>> =
        swr("children:$account:$folderId:${sort.api}", ListSerializer(Asset.serializer())) { partial ->
            allPages(
                first = { api.children(account, folderId, sort = sort.api) },
                next = { api.children(account, folderId, sort = sort.api, after = it) },
                partial = partial,
            )
        }

    /**
     * Every file under a folder, subfolders included ("Flatten folders"). Breadth-first and
     * bounded (40 folders, 500 files) so a huge project can't stall the screen.
     */
    suspend fun descendantFiles(account: String, folderId: String, sort: SortOrder): List<Asset> {
        val out = mutableListOf<Asset>()
        val queue = ArrayDeque(listOf(folderId))
        var visited = 0
        while (queue.isNotEmpty() && visited < 40 && out.size < 500) {
            val id = queue.removeFirst()
            visited++
            val items = allPages(
                first = { api.children(account, id, sort = sort.api) },
                next = { api.children(account, id, sort = sort.api, after = it) },
                partial = {},
                limit = 500,
            )
            items.forEach { if (it.isFolder) queue.add(it.id) else out.add(it) }
        }
        return out.take(500)
    }

    /** The asset a card was showing when it was opened, until the viewer has loaded its own copy. */
    private val stashed = android.util.LruCache<String, Asset>(32)
    fun stash(a: Asset) { stashed.put(a.id, a) }
    fun stashed(id: String): Asset? = stashed.get(id)

    fun cachedChildren(account: String, folderId: String, sort: SortOrder): List<Asset>? =
        cache.peek("children:$account:$folderId:${sort.api}")

    fun invalidateFolder(account: String, folderId: String) {
        SortOrder.entries.forEach { cache.invalidate("children:$account:$folderId:${it.api}") }
        // Its card count and mosaic too (keyed on updated_at, which Frame.io doesn't bump).
        stalePreviews.add(folderId)
    }

    /** Folders whose cached preview (count, thumbnails) is out of date; refetched on next ask. */
    private val stalePreviews = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun folder(account: String, id: String): Asset = api.folder(account, id).data

    private val previewGate = Semaphore(3)

    private val thumbed = android.util.LruCache<String, Asset>(200)

    /**
     * Search results come without media links: fetch the file (or a stack's current
     * version) once for its thumbnail. Cached per change, three requests at a time.
     */
    suspend fun withThumbnail(account: String, asset: Asset): Asset? {
        if (asset.isFolder || asset.thumbnailUrl != null) return asset
        val key = "${asset.id}:${asset.updatedAt}"
        thumbed.get(key)?.let { return it }
        return previewGate.withPermit {
            runCatchingCancellable {
                if (asset.isVersionStack) {
                    val head = api.versions(account, asset.id, pageSize = 1).data.firstOrNull() ?: return@runCatchingCancellable null
                    asset.copy(headVersion = head)
                } else {
                    api.file(account, asset.id, include = FrameioApi.INCLUDE_BROWSE).data
                }
            }.getOrNull()?.also { thumbed.put(key, it) }
        }
    }

    /**
     * How many versions a stack has (the API doesn't say on the stack itself). Cached per
     * stack and last change, so a new version refreshes it.
     */
    suspend fun versionCount(account: String, stack: Asset): Int? {
        val key = "vcount:$account:${stack.id}:${stack.updatedAt}"
        cache.get(key, Int.serializer())?.let { return it }
        return previewGate.withPermit {
            runCatchingCancellable {
                allPages(first = { api.versionIds(account, stack.id) }, next = { api.versionIds(account, stack.id, after = it) }).size
            }.getOrNull()
                ?.also { cache.put(key, Int.serializer(), it) }
        }
    }

    /**
     * Mosaic thumbnails + item count for a folder card. Frame.io doesn't bump a
     * folder's `updated_at` when its children change, so callers pass [force]
     * after copy/move/refresh to skip the cached value.
     */
    suspend fun folderPreview(account: String, folder: Asset, force: Boolean = false): dev.finalish.core.model.FolderPreview? {
        val key = "preview2:$account:${folder.id}:${folder.updatedAt}"
        val stale = stalePreviews.remove(folder.id)
        if (!force && !stale) cache.get(key, dev.finalish.core.model.FolderPreview.serializer())?.let { return it }
        return previewGate.withPermit {
            runCatchingCancellable {
                val page = api.childrenPreview(account, folder.id)
                val thumbs = page.data
                    .sortedByDescending { it.id == folder.coverFileId }
                    .mapNotNull { child -> if (child.isFolder) null else child.thumbnailCandidates.lastOrNull { MediaKind.Image == child.kind } ?: child.thumbnailUrl }
                    .take(3)
                val preview = dev.finalish.core.model.FolderPreview(thumbs, page.totalCount ?: page.data.size.takeIf { page.links?.next == null })
                cache.put(key, dev.finalish.core.model.FolderPreview.serializer(), preview)
                preview
            }.getOrNull()
        }
    }

    /**
     * Fallback cover for a project without an image set on Frame.io: thumbnails of the
     * files at the root, or (when the root only has folders) of the first sub-folders.
     */
    suspend fun projectCover(account: String, project: dev.finalish.core.model.Project): dev.finalish.core.model.FolderPreview {
        val key = "projcover2:$account:${project.id}:${project.updatedAt}"
        if (project.rootFolderId !in stalePreviews) cache.get(key, dev.finalish.core.model.FolderPreview.serializer())?.let { return it }
        val root = Asset(id = project.rootFolderId, type = "folder", updatedAt = project.updatedAt)
        val rootPreview = folderPreview(account, root)
        val direct = rootPreview?.thumbnails.orEmpty()
        val thumbs = if (direct.isNotEmpty()) direct else runCatchingCancellable {
            api.childrenPreview(account, project.rootFolderId).data.filter { it.isFolder }.take(3)
                .flatMap { folderPreview(account, it)?.thumbnails.orEmpty().take(1) }
        }.getOrDefault(emptyList())
        val cover = dev.finalish.core.model.FolderPreview(thumbs, rootPreview?.count)
        if (thumbs.isNotEmpty()) cache.put(key, dev.finalish.core.model.FolderPreview.serializer(), cover)
        return cover
    }

    /** A stack's versions, head (current) first. */
    suspend fun versionsNow(account: String, stackId: String): List<Asset> =
        allPages(first = { api.versions(account, stackId) }, next = { api.versions(account, stackId, after = it) })

    /** True when [assetId] is a version stack; false for a file, or when it can't be told. */
    suspend fun isVersionStack(account: String, assetId: String): Boolean =
        runCatchingCancellable { api.versionStack(account, assetId) }.isSuccess

    suspend fun versionStackParent(account: String, stackId: String): String? =
        api.versionStack(account, stackId).data.parentId

    fun file(account: String, fileId: String): Flow<Remote<Asset>> =
        swr("file:$account:$fileId", Asset.serializer()) { _ ->
            // Including `original` 403s when downloads are disabled — retry without it.
            runCatchingCancellable { api.file(account, fileId).data }.getOrElse { err ->
                if (err is retrofit2.HttpException && err.code() == 403) {
                    api.file(account, fileId, FrameioApi.INCLUDE_PLAYBACK_NO_ORIGINAL).data
                } else throw err
            }
        }

    suspend fun fileNow(account: String, fileId: String): Asset =
        runCatchingCancellable { api.file(account, fileId).data }.getOrElse { err ->
            if (err is retrofit2.HttpException && err.code() == 403) {
                api.file(account, fileId, FrameioApi.INCLUDE_PLAYBACK_NO_ORIGINAL).data
            } else throw err
        }

    fun versions(account: String, stackId: String): Flow<Remote<List<Asset>>> =
        swr("versions:$account:$stackId", ListSerializer(Asset.serializer())) { _ ->
            allPages(first = { api.versions(account, stackId) }, next = { api.versions(account, stackId, after = it) })
        }

    suspend fun createFolder(account: String, parent: String, name: String): Asset =
        api.createFolder(account, parent, body { putJsonObject("data") { put("name", name) } }).data
            .also { invalidateFolder(account, parent) }

    suspend fun rename(account: String, asset: Asset, name: String): Asset =
        api.rename(account, FrameioApi.kindPath(asset.type), asset.id, body { putJsonObject("data") { put("name", name) } }).data

    suspend fun move(account: String, asset: Asset, destination: String) {
        api.move(account, FrameioApi.kindPath(asset.type), asset.id, body { putJsonObject("data") { put("parent_id", destination) } })
        invalidateFolder(account, destination)
        asset.parentId?.let { invalidateFolder(account, it) }
    }

    suspend fun copy(account: String, asset: Asset, destination: String) {
        val r = api.copyFile(account, asset.id, body { putJsonObject("data") { put("parent_id", destination) } })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        invalidateFolder(account, destination)
    }

    suspend fun delete(account: String, asset: Asset) {
        val r = api.delete(account, FrameioApi.kindPath(asset.type), asset.id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        asset.parentId?.let { invalidateFolder(account, it) }
    }

    suspend fun stackVersions(account: String, folder: String, fileIds: List<String>): Asset =
        api.createVersionStack(account, folder, body {
            putJsonObject("data") { putJsonArray("file_ids") { fileIds.forEach { add(JsonPrimitive(it)) } } }
        }).data.also { invalidateFolder(account, folder) }

    /** Moves [files] into an existing stack, oldest first, so the newest ends up as the current version. */
    suspend fun addToStack(account: String, stackId: String, files: List<Asset>) {
        files.forEach { move(account, it, stackId) }
        cache.invalidate("versions:$account:$stackId")
    }

    // ---- Comments ----------------------------------------------------------

    fun comments(account: String, fileId: String): Flow<Remote<List<Comment>>> =
        swr("comments:$account:$fileId", ListSerializer(Comment.serializer())) { _ ->
            allPages(
                first = { api.comments(account, fileId) },
                next = { api.comments(account, fileId, after = it) },
                partial = {},
            ).let { nameGuestAuthors(account, it) }
        }

    private val auditUnavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Ownerless comments the audit log has no entry for (e.g. left on a review link before the
     * account moved to V4): looked up once per session, not on every comments refresh.
     */
    private val authorsNotLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Comments left by people outside the account (share-link reviewers) come back with no
     * owner. The account's audit log still records who created each one, so look them up there
     * (once — authors are remembered per comment id).
     */
    private suspend fun nameGuestAuthors(account: String, comments: List<Comment>): List<Comment> {
        val all = comments + comments.flatMap { it.replies }
        val ownerless = all.filter { it.owner == null && !it.pending }
        if (ownerless.isEmpty()) return comments
        val key = "authors:$account"
        val ser = kotlinx.serialization.builtins.MapSerializer(String.serializer(), User.serializer())
        val known = (cache.get(key, ser) ?: emptyMap()).toMutableMap()
        val wanted = ownerless.map { it.id }.filterNot { it in known || it in authorsNotLogged }.toMutableSet()
        val days = ownerless.filter { it.id in wanted }.mapNotNull { it.createdAt?.take(10) }.sorted()
        if (wanted.isNotEmpty() && days.isNotEmpty() && account !in auditUnavailable) {
            runCatchingCancellable {
                val to = java.time.LocalDate.parse(days.last()).plusDays(1).toString()
                var after: String? = null
                var pages = 0
                do {
                    val page = api.auditLogs(
                        account, eventType = "comment_created", resourceType = "comment",
                        fromDate = days.first(), toDate = to, after = after,
                    )
                    page.data.forEach { e ->
                        val who = e.user ?: e.actor
                        if (who != null && wanted.remove(e.resourceId)) known[e.resourceId] = who
                    }
                    after = page.links?.next?.let(::afterCursor)
                } while (wanted.isNotEmpty() && after != null && ++pages < 30)
                authorsNotLogged += wanted
                cache.put(key, ser, known)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                // Only the account lacking the audit log stops asking; being offline once doesn't.
                if (it is retrofit2.HttpException && it.code() in setOf(400, 403, 404)) auditUnavailable += account
                // The log refuses this lookup (e.g. dates outside what the plan keeps): don't ask again for
                // these comments on every refresh; newer comments still get their own lookup.
                if (it is retrofit2.HttpException && it.code() == 422) authorsNotLogged += wanted
                dev.finalish.core.util.AppLog.w("Audit log lookup for comment authors failed: ${it.message}")
            }
        }
        fun named(c: Comment) = if (c.owner == null) known[c.id]?.let { c.copy(owner = it) } ?: c else c
        return comments.map { c -> named(c).let { it.copy(replies = it.replies.map(::named)) } }
    }

    suspend fun cachedComments(account: String, fileId: String): List<Comment>? =
        cache.get("comments:$account:$fileId", ListSerializer(Comment.serializer()))

    suspend fun storeComments(account: String, fileId: String, comments: List<Comment>) =
        cache.put("comments:$account:$fileId", ListSerializer(Comment.serializer()), comments)

    suspend fun createComment(account: String, fileId: String, draft: CommentDraft): Comment =
        api.createComment(account, fileId, draft.toBody()).data

    suspend fun updateComment(account: String, commentId: String, text: String? = null, completed: Boolean? = null): Comment =
        api.updateComment(account, commentId, body {
            putJsonObject("data") {
                text?.let { put("text", it) }
                completed?.let { put("completed", it) }
            }
        }).data

    /** Updates any editable part of a comment; [fields] go straight into `data`. */
    suspend fun updateCommentFields(account: String, commentId: String, fields: Map<String, JsonElement>): Comment =
        api.updateComment(account, commentId, body { put("data", JsonObject(fields)) }).data

    suspend fun deleteComment(account: String, commentId: String) {
        val r = api.deleteComment(account, commentId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    // ---- Metadata ----------------------------------------------------------

    /**
     * Clears one scalar field. The API rejects null (422) and empty lists (500);
     * an empty string is what clears text/number/date/rating values.
     */
    suspend fun clearMetadata(account: String, projectId: String, fileId: String, fieldId: String) {
        val candidates = listOf<JsonElement>(
            JsonPrimitive(""),
            kotlinx.serialization.json.JsonNull,
            kotlinx.serialization.json.JsonArray(emptyList()),
        )
        var last: Throwable? = null
        for (v in candidates) {
            val r = api.updateMetadata(account, projectId, body {
                putJsonObject("data") {
                    putJsonArray("file_ids") { add(JsonPrimitive(fileId)) }
                    putJsonArray("values") { add(buildJsonObject { put("field_definition_id", fieldId); put("value", v) }) }
                }
            })
            if (r.isSuccessful) { cache.invalidate("file:$account:$fileId"); return }
            last = retrofit2.HttpException(r)
        }
        throw last!!
    }

    // ---- Metadata field definitions (account level) -----------------------

    suspend fun fieldDefinitions(account: String): List<dev.finalish.core.model.FieldDefinition> =
        allPages(
            first = { api.fieldDefinitions(account) },
            next = { api.fieldDefinitions(account, after = it) },
            partial = {},
            limit = 500,
        )

    /** A new field with Frame.io's defaults for its type; [options] only for select fields. */
    suspend fun createFieldDefinition(account: String, type: String, name: String, options: List<String> = emptyList()):
        dev.finalish.core.model.FieldDefinition =
        api.createFieldDefinition(account, body {
            putJsonObject("data") {
                put("field_type", type)
                put("name", name)
                fieldConfiguration(type, options)?.let { put("field_configuration", it) }
            }
        }).data

    suspend fun renameFieldDefinition(account: String, field: dev.finalish.core.model.FieldDefinition, name: String):
        dev.finalish.core.model.FieldDefinition =
        api.updateFieldDefinition(account, field.id, body {
            putJsonObject("data") { put("field_type", field.type); put("name", name) }
        }).data

    /** Deleting a field removes its values from every file in the account. */
    suspend fun deleteFieldDefinition(account: String, fieldId: String) {
        val r = api.deleteFieldDefinition(account, fieldId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    private fun fieldConfiguration(type: String, options: List<String>): JsonObject? = when (type) {
        "select", "select_multi" -> buildJsonObject {
            put("enable_add_new", true)
            putJsonArray("options") { options.forEach { o -> add(buildJsonObject { put("display_name", o) }) } }
        }
        "rating" -> buildJsonObject { put("max_value", 5); put("style", "star") }
        "number" -> buildJsonObject { put("scale", 0) }
        "date" -> buildJsonObject { put("display_format", "local"); put("include_time", false) }
        "user_single", "user_multi" -> buildJsonObject { put("member_options_type", "all_project_members"); put("notify_members", false) }
        else -> null
    }

    suspend fun fullMetadata(account: String, fileId: String): List<dev.finalish.core.model.MetadataField> =
        api.metadata(account, fileId).data.metadata

    suspend fun updateMetadata(account: String, projectId: String, fileIds: List<String>, fieldId: String, value: JsonElement) {
        val r = api.updateMetadata(account, projectId, body {
            putJsonObject("data") {
                putJsonArray("file_ids") { fileIds.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("values") {
                    add(buildJsonObject {
                        put("field_definition_id", fieldId)
                        put("value", value)
                    })
                }
            }
        })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        fileIds.forEach { cache.invalidate("file:$account:$it") }
    }

    // ---- Shares & collections ----------------------------------------------

    fun cachedShares(account: String, projectId: String): List<Share>? = cache.peek("shares:$account:$projectId")

    fun shares(account: String, projectId: String): Flow<Remote<List<Share>>> =
        swr("shares:$account:$projectId", ListSerializer(Share.serializer())) { _ ->
            allPages(first = { api.shares(account, projectId) }, next = { api.shares(account, projectId, after = it) })
        }

    suspend fun createShare(
        account: String,
        projectId: String,
        name: String,
        assetIds: List<String>,
        downloads: Boolean,
        comments: Boolean,
        passphrase: String?,
        expiration: String?,
        access: String = "public",
        showAllVersions: Boolean = false,
    ): Share = api.createShare(account, projectId, body {
        putJsonObject("data") {
            put("type", "asset")
            put("access", access)
            put("show_all_asset_versions", showAllVersions)
            put("name", name)
            putJsonArray("asset_ids") { assetIds.forEach { add(JsonPrimitive(it)) } }
            put("downloading_enabled", downloads)
            put("commenting_enabled", comments)
            passphrase?.takeIf { it.isNotBlank() }?.let { put("passphrase", it) }
            expiration?.let { put("expiration", it) }
        }
    }).data.also { cache.invalidate("shares:$account:$projectId") }

    suspend fun addToShare(account: String, shareId: String, assetId: String) {
        val r = api.addShareAsset(account, shareId, body { putJsonObject("data") { put("asset_id", assetId) } })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun share(account: String, shareId: String): Share = api.share(account, shareId).data

    /**
     * What a share link holds. The share listing can come back with empty media links, so files
     * without a thumbnail are re-read (at most 24, in parallel) to show their pictures.
     */
    suspend fun shareAssets(account: String, shareId: String): List<Asset> {
        val items = allPages(
            first = { api.shareAssets(account, shareId, include = FrameioApi.INCLUDE_BROWSE) },
            next = { api.shareAssets(account, shareId, include = FrameioApi.INCLUDE_BROWSE, after = it) },
        )
        val missing = items.filter { !it.isFolder && it.thumbnailUrl == null }.take(24).map { it.id }.toSet()
        if (missing.isEmpty()) return items
        val fresh = kotlinx.coroutines.coroutineScope {
            missing.map { id -> async { runCatchingCancellable { api.file(account, id, include = FrameioApi.INCLUDE_BROWSE).data }.getOrNull() } }
                .mapNotNull { it.await() }.associateBy { it.id }
        }
        return items.map { fresh[it.id] ?: it }
    }

    private val shareCovers = android.util.LruCache<String, String>(100)

    /**
     * A picture for a share link's card: the first shared file's thumbnail (from the file itself
     * when the share listing has none), or the cover of a shared folder. Cached for the session (the signed URLs outlive it).
     */
    suspend fun shareCover(account: String, shareId: String): String? {
        shareCovers.get(shareId)?.let { return it }
        return runCatchingCancellable {
            val items = api.shareAssets(account, shareId, include = FrameioApi.INCLUDE_BROWSE, pageSize = 12).data
            items.firstNotNullOfOrNull { it.thumbnailUrl }
                // The share listing can come back with empty media links: ask the file itself.
                ?: items.firstOrNull { !it.isFolder }?.let { runCatchingCancellable { api.file(account, it.id, include = FrameioApi.INCLUDE_BROWSE).data.thumbnailUrl }.getOrNull() }
                ?: items.firstOrNull { it.isFolder }?.let { folderPreview(account, it)?.thumbnails?.firstOrNull() }
        }.getOrNull()?.also { shareCovers.put(shareId, it) }
    }

    suspend fun removeFromShare(account: String, shareId: String, assetId: String) {
        val r = api.removeShareAsset(account, shareId, assetId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun updateShare(account: String, shareId: String, fields: Map<String, JsonElement>): Share =
        api.updateShare(account, shareId, body { put("data", JsonObject(fields)) }).data

    suspend fun watermarkTemplates(account: String): List<dev.finalish.core.model.WatermarkTemplate> =
        api.watermarkTemplates(account).data

    /** Frame.io doesn't return a share's template, so the one picked here is remembered locally. */
    suspend fun shareWatermarkTemplate(shareId: String): String? = cache.get("wmtpl:$shareId", String.serializer())

    /** [templateId] null = the workspace or account default template. */
    suspend fun setShareWatermarkTemplate(account: String, shareId: String, templateId: String?): Share {
        val updated = api.updateShareExperimental(account, shareId, body {
            putJsonObject("data") { put("watermark_template_id", templateId?.let { JsonPrimitive(it) } ?: JsonNull) }
        }).data
        if (templateId != null) cache.put("wmtpl:$shareId", String.serializer(), templateId) else cache.invalidate("wmtpl:$shareId")
        return updated
    }

    suspend fun setShareEnabled(account: String, share: Share, enabled: Boolean): Share =
        api.updateShare(account, share.id, body { putJsonObject("data") { put("enabled", enabled) } }).data

    suspend fun deleteShare(account: String, projectId: String, shareId: String) {
        val r = api.deleteShare(account, shareId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        cache.invalidate("shares:$account:$projectId")
    }

    fun collections(account: String, projectId: String): Flow<Remote<List<Collection>>> =
        swr("collections:$account:$projectId", ListSerializer(Collection.serializer())) { _ ->
            api.collections(account, projectId).data
        }

    // ---- Search ------------------------------------------------------------

    private fun SearchHit.toResult(): SearchResult? {
        val r = result as? JsonObject ?: return null
        return runCatchingCancellable {
            when (type) {
                "project_result" -> SearchResult.ProjectHit(AppJson.decodeFromJsonElement(Project.serializer(), r))
                else -> SearchResult.AssetHit(AppJson.decodeFromJsonElement(Asset.serializer(), r))
            }
        }.getOrNull()
    }

    // ---- Invited projects & members ----------------------------------------

    suspend fun accountUsers(account: String): List<dev.finalish.core.model.UserRole> =
        allPages(first = { api.accountUsers(account) }, next = { api.accountUsers(account, after = it) })

    /** [scope] is "projects" or "workspaces". */
    suspend fun members(account: String, scope: String, id: String): List<dev.finalish.core.model.UserRole> {
        val sort = if (scope == "folders") null else "name_asc"
        val all = allPages(
            first = { api.members(account, scope, id, sort = sort) },
            next = { api.members(account, scope, id, sort = sort, after = it) },
            partial = {},
            limit = 500,
        )
        return if (sort == null) all.sortedBy { it.user.displayName.lowercase() } else all
    }

    suspend fun setMemberRole(account: String, scope: String, id: String, userId: String, role: String) {
        val r = api.setMemberRole(account, scope, id, userId, body { putJsonObject("data") { put("role", role) } })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun removeMember(account: String, scope: String, id: String, userId: String) {
        val r = api.removeMember(account, scope, id, userId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    // ---- User groups ---------------------------------------------------------

    suspend fun groups(account: String): List<dev.finalish.core.model.UserGroup> =
        allPages(first = { api.groups(account) }, next = { api.groups(account, after = it) }, partial = {}, limit = 500)

    suspend fun createGroup(account: String, name: String, emoji: String): dev.finalish.core.model.UserGroup =
        api.createGroup(account, body { putJsonObject("data") { put("name", name); put("emoji", emoji) } }).data

    suspend fun updateGroup(account: String, groupId: String, name: String, emoji: String): dev.finalish.core.model.UserGroup =
        api.updateGroup(account, groupId, body { putJsonObject("data") { put("name", name); put("emoji", emoji) } }).data

    suspend fun deleteGroup(account: String, groupId: String) {
        val r = api.deleteGroup(account, groupId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun groupUsers(account: String, groupId: String): List<User> =
        allPages(first = { api.groupUsers(account, groupId) }, next = { api.groupUsers(account, groupId, after = it) }, partial = {}, limit = 1_000)

    suspend fun addGroupUsers(account: String, groupId: String, userIds: List<String>) {
        val r = api.addGroupUsers(account, groupId, body { putJsonObject("data") { putJsonArray("user_ids") { userIds.forEach { add(JsonPrimitive(it)) } } } })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun removeGroupUser(account: String, groupId: String, userId: String) {
        val r = api.removeGroupUser(account, groupId, userId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    /** Groups with a role on a project or workspace ([scope] "projects" / "workspaces"); folders have none. */
    suspend fun scopeGroups(account: String, scope: String, id: String): List<dev.finalish.core.model.GroupRole> =
        if (scope == "folders") emptyList() else api.scopeGroups(account, scope, id).data

    suspend fun setGroupRole(account: String, scope: String, id: String, groupId: String, role: String) {
        val r = api.setGroupRole(account, scope, id, groupId, body { putJsonObject("data") { put("role", role) } })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun removeGroupRole(account: String, scope: String, id: String, groupId: String) {
        val r = api.removeGroupRole(account, scope, id, groupId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun renameWorkspace(account: String, workspaceId: String, name: String) {
        api.updateWorkspace(account, workspaceId, body { putJsonObject("data") { put("name", name) } })
        cache.invalidate("ws:$account")
    }

    suspend fun deleteWorkspace(account: String, workspaceId: String) {
        val r = api.deleteWorkspace(account, workspaceId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        cache.invalidate("ws:$account")
    }

    // ---- Comment attachments ------------------------------------------------

    suspend fun createAttachment(account: String, commentId: String, name: String, size: Long, mediaType: String) =
        api.createAttachment(account, commentId, body {
            putJsonObject("data") {
                put("name", name)
                put("file_size", size)
                put("media_type", mediaType)
            }
        }).data

    suspend fun deleteAttachment(account: String, commentId: String, attachmentId: String) {
        val r = api.deleteAttachment(account, commentId, attachmentId)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    // ---- Share reviewers & activity -------------------------------------------

    suspend fun shareReviewers(account: String, shareId: String): List<User> = api.shareReviewers(account, shareId).data

    suspend fun addShareReviewers(account: String, shareId: String, emails: List<String>, message: String, notify: Boolean) {
        val r = api.addShareReviewers(account, shareId, body {
            putJsonObject("data") {
                put("message", message)
                put("notify_by_email", notify)
                putJsonObject("reviewers") { putJsonArray("emails") { emails.forEach { add(JsonPrimitive(it)) } } }
            }
        })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun removeShareReviewer(account: String, shareId: String, user: User) {
        val r = api.removeShareReviewers(account, shareId, body {
            putJsonObject("data") {
                putJsonObject("reviewers") {
                    if (user.id != null) putJsonArray("user_ids") { add(JsonPrimitive(user.id)) }
                    else putJsonArray("emails") { add(JsonPrimitive(user.email.orEmpty())) }
                }
            }
        })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun shareActivities(account: String, shareId: String) = api.shareActivities(account, shareId).data

    // ---- Trash, import, copy, versions --------------------------------------

    suspend fun recentlyDeleted(account: String, projectId: String): List<Asset> =
        allPages(first = { api.recentlyDeleted(account, projectId) }, next = { api.recentlyDeleted(account, projectId, after = it) })

    suspend fun restore(account: String, asset: Asset) {
        val r = api.restore(account, FrameioApi.kindPath(asset.type), asset.id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        asset.parentId?.let { invalidateFolder(account, it) }
    }

    suspend fun importFromUrl(account: String, folderId: String, name: String, url: String): Asset =
        api.remoteUpload(account, folderId, body {
            putJsonObject("data") {
                put("name", name)
                put("source_url", url)
            }
        }).data.also { invalidateFolder(account, folderId) }

    /** Copies any mix of files, folders and version stacks (bulk endpoints where possible). */
    suspend fun copyAssets(account: String, assets: List<Asset>, destination: String) {
        val files = assets.filter { it.isFile }.map { it.id }
        val folders = assets.filter { it.isFolder }.map { it.id }
        files.chunked(100).forEach { ids -> bulk(api::bulkCopy, account, "files", "file_ids", ids, destination) }
        folders.chunked(100).forEach { ids -> bulk(api::bulkCopy, account, "folders", "folder_ids", ids, destination) }
        assets.filter { it.isVersionStack }.forEach { s ->
            val r = api.copyAsset(account, "version_stacks", s.id, body { putJsonObject("data") { put("parent_id", destination) } })
            if (!r.isSuccessful) throw retrofit2.HttpException(r)
        }
        invalidateFolder(account, destination)
    }

    /** Moves any mix of assets; files and folders go through the bulk endpoints. */
    suspend fun moveAssets(account: String, assets: List<Asset>, destination: String) {
        val files = assets.filter { it.isFile }.map { it.id }
        val folders = assets.filter { it.isFolder }.map { it.id }
        files.chunked(100).forEach { ids -> bulk(api::bulkMove, account, "files", "file_ids", ids, destination) }
        folders.chunked(100).forEach { ids -> bulk(api::bulkMove, account, "folders", "folder_ids", ids, destination) }
        assets.filter { it.isVersionStack }.forEach { move(account, it, destination) }
        invalidateFolder(account, destination)
        assets.mapNotNull { it.parentId }.distinct().forEach { invalidateFolder(account, it) }
    }

    private suspend fun bulk(
        call: suspend (String, String, JsonObject) -> retrofit2.Response<Unit>,
        account: String, kind: String, key: String, ids: List<String>, destination: String,
    ) {
        val r = call(account, kind, body {
            putJsonObject("data") {
                putJsonArray(key) { ids.forEach { add(JsonPrimitive(it)) } }
                put("parent_id", destination)
            }
        })
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    /**
     * Moves [fileId] inside its version stack: "top"/"bottom", or above/below another version.
     * Returns the stack's new order, head (current version) first — same as [versions].
     */
    suspend fun reorderVersion(account: String, stackId: String, fileId: String, moveTo: String? = null, above: String? = null, below: String? = null): List<Asset> {
        val r = api.reorderVersion(account, stackId, body {
            putJsonObject("data") {
                put("target_file_id", fileId)
                moveTo?.let { put("move_to", it) }
                above?.let { put("move_above_file_id", it) }
                below?.let { put("move_below_file_id", it) }
            }
        })
        cache.invalidate("versions:$account:$stackId")
        return r.data
    }

    /** One page of search results; returns the results and the next cursor. */
    suspend fun searchPage(
        account: String, query: String, semantic: Boolean, after: String?,
        files: Boolean, folders: Boolean, projects: Boolean,
    ): Pair<List<SearchResult>, String?> {
        val page = api.searchPage(account, body {
            put("engine", if (semantic) "nlp" else "lexical")
            put("query", query)
            putJsonObject("filters") {
                put("files_and_version_stacks", files)
                put("folders", folders)
                put("projects", projects)
            }
        }, after)
        return page.data.mapNotNull { it.toResult() } to page.links?.next?.let(::afterCursor)
    }

    fun clearCache() = cache.clear()
    fun cacheSize() = cache.sizeBytes()

    private inline fun body(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)
}

sealed interface SearchResult {
    data class AssetHit(val asset: Asset) : SearchResult
    data class ProjectHit(val project: Project) : SearchResult
}

/** Everything needed to create a comment (also persisted for offline sync). */
@Serializable
data class CommentDraft(
    val text: String,
    val frame: Int? = null,
    val durationFrames: Int? = null,
    val page: Int? = null,
    val anchorX: Float? = null,
    val anchorY: Float? = null,
    val annotation: String? = null,
) {
    fun toBody(): JsonObject = buildJsonObject {
        putJsonObject("data") {
            put("text", text)
            frame?.let { put("timestamp", it) }
            durationFrames?.takeIf { it > 0 && frame != null }?.let { put("duration", it) }
            page?.let { put("page", it) }
            if (anchorX != null && anchorY != null) {
                putJsonObject("anchor") {
                    put("x", anchorX)
                    put("y", anchorY)
                }
            }
            annotation?.let { put("annotation", it) }
            val links = URL_REGEX.findAll(text).map { m ->
                buildJsonObject {
                    put("start_index", m.range.first)
                    put("end_index", m.range.last + 1)
                    put("url", m.value)
                }
            }.toList()
            if (links.isNotEmpty()) put("links", JsonArray(links))
        }
    }

    companion object {
        val URL_REGEX = Regex("""https?://[^\s]+""")
    }
}
