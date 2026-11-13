package dev.wetube.app.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
    )

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
    fun play(item: MediaItem, speed: Float = 1f, startAtMs: Long? = null) {
        enqueue(Pending(item, speed, startAtMs, play = true))
    }

    /**
     * Replaces the current media *without* forcing playback to start.
     *
     * Quality and audio-only changes rebuild the same video, so they must keep
     * the viewer's play/pause state. Leaving the controller's play-when-ready
     * intent untouched does that: a playing video keeps playing and a paused
     * one stays paused.
     */
    fun load(item: MediaItem, speed: Float = 1f, startAtMs: Long? = null) {
        enqueue(Pending(item, speed, startAtMs, play = false))
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
        controller.setMediaItem(request.item)
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

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        pending = null
        _controller.value = null
    }
}
