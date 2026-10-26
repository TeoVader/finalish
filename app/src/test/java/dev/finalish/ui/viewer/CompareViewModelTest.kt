package dev.finalish.ui.viewer

import dev.finalish.FinalishApp
import dev.finalish.testing.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FinalishApp::class)
class CompareViewModelTest : ViewModelTest() {
    private lateinit var vm: CompareViewModel

    @Before fun setUp() {
        vm = CompareViewModel(app, frameio.repository) { PlayerController(app) }
    }

    @Test fun `toggle play when stopped triggers play on both without seeking`() {
        assertFalse(vm.left.isPlaying)
        assertFalse(vm.right.isPlaying)
        vm.onEvent(CompareEvent.TogglePlay)
        assertTrue(vm.left.player.playWhenReady)
        assertTrue(vm.right.player.playWhenReady)
    }

    @Test fun `pause pauses both players and resets right speed`() {
        vm.right.setPlaybackSpeed(1.1f)
        assertEquals(1.1f, vm.right.speed, 0.01f)
        vm.onEvent(CompareEvent.Pause)
        assertEquals(1.0f, vm.right.speed, 0.01f)
        assertFalse(vm.left.player.playWhenReady)
        assertFalse(vm.right.player.playWhenReady)
    }
}
