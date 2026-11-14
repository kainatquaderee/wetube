package dev.wetube.app.player.remote

/** A renderer's position and duration, both in milliseconds. */
data class RemotePosition(val positionMs: Long, val durationMs: Long)

/**
 * How the renderer says its transport is doing.
 *
 * The five states exist because the poll loop must not mistake a device that is
 * still buffering for one that has stopped, and must not read a "no media yet"
 * answer as a user request to pause. `OTHER` is every vendor-specific state we
 * do not model; it is reported as-is and acted on like [TRANSITIONING], i.e. it
 * changes nothing by itself.
 */
enum class RemoteTransport { PLAYING, PAUSED, STOPPED, TRANSITIONING, OTHER }

/**
 * A failed renderer call, carrying the failure the UI is meant to show.
 *
 * Renderers throw this instead of leaking an `IOException` or a SOAP detail, so
 * the playback manager has exactly one thing to catch and the taxonomy in
 * [RemoteFailure] stays the single source of user-visible text.
 */
class RemoteCallException(
    val failure: RemoteFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

/**
 * The control surface every cast family implements.
 *
 * This is the seam for a future receiver of our own: a `CUSTOM` protocol would
 * implement this interface and everything above it — the session state machine,
 * the output router, the UI — stays untouched. Nothing here mentions UPnP, HTTP
 * or any wire format.
 *
 * Implementations must not be reusable after a failed [load]; the manager builds
 * a fresh renderer for every connection.
 */
interface RemoteRenderer {

    /**
     * Refines what this renderer can do, from what the device itself advertises.
     *
     * Advisory by contract: [caps] is the safe answer, and an implementation that
     * cannot prove otherwise must return it unchanged rather than throw. A device
     * that lies here still answers the call with a fault at call time.
     */
    suspend fun probe(caps: RemoteCapabilities): RemoteCapabilities

    /** Points the renderer at [media] without starting it. */
    suspend fun load(media: RemoteMedia)

    suspend fun play()

    suspend fun pause()

    suspend fun stop()

    /** Absolute seek for the currently loaded media. */
    suspend fun seekTo(positionMs: Long)

    suspend fun position(): RemotePosition

    suspend fun transportState(): RemoteTransport

    /** Volume in `0f..1f`, or `null` when this renderer has no such control. */
    suspend fun volume(): Float?

    /** [level] is clamped to `0f..1f` by the implementation. */
    suspend fun setVolume(level: Float)
}
