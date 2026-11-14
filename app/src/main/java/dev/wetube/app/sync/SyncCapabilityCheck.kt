package dev.wetube.app.sync

import dev.wetube.app.data.backend.OpResult
import dev.wetube.app.data.backend.PlaylistPrivacy
import dev.wetube.app.data.backend.SyncBackend

/** One probe's result, as display-ready data (no Android imports). */
data class CapabilityProbe(val name: String, val passed: Boolean, val detail: String)

/**
 * A read-only capability report plus an explicitly-invoked write probe.
 *
 * The account-write endpoints are the least verifiable part of sync: they are
 * hand-rolled against the InnerTube web client, which changes without notice,
 * and the only honest way to know whether they still work against a given
 * account is to call them. This class exists so that question can be answered
 * *without* the engine: [checkReads] touches nothing, and [checkWriteProbe]
 * creates and then immediately deletes one private playlist of its own, so a
 * user (or a developer in a debug build) can learn that the endpoints are live
 * before any real reconciliation is ever pushed.
 *
 * It holds no state and no Android types, so it is a plain object a unit test
 * can drive through [SyncBackend] — the same fake the engine is tested with.
 * It never throws: every failure is an [OpResult] and is mapped to a probe, so a
 * caller can always render something.
 */
class SyncCapabilityCheck(private val backend: SyncBackend) {

    /**
     * Probes the three reads a sync begins with, in order: the account's own
     * channel id, its subscriptions, and its playlists. Nothing is written.
     *
     * Each read becomes one [CapabilityProbe]; a failed [OpResult] becomes a
     * failed probe carrying the transport's reason so the user sees the actual
     * error rather than a bare "failed".
     */
    suspend fun checkReads(): List<CapabilityProbe> = listOf(
        probe(PROBE_ACCOUNT, backend.accountChannelId()) { "channel id received" },
        probe(PROBE_SUBSCRIPTIONS, backend.pullSubscriptions()) { "${it.size} subscription(s)" },
        probe(PROBE_PLAYLISTS, backend.pullPlaylists()) { "${it.size} playlist(s)" },
    )

    /**
     * Creates a private playlist named [WRITE_PROBE_TITLE] and deletes exactly
     * the id the create returned, reporting both steps.
     *
     * A failed create yields two probes: the create failure, and a delete probe
     * marked skipped — nothing is ever deleted that this call did not itself
     * create, and the returned id is the only thing a successful run deletes.
     * The playlist is private and empty, so the only trace a successful probe
     * leaves is a create/delete pair in the account's activity.
     */
    suspend fun checkWriteProbe(): List<CapabilityProbe> {
        val created = backend.createPlaylist(WRITE_PROBE_TITLE, PlaylistPrivacy.PRIVATE)
        if (created !is OpResult.Ok) {
            return listOf(
                CapabilityProbe(PROBE_CREATE_PLAYLIST, passed = false, detail = describe(created)),
                CapabilityProbe(PROBE_DELETE_PLAYLIST, passed = false, detail = SKIPPED_CREATE_FAILED),
            )
        }
        val id = created.value
        val create = CapabilityProbe(PROBE_CREATE_PLAYLIST, passed = true, detail = "created $id")
        val delete = when (val removed = backend.deletePlaylist(id)) {
            is OpResult.Ok -> CapabilityProbe(PROBE_DELETE_PLAYLIST, passed = true, detail = "deleted $id")
            else -> CapabilityProbe(PROBE_DELETE_PLAYLIST, passed = false, detail = describe(removed))
        }
        return listOf(create, delete)
    }

    /**
     * Maps one [OpResult] to a probe. [describeOk] turns a successful payload
     * into a short, display-ready detail (a count, or just "received"); the
     * failure cases all carry the transport's [reason][OpResult.Retryable.reason]
     * verbatim.
     */
    private fun <T> probe(
        name: String,
        result: OpResult<T>,
        describeOk: (T) -> String,
    ): CapabilityProbe = when (result) {
        is OpResult.Ok -> CapabilityProbe(name, passed = true, detail = describeOk(result.value))
        is OpResult.Retryable -> CapabilityProbe(name, passed = false, detail = result.reason)
        is OpResult.Terminal -> CapabilityProbe(name, passed = false, detail = result.reason)
        is OpResult.AuthRequired -> CapabilityProbe(name, passed = false, detail = result.reason)
    }

    /** The reason a failed [OpResult] carries, or a fallback for a stray `Ok`. */
    private fun describe(result: OpResult<*>): String = when (result) {
        is OpResult.Ok -> "unexpected success"
        is OpResult.Retryable -> result.reason
        is OpResult.Terminal -> result.reason
        is OpResult.AuthRequired -> result.reason
    }

    companion object {
        /** The private playlist a write probe creates and immediately deletes. */
        const val WRITE_PROBE_TITLE = "WeTube sync probe"

        const val PROBE_ACCOUNT = "Account channel"
        const val PROBE_SUBSCRIPTIONS = "Subscriptions"
        const val PROBE_PLAYLISTS = "Playlists"
        const val PROBE_CREATE_PLAYLIST = "Create private playlist"
        const val PROBE_DELETE_PLAYLIST = "Delete private playlist"

        private const val SKIPPED_CREATE_FAILED = "skipped: no playlist was created"
    }
}
