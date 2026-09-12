package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.Log
import com.musicd.sharecard.meta.CacheStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The metadata caches, kept in the app's own storage.
 *
 * WHY THIS EXISTS. The device is never switched off, but the SERVICE is — a
 * reboot, an update, Android reclaiming memory — and every one of those threw
 * away every album the app had ever looked up. Playing a record for the second
 * time paid for MusicBrainz, Wikipedia, Pitchfork and Qobuz all over again, and
 * each of those is a rate-gated request with a spinner in front of somebody.
 *
 * A JSON file per namespace rather than SQLite. There are a few hundred albums
 * in here at a few hundred bytes each, read once at startup and written in
 * whole; a database would be a schema, a migration and a dependency for a map
 * that fits comfortably in memory. If this ever grows past that, the seam to
 * change is [CacheStore] and nothing above it.
 *
 * NOTHING ON THE NETWORK REACHES THIS. No route reads or writes it; it is
 * written by the lookup path on its own thread. The rule that every route is a
 * read is unchanged.
 *
 * WRITES ARE COALESCED, and that is not an optimisation. A put happens inside
 * the request that is drawing somebody's card, and rewriting the file there
 * would put a disk write on the path of every album. Puts land in memory and a
 * single background thread flushes what changed, at most every few seconds.
 */
class CacheFile(context: Context) : CacheStore {

    private val dir = File(context.filesDir, "cache")
    private val lock = Any()

    /** namespace -> key -> value, as read and as being written. */
    private val held = HashMap<String, MutableMap<String, String>>()
    private val loaded = HashSet<String>()
    private val dirty = HashSet<String>()

    private val pending = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sharecard-cache").apply { isDaemon = true }
    }

    override fun load(namespace: String): Map<String, String> = synchronized(lock) {
        entries(namespace).toMap()
    }

    override fun put(namespace: String, key: String, value: String) {
        synchronized(lock) {
            entries(namespace)[key] = value
            dirty += namespace
        }
        schedule()
    }

    override fun remove(namespace: String, key: String) {
        synchronized(lock) {
            if (entries(namespace).remove(key) == null) return
            dirty += namespace
        }
        schedule()
    }

    /** Write anything outstanding now. Called when the service is stopping. */
    fun flush() = writeDirty()

    // ------------------------------------------------------------- internals

    private fun entries(namespace: String): MutableMap<String, String> {
        val map = held.getOrPut(namespace) { LinkedHashMap() }
        if (loaded.add(namespace)) {
            try {
                val file = fileFor(namespace)
                if (file.isFile) {
                    val json = JSONObject(file.readText())
                    for (key in json.keys()) {
                        json.optString(key).takeIf { it.isNotEmpty() }?.let { map[key] = it }
                    }
                }
            } catch (t: Throwable) {
                // A file from a version that shaped it differently, or a
                // half-written one after a power cut. Starting empty costs one
                // slow lookup per album; refusing to start costs the app.
                Log.w(TAG, "could not read the $namespace cache: $t")
                map.clear()
            }
        }
        return map
    }

    private fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        try {
            writer.schedule({ writeDirty() }, WRITE_AFTER_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            // Shutting down. What is held stays in memory and is simply not
            // written, which is the same as never having been cached.
            pending.set(false)
            Log.d(TAG, "cache write not scheduled: $t")
        }
    }

    private fun writeDirty() {
        pending.set(false)
        val snapshot: List<Pair<String, Map<String, String>>>
        synchronized(lock) {
            if (dirty.isEmpty()) return
            snapshot = dirty.map { it to held[it].orEmpty().toMap() }
            dirty.clear()
        }
        for ((namespace, entries) in snapshot) {
            try {
                dir.mkdirs()
                val json = JSONObject()
                for ((key, value) in entries) json.put(key, value)
                // Written beside and moved into place, so a kill halfway
                // through leaves the previous file rather than half of this
                // one. The read above survives a bad file anyway; this stops
                // it having to.
                val tmp = File(dir, "$namespace.tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(fileFor(namespace))) {
                    fileFor(namespace).writeText(json.toString())
                    tmp.delete()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not write the $namespace cache: $t")
            }
        }
    }

    private fun fileFor(namespace: String) = File(dir, "$namespace.json")

    private companion object {
        const val TAG = "CacheFile"

        /**
         * Long enough that a card's four lookups become one write, short enough
         * that a reboot a moment later has not lost the album.
         */
        const val WRITE_AFTER_MS = 3_000L
    }
}
