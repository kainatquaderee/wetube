package dev.wetube.app.player

import androidx.media3.common.MediaItem
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.player.remote.RemoteFailure
import dev.wetube.app.player.remote.RemoteMedia
import dev.wetube.app.player.remote.RemotePlaybackManager
import dev.wetube.app.player.remote.RemoteSessionState
import dev.wetube.app.player.remote.RemoteTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where playback is happening.
 *
 * The UI and the MediaSession both need one answer to this question, and they
 * need it as a flow: the cast panel changes it, and the player screen has to
 * stop drawing a local controller the moment a TV takes over. Keeping the two
 * states in one sealed value means there is no window in which both or neither
 * look active.
 */
sealed interface PlaybackOutput {

    /** The phone is (or would be) playing. */
    data object Local : PlaybackOutput

    /** The chosen renderer owns playback; the phone stays silent. */
    data class Remote(val target: RemoteTarget) : PlaybackOutput
}

/**
 * The only place that decides whether sound comes from the phone or a TV.
 *
 * It owns nothing of its own: the local side is [PlaybackController] and the
 * remote side is [RemotePlaybackManager]. Its job is the handoff — pause local
 * only after a remote connect succeeded, and on the way back read the remote
 * position first so the phone resumes there.
 *
 * The router also forwards the discovery and session flows the device sheet
 * needs, so the UI has a single object to hold instead of reaching into two
 * layers that must stay in step.
 */
class OutputRouter(
    private val local: PlaybackController,
    private val remote: RemotePlaybackManager,
) {

    private val _output = MutableStateFlow<PlaybackOutput>(PlaybackOutput.Local)
    val output: StateFlow<PlaybackOutput> = _output.asStateFlow()

    /** The live cast session, including `Connecting` and `Failed`. */
    val session: StateFlow<RemoteSessionState> = remote.state

    /** Renderers found by the most recent sweep. */
    val renderers: StateFlow<List<RemoteTarget>> = remote.discovery.targets

    /** True while a sweep is running. */
    val scanning: StateFlow<Boolean> = remote.discovery.scanning

    /** Why the last sweep found nothing, when that is worth telling the user. */
    val discoveryFailure: StateFlow<RemoteFailure?> = remote.discovery.failure

    /** True while a renderer, not the phone, is the output. */
    val isRemote: Boolean get() = _output.value is PlaybackOutput.Remote

    /** Starts the sheet's scanning loop. Idempotent. */
    fun startDiscovery() = remote.discovery.start()

    /** Stops scanning and forgets the current results. Idempotent. */
    fun stopDiscovery() = remote.discovery.stop()

    /** One manual sweep, for pull-to-refresh. Never throws. */
    suspend fun refreshRenderers(): List<RemoteTarget> = remote.discovery.refresh()

    /**
     * Why a cast cannot start at all right now, or `null` when the network can
     * carry one.
     *
     * The cast button runs this before it resolves a stream or opens a socket,
     * so a device that is obviously unreachable — mobile data, no network, the
     * LAN permission missing — gets its honest reason at once instead of after
     * a slow, generic handshake failure.
     */
    fun castBlockedReason(): RemoteFailure? = remote.castBlockedReason()

    /**
     * Moves playback to [target], starting where the phone is unless [startAtMs]
     * overrides it.
     *
     * A quality or audio-only change re-casts the same video, so it passes the
     * renderer's own live position rather than the paused phone's stale one. A
     * fresh cast leaves [startAtMs] unset and picks up the phone's position.
     *
     * Returns `true` when the renderer accepted the handoff; the local player is
     * paused only in that case, and [output] then reports [PlaybackOutput.Remote].
     * On `false` nothing about local playback changed and [session] carries the
     * reason.
     */
    suspend fun playRemote(
        target: RemoteTarget,
        media: RemoteMedia,
        startAtMs: Long? = null,
    ): Boolean {
        val start = startAtMs ?: (local.controller.value?.currentPosition ?: 0L)
        if (!remote.connect(target, media, start)) return false
        local.pause()
        _output.value = PlaybackOutput.Remote(target)
        return true
    }

    /**
     * Stops the renderer and puts [item] back on the phone at the position the
     * TV had reached.
     *
     * The load is non-forcing, so the viewer's play/pause state is not invented
     * here: if casting was paused, the phone stays paused at that position and
     * the player's own play button resumes it. [startAtMs] overrides the
     * renderer's last position, which is how opening a different video while
     * casting avoids resuming the new video at the old one's position.
     */
    suspend fun returnLocal(item: MediaItem, speed: Float = 1f, startAtMs: Long? = null) {
        val position = remote.disconnect()
        local.load(item, speed, startAtMs = startAtMs ?: position)
        _output.value = PlaybackOutput.Local
    }

    suspend fun play(): Boolean = remote.play()

    suspend fun pause(): Boolean = remote.pause()

    suspend fun seekTo(positionMs: Long): Boolean = remote.seekTo(positionMs)

    suspend fun setVolume(level: Float): Boolean = remote.setVolume(level)
}

/**
 * The castable item for [this] video, or the caller can build it directly.
 *
 * `StreamResolver.pickProgressive` picks [format] — one muxed URL a renderer can
 * open by itself — and this only repackages the metadata a TV shows. Kept out of
 * the remote package so nothing there depends on backend models.
 */
fun VideoDetail.toRemoteMedia(format: StreamFormat, audioOnly: Boolean = false): RemoteMedia =
    RemoteMedia(
        videoId = item.id,
        url = format.url,
        mime = format.mime,
        title = item.title,
        creator = item.channelName,
        artworkUrl = item.thumbnail,
        audioOnly = audioOnly,
    )