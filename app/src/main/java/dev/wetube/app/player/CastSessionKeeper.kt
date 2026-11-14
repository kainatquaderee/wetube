package dev.wetube.app.player

import android.content.Context
import dev.wetube.app.player.remote.RemoteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns the cast session's lifecycle into the keep-alive service's lifecycle.
 *
 * The session flow is hot, process-scoped and changes every poll tick, so the
 * observer reduces it to just the one fact the service cares about: the friendly
 * name of the device a cast is currently pointed at, or `null` when nothing is
 * being cast. `distinctUntilChanged` then means a once-a-second position update
 * never re-posts the notification, while a real transition (connect, switch
 * device, disconnect, failure) does exactly one thing.
 *
 * Collection runs on `Main.immediate` on purpose. The service is started from
 * the same frame in which the session becomes `Connecting`, when the app still
 * has a visible activity, so Android's foreground-service start restriction is
 * satisfied without an exemption.
 */
internal class CastSessionKeeper(
    private val context: Context,
    private val session: StateFlow<RemoteSessionState>,
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch(Dispatchers.Main.immediate) {
            session
                .map { state -> state.castDeviceName() }
                .distinctUntilChanged()
                .collect { device ->
                    if (device == null) {
                        CastKeepAliveService.stop(context)
                    } else {
                        // Re-starting an already running service updates its
                        // notification in place, so switching devices keeps the
                        // same service and wake lock.
                        CastKeepAliveService.start(context, device)
                    }
                }
        }
    }
}

/**
 * The device a cast is running against, or `null` when it is not.
 *
 * `Connecting` counts: the handoff is already in flight and may take several
 * seconds on a slow renderer, and that is exactly when the screen may go off.
 * `Failed` and `Idle` both mean nothing is playing on a renderer, so the
 * keep-alive must end and its wake lock be released.
 */
private fun RemoteSessionState.castDeviceName(): String? = when (this) {
    is RemoteSessionState.Connecting -> target.friendlyName
    is RemoteSessionState.Active -> target.friendlyName
    is RemoteSessionState.Failed -> null
    RemoteSessionState.Idle -> null
}