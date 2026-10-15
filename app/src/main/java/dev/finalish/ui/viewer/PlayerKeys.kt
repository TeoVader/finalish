package dev.finalish.ui.viewer

import androidx.compose.ui.input.key.Key

/** What a hardware key does in the viewer (tablets, Chromebooks, a phone with a keyboard). */
enum class PlayerKey { TogglePlay, Pause, Forward, Back, StepBack, StepForward, JumpBack, JumpForward, Fullscreen, ExitFullscreen }

/**
 * Keys as in the Frame.io web player: Space plays and pauses, J/K/L shuttle, the arrows step a frame
 * (a second with Shift), F toggles full screen. Null for any other key, which stays with the app.
 */
fun playerKeyFor(key: Key, shift: Boolean): PlayerKey? = when (key) {
    Key.Spacebar -> PlayerKey.TogglePlay
    Key.K -> PlayerKey.Pause
    Key.L -> PlayerKey.Forward
    Key.J -> PlayerKey.Back
    Key.DirectionLeft -> if (shift) PlayerKey.JumpBack else PlayerKey.StepBack
    Key.DirectionRight -> if (shift) PlayerKey.JumpForward else PlayerKey.StepForward
    Key.F -> PlayerKey.Fullscreen
    Key.Escape -> PlayerKey.ExitFullscreen
    else -> null
}

/**
 * The L shuttle: the first press plays at the speed the user picked, each further press while
 * playing doubles it up to 4x, and K (or Space) goes back to that speed.
 */
class Shuttle(private val maxSpeed: Float = 4f) {
    /** The speed to come back to after a shuttle; null when not shuttling. */
    var baseSpeed: Float? = null
        private set

    /** The speed for an L press, given whether the player is playing and at what speed. */
    fun forward(playing: Boolean, speed: Float): Float {
        // From a pause, play at the speed picked before any shuttle (like an NLE).
        if (!playing) return baseSpeed?.also { baseSpeed = null } ?: speed
        if (baseSpeed == null) baseSpeed = speed
        return (speed * 2).coerceAtMost(maxSpeed)
    }

    /** The speed to restore on a pause (null: leave it as it is). */
    fun stop(): Float? = baseSpeed.also { baseSpeed = null }
}
