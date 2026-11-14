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

/**
 * True while the player intends to be producing sound: it is set to play and is
 * either rendering or filling its buffer.
 *
 * This is the screen-on predicate, and it is deliberately *not* just
 * [rememberIsPlaying]: a video that stalls to buffer mid-play still has someone
 * watching the screen, and letting the screen time out through a spinner would
 * be the bug this exists to fix. It is narrower in every other direction, which
 * is the part that matters — a paused, ended or idle player clears it, and a
 * [null] player (which is what the screen passes while a TV owns playback)
 * clears it too, so casting never holds the phone's screen on.
 */
@Composable
fun rememberPlayingIntent(player: Player?): Boolean {
    var intent by remember(player) {
        mutableStateOf(player?.hasPlayingIntent() == true)
    }

    DisposableEffect(player) {
        if (player == null) {
            intent = false
            return@DisposableEffect onDispose { }
        }
        intent = player.hasPlayingIntent()
        val listener = object : Player.Listener {
            // Any of the three can flip the answer on its own: play/pause moves
            // `playWhenReady`, the first frame moves the playback state off
            // BUFFERING, and a buffered renderer reports `isPlaying`. Recompute
            // from the player each time rather than trying to track the
            // combination here.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                intent = player.hasPlayingIntent()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                intent = player.hasPlayingIntent()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                intent = player.hasPlayingIntent()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    return intent
}

private fun Player.hasPlayingIntent(): Boolean =
    playWhenReady && (isPlaying || playbackState == Player.STATE_BUFFERING)

/**
 * True while the player sits at the end of its media.
 *
 * The end screen is driven from this rather than from the media item, so it
 * appears on the finished frame and clears the moment the next item is
 * prepared, replayed or seeked away from the end.
 */
@Composable
fun rememberPlaybackEnded(player: Player?): Boolean {
    var ended by remember(player) { mutableStateOf(false) }

    DisposableEffect(player) {
        if (player == null) {
            ended = false
            return@DisposableEffect onDispose { }
        }
        ended = player.playbackState == Player.STATE_ENDED
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    return ended
}

/** Polls the player position for caption timing and the shorts progress bar. */
@Composable
fun rememberPlayerPosition(player: Player?, intervalMs: Long = 250L): Long {
    // No player: return a constant and start no polling loop. The old code
    // ticked twice a second writing zero while the screen loaded or errored.
    if (player == null) return 0L
    var position by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition
            delay(intervalMs)
        }
    }
    return position
}

@Composable
fun rememberPlayerDuration(player: Player?): Long {
    if (player == null) return 0L
    var duration by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            // Duration is unknown until the media is prepared, so keep the last
            // known value instead of flashing the progress bar to zero.
            val known = player.duration.takeIf { it > 0 }
            if (known != null) duration = known
            delay(500)
        }
    }
    return duration
}
