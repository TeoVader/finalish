@file:OptIn(UnstableApi::class)

package dev.finalish

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.finalish.core.auth.AuthManager
import dev.finalish.core.data.FrameioRepository
import dev.finalish.core.data.JsonCache
import dev.finalish.core.data.SettingsStore
import dev.finalish.core.network.AppJson
import dev.finalish.core.network.FrameioApi
import dev.finalish.core.network.Network
import dev.finalish.core.transfer.TransferManager
import dev.finalish.core.transfer.TransferStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual dependency container — tiny app, no DI framework needed. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val baseHttp: OkHttpClient = Network.baseClient()
    val auth = AuthManager(context, baseHttp, AppJson)
    private val apiHttp = Network.apiClient(baseHttp, auth)
    val api: FrameioApi = Network.api(apiHttp)
    val settings = SettingsStore(context, appScope)
    val repository = FrameioRepository(api, JsonCache(context.cacheDir))
    val transfers = TransferStore(context.filesDir)
    val transferManager = TransferManager(context, transfers, settings, appScope)
    val activityWatch = dev.finalish.core.notify.ActivityWatch(context)
    /** Lazy: it reads every saved report from disk, and only the reporter and its worker need it. */
    val feedback by lazy { dev.finalish.core.feedback.FeedbackStore(context) }
    val reportRelay = dev.finalish.core.feedback.ReportRelay(context, baseHttp, BuildConfig.REPORT_RELAY_URL)

    /** Shared on-disk cache for streamed media so re-opening a clip is instant. */
    val mediaCache: SimpleCache by lazy {
        // v1 keyed streams by path only and could mix files; drop it.
        File(context.cacheDir, "media").takeIf { it.exists() }?.deleteRecursively()
        SimpleCache(
            File(context.cacheDir, "media-v2"),
            LeastRecentlyUsedCacheEvictor(768L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        )
    }

    /**
     * Empties everything the app keeps in cacheDir: API responses, images, streamed media and the
     * viewer's downloads (PDF, Office, scrub proxies, waveforms). Blocking: call it off the main thread,
     * with no viewer open (Settings, sign out); a file deleted under an open reader stays readable anyway.
     */
    fun clearCaches() {
        repository.clearCache()
        SingletonImageLoader.get(context).let { loader ->
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        // Through SimpleCache, not by deleting its folder: it keeps an index and holds a lock on it.
        runCatching { mediaCache.keys.toList().forEach { mediaCache.removeResource(it) } }
        for (dir in listOf("pdf", "office", "scrub", "waveforms")) runCatching { File(context.cacheDir, dir).deleteRecursively() }
    }
}

class FinalishApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.finalish.core.util.AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        dev.finalish.core.util.AppLocale.appContext = this
        dev.finalish.core.util.AppLog.installCrashRecorder(this)
        container = AppContainer(this)
        container.transferManager.scheduleCommentSync()
        container.transferManager.reconcileUploads()
        // Any end of a session (also an expired one) forgets the cached user and data,
        // so the next Adobe ID never sees the previous one's.
        container.appScope.launch {
            var signedIn = container.auth.state.value is dev.finalish.core.auth.AuthState.SignedIn
            container.auth.state.collect { state ->
                val now = state is dev.finalish.core.auth.AuthState.SignedIn
                if (signedIn && !now) container.repository.clearCache()
                signedIn = now
            }
        }
        // Keep the background activity check in line with the setting.
        container.appScope.launch {
            container.settings.settings.map { it.notifications }.distinctUntilChanged().collect { on ->
                dev.finalish.core.notify.ActivityWatch.schedule(this@FinalishApp, on)
            }
        }
        // "Matching app icon": the launcher alias is switched only once the app leaves the screen.
        // Disabling the alias that started the open task closes the app (seen on Xiaomi, Android 15).
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                container.appScope.launch {
                    val s = container.settings.current()
                    dev.finalish.ui.theme.AppIcon.apply(this@FinalishApp, s.matchIcon, s.accent, s.dynamicAccent)
                }
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.baseHttp }))
                // Local video frames (upload thumbnails in Transfers).
                add(coil3.video.VideoFrameDecoder.Factory())
                add(coil3.svg.SvgDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images"))
                    .maxSizeBytes(384L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()

    companion object {
        fun container(context: Context): AppContainer =
            (context.applicationContext as FinalishApp).container
    }
}
