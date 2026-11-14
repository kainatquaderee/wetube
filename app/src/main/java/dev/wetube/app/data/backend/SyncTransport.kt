package dev.wetube.app.data.backend

import kotlinx.serialization.json.JsonObject

/**
 * The outcome of one InnerTube POST, in the two shapes sync cares about.
 *
 * The point of this type is that it has no third case: the transport either got
 * an HTTP answer (any status, including 4xx/5xx) or it did not. That split is
 * what lets [InnerTubeSync] classify retry policy without ever seeing an
 * exception, and it keeps a socket failure from being silently confused with a
 * server that answered "no".
 */
internal sealed interface SyncReply {

    /** An HTTP response, for any status code; [body] is empty when there was none. */
    data class Http(val code: Int, val body: String) : SyncReply

    /** No HTTP answer at all: socket, DNS or timeout. [reason] is display-only. */
    data class Unreachable(val reason: String) : SyncReply
}

/**
 * The one seam between sync and the network; it must never throw.
 *
 * [InnerTubeSync] is deliberately free of session plumbing, so it can be unit
 * tested with canned replies and no account. Everything a real call needs that
 * is *not* part of the request semantics — the `context` envelope, the auth
 * headers, the visitor id, the endpoint URL — lives behind this interface. A
 * fake implementation is then a plain object that returns scripted [SyncReply]s
 * and records what it was asked, which is exactly how the tests drive the sync
 * class without a socket or a `Context`.
 *
 * [extra] is the non-`context` members of the request body (for example
 * `browseId`, `channelIds`); the transport is responsible for wrapping them in
 * the `context` envelope and signing the request.
 *
 * A transport that hits an [java.io.IOException] maps it to
 * [SyncReply.Unreachable] rather than letting it escape, so the "never throws"
 * contract holds even for the real HTTP implementation.
 */
internal fun interface SyncTransport {
    suspend fun post(path: String, extra: JsonObject): SyncReply
}
