package dev.wetube.app.player.remote

/**
 * The one seam between the DLNA renderer and the network.
 *
 * It exists so the renderer and the playback manager can be tested without a
 * socket, and so the choice of transport stays in exactly one class. The only
 * implementation is [RawSocketLanHttp]: a renderer lives on a private address,
 * and Android's cleartext policy is expressed as a URL allow-list that cannot
 * name RFC1918 ranges, so a policy-consulting stack would force the app to
 * weaken that ban. A raw socket is not covered by the policy and needs no
 * exemption.
 *
 * Both calls return the response body as text regardless of HTTP status. A UPnP
 * fault is delivered as HTTP 500 with an XML body, so turning the status into an
 * exception here would throw the fault away; [UpnpSoap.parse] distinguishes them.
 */
interface LanHttp {

    /**
     * Sends a `POST` with [body] encoded as UTF-8 and returns the response body.
     *
     * [contentType] is passed separately from [headers] because it and
     * `Content-Length` are written by the transport itself. Callers put
     * action-specific headers such as `SOAPACTION` in [headers].
     */
    suspend fun post(
        url: String,
        contentType: String,
        headers: Map<String, String>,
        body: String,
    ): String

    /** Sends a `GET` and returns the response body. */
    suspend fun get(url: String): String
}