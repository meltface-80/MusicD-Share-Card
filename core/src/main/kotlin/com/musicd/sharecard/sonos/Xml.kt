package com.musicd.sharecard.sonos

import com.musicd.sharecard.Log
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

    private const val TAG = "Xml"

    /**
     * Built once, and NEVER allowed to throw while doing it.
     *
     * This class died in a static initialiser on a real device and took the
     * whole app with it. `setXIncludeAware` is not implemented by Android's
     * DocumentBuilderFactory, and the base class answers with
     * `UnsupportedOperationException: This parser does not support
     * specification "Unknown" version "0.0"`. Thrown from `<clinit>`, that does
     * not fail one parse — the CLASS never loads, so every later use throws
     * NoClassDefFoundError, for the life of the process.
     *
     * It cost three releases. The `setFeature` calls were already guarded; the
     * two property setters beside them were not, and they are the ones Android
     * refuses. So now EVERY setting goes through [quietly], including the
     * factory's own construction: hardening this parser is best-effort by
     * nature, because it runs on whatever XML implementation the platform
     * happens to ship.
     */
    private val factory: DocumentBuilderFactory? = buildFactory()

    private fun buildFactory(): DocumentBuilderFactory? = try {
        DocumentBuilderFactory.newInstance().apply {
            // A Sonos reply has no legitimate DOCTYPE, so refusing one outright
            // costs nothing and closes XXE and billion-laughs together. This is
            // the one that actually matters, which is why its failure is a
            // warning rather than a shrug.
            quietly("disallow-doctype-decl", loud = true) {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            quietly("external-general-entities") {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            quietly("external-parameter-entities") {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            quietly("load-external-dtd") {
                setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
                )
            }
            // THE LINE THAT KILLED THE APP. Android does not implement it.
            quietly("XInclude") { isXIncludeAware = false }
            quietly("expand-entity-references") { isExpandEntityReferences = false }

            // NAMESPACE-AWARE PARSING IS OFF, AND THAT IS THE POINT.
            //
            // A namespace-aware parser REJECTS THE WHOLE DOCUMENT if any
            // element uses a prefix that is not declared — "The prefix 'u' for
            // element 'u:GetZoneGroupStateResponse' is not bound". Not the
            // element: the document. Sonos replies are full of prefixes (s:, u:,
            // dc:, upnp:, r:), assembled by several services and music
            // providers, any of which can emit one without a declaration.
            //
            // Nothing here reads a namespace URI: every lookup goes through
            // [localName], which strips the prefix from the tag name itself. So
            // awareness buys this app nothing and costs it every reply that is
            // not perfectly formed.
            quietly("namespace-aware") { isNamespaceAware = false }
        }
    } catch (e: Throwable) {
        // Throwable, not Exception: the failure this is standing in for was an
        // Error, and a parser that cannot be built must leave the app able to
        // say so rather than unable to start.
        Log.e(TAG, "could not build an XML parser factory", e)
        null
    }

    /**
     * Apply one optional setting, surviving a platform that refuses it.
     *
     * [loud] marks the settings whose absence is worth knowing about — losing
     * the DOCTYPE ban is a real weakening, where losing XInclude is not.
     */
    private inline fun quietly(what: String, loud: Boolean = false, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            if (loud) {
                Log.w(TAG, "this platform's XML parser would not accept $what: ${e.message}")
            } else {
                Log.d(TAG, "XML parser setting $what not supported: ${e.message}")
            }
        }
    }

    /** Null rather than an exception: malformed XML from a player is a miss. */
    fun parse(text: String): Element? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val factory = this.factory ?: return null
        return try {
            // The builder is not thread-safe; the factory is only safe to share
            // because it is never reconfigured after construction.
            val builder = synchronized(factory) { factory.newDocumentBuilder() }
            builder.parse(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8)))
                .documentElement
        } catch (e: Throwable) {
            // Throwable: a parser on an unfamiliar platform can answer with an
            // Error, and one bad reply must not end the process.
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
