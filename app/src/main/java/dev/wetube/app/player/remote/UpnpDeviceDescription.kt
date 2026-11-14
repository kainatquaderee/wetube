package dev.wetube.app.player.remote

import java.io.StringReader
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/** One `<service>` entry from a device description. */
internal data class UpnpService(val serviceType: String, val controlUrl: String)

/**
 * The parts of a UPnP device description the renderer needs, with every control
 * URL already absolute.
 *
 * Descriptions are namespace-tolerant: the device template in the wild uses a
 * default namespace, a prefix, or occasionally neither, and the local names are
 * what identify the elements.
 */
internal data class UpnpDevice(
    val deviceType: String,
    val friendlyName: String,
    val modelName: String,
    val udn: String,
    val services: List<UpnpService>,
) {

    /** Absolute `AVTransport` control URL, or `null` when the device omits it. */
    val avTransportUrl: String? get() = serviceUrl(AV_TRANSPORT)

    /** Absolute `RenderingControl` control URL; `null` means "no volume over here". */
    val renderingControlUrl: String? get() = serviceUrl(RENDERING_CONTROL)

    /** Absolute `ConnectionManager` control URL, used only for the advisory probe. */
    val connectionManagerUrl: String? get() = serviceUrl(CONNECTION_MANAGER)

    fun serviceUrl(name: String): String? =
        services.firstOrNull { it.matchesService(name) }?.controlUrl

    internal companion object {
        const val AV_TRANSPORT = "AVTransport"
        const val RENDERING_CONTROL = "RenderingControl"
        const val CONNECTION_MANAGER = "ConnectionManager"
    }
}

/** `urn:schemas-upnp-org:service:AVTransport:1` matches `AVTransport`, any version. */
private fun UpnpService.matchesService(name: String): Boolean =
    serviceType.lowercase(Locale.US).contains(":service:${name.lowercase(Locale.US)}:")

/** The marker every `deviceType` of a renderer carries. */
internal const val MEDIA_RENDERER_TOKEN = ":device:MediaRenderer:"

/**
 * Parses a UPnP device description into [UpnpDevice], or `null` when it is not a
 * media renderer, is malformed, or points somewhere we refuse to go.
 *
 * [location] is the URL the description was fetched from. Real devices return
 * `controlURL` values that are relative to it (`/AVTransport/…/control.xml`);
 * resolving them against the base is the single-most common way a hand-rolled
 * control point breaks, so it happens here once instead of at every call site.
 */
internal object UpnpDeviceDescription {

    fun parse(xml: String?, location: String?): UpnpDevice? {
        if (xml.isNullOrBlank() || location.isNullOrBlank()) return null
        // A description never carries a DTD. [parseXmlDocument] already refuses
        // one, but this earlier copy documents the intent at the entry point and
        // keeps the guard even if the shared parser is ever swapped out.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null

        val base = runCatching { URI(location.trim()) }.getOrNull() ?: return null
        val document = parseXmlDocument(xml) ?: return null
        val device = document.documentElement?.childElement("device") ?: return null

        val deviceType = device.childElement("deviceType")?.text().orEmpty()
        if (!deviceType.contains(MEDIA_RENDERER_TOKEN, ignoreCase = true)) return null

        val services = device.childElement("serviceList")
            ?.children("service")
            .orEmpty()
            .mapNotNull { service ->
                val type = service.childElement("serviceType")?.text().orEmpty()
                val raw = service.childElement("controlURL")?.text().orEmpty()
                if (type.isEmpty() || raw.isEmpty()) {
                    null
                } else {
                    resolveControlUrl(base, raw)?.let { UpnpService(type, it) }
                }
            }

        return UpnpDevice(
            deviceType = deviceType,
            friendlyName = device.childElement("friendlyName")?.text().orEmpty(),
            modelName = device.childElement("modelName")?.text().orEmpty(),
            udn = device.childElement("UDN")?.text().orEmpty(),
            services = services,
        )
    }

    /**
     * Resolves a `controlURL` against the description's own URL.
     *
     * `URI.resolve` returns an absolute `controlURL` unchanged, so both forms go
     * through one path. A value that cannot be parsed drops the service instead
     * of failing the whole device.
     */
    private fun resolveControlUrl(base: URI, controlUrl: String): String? = runCatching {
        base.resolve(controlUrl.trim()).toString().takeIf { it.isNotBlank() }
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// Shared, hardened XML plumbing.
//
// Both XML parsers in this package read documents a device on the LAN sent us,
// so they share one factory rather than each growing its own slightly different
// idea of what "safe" means.
// ---------------------------------------------------------------------------

/**
 * Parses [xml] into a namespace-aware [Document], or `null` on any failure.
 *
 * A document that declares a DTD is refused here, in the one entry point both
 * XML parsers in this package go through, so no caller can forget it. That
 * blocks entity expansion (the "billion laughs" family) and external-entity
 * fetches on every platform, including the platform factory builds that ignore
 * the SAX hardening features applied below. Callers may keep their own earlier
 * `DOCTYPE` check as defence in depth.
 *
 * The platform `DocumentBuilderFactory` is not Xerces, so those entity/DTD
 * features are applied best-effort; the `DOCTYPE` guard is the portable part. A
 * malformed body is an ordinary condition here, not a reason to write to
 * stderr, hence the quiet error handler.
 */
internal fun parseXmlDocument(xml: String): Document? {
    // Centralised DTD refusal: see the KDoc above. This is deliberately the
    // first thing that happens, before any factory is configured, so a hostile
    // SOAP response cannot reach a parser that might expand its entities.
    if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null

    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    runCatching { factory.isExpandEntityReferences = false }
    runCatching { factory.isXIncludeAware = false }
    listOf(
        "http://apache.org/xml/features/disallow-doctype-decl",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
    ).forEach { feature -> runCatching { factory.setFeature(feature, false) } }
    // These are the JAXP property URIs behind XMLConstants.ACCESS_EXTERNAL_DTD /
    // ACCESS_EXTERNAL_SCHEMA. The constants themselves are absent from the
    // Android SDK's javax.xml stubs, so the literals are used and the whole
    // block stays best-effort: a platform that does not know a property throws,
    // and the runCatching keeps it from taking the parse down with it.
    runCatching { factory.setAttribute(ACCESS_EXTERNAL_DTD, "") }
    runCatching { factory.setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }

    return runCatching {
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(QuietErrorHandler)
        builder.parse(InputSource(StringReader(xml)))
    }.getOrNull()
}

/** JAXP property that switches off DTD access. See [parseXmlDocument]. */
private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"

/** JAXP property that switches off schema access. See [parseXmlDocument]. */
private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

/** The element's tag without a namespace prefix, whatever the parser set. */
internal fun Element.localTag(): String = localName ?: nodeName.substringAfterLast(':')

/** The element's trimmed text content. */
internal fun Element.text(): String = textContent?.trim().orEmpty()

/** Direct child elements, in document order. */
internal fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** Direct child elements whose local name is [tag]. */
internal fun Element.children(tag: String): List<Element> = childElements().filter { it.localTag() == tag }

/** First direct child element whose local name is [tag]. */
internal fun Element.childElement(tag: String): Element? = children(tag).firstOrNull()

/**
 * The first descendant whose local name is [tag], in document order.
 *
 * Walking ourselves instead of using `getElementsByTagNameNS("*", …)` keeps the
 * match independent of how a given parser reports `localName` for elements in
 * no namespace.
 *
 * The walk is iterative with an explicit stack rather than recursive: a device
 * can nest elements tens of thousands deep inside the body cap, and a recursive
 * walk overflows the JVM stack. [StackOverflowError] is an [Error], so the
 * `catch (Exception)` guards around the parsers would not stop it. Children are
 * pushed in reverse so the pop order is exactly document order.
 */
internal fun Element.descendant(tag: String): Element? {
    val stack = ArrayDeque<Element>()
    childElements().asReversed().forEach { stack.addLast(it) }
    while (stack.isNotEmpty()) {
        val element = stack.removeLast()
        if (element.localTag() == tag) return element
        element.childElements().asReversed().forEach { stack.addLast(it) }
    }
    return null
}

private object QuietErrorHandler : ErrorHandler {
    override fun warning(exception: SAXParseException) = Unit
    override fun error(exception: SAXParseException): Unit = throw exception
    override fun fatalError(exception: SAXParseException): Unit = throw exception
}