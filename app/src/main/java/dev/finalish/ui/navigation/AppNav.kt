package dev.finalish.ui.navigation

import kotlinx.coroutines.ensureActive
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.finalish.core.auth.AuthState
import dev.finalish.core.model.Asset
import dev.finalish.core.model.Project
import dev.finalish.ui.SessionViewModel
import dev.finalish.ui.browser.FolderScreen
import dev.finalish.ui.components.ReadableScreen
import dev.finalish.ui.home.HomeScreen
import dev.finalish.ui.login.LoginScreen
import dev.finalish.ui.shares.CollectionsScreen
import dev.finalish.ui.shares.SharesScreen
import dev.finalish.ui.theme.Motion
import dev.finalish.ui.theme.Palette
import dev.finalish.ui.viewer.CompareScreen
import dev.finalish.ui.viewer.ViewerScreen

private val push: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(Motion.enter()) { it / 4 } + fadeIn(Motion.enter())
}
private val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(Motion.exit()) { -it / 10 } + fadeOut(Motion.exit())
}
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(Motion.enter()) { -it / 10 } + fadeIn(Motion.enter())
}
/**
 * Predictive back: the page follows the finger. It shrinks a little and drifts toward the side
 * the swipe comes from (left edge → right, right edge → left), like the system back animation,
 * instead of shrinking into the centre.
 */
private val predictivePop: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { edge ->
    val dir = if (edge == androidx.activity.BackEventCompat.EDGE_RIGHT) -1 else 1
    // Linear: the transition is scrubbed by the finger, so the page must track it 1:1.
    androidx.compose.animation.scaleOut(tween(300, easing = LinearEasing), targetScale = 0.9f) +
        slideOutHorizontally(tween(300, easing = LinearEasing)) { dir * it / 6 } +
        // Gone by two thirds of the gesture, so no ghost of the old screen lingers over the new one.
        fadeOut(tween(200, easing = LinearEasing))
}
private val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = { _ ->
    fadeIn(tween(300, easing = LinearEasing), initialAlpha = 0.4f)
}
private val pop: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(Motion.exit()) { it / 4 } + fadeOut(Motion.exit())
}

@Composable
fun AppNav(
    session: SessionViewModel,
    pendingRedirect: Uri?,
    onRedirectConsumed: () -> Unit,
    nav: NavHostController = rememberNavController(),
) {
    val auth by session.container.auth.state.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val uploads by session.incomingUploads.collectAsStateWithLifecycle()
    val deepLink by session.deepLink.collectAsStateWithLifecycle()
    val homeTab by session.homeTab.collectAsStateWithLifecycle()
    val signedIn = auth is AuthState.SignedIn

    var lastSignedIn by remember { mutableStateOf(signedIn) }
    LaunchedEffect(signedIn) {
        if (signedIn == lastSignedIn) return@LaunchedEffect
        lastSignedIn = signedIn
        val target: Any = if (signedIn) HomeRoute else LoginRoute
        nav.navigate(target) { popUpTo(0) { inclusive = true } }
    }

    val linkContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(deepLink, signedIn, state.accountId) {
        val link = deepLink ?: return@LaunchedEffect
        val account = state.accountId
        if (!signedIn || account == null) return@LaunchedEffect
        val c = dev.finalish.FinalishApp.container(linkContext)
        val target = dev.finalish.core.data.FrameLinks.resolve(link, account, c.repository, c.baseHttp)
        // Restarted mid-resolve (e.g. the account changed): the new run handles the link; resolve may
        // have swallowed the cancellation and answered "browser".
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // Cleared only now: clearing the key earlier would cancel this effect mid-resolve.
        session.consumeLink()
        when (val t = target) {
            is dev.finalish.core.data.LinkTarget.File -> nav.navigate(ViewerRoute(t.assetId, type = t.type))
            is dev.finalish.core.data.LinkTarget.Folder -> nav.navigate(FolderRoute(t.projectId, t.rootFolderId, t.folderId, t.title, projectName = t.projectName))
            is dev.finalish.core.data.LinkTarget.Project -> nav.navigate(FolderRoute(t.projectId, t.rootFolderId, t.rootFolderId, t.name, isProjectRoot = true, projectName = t.name))
            is dev.finalish.core.data.LinkTarget.Shares -> nav.navigate(SharesRoute(t.projectId, t.projectName))
            // Someone else's share (or a page the app has no screen for): the browser, never back to us.
            is dev.finalish.core.data.LinkTarget.Browser -> runCatching {
                val browser = android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_BROWSER)
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW, t.url.toUri())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                view.selector = browser
                linkContext.startActivity(view)
            }
        }
    }

    val account = state.accountId.orEmpty()
    val appContainer = dev.finalish.FinalishApp.container(androidx.compose.ui.platform.LocalContext.current)
    fun openAsset(a: Asset, projectId: String = a.projectId, root: String = "", projectName: String = "") {
        if (a.isFolder) nav.navigate(FolderRoute(projectId, root.ifBlank { a.id }, a.id, a.name, projectName = projectName))
        else {
            // The viewer starts from the card's data, so its stage exists on the first frame.
            appContainer.repository.stash(a)
            nav.navigate(ViewerRoute(a.id, a.type, folderId = a.parentId))
        }
    }
    fun openProject(p: Project) = nav.navigate(FolderRoute(p.id, p.rootFolderId, p.rootFolderId, p.name, isProjectRoot = true, projectName = p.name))

    // Feedback can be requested from anywhere (shake, menus): open the reporter.
    LaunchedEffect(nav) {
        dev.finalish.core.feedback.FeedbackLauncher.requests.collect { kind ->
            nav.navigate(FeedbackRoute(kind.name)) { launchSingleTop = true }
        }
    }
    // Breadcrumbs for reports: which screens the user went through.
    androidx.compose.runtime.DisposableEffect(nav) {
        val l = androidx.navigation.NavController.OnDestinationChangedListener { _, dest, args ->
            val name = dest.route?.substringAfterLast('.')?.substringBefore('/')?.substringBefore('?') ?: "?"
            // Which file/folder/project the screen was showing, for reproducing a report: ids, flags and
            // numbers only. Names and titles (projects, folders, people) are the user's clients' data.
            val detail = args?.keySet().orEmpty().filterNot { it.startsWith("android-support-nav") }.sorted()
                .mapNotNull { k ->
                    @Suppress("DEPRECATION") val v = args?.get(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    v?.takeIf { k.endsWith("id", ignoreCase = true) || it.matches(breadcrumbScalar) }?.let { "$k=${it.take(60)}" }
                }
                .joinToString(" ")
            dev.finalish.core.feedback.Breadcrumbs.add(name.removeSuffix("Route") + if (detail.isNotEmpty()) " ($detail)" else "")
        }
        nav.addOnDestinationChangedListener(l)
        onDispose { nav.removeOnDestinationChangedListener(l) }
    }

    // One shared transition around all screens: a file's thumbnail grows into the viewer and back.
    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    androidx.compose.animation.SharedTransitionLayout {
    androidx.compose.runtime.CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    NavHost(
        navController = nav,
        startDestination = if (signedIn) HomeRoute else LoginRoute,
        modifier = Modifier.fillMaxSize().background(Palette.Ink),
        enterTransition = push,
        exitTransition = pushExit,
        popEnterTransition = popEnter,
        popExitTransition = pop,
        predictivePopEnterTransition = predictivePopEnter,
        predictivePopExitTransition = predictivePop,
    ) {
        composable<LoginRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            LoginScreen(pendingRedirect, onRedirectConsumed)
        }
        composable<HomeRoute>(enterTransition = { fadeIn(Motion.enter()) }) {
            androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
            HomeScreen(
                session = state,
                onSelectAccount = session::selectAccount,
                onSignOut = session::signOut,
                onOpenProject = ::openProject,
                onOpenShares = { nav.navigate(SharesRoute(it.id, it.name)) },
                onOpenCollections = { nav.navigate(CollectionsRoute(it.id, it.name)) },
                onOpenAsset = { openAsset(it) },
                onOpenOffline = { nav.navigate(ViewerRoute(it.fileId, "file", offline = true)) },
                onOpenUpload = { u -> u.remoteFileId?.let { nav.navigate(ViewerRoute(it, "file", folderId = u.folderId)) } },
                onRetrySession = session::load,
                requestedTab = homeTab,
                onTabShown = session::consumeHomeTab,
                onOpenMembers = { scope, id, title -> nav.navigate(MembersRoute(scope, id, title)) },
                onOpenTrash = { nav.navigate(TrashRoute(it.id, it.name)) },
                onOpenMetadataFields = { nav.navigate(MetadataFieldsRoute) },
                onOpenGroups = { nav.navigate(GroupsRoute) },
                onOpenLicenses = { nav.navigate(LicensesRoute) },
                onOpenNotifications = { nav.navigate(NotificationsRoute) },
            )
            }
        }
        composable<NotificationsRoute> {
            ReadableScreen {
                dev.finalish.ui.notifications.NotificationsScreen(
                    accountId = account,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { nav.navigate(ViewerRoute(it)) },
                    onOpenShares = { id, name -> nav.navigate(SharesRoute(id, name)) },
                )
            }
        }
        composable<FolderRoute> { entry ->
            val r = entry.toRoute<FolderRoute>()
            androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
            FolderScreen(
                accountId = account,
                projectId = r.projectId,
                rootFolderId = r.rootFolderId,
                folderId = r.folderId,
                title = r.title,
                onBack = { nav.popBackStack() },
                onOpenFolder = { openAsset(it, r.projectId, r.rootFolderId, projectName = r.projectName.ifBlank { if (r.isProjectRoot) r.title else "" }) },
                onOpenAsset = { openAsset(it, r.projectId, r.rootFolderId) },
                isProjectRoot = r.isProjectRoot,
                focusId = r.focusId,
                projectName = r.projectName.ifBlank { if (r.isProjectRoot) r.title else "" },
                onOpenShares = { name -> nav.navigate(SharesRoute(r.projectId, name)) },
                onOpenMembers = { name -> nav.navigate(MembersRoute("projects", r.projectId, name)) },
                onOpenTrash = { name -> nav.navigate(TrashRoute(r.projectId, name)) },
                onOpenFolderMembers = { id, name -> nav.navigate(MembersRoute("folders", id, name)) },
                onOpenTransfers = {
                    session.showHomeTab(dev.finalish.ui.home.HomeTab.Transfers.ordinal)
                    nav.popBackStack(HomeRoute, inclusive = false)
                },
            )
            }
        }
        composable<ViewerRoute>(
            enterTransition = { scaleIn(Motion.enter(), initialScale = 0.94f) + fadeIn(Motion.enter()) },
            popExitTransition = { scaleOut(Motion.exit(), targetScale = 0.94f) + fadeOut(Motion.exit()) },
        ) { entry -> androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
            val r = entry.toRoute<ViewerRoute>()
            val context = androidx.compose.ui.platform.LocalContext.current
            // Images opened from a folder: swipe left/right through the folder's other images.
            val images = remember(r.assetId, r.folderId) {
                val container = dev.finalish.FinalishApp.container(context)
                r.folderId?.takeIf { r.type == "file" && !r.offline }?.let { folder ->
                    container.repository.cachedChildren(account, folder, container.settings.settings.value.sort)
                        ?.filter { it.isFile && it.kind == dev.finalish.core.model.MediaKind.Image }
                }?.takeIf { list -> list.size > 1 && list.any { it.id == r.assetId } }
            }
            val openFolder: (String, String, String, String, String?) -> Unit = { projectId, rootId, folderId, title, focusId ->
                nav.navigate(FolderRoute(projectId, rootId, folderId, title, isProjectRoot = folderId == rootId, focusId = focusId))
            }
            if (images != null) {
                // One viewer for the whole set: swiping slides only the picture (see MediaArea).
                ViewerScreen(
                    accountId = account,
                    assetId = r.assetId,
                    type = "file",
                    onBack = { nav.popBackStack() },
                    onCompare = { a, b -> nav.navigate(CompareRoute(a.id, b.id)) },
                    onOpenFolder = openFolder,
                    siblings = images,
                )
            } else {
                ViewerScreen(
                    accountId = account,
                    assetId = r.assetId,
                    type = r.type,
                    onBack = { nav.popBackStack() },
                    onCompare = { a, b -> nav.navigate(CompareRoute(a.id, b.id)) },
                    onOpenFolder = openFolder,
                )
            }
        } }
        composable<CompareRoute> { entry ->
            val r = entry.toRoute<CompareRoute>()
            CompareScreen(account, r.leftId, r.rightId, onBack = { nav.popBackStack() })
        }
        composable<MembersRoute> { entry ->
            val r = entry.toRoute<MembersRoute>()
            ReadableScreen { dev.finalish.ui.members.MembersScreen(account, r.scope, r.id, r.title, onBack = { nav.popBackStack() }) }
        }
        composable<LicensesRoute> {
            ReadableScreen { dev.finalish.ui.settings.LicensesScreen(onBack = { nav.popBackStack() }) }
        }
        composable<MetadataFieldsRoute> {
            ReadableScreen { dev.finalish.ui.settings.MetadataFieldsScreen(account, state.account?.displayName, onBack = { nav.popBackStack() }) }
        }
        composable<GroupsRoute> {
            ReadableScreen {
                dev.finalish.ui.members.GroupsScreen(
                    account, state.account?.displayName, onBack = { nav.popBackStack() },
                    onOpenGroup = { g -> nav.navigate(GroupRoute(g.id, g.name, g.emoji, g.memberCount, g.source)) },
                )
            }
        }
        composable<GroupRoute> { entry ->
            val r = entry.toRoute<GroupRoute>()
            ReadableScreen {
                dev.finalish.ui.members.GroupScreen(
                    account,
                    dev.finalish.core.model.UserGroup(r.id, r.name, r.emoji, r.memberCount, r.source),
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<TrashRoute> { entry ->
            val r = entry.toRoute<TrashRoute>()
            ReadableScreen { dev.finalish.ui.browser.TrashScreen(account, r.projectId, r.projectName, onBack = { nav.popBackStack() }) }
        }
        composable<SharesRoute> { entry ->
            val r = entry.toRoute<SharesRoute>()
            ReadableScreen { SharesScreen(account, r.projectId, r.projectName, onBack = { nav.popBackStack() }) }
        }
        composable<FeedbackRoute> { entry ->
            val r = entry.toRoute<FeedbackRoute>()
            androidx.compose.runtime.DisposableEffect(Unit) {
                dev.finalish.core.feedback.FeedbackLauncher.open = true
                onDispose { dev.finalish.core.feedback.FeedbackLauncher.open = false }
            }
            ReadableScreen {
                dev.finalish.ui.feedback.FeedbackScreen(
                    runCatching { dev.finalish.core.feedback.FeedbackKind.valueOf(r.kind) }.getOrDefault(dev.finalish.core.feedback.FeedbackKind.Bug),
                    state.accountId,
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<CollectionsRoute> { entry ->
            val r = entry.toRoute<CollectionsRoute>()
            val collectionFallback = androidx.compose.ui.res.stringResource(dev.finalish.R.string.shares_collection)
            ReadableScreen {
                CollectionsScreen(
                    account, r.projectId, r.projectName,
                    onBack = { nav.popBackStack() },
                    onOpen = { c ->
                        c.rootFolderId?.let { nav.navigate(FolderRoute(r.projectId, it, it, c.name ?: collectionFallback)) }
                    },
                )
            }
        }
    }
    }
    }

    // "Share to Final(ish)" opens over whatever screen is showing, not only on Home.
    val shareAccount = state.accountId
    if (uploads.isNotEmpty() && signedIn && shareAccount != null) {
        val shareContainer = dev.finalish.FinalishApp.container(linkContext)
        val projects by androidx.compose.runtime.produceState(emptyList<dev.finalish.core.model.Project>(), shareAccount) {
            shareContainer.repository.workspacesWithProjects(shareAccount).collect { r -> value = r.data.orEmpty().flatMap { it.projects } }
        }
        dev.finalish.ui.home.ShareInFlow(
            accountId = shareAccount,
            uris = uploads,
            source = session.incomingSource,
            projects = projects,
            onDone = session::consumeUploads,
        )
    }
}

/** Route arguments safe to put in a report's breadcrumbs besides ids: flags and numbers. */
private val breadcrumbScalar = Regex("true|false|-?\\d+")
