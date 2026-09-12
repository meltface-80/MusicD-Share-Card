package com.musicd.sharecard.api

import com.musicd.sharecard.http.Request

/**
 * Who is allowed to change something.
 *
 * UNTIL NOW THIS APP HELD NO SECRETS, and that is the entire reason it could
 * answer the whole LAN without a password: every route was a read, nothing
 * mutated, and the most an uninvited guest could learn was which record was on.
 *
 * A Discord webhook URL breaks that. It is a credential, and anyone holding one
 * can post to that channel from anywhere on the internet, for as long as it
 * exists, whether or not they are ever on this network again. So the moment
 * webhooks arrived, the gate this repo's own rules demanded had to arrive with
 * them.
 *
 * The gate is deliberately narrow — it guards CONFIGURATION, not the card:
 *
 *  - Reading the card, the zones, the art: open, exactly as before.
 *  - Posting a card to an already-configured webhook: open. It is the everyday
 *    action, and "tap the button" must not mean "find the PIN first". The worst
 *    a stranger on your wifi can do with it is post a picture of your own album
 *    to your own channel.
 *  - ADDING or REMOVING a webhook: gated. That is where the credential enters
 *    and where the damage would be done.
 *
 * LOOPBACK IS TRUSTED WITHOUT A PIN, because a request from 127.0.0.1 came from
 * the app's own WebView on the device itself — and standing in front of the
 * device is a stronger claim than any PIN typed from across the house.
 */
class Access(private val pin: () -> String) {

    fun mayConfigure(request: Request): Boolean {
        if (isLoopback(request.remoteAddress)) return true
        val supplied = request.param("pin")?.trim().orEmpty()
        val expected = pin().trim()
        // Length-checked first so the comparison below never runs on nothing.
        if (supplied.isEmpty() || expected.isEmpty()) return false
        return constantTimeEquals(supplied, expected)
    }

    /**
     * The request came from this device.
     *
     * [Request.remoteAddress] is taken from the socket rather than any header,
     * which is what makes this worth anything at all: X-Forwarded-For and
     * friends are attacker-controlled and deliberately ignored.
     */
    internal fun isLoopback(address: String): Boolean =
        address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1" ||
            address.startsWith("127.")

    /**
     * Compared without an early exit.
     *
     * A six-digit PIN over a LAN is not a serious target for timing analysis,
     * but a comparison that returns on the first wrong character is a habit
     * worth not having in a file called Access.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var differences = 0
        for (i in a.indices) differences = differences or (a[i].code xor b[i].code)
        return differences == 0
    }
}
