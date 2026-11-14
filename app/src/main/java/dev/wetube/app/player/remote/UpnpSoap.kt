package dev.wetube.app.player.remote

import java.util.ArrayDeque
import org.w3c.dom.Element

/**
 * SOAP 1.1 envelopes for the UPnP AV actions we call, and the reader for what
 * comes back. Pure text in and text out; the transport is [LanHttp].
 *
 * Argument values are escaped before they are placed in the body. A video title
 * with an ampersand in it would otherwise produce an XML document the device
 * cannot parse, and the failure would look like a broken renderer.
 */
internal object UpnpSoap {

    /** Body parameter for every action that carries XML. */
    const val CONTENT_TYPE: String = "text/xml; charset=\"utf-8\""

    /** UPnP actions live under the service's own namespace. */
    private const val ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val ENCODING_STYLE = "http://schemas.xmlsoap.org/soap/encoding/"

    /**
     * The headers a SOAP call needs beyond `Content-Type`, `Content-Length` and
     * `Connection: close`, which the transport sets itself.
     */
    fun actionHeaders(serviceType: String, action: String): Map<String, String> =
        mapOf("SOAPACTION" to "\"$serviceType#$action\"")

    /**
     * Builds the request body for [action] with [args] in order.
     *
     * The action name goes into the envelope as the qualified element
     * `<u:Action xmlns:u="serviceType">`; the arguments are unqualified children,
     * which is what the device templates and every renderer we have seen expect.
     */
    fun envelope(
        serviceType: String,
        action: String,
        args: List<Pair<String, String>> = emptyList(),
    ): String {
        require(serviceType.isNotBlank()) { "A SOAP action needs its service type" }
        require(action.isNotBlank()) { "A SOAP action needs a name" }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
            append("<s:Envelope xmlns:s=\"").append(ENVELOPE_NAMESPACE)
            append("\" s:encodingStyle=\"").append(ENCODING_STYLE).append("\">")
            append("<s:Body>")
            append("<u:").append(action)
            append(" xmlns:u=\"").append(escape(serviceType)).append("\">")
            for ((name, value) in args) {
                append('<').append(name).append('>')
                append(escape(value))
                append("</").append(name).append('>')
            }
            append("</u:").append(action).append('>')
            append("</s:Body>")
            append("</s:Envelope>")
        }
    }

    /**
     * Escapes [value] for XML text and attribute content.
     *
     * Control characters below `0x20` cannot be represented in XML 1.0 at all
     * and would make the whole envelope unparseable, so they are dropped rather
     * than escaped; `\t`, `\n` and `\r` are legal and kept.
     */
    fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch < ' ' -> Unit
                ch == '\uFFFE' || ch == '\uFFFF' -> Unit
                else -> append(ch)
            }
        }
    }

    /**
     * Parses a SOAP response body.
     *
     * Returns [SoapResponse.Values] for anything that is not a fault, including
     * an empty body — the transport deliberately surfaces HTTP 500 responses, so
     * a fault arrives here as a normal document. Returns `null` when the bytes
     * are not parseable XML at all (an HTML error page, a truncated read), or
     * when the document declares a DTD, which [parseXmlDocument] refuses so a
     * hostile response cannot expand entities.
     */
    fun parse(xml: String?): SoapResponse? {
        if (xml.isNullOrBlank()) return null
        val root = parseXmlDocument(xml)?.documentElement ?: return null

        val fault = root.descendant("Fault")
        if (fault != null) {
            // A UPnP fault nests <errorCode>/<errorDescription> under <detail>;
            // a bare SOAP fault only has <faultcode>/<faultstring>.
            val code = fault.descendant("errorCode")?.text()?.toIntOrNull() ?: 0
            val detail = fault.descendant("errorDescription")?.text()?.takeIf { it.isNotEmpty() }
                ?: fault.descendant("faultstring")?.text().orEmpty()
            return SoapResponse.Fault(code, detail)
        }

        val values = LinkedHashMap<String, String>()
        collectLeaves(root, values)
        return SoapResponse.Values(values)
    }

    /** `GetPositionInfo` → both timelines in milliseconds. */
    fun position(response: SoapResponse.Values): PositionInfo =
        PositionInfo(
            positionMs = UpnpTime.parse(response.values["RelTime"]),
            durationMs = UpnpTime.parse(response.values["TrackDuration"]),
        )

    /** `GetTransportInfo` → the raw state, e.g. `PLAYING`; `null` when absent. */
    fun transportState(response: SoapResponse.Values): String? =
        response.values["CurrentTransportState"]?.takeIf { it.isNotEmpty() }

    /** `RenderingControl.GetVolume` → 0…100, or `null` when missing/unparseable. */
    fun volume(response: SoapResponse.Values): Int? =
        response.values["CurrentVolume"]?.toIntOrNull()?.coerceIn(0, 100)

    /**
     * Flattens every leaf element to `localName -> text`.
     *
     * The action responses are flat except for their envelope, so a map keyed by
     * local name is enough and keeps the reader independent of which prefix the
     * device chose. The first occurrence wins.
     *
     * Like [descendant] this is iterative, not recursive: a device can nest
     * elements tens of thousands deep within the body cap, and a recursive walk
     * raises [StackOverflowError], an [Error] the `catch (Exception)` guards
     * around [parse] do not cover. Pre-order is preserved, so `putIfAbsent`
     * still lets the first leaf in document order win.
     */
    private fun collectLeaves(root: Element, into: MutableMap<String, String>) {
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val element = stack.removeLast()
            val children = element.childElements()
            if (children.isEmpty()) {
                val value = element.text()
                if (value.isNotEmpty()) into.putIfAbsent(element.localTag(), value)
                continue
            }
            children.asReversed().forEach { stack.addLast(it) }
        }
    }
}

/**
 * What a SOAP response said.
 *
 * The transport does not treat HTTP 500 as an error, because that is exactly how
 * a UPnP fault is delivered; distinguishing the two cases is this type's job.
 */
internal sealed interface SoapResponse {

    /** A non-fault document, flattened to `localName -> text`. */
    data class Values(val values: Map<String, String>) : SoapResponse

    /**
     * An `s:Fault`. [code] is the UPnP `errorCode` when the device sent one, and
     * `0` for a bare SOAP fault; [detail] is the device's own description, for
     * the debug log.
     */
    data class Fault(val code: Int, val detail: String) : SoapResponse
}

/** A renderer's position and duration, both in milliseconds. */
internal data class PositionInfo(val positionMs: Long, val durationMs: Long)