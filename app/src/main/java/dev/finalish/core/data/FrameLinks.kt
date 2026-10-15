package dev.finalish.core.data

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where a Frame.io link leads inside the app. */
sealed interface LinkTarget {
    /** [type] is the asset type ("file" or "version_stack"), which the viewer needs to load it. */
    data class File(val assetId: String, val type: String = "file") : LinkTarget
    data class Folder(val projectId: String, val rootFolderId: String, val folderId: String, val title: String, val projectName: String) : LinkTarget
    data class Project(val projectId: String, val rootFolderId: String, val name: String) : LinkTarget
    data class Shares(val projectId: String, val projectName: String) : LinkTarget
    /** Not something this account can open (someone else's share, unknown page): the browser shows it. */
    data class Browser(val url: String) : LinkTarget
}

/**
 * Frame.io links opened from other apps:
 * - `next.frame.io/project/{p}/view/{file}` → the file
 * - `next.frame.io/project/{p}/{folder}` → the folder; `next.frame.io/project/{p}` → the project
 * - `next.frame.io/share/{id}` and `f.io/{code}` (which redirects there) → what the link shares,
 *   when the share belongs to this account
 */
object FrameLinks {
    val HOSTS = setOf("next.frame.io", "f.io")

    fun handles(uri: Uri): Boolean = uri.host in HOSTS

    /** The pieces of a next.frame.io path, without touching the network. */
    sealed interface Parsed {
        data class View(val assetId: String) : Parsed
        data class InProject(val projectId: String, val folderId: String?) : Parsed
        data class ShareLink(val shareId: String) : Parsed
        data object Other : Parsed
    }

    fun parse(segments: List<String>): Parsed = when {
        segments.getOrNull(0) == "project" && segments.getOrNull(2) == "view" && segments.size >= 4 -> Parsed.View(segments[3])
        segments.getOrNull(0) == "project" && segments.size >= 2 -> Parsed.InProject(segments[1], segments.getOrNull(2))
        segments.getOrNull(0) == "share" && segments.size >= 2 -> Parsed.ShareLink(segments[1])
        else -> Parsed.Other
    }

    /** f.io short links answer with a redirect to the full share URL. */
    suspend fun expand(url: String, http: OkHttpClient): String = withContext(Dispatchers.IO) {
        if (url.toUri().host != "f.io") return@withContext url
        runCatching {
            val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.header("Location") }
        }.getOrNull()?.takeIf { it.startsWith("https://") } ?: url
    }

    suspend fun resolve(url: String, account: String, repo: FrameioRepository, http: OkHttpClient): LinkTarget {
        val full = expand(url, http)
        val uri = full.toUri()
        if (uri.host != "next.frame.io") return LinkTarget.Browser(url)
        return runCatching {
            when (val p = parse(uri.pathSegments)) {
                // A view link can point at a version stack too: the viewer needs to know which.
                is Parsed.View -> LinkTarget.File(p.assetId, if (repo.isVersionStack(account, p.assetId)) "version_stack" else "file")
                is Parsed.InProject -> {
                    val project = repo.project(account, p.projectId)
                    val folder = p.folderId?.takeIf { it != project.rootFolderId }
                    if (folder == null) LinkTarget.Project(project.id, project.rootFolderId, project.name)
                    else LinkTarget.Folder(project.id, project.rootFolderId, folder, repo.folder(account, folder).name, project.name)
                }
                is Parsed.ShareLink -> {
                    // Only this account's own shares can be opened here; a client's link goes to the browser.
                    val items = repo.shareAssets(account, p.shareId)
                    val only = items.singleOrNull()
                    when {
                        only != null && !only.isFolder -> LinkTarget.File(only.id, only.type)
                        only != null -> {
                            val project = repo.project(account, only.projectId)
                            LinkTarget.Folder(project.id, project.rootFolderId, only.id, only.name, project.name)
                        }
                        items.isNotEmpty() -> LinkTarget.Shares(items.first().projectId, repo.project(account, items.first().projectId).name)
                        else -> LinkTarget.Browser(full)
                    }
                }
                Parsed.Other -> LinkTarget.Browser(full)
            }
        }.getOrElse { LinkTarget.Browser(full) }
    }
}
