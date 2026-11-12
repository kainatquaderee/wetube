package dev.wetube.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Playback flags that Compose can actually observe.
 *
 * Reading `player.playbackState` during composition is not observable: the
 * player is a plain object, not snapshot state, so a spinner gated on it can be
 * composed while buffering and then never receive a reason to leave. These
 * helpers keep the flags in snapshot state and update them from a listener.
 */
private data class PlaybackFlags(
    val buffering: Boolean,
    val playing: Boolean,
)

@Composable
private fun rememberPlaybackFlags(player: Player?): PlaybackFlags {
    var flags by remember(player) {
        mutableStateOf(
            PlaybackFlags(
                buffering = player?.playbackState == Player.STATE_BUFFERING,
                playing = player?.isPlaying == true,
            ),
        )
    }

    DisposableEffect(player) {
        if (player == null) {
            flags = PlaybackFlags(false, false)
            return@DisposableEffect onDispose { }
        }
        flags = PlaybackFlags(
            buffering = player.playbackState == Player.STATE_BUFFERING,
            playing = player.isPlaying,
        )
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                flags = flags.copy(buffering = playbackState == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                flags = flags.copy(playing = isPlaying)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    return flags
}

/**
 * True once playback has been buffering for [delayMs]. The delay keeps a short
 * seek from flashing a spinner on screen, like the official player.
 */
@Composable
fun rememberIsBuffering(player: Player?, delayMs: Long = 350L): Boolean {
    val flags = rememberPlaybackFlags(player)
    var shown by remember(player) { mutableStateOf(false) }

    LaunchedEffect(flags.buffering, flags.playing, delayMs) {
        if (flags.buffering && !flags.playing) {
            delay(delayMs)
            shown = true
        } else {
            shown = false
        }
    }
    return shown
}

@Composable
fun rememberIsPlaying(player: Player?): Boolean = rememberPlaybackFlags(player).playing

/** Polls the player position for caption timing and the shorts progress bar. */
@Composable
fun rememberPlayerPosition(player: Player?, intervalMs: Long = 250L): Long {
    var position by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            position = player?.currentPosition ?: 0L
            delay(intervalMs)
        }
    }
    return position
}

@Composable
fun rememberPlayerDuration(player: Player?): Long {
    var duration by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            // Duration is unknown until the media is prepared, so keep the last
            // known value instead of flashing the progress bar to zero.
            val known = player?.duration?.takeIf { it > 0 }
            if (known != null) duration = known
            delay(500)
        }
    }
    return duration
}
