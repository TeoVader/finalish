package dev.finalish.core.feedback

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.finalish.FinalishApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Opens the reporter from anywhere, optionally snapshotting the current screen first. */
object FeedbackLauncher {
    private val _requests = MutableSharedFlow<FeedbackKind>(extraBufferCapacity = 1)
    val requests = _requests.asSharedFlow()

    /** True while the reporter is on screen, so a shake doesn't open it twice. */
    @Volatile var open = false

    /** The last report was opened by shaking the phone (the screen says so, and how to turn it off). */
    @Volatile var viaShake = false
        private set

    suspend fun launch(activity: Activity?, kind: FeedbackKind, capture: Boolean, shake: Boolean = false) {
        if (open) return
        viaShake = shake
        val store = FinalishApp.container((activity ?: return).applicationContext).feedback
        val shot = if (capture) {
            delay(250) // let menus/sheets finish closing
            ScreenCapture.capture(activity)
        } else null
        withContext(Dispatchers.IO) {
            if (shot != null) store.draftScreenshot.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            else store.draftScreenshot.delete()
        }
        _requests.tryEmit(kind)
    }
}

/** Shake the phone (two firm shakes) to report a bug. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var lastPeak = 0L
    private var peaks = 0
    private var lastTrigger = 0L

    fun start() {
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm?.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        if (g < 2.6f) return
        val now = System.currentTimeMillis()
        if (now - lastPeak > 1_000) peaks = 0
        if (now - lastPeak > 150) { peaks++; lastPeak = now }
        if (peaks >= 2 && now - lastTrigger > 3_000) {
            lastTrigger = now
            peaks = 0
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
