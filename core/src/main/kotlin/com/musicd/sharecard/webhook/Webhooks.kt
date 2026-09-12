package com.musicd.sharecard.webhook

import java.net.URI

/**
 * One place a card can be posted to.
 *
 * THE URL IS A CREDENTIAL AND NEVER LEAVES THIS PROCESS. Anyone holding a
 * Discord webhook URL can post to that channel from anywhere, forever, until it
 * is deleted in Discord — so no route returns it, and every listing carries
 * [masked] instead. The page shows the mask, the server does the posting, and
 * the secret stays on the device it was typed into.
 */
data class Webhook(
    val id: String,
    val name: String,
    val url: String,
    /**
     * The name Discord shows on the message, overriding the webhook's own.
     *
     * HOW CLOSE THIS CAN GET, AND WHERE IT STOPS. Discord lets a webhook choose
     * a display name and an avatar per message, so a card can arrive as
     * "Menzies" with Menzies' picture instead of the webhook's. What it CANNOT
     * do is drop the APP tag beside the name: Discord marks every webhook
     * message that way on purpose, so a reader can always tell a person from an
     * integration, and no field turns it off.
     *
     * Posting as the account itself would mean driving a user token, which is
     * self-botting — against Discord's terms and a good way to lose the
     * account. Not something to build.
     *
     * Empty means "leave it to the webhook's own settings in Discord".
     */
    val username: String = "",
    /** A public https image. Discord fetches it; this app never does. */
    val avatarUrl: String = ""
) {
    /**
     * Enough to recognise which webhook this is, and not enough to use it.
     *
     * A Discord URL ends `/api/webhooks/<id>/<token>`, and the token is the
     * whole secret. The id is not sensitive on its own, so it is shown; the
     * token is reduced to its first few characters, which distinguishes two
     * webhooks in the same channel without handing either over.
     */
    val masked: String
        get() {
            val parts = url.trimEnd('/').split('/')
            val token = parts.lastOrNull().orEmpty()
            val channelId = parts.getOrNull(parts.size - 2).orEmpty()
            val head = token.take(4)
            return if (channelId.isEmpty()) "…/${head}…" else "…/$channelId/$head…"
        }

    val kind: String get() = "Discord"
}

/** Where webhooks live between runs. The Android module supplies a real one. */
interface WebhookStore {
    fun all(): List<Webhook>
    fun add(webhook: Webhook)
    fun remove(id: String): Boolean

    /**
     * The PIN a device across the house must supply to add or remove one.
     *
     * Generated once and kept; shown on the device's own screen, which is the
     * one place it can be shown without also showing it to whoever else is on
     * the network.
     */
    fun pin(): String

    companion object {
        /** Remembers nothing. Used by the tests and by a build with no storage. */
        fun inMemory(pin: String = "000000"): WebhookStore = object : WebhookStore {
            private val items = LinkedHashMap<String, Webhook>()
            override fun all(): List<Webhook> = items.values.toList()
            override fun add(webhook: Webhook) { items[webhook.id] = webhook }
            override fun remove(id: String): Boolean = items.remove(id) != null
            override fun pin(): String = pin
        }
    }
}

/** What went wrong with a URL somebody typed in. */
class WebhookRejected(message: String) : Exception(message)

object WebhookUrls {

    /**
     * Discord's own hosts, and nothing else.
     *
     * Accepting any URL would turn this into a general-purpose poster that
     * anyone on the LAN could aim at a host of their choosing — which is an
     * SSRF with a file attached. The app claims to post to Discord, so Discord
     * is what it accepts; another service is a deliberate change, not a
     * side effect of a loose check.
     */
    private val HOSTS = setOf(
        "discord.com", "discordapp.com", "www.discord.com", "canary.discord.com",
        "ptb.discord.com"
    )

    /**
     * Check a webhook URL, or say precisely why not.
     *
     * The messages matter: somebody pasting a URL that Discord gave them and
     * being told only "invalid" has no idea whether they copied half of it,
     * used the wrong menu, or pasted a channel link instead.
     */
    fun validate(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) throw WebhookRejected("Paste the webhook URL from Discord.")

        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw WebhookRejected("That is not a URL.")

        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw WebhookRejected("A webhook URL must start with https://")
        }
        val host = uri.host?.lowercase()
            ?: throw WebhookRejected("That URL names no host.")
        if (host !in HOSTS) {
            throw WebhookRejected(
                "Only Discord webhooks are supported. That URL points at $host."
            )
        }
        if (!uri.path.startsWith("/api/webhooks/")) {
            throw WebhookRejected(
                "That is a Discord link but not a webhook. In Discord: Edit Channel → " +
                    "Integrations → Webhooks → Copy Webhook URL."
            )
        }
        val parts = uri.path.removePrefix("/api/webhooks/").split('/').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[1].length < 8) {
            throw WebhookRejected("That webhook URL looks truncated — copy the whole thing.")
        }
        return url
    }

    /**
     * A display name Discord will accept.
     *
     * Discord rejects a few names outright ("clyde", "discord") and caps the
     * length. Silently sending one that will be refused turns a working post
     * into a 400 nobody can explain.
     */
    fun validateUsername(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) return ""
        if (name.length > 80) throw WebhookRejected("That name is too long for Discord.")
        val folded = name.lowercase()
        if (folded.contains("clyde") || folded.contains("discord")) {
            throw WebhookRejected("Discord does not allow \u201Cclyde\u201D or \u201Cdiscord\u201D in a webhook name.")
        }
        return name
    }

    /**
     * An avatar URL Discord can fetch.
     *
     * Discord does the fetching, not this app, so this is not an SSRF check —
     * it is a check that the thing will work at all. A LAN address is invisible
     * to Discord's servers and would silently fall back to the default avatar.
     */
    fun validateAvatar(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return ""
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw WebhookRejected("That avatar is not a URL.")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw WebhookRejected("An avatar URL must start with https://")
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) throw WebhookRejected("That avatar URL names no host.")
        if (host == "localhost" || host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("127.") || host.startsWith("172.")
        ) {
            throw WebhookRejected(
                "Discord fetches the avatar itself, so it has to be a public URL — " +
                    "an address on your own network is invisible to it."
            )
        }
        return url
    }

    /** A short, stable id for a webhook, so the page can name one without the URL. */
    fun idFor(url: String): String {
        val token = url.trimEnd('/').substringAfterLast('/')
        // A hash rather than the token itself: an id ends up in a URL path, a
        // log line and the page, and none of those should carry the secret.
        var hash = 0x811c9dc5.toInt()
        for (c in url) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return (java.lang.Integer.toHexString(hash) + token.take(2)).take(10)
    }
}
