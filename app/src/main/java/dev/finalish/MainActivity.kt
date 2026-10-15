package dev.finalish

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dev.finalish.ui.SessionViewModel
import kotlinx.coroutines.launch
import dev.finalish.ui.navigation.AppNav
import dev.finalish.ui.theme.FinalishTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

class MainActivity : androidx.activity.ComponentActivity() {

    private val session: SessionViewModel by viewModels { SessionViewModel.Factory }
    private var pendingRedirect by mutableStateOf<Uri?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.finalish.core.util.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        // Ask for notifications when the first upload/download starts, where it makes sense.
        if (Build.VERSION.SDK_INT >= 33) {
            lifecycleScope.launch {
                session.container.transferManager.notificationPermissionNeeded.collect {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // Shake to report a bug (captures the current screen first).
        shake = dev.finalish.core.feedback.ShakeDetector(this) {
            if (session.container.settings.settings.value.shakeToReport) {
                lifecycleScope.launch {
                    dev.finalish.core.feedback.FeedbackLauncher.launch(this@MainActivity, dev.finalish.core.feedback.FeedbackKind.Bug, capture = true, shake = true)
                }
            }
        }

        setContent {
            val prefs by session.container.settings.settings.collectAsStateWithLifecycle()
            dev.finalish.ui.theme.ApplyThemeSettings(prefs.accent, prefs.dynamicAccent, prefs.pureBlack)
            FinalishTheme {
                AppNav(
                    session = session,
                    pendingRedirect = pendingRedirect,
                    onRedirectConsumed = { pendingRedirect = null },
                )
            }
        }
    }

    private var shake: dev.finalish.core.feedback.ShakeDetector? = null

    override fun onResume() {
        super.onResume()
        shake?.start()
    }

    override fun onPause() {
        shake?.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val auth = session.container.auth
                when {
                    auth.isRedirect(data) -> pendingRedirect = data
                    dev.finalish.core.data.FrameLinks.handles(data) -> session.openLink(data.toString())
                }
            }
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { session.offerUploads(listOf(it), sourceAppName()) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { session.offerUploads(it, sourceAppName()) }
            }
        }
    }

    /** Label of the app that shared to us; null when Android doesn't say or hides it. */
    private fun sourceAppName(): String? {
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return null
        if (pkg == packageName) return null
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    }
}
