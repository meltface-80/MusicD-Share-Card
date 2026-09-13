package com.musicd.sharecard

import com.musicd.sharecard.lms.LmsDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast framing, checked against bytes assembled by hand.
 *
 * No Lyrion server is reachable from here and no packet was captured, so this
 * pins the framing as DOCUMENTED rather than as observed. If the real thing
 * differs, these bytes are the place to correct it — which is the whole reason
 * the parse is a pure function with the socket somewhere else.
 */
class LmsDiscoveryTest {

    /** 'E', then each tag with a real length and value. */
    private fun reply(vararg fields: Pair<String, String>): ByteArray {
        val out = ArrayList<Byte>()
        out += 'E'.code.toByte()
        for ((tag, value) in fields) {
            for (c in tag) out += c.code.toByte()
            out += value.length.toByte()
            for (c in value) out += c.code.toByte()
        }
        return out.toByteArray()
    }

    @Test
    fun `the request asks for the port, the name and the version`() {
        val request = LmsDiscovery.request()
        assertEquals('e'.code.toByte(), request[0])
        val text = String(request, Charsets.US_ASCII)
        assertTrue(text, text.contains("JSON"))
        assertTrue(text, text.contains("NAME"))
        assertTrue(text, text.contains("VERS"))
        // A request declares a zero length for every tag it asks about.
        assertEquals(1 + 3 * 5, request.size)
    }

    @Test
    fun `a reply gives the port the endpoint is really on`() {
        val fields = LmsDiscovery.parseReply(
            reply("NAME" to "attic", "JSON" to "9002", "VERS" to "9.0.0")
        )
        assertEquals("attic", fields["NAME"])
        // Not assumed to be 9000: a server moved off the default is still found.
        assertEquals("9002", fields["JSON"])
        assertEquals("9.0.0", fields["VERS"])
    }

    @Test
    fun `anything that is not a reply is ignored`() {
        // Our own broadcast comes back on some networks, and a stray packet on
        // 3483 is a Squeezebox talking to its server rather than to us.
        assertTrue(LmsDiscovery.parseReply(ByteArray(0)).isEmpty())
        assertTrue(LmsDiscovery.parseReply("eNAME".toByteArray()).isEmpty())
    }

    @Test
    fun `a truncated packet keeps the fields that did parse`() {
        val whole = reply("JSON" to "9000", "NAME" to "attic")
        // Cut inside the NAME value: the port before it is still worth having.
        val cut = whole.copyOf(whole.size - 3)
        val fields = LmsDiscovery.parseReply(cut)
        assertEquals("9000", fields["JSON"])
        assertTrue("a half-read name must not be invented", fields["NAME"] == null)
    }
}
