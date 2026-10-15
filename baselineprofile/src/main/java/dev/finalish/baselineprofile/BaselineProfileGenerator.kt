package dev.finalish.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code paths of the hot journeys (cold start, projects, folder grid,
 * opening a video) so they ship pre-compiled: faster startup and smoother first
 * scrolls/animations.
 *
 * Run on a connected device that is signed in to Frame.io:
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile
 * Signed out, it still records the startup path.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "dev.finalish", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        // Projects home: wait for content, then open the first project card.
        if (!device.wait(Until.hasObject(By.textContains(" ago")), 15_000)) return@collect // signed out
        // Open the first project card (its "… ago · size" subtitle is part of the card).
        val project = device.findObject(By.textContains(" ago").clickable(false)) ?: return@collect
        device.click(project.visibleCenter.x, project.visibleBounds.top - 120)
        device.waitForIdle()
        device.waitForIdle()

        // Folder: let thumbnails load, scroll the grid both ways.
        device.wait(Until.hasObject(By.textContains("asset")), 10_000)
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }

        // Viewer: open a video, let it play a moment, come back.
        device.findObject(By.textContains(".mp4"))?.let { video ->
            video.click()
            device.wait(Until.hasObject(By.text("Comments")), 8_000)
            Thread.sleep(3_000)
            device.pressBack()
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }
}
