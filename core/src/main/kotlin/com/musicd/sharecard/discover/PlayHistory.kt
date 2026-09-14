package com.musicd.sharecard.discover

import com.musicd.sharecard.Log
import com.musicd.sharecard.library.Normalize
import com.musicd.sharecard.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What this app has actually made a card for.
 *
 * THE FOURTH THING IN THIS APP THAT WRITES TO DISK, and it was a decision
 * before it was a file. The other three are a pairing token, the webhook URLs
 * and the metadata cache; this one is a record of what somebody listened to,
 * which is a different kind of thing to keep and is why it is worth being
 * explicit about what it is for and what it is not.
 *
 * IT EXISTS BECAUSE "NEW MUSIC BASED ON YOUR LISTENING" NEEDS A SOURCE OF
 * TRUTH AND THIS APP HAD NONE. It drew a card and forgot. Offered a
 * ListenBrainz username instead, or keying off whatever is on screen right
 * now, the owner chose this: it needs no account, it works the same for Roon,
 * Sonos, Lyrion and UPnP, and it is the only one of the three where the words
 * mean what they say. The cost, stated rather than discovered: it starts
 * empty, so the screen it feeds is thin until the app has been used for a
 * while.
 *
 * WHAT IT HOLDS IS DELIBERATELY THIN. An artist, an album, and when it was
 * last seen. No track, no timestamps per play, no count of how many times —
 * none of that is needed to ask "what is new by acts this person hears", and
 * a record of what somebody played and when is worth more to a stranger than
 * a list of names. It is also capped: [LIMIT] acts, oldest dropped first.
 *
 * NOTHING ON THE NETWORK REACHES IT. The same rule the other three keep: no
 * route returns it, no route writes it, and the only caller is the card path
 * after the request that drew the card has already been answered.
 */
interface PlayHistory {

    /** One act, and the last record of theirs this app drew. */
    data class Heard(val artist: String, val album: String, val seenAt: Long)

    /** Most recently seen first. */
    fun recent(limit: Int = LIMIT): List<Heard>

    /**
     * Remember a card that was drawn.
     *
     * FOLDED ON THE ARTIST, NOT ON THE PAIR. The question this feeds is "what
     * is new by acts this person hears", so a household that plays six Bowie
     * records should weigh as Bowie once and not crowd out everyone else — and
     * the album is kept only so the screen can say why an act is in the list.
     */
    fun remember(artist: String, album: String)

    companion object {
        /**
         * How many acts are kept.
         *
         * Enough that a household's taste is represented and few enough that
         * the file stays small and a lookup over it stays cheap. Oldest out
         * first, so an act nobody has played for months stops shaping the
         * screen — which is the behaviour wanted from something called "based
         * on your listening".
         */
        const val LIMIT = 60

        /** Remembers nothing, for tests and for a host with no storage. */
        fun inMemory(): PlayHistory = object : PlayHistory {
            private val held = ArrayList<Heard>()
            override fun recent(limit: Int): List<Heard> =
                synchronized(held) { held.takeLast(limit).reversed() }
            override fun remember(artist: String, album: String) {
                synchronized(held) { fold(held, artist, album, System.currentTimeMillis()) }
            }
        }

        /**
         * Add or move-to-front, in place, and cap.
         *
         * Shared by both implementations so there is ONE rule for what
         * "remember" means — the file store and the in-memory one falling out
         * of step is how a test passes against behaviour the app does not have.
         */
        internal fun fold(into: MutableList<Heard>, artist: String, album: String, now: Long) {
            val act = artist.trim()
            if (act.isEmpty()) return
            val key = Normalize.text(act)
            if (key.isEmpty()) return
            into.removeAll { Normalize.text(it.artist) == key }
            into += Heard(act, album.trim(), now)
            while (into.size > LIMIT) into.removeAt(0)
        }
    }
}

/**
 * [PlayHistory] as one small JSON file.
 *
 * WRITTEN WHOLE AND ATOMICALLY, like the settings: beside and moved into
 * place, so a kill halfway through leaves the previous file rather than half
 * of this one. Held in memory after the first read because the screen asks for
 * it and the card path appends to it.
 *
 * A BAD FILE COSTS THE HISTORY, NEVER THE APP — the rule every store here
 * keeps. A file from a version that shaped it differently, or a half-written
 * one, means an empty history and a thin screen, which is visible and
 * recoverable, rather than a server that will not start.
 */
class FilePlayHistory(private val file: File) : PlayHistory {

    private val lock = Any()

    @Volatile
    private var held: MutableList<PlayHistory.Heard>? = null

    override fun recent(limit: Int): List<PlayHistory.Heard> =
        synchronized(lock) { load().takeLast(limit).reversed() }

    override fun remember(artist: String, album: String) {
        synchronized(lock) {
            val list = load()
            val before = list.toList()
            PlayHistory.fold(list, artist, album, System.currentTimeMillis())
            // NOTHING CHANGED MEANS NOTHING IS WRITTEN. The same record playing
            // through a page refresh must not rewrite the file every time.
            if (before.size == list.size && before.lastOrNull()?.artist == list.lastOrNull()?.artist &&
                before.lastOrNull()?.album == list.lastOrNull()?.album
            ) return
            save(list)
        }
    }

    private fun load(): MutableList<PlayHistory.Heard> {
        held?.let { return it }
        val list = try {
            if (!file.isFile) ArrayList()
            else {
                val rows = JSONArray(file.readText())
                val out = ArrayList<PlayHistory.Heard>(rows.length())
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val artist = row.str("artist")
                    if (artist.isBlank()) continue
                    out += PlayHistory.Heard(artist, row.str("album"), row.optLong("seenAt"))
                }
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read ${file.name}, starting empty: $t")
            ArrayList()
        }
        held = list
        return list
    }

    private fun save(list: List<PlayHistory.Heard>) {
        try {
            file.parentFile?.mkdirs()
            val rows = JSONArray()
            for (h in list) {
                rows.put(
                    JSONObject().put("artist", h.artist).put("album", h.album)
                        .put("seenAt", h.seenAt)
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(rows.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(rows.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            // It still applies for as long as this process lives; it simply
            // will not survive a restart. Refusing here would take the card
            // down with it, which is never the trade.
            Log.w(TAG, "could not write ${file.name}: $t")
        }
    }

    private companion object {
        const val TAG = "PlayHistory"
    }
}
