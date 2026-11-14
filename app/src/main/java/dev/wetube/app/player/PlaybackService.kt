package dev.wetube.app.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import dev.wetube.app.R
import dev.wetube.app.WeTubeApp
import dev.wetube.app.player.remote.RemoteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player. Playback survives the UI going away because the player lives
 * here, behind a MediaSession, not in a composable.
 *
 * While a renderer owns playback the local player is deliberately silent, so
 * the session's transport commands must not reach it: a lock-screen play would
 * otherwise resume audio on the phone behind a live TV. [RemoteRoutingCallback]
 * forwards them to [OutputRouter] instead and vetoes the local execution. Only
 * a seek is allowed to touch the local player — its resulting discontinuity is
 * the one place the target position is visible — and the mirror listener below
 * then sends that position to the renderer.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** Service-scoped work; nothing here outlives [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val output: OutputRouter
        get() = (application as WeTubeApp).locator.output

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                StreamSourceFactory(this, (application as WeTubeApp).locator.http),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
            }

        player = exo
        session = MediaSession.Builder(this, exo)
            .setCallback(RemoteRoutingCallback())
            .build()

        // A controller's absolute seek still lands on the local player (only
        // that way do we learn the requested position), but while casting the
        // sound must move on the renderer. Mirroring here also covers the
        // notification's scrubber, which is an external controller like any
        // other. The load that ends a cast also seeks, so it is excluded by
        // requiring an open session.
        exo.addListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                    if (!output.isRemote) return
                    if (output.session.value !is RemoteSessionState.Active) return
                    scope.launch { output.seekTo(newPosition.positionMs) }
                }
            },
        )

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.notification_channel_name)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification) }

        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A cast is driven by a process-scoped poll loop. Tearing the service
        // down here would take the notification and the only visible cast
        // controls with it while the TV is still playing.
        if (output.isRemote) return
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Routes session transport to the renderer while casting.
     *
     * `onPlayerCommandRequest` is the only hook Media3 1.5 exposes for this
     * (there is no `onPlay`/`onPause`/`onStop` on `MediaSession.Callback`).
     * Returning [SessionResult.RESULT_SUCCESS] lets the local player execute the
     * command; any other value vetoes it and is reported to the controller.
     *
     * Play and pause both arrive as [Player.COMMAND_PLAY_PAUSE], so intent has
     * to be read from the renderer's current state: the command toggles.
     */
    private inner class RemoteRoutingCallback : MediaSession.Callback {
        // Deprecated in Media3 1.5 in favour of restricting commands through
        // `setAvailableCommands`, but that only hides a command: it cannot send
        // it somewhere else, which is exactly what casting needs. This remains
        // the only per-command interception hook in 1.5.1.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            @Player.Command playerCommand: Int,
        ): Int {
            if (!output.isRemote) return SessionResult.RESULT_SUCCESS

            val active = output.session.value as? RemoteSessionState.Active
            return when (playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> {
                    // Read the session inside the coroutine: two quick taps must
                    // see the first one's result rather than toggling from the
                    // same stale snapshot.
                    scope.launch {
                        val live = output.session.value as? RemoteSessionState.Active
                            ?: return@launch
                        if (live.paused) output.play() else output.pause()
                    }
                    SessionResult.RESULT_INFO_SKIPPED
                }

                Player.COMMAND_STOP -> {
                    // Stop is not a toggle, so it can never resume a paused cast.
                    scope.launch { output.pause() }
                    SessionResult.RESULT_INFO_SKIPPED
                }

                // Preparing a silent local player has no meaning while the
                // renderer owns the timeline.
                Player.COMMAND_PREPARE -> SessionResult.RESULT_INFO_SKIPPED

                // The notification's ±10 s buttons are relative, and the local
                // player's position is stale while casting, so compute them
                // from the renderer's own position instead of letting them run
                // locally and mirroring the result.
                Player.COMMAND_SEEK_BACK -> {
                    if (active != null) {
                        val step = player?.seekBackIncrement ?: 0L
                        scope.launch {
                            output.seekTo((active.positionMs - step).coerceAtLeast(0L))
                        }
                    }
                    SessionResult.RESULT_INFO_SKIPPED
                }

                Player.COMMAND_SEEK_FORWARD -> {
                    if (active != null) {
                        val step = player?.seekForwardIncrement ?: 0L
                        val limit = active.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                        scope.launch {
                            output.seekTo((active.positionMs + step).coerceAtMost(limit))
                        }
                    }
                    SessionResult.RESULT_INFO_SKIPPED
                }

                // Everything else (including an absolute seek) runs locally;
                // the discontinuity listener mirrors the seek to the renderer.
                else -> SessionResult.RESULT_SUCCESS
            }
        }
    }
}