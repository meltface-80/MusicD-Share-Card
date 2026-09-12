package com.musicd.sharecard.roon

import com.musicd.sharecard.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Roon's zone feed, trimmed to what a share card needs.
 *
 * Ported from MusicD Remote Lite, which carries the same parsing with the
 * volume, output, settings and queue models beside it. Those are all about
 * CONTROLLING a zone, and this app controls nothing — so they are left behind
 * rather than carried as dead weight.
 *
 * `now_playing.three_line` is the whole reason Roon is worth asking: line1 is
 * the track, line2 the artist and line3 the ALBUM. A Sonos speaker fed by Roon
 * sees none of that — it gets a stream with Roon's session id where the title
 * should be.
 */
data class NowPlaying(
    val line1: String,
    val line2: String,
    val line3: String,
    val lengthSeconds: Int?,
    val seekPosition: Int?,
    val imageKey: String?
) {
    companion object {
        fun parse(o: JSONObject?): NowPlaying? {
            if (o == null) return null
            val three = o.optJSONObject("three_line")
            val two = o.optJSONObject("two_line")
            val one = o.optJSONObject("one_line")
            val src = three ?: two ?: one
            return NowPlaying(
                line1 = src?.str("line1").orEmpty(),
                line2 = src?.str("line2").orEmpty(),
                line3 = src?.str("line3").orEmpty(),
                lengthSeconds = o.optInt("length", -1).takeIf { it >= 0 },
                seekPosition = o.optInt("seek_position", -1).takeIf { it >= 0 },
                imageKey = o.str("image_key").takeIf { it.isNotEmpty() }
            )
        }
    }
}

data class Zone(
    val zoneId: String,
    val displayName: String,
    val state: String,
    val nowPlaying: NowPlaying?
) {
    val isPlaying: Boolean get() = state == "playing"

    companion object {
        fun parse(o: JSONObject): Zone {
            return Zone(
                zoneId = o.str("zone_id"),
                displayName = o.str("display_name"),
                state = o.str("state", "stopped"),
                nowPlaying = NowPlaying.parse(o.optJSONObject("now_playing"))
            )
        }
    }
}

/**
 * Applies the subscribe_zones stream. The first message is "Subscribed" with
 * the full set; everything after is "Changed" with added/removed/changed
 * deltas plus a separate, much more frequent seek-position delta that must not
 * clobber anything else.
 */
class ZoneStore {
    /**
     * Guards [zones] and [revision], and is what a waiter blocks on.
     *
     * This feed is written by the MOO socket thread and read by every HTTP
     * worker, which was an unguarded LinkedHashMap across threads. The lock
     * was needed anyway; the waiting is what it was added for.
     */
    private val lock = Object()
    private val zones = LinkedHashMap<String, Zone>()
    private var revision = 0L

    /**
     * Bumped when something MATERIAL changes — a zone appears, disappears,
     * starts, stops, or moves to another track.
     *
     * Deliberately NOT bumped by a seek-position update. Roon sends one of
     * those roughly every second for every playing zone, so waking a waiting
     * client on them would turn a long poll straight back into a 1 Hz poll.
     * The page interpolates the progress bar between updates and resynchronises
     * whenever a wait times out, which is far more often than the drift matters.
     */
    val version: Long get() = synchronized(lock) { revision }

    /**
     * Blocks until [version] moves past [since], then returns it. Returns the
     * current version immediately if it has already moved, and after
     * [timeoutMs] if nothing happens.
     */
    fun awaitChange(since: Long, timeoutMs: Long): Long = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (revision <= since) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            // A spurious wakeup returns to the loop condition, not out of it.
            (lock as Object).wait(remaining)
        }
        revision
    }

    private fun bump() {
        revision++
        (lock as Object).notifyAll()
    }

    fun applySubscribed(body: JSONObject) = synchronized(lock) {
        zones.clear()
        body.optJSONArray("zones")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
        }
        bump()
    }

    fun applyChanged(body: JSONObject) = synchronized(lock) {
        var material = false
        body.optJSONArray("zones_removed")?.let { arr ->
            for (i in 0 until arr.length()) zones.remove(arr.getString(i))
            if (arr.length() > 0) material = true
        }
        body.optJSONArray("zones_added")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
            if (arr.length() > 0) material = true
        }
        body.optJSONArray("zones_changed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
            if (arr.length() > 0) material = true
        }
        body.optJSONArray("zones_seek_changed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val zoneId = e.str("zone_id")
                val existing = zones[zoneId] ?: continue
                val np = existing.nowPlaying ?: continue
                val pos = e.optInt("seek_position", -1).takeIf { it >= 0 } ?: continue
                zones[zoneId] = existing.copy(nowPlaying = np.copy(seekPosition = pos))
            }
            // No bump: see [version]. A ticking clock is not news.
        }
        if (material) bump()
    }

    fun clear() = synchronized(lock) {
        zones.clear()
        bump()
    }

    fun all(): List<Zone> = synchronized(lock) {
        zones.values.sortedBy { it.displayName.lowercase() }
    }

    fun byId(id: String?): Zone? =
        if (id == null) null else synchronized(lock) { zones[id] }
}
