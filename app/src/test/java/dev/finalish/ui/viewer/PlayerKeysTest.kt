package dev.finalish.ui.viewer

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun mapsTheWebPlayerKeys() {
        assertEquals(PlayerKey.TogglePlay, playerKeyFor(Key.Spacebar, shift = false))
        assertEquals(PlayerKey.Back, playerKeyFor(Key.J, shift = false))
        assertEquals(PlayerKey.Pause, playerKeyFor(Key.K, shift = false))
        assertEquals(PlayerKey.Forward, playerKeyFor(Key.L, shift = false))
        assertEquals(PlayerKey.StepBack, playerKeyFor(Key.DirectionLeft, shift = false))
        assertEquals(PlayerKey.StepForward, playerKeyFor(Key.DirectionRight, shift = false))
        assertEquals(PlayerKey.JumpBack, playerKeyFor(Key.DirectionLeft, shift = true))
        assertEquals(PlayerKey.JumpForward, playerKeyFor(Key.DirectionRight, shift = true))
        assertEquals(PlayerKey.Fullscreen, playerKeyFor(Key.F, shift = false))
    }

    @Test
    fun otherKeysStayWithTheApp() {
        assertNull(playerKeyFor(Key.A, shift = false))
        assertNull(playerKeyFor(Key.Enter, shift = false))
    }

    @Test
    fun lStartsAtThePickedSpeedThenDoublesUpToFour() {
        val s = Shuttle()
        assertEquals(1.5f, s.forward(playing = false, speed = 1.5f))
        assertEquals(2f, s.forward(playing = true, speed = 1f))
        assertEquals(4f, s.forward(playing = true, speed = 2f))
        assertEquals(4f, s.forward(playing = true, speed = 4f))
    }

    @Test
    fun stopGoesBackToTheSpeedBeforeTheShuttle() {
        val s = Shuttle()
        s.forward(playing = true, speed = 1.25f)
        s.forward(playing = true, speed = 2.5f)
        assertEquals(1.25f, s.stop())
        assertNull(s.stop())
    }

    @Test
    fun lAfterAPauseMidShuttlePlaysAtThePickedSpeed() {
        val s = Shuttle()
        s.forward(playing = true, speed = 1f)
        assertEquals(1f, s.forward(playing = false, speed = 4f))
        assertNull(s.stop())
    }
}
