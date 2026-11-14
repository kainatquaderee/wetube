package dev.wetube.app.player.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * Finds renderers on the user's own network.
 *
 * Discovery only ever runs while the device sheet is open: [start] when it opens,
 * [stop] when it closes. Nothing here scans in the background, and the results
 * live in memory for the session only.
 *
 * The three flows are the whole state a sheet needs. [failure] is empty while a
 * sweep is in flight or after a good one; it is set to a specific reason when a
 * sweep could not run at all (mobile data) or ran and found nobody, because an
 * empty list on its own cannot tell the user which of the two happened — and a
 * silent empty list is how "casting is broken" gets reported as "no devices".
 */
interface RemoteDiscovery {

    /** Every renderer found by the most recent completed sweep. */
    val targets: StateFlow<List<RemoteTarget>>

    /** True from the start of a sweep until it finishes. */
    val scanning: StateFlow<Boolean>

    /** Why the last sweep produced nothing, or `null`. */
    val failure: StateFlow<RemoteFailure?>

    /** Begins scanning while the sheet is open. Idempotent. */
    fun start()

    /** Cancels any running scan and forgets the current results. Idempotent. */
    fun stop()

    /**
     * Runs one sweep to completion and returns what it found.
     *
     * Cancellable, and never throws to the caller: a network or socket problem is
     * reported through [failure] and an empty (or stale) result. A cancelled
     * caller sees the usual cooperative `CancellationException`, which is not a
     * failure and leaves the flows untouched.
     */
    suspend fun refresh(): List<RemoteTarget>

    /**
     * Why a cast cannot start at all right now, or `null` when the network can
     * carry one.
     *
     * This is the cheap, synchronous preflight the cast button runs *before* it
     * resolves a stream or opens a socket: it answers "mobile data / no
     * network / blocked" immediately, so the user gets the honest reason
     * instead of a slow, generic "that device didn't answer" after a doomed
     * handshake. The default is `null` so non-network implementations (and test
     * doubles) are not forced to model connectivity.
     */
    fun castBlockedReason(): RemoteFailure? = null
}
