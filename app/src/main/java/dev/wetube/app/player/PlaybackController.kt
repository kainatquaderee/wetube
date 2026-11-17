package dev.wetube.app.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.wetube.app.data.model.AudioStream
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin bridge between Compose and the playback service.
 *
 * The controller is a proxy: it survives activity recreation, and every command
 * is forwarded to [PlaybackService] over a MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context) {

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null

    /**
     * The audio tracks of whatever is in the player, plus the viewer's own pick.
     *
     * It lives here rather than in the player ViewModel because the pick has to
     * outlive the screen: the player does, so leaving and reopening the same
     * video must not silently change the language again. The session drops the
     * pick itself as soon as another video is loaded.
     */
    val audio = AudioTrackSession()

    /** Mirrors the player's tracks into [audio]; removed again by [release]. */
    private var tracksListener: Player.Listener? = null

    /**
     * A request made before the controller finished connecting. Starting a
     * video straight after launch used to be a no-op because of this race.
     */
    private var pending: Pending? = null

    private data class Pending(
        val item: MediaItem,
        val speed: Float,
        val startAtMs: Long?,
        /** True only for an explicit user request to start playback. */
        val play: Boolean,
        /** The dubs the resolution offered for [item]; empty for single-audio media. */
        val streams: List<AudioStream> = emptyList(),
    )

    /**
     * The item in the player and the dubs it was resolved with.
     *
     * Kept because a dub is a different URL, not a different track: the only way
     * to hear one is to build the item again, and the video half of it lives
     * here. `currentMediaItem` cannot be used instead — the session does not have
     * to send a local URI back over the boundary.
     */
    private var current: MediaItem? = null
    private var currentStreams: List<AudioStream> = emptyList()

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val request = MediaController.Builder(context, token).buildAsync()
        future = request
        request.addListener(
            {
                val controller = runCatching { request.get() }.getOrNull()
                _controller.value = controller
                if (controller != null) {
                    val listener = object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            audio.onTracksChanged(controller, tracks)
                        }
                    }
                    controller.addListener(listener)
                    tracksListener = listener
                    pending?.let { queued ->
                        pending = null
                        start(controller, queued)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Starts [item] and explicitly begins playback. Used when the viewer opens a video. */
    fun play(
        item: MediaItem,
        speed: Float = 1f,
        startAtMs: Long? = null,
        streams: List<AudioStream> = emptyList(),
    ) {
        enqueue(Pending(item, speed, startAtMs, play = true, streams = streams))
    }

    /**
     * Replaces the current media *without* forcing playback to start.
     *
     * Quality and audio-only changes rebuild the same video, so they must keep
     * the viewer's play/pause state. Leaving the controller's play-when-ready
     * intent untouched does that: a playing video keeps playing and a paused
     * one stays paused.
     */
    fun load(
        item: MediaItem,
        speed: Float = 1f,
        startAtMs: Long? = null,
        streams: List<AudioStream> = emptyList(),
    ) {
        enqueue(Pending(item, speed, startAtMs, play = false, streams = streams))
    }

    private fun enqueue(request: Pending) {
        val controller = _controller.value
        if (controller == null) {
            pending = request
            connect()
            return
        }
        start(controller, request)
    }

    private fun start(controller: MediaController, request: Pending) {
        current = request.item
        currentStreams = request.streams
        controller.setMediaItem(request.item)
        // Track selection belongs to the item, not to the player's construction:
        // the same player carries every video, so the steering is re-applied here
        // and nowhere else. Before prepare, because that is what selects tracks.
        audio.onMediaItem(controller, request.item.mediaId, request.streams)
        controller.prepare()
        controller.setPlaybackSpeed(request.speed)
        request.startAtMs?.takeIf { it > 0 }?.let(controller::seekTo)
        if (request.play) controller.play()
    }

    fun seekTo(positionMs: Long) {
        _controller.value?.seekTo(positionMs)
    }

    fun pause() {
        _controller.value?.pause()
    }

    fun setSpeed(speed: Float) {
        _controller.value?.setPlaybackSpeed(speed)
    }

    /**
     * Applies the viewer's own audio pick to what is in the player.
     *
     * Goes through [audio] so the pick is remembered with the player's lifetime
     * and follows the same video through a quality change or a step-down to
     * audio only.
     */
    fun chooseAudio(option: AudioTrackOption) {
        val controller = _controller.value ?: return
        val stream = option.stream
        val rebuilt = stream?.let { dub ->
            current?.let { StreamResolver.withAudio(it, dub, currentStreams) }
        }
        audio.choose(controller, option)
        if (stream == null || rebuilt == null) return
        // A rebuild, not a new item: setMediaItem replaces what is playing, so
        // nothing plays twice, and `load` leaves play-when-ready alone, so a
        // video that was playing keeps playing and a paused one stays paused.
        // Speed and position are carried over for the same reason — they belong
        // to the viewer's session, not to the URL.
        load(
            item = rebuilt,
            speed = controller.playbackParameters.speed,
            startAtMs = controller.currentPosition,
            streams = currentStreams,
        )
    }

    fun release() {
        tracksListener?.let { listener -> _controller.value?.removeListener(listener) }
        tracksListener = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        pending = null
        _controller.value = null
    }
}
