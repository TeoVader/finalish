package dev.finalish.core.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.finalish.core.util.runCatchingCancellable
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
    /** Not something the user's accounts can open (someone else's share, unknown page): the browser shows it. */
    data class Browser(val url: String) : LinkTarget
    /** [target] lives in another of the user's accounts: switch to [accountId] first. */
    data class InAccount(val accountId: String, val target: LinkTarget) : LinkTarget
}

/**
 * Frame.io links opened from other apps:
 * - `next.frame.io/project/{p}/view/{file}` → the file
 * - `next.frame.io/project/{p}/{folder}` → the folder; `next.frame.io/project/{p}` → the project
 * - `next.frame.io/share/{id}` and `f.io/{code}` (which redirects there) → what the link shares,
 *   when the share belongs to one of the user's accounts (the current one first)
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

    /** [others] are the user's other accounts, tried in order for a share link the current one doesn't hold. */
    suspend fun resolve(url: String, account: String, repo: FrameioRepository, http: OkHttpClient, others: List<String> = emptyList()): LinkTarget {
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
                    // The API reads shares only inside accounts the user belongs to: look in each, the
                    // current one first. A share from anyone else's account goes to the browser.
                    (listOf(account) + others.filter { it != account }).firstNotNullOfOrNull { acc ->
                        runCatchingCancellable { shareTarget(acc, p.shareId, repo) }.getOrNull()
                            ?.let { if (acc == account) it else LinkTarget.InAccount(acc, it) }
                    } ?: LinkTarget.Browser(full)
                }
                Parsed.Other -> LinkTarget.Browser(full)
            }
        }.getOrElse { LinkTarget.Browser(full) }
    }

    /** What a share holds, opened in [account]; null when the share has nothing (or isn't there). */
    private suspend fun shareTarget(account: String, shareId: String, repo: FrameioRepository): LinkTarget? {
        val items = repo.shareAssets(account, shareId)
        val only = items.singleOrNull()
        return when {
            only != null && !only.isFolder -> LinkTarget.File(only.id, only.type)
            only != null -> {
                val project = repo.project(account, only.projectId)
                LinkTarget.Folder(project.id, project.rootFolderId, only.id, only.name, project.name)
            }
            items.isNotEmpty() -> LinkTarget.Shares(items.first().projectId, repo.project(account, items.first().projectId).name)
            else -> null
        }
    }

    /**
     * Opens [url] in a Custom Tab of a real browser, never in this app: the browser's package is set
     * explicitly, because asking Android for "the browser" (an app-browser selector) shows a chooser
     * of every app on some phones. No browser at all: the plain chooser, without us.
     */
    fun openInBrowserTab(context: Context, url: String) {
        val uri = url.toUri()
        val browser = browserPackage(context)
        runCatching {
            if (browser == null) {
                val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(
                    Intent.createChooser(view, null)
                        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, dev.finalish.MainActivity::class.java)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            val tab = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(
                    CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(0xFF0A0A0E.toInt())
                        .setNavigationBarColor(0xFF0A0A0E.toInt())
                        .build(),
                )
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setShowTitle(true)
                .build()
            tab.intent.setPackage(browser)
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, uri)
        }.onFailure { dev.finalish.core.util.AppLog.w("Couldn't open a link in a browser", it) }
    }

    /** The default browser when it supports Custom Tabs, else any browser that does, else any browser. */
    private fun browserPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
        return CustomTabsClient.getPackageName(context, browsers) ?: browsers.firstOrNull()
    }
}
