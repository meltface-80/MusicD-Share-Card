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
    val url: String
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
