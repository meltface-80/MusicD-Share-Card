package com.musicd.sharecard.sonos

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The XML reading shared by everything that talks to a player.
 *
 * DocumentBuilderFactory rather than a dependency: it is in the JDK and in
 * android.jar, so :core stays a plain Kotlin module that an APK can also carry.
 *
 * EVERY document parsed here arrived over the network from a device on the
 * LAN, so the parser is locked down before it sees a byte. An XML parser with
 * its defaults left alone will resolve a DOCTYPE's external entities — which
 * on this app's Android host means a declaration in a SOAP reply could read a
 * file out of the app's own sandbox and send it back inside a card. The
 * features below are the OWASP-recommended set, and `disallow-doctype-decl`
 * alone would be enough; the rest are belt and braces for parsers that do not
 * honour it.
 */
internal object Xml {

    private val factory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            // A Sonos reply has no legitimate DOCTYPE, so refusing one outright
            // costs nothing and closes XXE and billion-laughs together.
            setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureQuietly(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
            )
            isXIncludeAware = false
            isExpandEntityReferences = false

            // NAMESPACE-AWARE PARSING IS OFF, AND THAT IS THE POINT.
            //
            // A namespace-aware parser REJECTS THE WHOLE DOCUMENT if any
            // element uses a prefix that is not declared — "The prefix 'u' for
            // element 'u:GetZoneGroupStateResponse' is not bound". Not the
            // element: the document. One stray prefix anywhere in a reply and
            // there is no topology, no DIDL, and nothing to say why beyond
            // "that was not XML".
            //
            // Sonos replies are full of prefixes (s:, u:, dc:, upnp:, r:) and
            // they are assembled by several different services and music
            // providers, any of which can emit one without a declaration. A
            // strict parser turns somebody else's sloppy XML into this app
            // reporting that it cannot see their speakers.
            //
            // Nothing here reads a namespace URI: every lookup goes through
            // [localName], which strips the prefix from the tag name itself. So
            // awareness buys this app exactly nothing and costs it every reply
            // that is not perfectly formed. The XXE protections above are what
            // actually matter for safety, and they are unaffected.
            isNamespaceAware = false
        }

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (e: Exception) {
            // An implementation that does not know the feature is not a reason
            // to refuse to start; the ones that matter are supported by both
            // the JDK's parser and Android's.
        }
    }

    /** Null rather than an exception: malformed XML from a player is a miss. */
    fun parse(text: String): Element? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            // The builder is not thread-safe; the factory is only safe to share
            // because it is never reconfigured after construction.
            val builder = synchronized(factory) { factory.newDocumentBuilder() }
            builder.parse(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8)))
                .documentElement
        } catch (e: Exception) {
            null
        }
    }

    /** The tag name without its namespace prefix or URI. */
    fun localName(node: Node): String =
        node.localName ?: node.nodeName.substringAfterLast(':')

    fun children(node: Node): List<Element> {
        val kids = node.childNodes
        val out = ArrayList<Element>(kids.length)
        for (i in 0 until kids.length) {
            (kids.item(i) as? Element)?.let(out::add)
        }
        return out
    }

    /** Every descendant element, the node itself included, depth first. */
    fun descendants(root: Element): Sequence<Element> = sequence {
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            yield(node)
            // Reversed so the natural document order comes back out of the
            // stack — a caller taking the "first" match means the first one
            // written, not the last.
            for (child in children(node).asReversed()) stack.addLast(child)
        }
    }

    /** The first descendant with this local name, at any depth. */
    fun find(root: Element, name: String): Element? =
        descendants(root).firstOrNull { localName(it) == name }

    fun text(element: Element?): String = element?.textContent?.trim().orEmpty()
}
