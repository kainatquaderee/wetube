package dev.wetube.app.player.remote

import dev.wetube.app.data.model.StreamFormat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Where a cast session is right now.
 *
 * [Active] is polled, not pushed: a renderer has no event channel we can rely
 * on, so the manager asks every second. [transitioning] exists so the UI can
 * show a spinner without mistaking a buffering TV for a paused one, which is
 * also why `paused` is carried separately instead of being folded into it.
 */
sealed interface RemoteSessionState {

    /** Nothing is cast. The phone owns playback (or nothing is playing). */
    data object Idle : RemoteSessionState

    /** A device was chosen and the handoff is in flight. */
    data class Connecting(val target: RemoteTarget) : RemoteSessionState

    /** The renderer accepted the media and is being polled. */
    data class Active(
        val target: RemoteTarget,
        val positionMs: Long,
        val durationMs: Long,
        val paused: Boolean,
        val transitioning: Boolean,
        val volume: Float?,
    ) : RemoteSessionState

    /** The session ended badly; [target] is `null` only when none was chosen. */
    data class Failed(val target: RemoteTarget?, val failure: RemoteFailure) : RemoteSessionState
}

/**
 * Owns one cast session: the connect handshake, the poll loop, the commands the
 * UI sends and the stream-death watchdog.
 *
 * A single [Mutex] serialises connect, disconnect, polling and user commands,
 * because a renderer is a single stateful endpoint and two overlapping SOAP
 * conversations would let a stale Play land after a Pause. Discovery is not
 * part of that lock: it is a separate socket and the sheet keeps scanning while
 * a session is active.
 *
 * The class is deliberately indifferent to the transport. It receives a
 * [RemoteRenderer] factory and a way to re-resolve a URL ([resolveProgressive]),
 * so nothing here imports UPnP, Media3 or a backend.
 */
class RemotePlaybackManager(
    /**
     * Exposed rather than hidden: the output router forwards [RemoteDiscovery]
     * flows to the picker, and discovery does not belong in this lock.
     */
    val discovery: RemoteDiscovery,
    /**
     * Re-resolves a fresh progressive URL for a video when a renderer lost the
     * one it was playing. Returns `null` when the backend now has nothing
     * cast-eligible, which ends the session honestly instead of retrying an
     * expired URL.
     */
    private val resolveProgressive: suspend (String) -> StreamFormat?,
    /** Owns the poll job; the caller keeps it alive as long as the app is. */
    private val scope: CoroutineScope,
    private val http: LanHttp = RawSocketLanHttp(),
    private val rendererFor: (RemoteTarget) -> RemoteRenderer = { DlnaRenderer(it, http) },
    /**
     * Monotonic wall clock in milliseconds, used only as a difference. Injectable
     * so a starved poll gap can be simulated in tests; the default cannot be
     * moved by the user changing the device clock, which is why it is
     * [System.nanoTime] rather than `currentTimeMillis`.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private val _state = MutableStateFlow<RemoteSessionState>(RemoteSessionState.Idle)
    val state: StateFlow<RemoteSessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var session: Session? = null
    private var pollJob: Job? = null

    /** Everything learned about one connection: all fields guarded by [mutex]. */
    private class Session(
        val target: RemoteTarget,
        val renderer: RemoteRenderer,
        var media: RemoteMedia,
    ) {
        var positionMs: Long = 0
        var durationMs: Long = 0
        var lastGoodPositionMs: Long = 0
        var seenPosition: Boolean = false
        var paused: Boolean = false
        var transitioning: Boolean = true
        var volume: Float? = null
        var ticks: Int = 0
        var pollFailures: Int = 0
        var retries: Int = 0
    }

    /**
     * Hands [media] to [target] and starts polling.
     *
     * The sequence is fixed and short: refine capabilities, set the URI, seek
     * only when the handoff is far enough in to be worth a round trip, then
     * play. Anything before `play` may be refused by the renderer, and the
     * refusal is already a [RemoteFailure] by the time it gets here.
     *
     * Returns `true` only when the renderer accepted the handoff; on `false`
     * [state] is [RemoteSessionState.Failed] and the caller must leave playback
     * on the phone untouched.
     */
    suspend fun connect(
        target: RemoteTarget,
        media: RemoteMedia,
        startAtMs: Long = 0L,
    ): Boolean = mutex.withLock {
        endSessionLocked()

        _state.value = RemoteSessionState.Connecting(target)
        val renderer = rendererFor(target)

        val refined = try {
            withTimeout(CONNECT_BUDGET_MS) {
                val caps = renderer.probe(target.capabilities)
                renderer.load(media)
                if (startAtMs > SEEK_THRESHOLD_MS) renderer.seekTo(startAtMs)
                renderer.play()
                caps
            }
        } catch (timeout: TimeoutCancellationException) {
            failLocked(target, RemoteFailure.Unreachable(target.address), renderer)
            return@withLock false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: RemoteCallException) {
            failLocked(target, failure.failure, renderer)
            return@withLock false
        } catch (failure: Exception) {
            failLocked(target, RemoteFailure.Unreachable(target.address), renderer)
            return@withLock false
        }

        // The probe is advisory, but the refined capabilities are what the UI
        // should offer, so keep them on the target it reads back.
        val activeTarget = target.copy(capabilities = refined)
        val opened = Session(activeTarget, renderer, media).apply {
            positionMs = startAtMs.coerceAtLeast(0)
            lastGoodPositionMs = positionMs
            seenPosition = positionMs > 0
            // Read after the handshake, outside the connect budget: an unknown
            // volume must never turn a successful cast into a timeout.
            volume = ignoringFailure { renderer.volume() }
        }
        session = opened
        publishLocked(opened)
        startPollingLocked()
        true
    }

    /**
     * Stops the renderer and returns where it was, in milliseconds.
     *
     * The last position is read once more before the stop so a disconnect while
     * the phone was asleep still resumes near the right place. Every failure is
     * swallowed: the user always gets the phone back, even from a dead TV.
     */
    suspend fun disconnect(): Long {
        pollJob?.cancel()
        pollJob = null

        return mutex.withLock {
            val open = session
            session = null
            _state.value = RemoteSessionState.Idle
            if (open == null) return@withLock 0L

            ignoringFailure { open.renderer.position() }?.let { fresh ->
                if (fresh.positionMs > 0) open.positionMs = fresh.positionMs
            }
            ignoringFailure { open.renderer.stop() }
            open.positionMs
        }
    }

    suspend fun play(): Boolean = command { open ->
        open.renderer.play()
        open.paused = false
        open.transitioning = true
    }

    suspend fun pause(): Boolean = command { open ->
        open.renderer.pause()
        open.paused = true
        open.transitioning = false
    }

    suspend fun seekTo(positionMs: Long): Boolean = command { open ->
        val target = positionMs.coerceAtLeast(0)
        open.renderer.seekTo(target)
        open.positionMs = target
        if (target > 0) {
            open.seenPosition = true
            open.lastGoodPositionMs = target
        }
    }

    suspend fun setVolume(level: Float): Boolean = command { open ->
        val normalized = level.coerceIn(0f, 1f)
        open.renderer.setVolume(normalized)
        open.volume = normalized
    }

    // -- poll loop ----------------------------------------------------------

    private fun startPollingLocked() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastTickAt = nowMs()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val tickAt = nowMs()
                // A gap far wider than one interval means this loop was starved:
                // the process was frozen (screen off, Doze) or the device stalled
                // for long enough that several ticks went unobserved. Whatever
                // the renderer did in that window is unknown, so its last
                // published state cannot be trusted as current. Force a transport
                // read on this tick instead of waiting for the next scheduled one,
                // so a stream that died in the gap is recovered or surfaced as
                // TransportLost rather than left looking live.
                val starved = tickAt - lastTickAt > STARVED_GAP_MS
                lastTickAt = tickAt
                val keepGoing = try {
                    mutex.withLock {
                        val open = session
                        if (open == null) false else pollTickLocked(open, forceTransport = starved)
                    }
                } catch (cancel: CancellationException) {
                    // Cooperative shutdown (disconnect, a new connect) is not a
                    // device failure.
                    throw cancel
                } catch (failure: Exception) {
                    // A poll must never die silently. An unexpected throw from
                    // anywhere else in the tick would otherwise leave the UI
                    // showing a live session over a renderer nobody is driving
                    // any more, with the TV still playing. Turn it into an
                    // honest end-of-session instead, under NonCancellable so the
                    // failure lands even while this job is unwinding.
                    withContext(NonCancellable) {
                        mutex.withLock {
                            val open = session
                            if (open != null) {
                                failLocked(
                                    target = open.target,
                                    failure = RemoteFailure.TransportLost(open.target.friendlyName),
                                    guard = open,
                                )
                            }
                        }
                    }
                    false
                }
                if (!keepGoing) return@launch
            }
        }
    }

    /**
     * One poll: position every tick, transport every third.
     *
     * Position is cheap and is what the scrubber shows; transport is the part
     * that decides paused/playing/stopped, and reading it every tick would
     * triple the control traffic for no visible benefit.
     *
     * [forceTransport] reads transport on this tick regardless of the every-third
     * cadence; the poll loop sets it after a starved gap, when only transport can
     * tell whether the unobserved renderer is still ours.
     *
     * Returns `false` when the session ended, so the caller can stop looping.
     */
    private suspend fun pollTickLocked(open: Session, forceTransport: Boolean = false): Boolean {
        if (session !== open) return false

        val position = try {
            open.renderer.position()
        } catch (timeout: TimeoutCancellationException) {
            return pollFailedLocked(open)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            return pollFailedLocked(open)
        }
        open.pollFailures = 0
        if (position.durationMs > 0) open.durationMs = position.durationMs
        open.positionMs = position.positionMs
        if (position.positionMs > 0) {
            open.seenPosition = true
            open.lastGoodPositionMs = position.positionMs
        }

        open.ticks++
        if (forceTransport || open.ticks % TRANSPORT_EVERY_TICKS == 0) {
            val transport = try {
                open.renderer.transportState()
            } catch (timeout: TimeoutCancellationException) {
                return pollFailedLocked(open)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                return pollFailedLocked(open)
            }
            open.pollFailures = 0
            when (transport) {
                RemoteTransport.PLAYING -> {
                    open.paused = false
                    open.transitioning = false
                }

                RemoteTransport.PAUSED -> {
                    open.paused = true
                    open.transitioning = false
                }

                // A TV that is still buffering has not paused and has not
                // failed; changing either here would fight the user's command.
                RemoteTransport.TRANSITIONING -> open.transitioning = true

                // A vendor state we do not model. Do not guess: leave paused
                // and transitioning exactly as they were.
                RemoteTransport.OTHER -> Unit

                RemoteTransport.STOPPED -> {
                    open.transitioning = false
                    if (open.seenPosition && open.positionMs == 0L) {
                        if (!recoverLocked(open)) return false
                    } else {
                        open.paused = true
                    }
                }
            }
        }

        publishLocked(open)
        return session === open
    }

    /** Counts a failed poll and gives up after [MAX_POLL_FAILURES] in a row. */
    private suspend fun pollFailedLocked(open: Session): Boolean {
        // A disconnect that raced this tick already ended the session; its
        // failure is stale and must not resurrect a Failed state.
        if (session !== open) return false
        open.pollFailures++
        if (open.pollFailures < MAX_POLL_FAILURES) return true
        failLocked(open.target, RemoteFailure.TransportLost(open.target.friendlyName), guard = open)
        return false
    }

    /**
     * The stream-death watchdog.
     *
     * A renderer that was making progress and then reports `STOPPED` at zero
     * did not reach the end of the video: the media URL expired underneath it.
     * Re-resolve the same video and reload, capped at [MAX_STREAM_RETRIES], so
     * one dead URL does not end a long cast. Once the cap is reached the session
     * fails honestly rather than looping forever.
     */
    private suspend fun recoverLocked(open: Session): Boolean {
        if (open.retries >= MAX_STREAM_RETRIES) {
            failLocked(open.target, RemoteFailure.TransportLost(open.target.friendlyName), guard = open)
            return false
        }

        val fresh = try {
            resolveProgressive(open.media.videoId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            null
        }
        if (fresh == null) {
            failLocked(open.target, RemoteFailure.StreamUnavailable, guard = open)
            return false
        }

        open.retries++
        open.media = open.media.withStream(fresh.url, fresh.mime)
        try {
            open.renderer.load(open.media)
            if (open.lastGoodPositionMs > 0) open.renderer.seekTo(open.lastGoodPositionMs)
            // A paused cast stays paused after the reload; only a session the
            // user expects to be playing is restarted.
            if (!open.paused) open.renderer.play()
        } catch (timeout: TimeoutCancellationException) {
            failLocked(open.target, RemoteFailure.Unreachable(open.target.address), guard = open)
            return false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: RemoteCallException) {
            failLocked(open.target, failure.failure, guard = open)
            return false
        } catch (failure: Exception) {
            failLocked(open.target, RemoteFailure.Unreachable(open.target.address), guard = open)
            return false
        }

        open.positionMs = open.lastGoodPositionMs
        open.seenPosition = false
        open.transitioning = !open.paused
        return true
    }

    // -- internals ----------------------------------------------------------

    /** Applies one renderer command, mapping every failure to [state]. */
    private suspend fun command(block: suspend (Session) -> Unit): Boolean = mutex.withLock {
        val open = session ?: return@withLock false
        try {
            block(open)
        } catch (timeout: TimeoutCancellationException) {
            failLocked(open.target, RemoteFailure.Unreachable(open.target.address), guard = open)
            return@withLock false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: RemoteCallException) {
            failLocked(open.target, failure.failure, guard = open)
            return@withLock false
        } catch (failure: Exception) {
            failLocked(open.target, RemoteFailure.Unreachable(open.target.address), guard = open)
            return@withLock false
        }
        publishLocked(open)
        true
    }

    private fun publishLocked(open: Session) {
        if (session !== open) return
        _state.value = RemoteSessionState.Active(
            target = open.target,
            positionMs = open.positionMs,
            durationMs = open.durationMs,
            paused = open.paused,
            transitioning = open.transitioning,
            volume = open.volume,
        )
    }

    /**
     * Ends the session as failed, and stops the renderer on the way out.
     *
     * A failure the user can read is useless if the TV keeps blaring underneath
     * it, so any renderer still attached is asked to stop. The call is best
     * effort — the reason we are here is usually that this device stopped
     * answering — and it runs under [NonCancellable] so a disconnect racing the
     * same failure cannot cut the stop short.
     *
     * [guard] is the session the caller believes is open. A concurrent
     * disconnect may already have ended it; in that case this failure is stale
     * and is dropped rather than overwriting a clean [RemoteSessionState.Idle].
     */
    private suspend fun failLocked(
        target: RemoteTarget?,
        failure: RemoteFailure,
        renderer: RemoteRenderer? = null,
        guard: Session? = null,
    ) {
        if (guard != null && session !== guard) return
        val doomed = renderer ?: guard?.renderer
        session = null
        _state.value = RemoteSessionState.Failed(target, failure)
        if (doomed != null) {
            withContext(NonCancellable) { ignoringFailure { doomed.stop() } }
        }
    }

    /**
     * Why a cast cannot start at all right now, or `null` when the network can
     * carry one.
     *
     * Delegated to discovery, which owns the network gate: this class is
     * transport-agnostic and deliberately does not know about Android
     * connectivity.
     */
    fun castBlockedReason(): RemoteFailure? = discovery.castBlockedReason()

    /**
     * Forgets the open session, stopping its renderer on the way out. Called by
     * a new connect so a stale device never keeps playing in the background.
     */
    private suspend fun endSessionLocked() {
        pollJob?.cancel()
        pollJob = null
        val old = session
        session = null
        if (old != null) ignoringFailure { old.renderer.stop() }
    }

    /**
     * Runs a renderer call whose failure should not escape. Cancellation is
     * always rethrown: a caller that went away is not a device error.
     */
    private suspend fun <T> ignoringFailure(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            null
        }

    private companion object {
        /** Position once a second; transport every third, i.e. every three. */
        const val POLL_INTERVAL_MS = 1_000L
        const val TRANSPORT_EVERY_TICKS = 3

        /** One dropped poll is a hiccup; three in a row means the device is gone. */
        const val MAX_POLL_FAILURES = 3

        /**
         * A tick this much later than the previous one was starved, not merely
         * slow: a healthy tick is bounded by two 4 s control budgets plus the 1 s
         * interval (~9 s worst case), so 20 s cannot be reached without the loop
         * actually having been stopped.
         */
        const val STARVED_GAP_MS = 20_000L

        /** A seek to the very start is noise; a real handoff position is not. */
        const val SEEK_THRESHOLD_MS = 5_000L

        /** Reloads allowed after a stream dies before the session is declared lost. */
        const val MAX_STREAM_RETRIES = 3

        /**
         * Hard bound on the whole handshake. The renderer's per-call budgets
         * (probe 3 s twice, load 6 s, transport 4 s twice) sum to 20 s, so the
         * extra margin avoids pre-empting a device that used every one of them.
         */
        const val CONNECT_BUDGET_MS = 24_000L
    }
}