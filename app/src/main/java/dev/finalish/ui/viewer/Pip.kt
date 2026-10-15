package dev.finalish.ui.viewer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer

private const val ACTION_PIP_TOGGLE = "dev.finalish.PIP_TOGGLE"

/** True while the activity is shown as a picture-in-picture window. */
@Composable
fun rememberInPictureInPicture(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * Lets a playing video continue in a floating window when the user leaves the app
 * (home gesture, switching apps), with a play/pause button in the window.
 */
@Composable
fun PictureInPictureEffect(
    enabled: Boolean,
    aspect: Float,
    playing: Boolean,
    sourceRect: android.graphics.Rect?,
    onTogglePlay: () -> Unit,
) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val toggle by rememberUpdatedState(onTogglePlay)
    val canAutoEnter by rememberUpdatedState(enabled)
    // Read the latest values: the pre-Android 12 leave hint below is created once, and would
    // otherwise enter PiP with the first composition's aspect (16:9 before the video size is known).
    val currentAspect by rememberUpdatedState(aspect)
    val currentPlaying by rememberUpdatedState(playing)
    val currentSource by rememberUpdatedState(sourceRect)

    fun params(): PictureInPictureParams {
        val ratio = currentAspect.coerceIn(0.42f, 2.39f)
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(playPauseAction(activity, currentPlaying)))
        currentSource?.takeIf { !it.isEmpty }?.let { b.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(canAutoEnter).setSeamlessResizeEnabled(true)
        return b.build()
    }

    // Keep the system's PiP parameters in sync (auto-enter on Android 12+).
    DisposableEffect(enabled, aspect, playing, sourceRect) {
        runCatching { activity.setPictureInPictureParams(params()) }
        onDispose {}
    }

    // Older versions: enter explicitly when the user leaves the app.
    DisposableEffect(activity) {
        val leave = Runnable {
            if (Build.VERSION.SDK_INT < 31 && canAutoEnter) runCatching { activity.enterPictureInPictureMode(params()) }
        }
        activity.addOnUserLeaveHintListener(leave)
        onDispose {
            activity.removeOnUserLeaveHintListener(leave)
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching { activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
        }
    }

    // Play/pause button inside the PiP window.
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_PIP_TOGGLE) toggle()
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { activity.unregisterReceiver(receiver) }
    }
}

private fun playPauseAction(context: Context, playing: Boolean): RemoteAction {
    val intent = PendingIntent.getBroadcast(
        context, 0,
        Intent(ACTION_PIP_TOGGLE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val icon = Icon.createWithResource(context, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    val label = context.getString(if (playing) dev.finalish.R.string.viewer_pause else dev.finalish.R.string.viewer_play)
    return RemoteAction(icon, label, label, intent)
}
