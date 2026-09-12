package com.musicd.sharecard.meta

import com.musicd.sharecard.Log
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Serialises calls to one host and spaces them out. */
class RateGate(private val intervalMs: Long) {
    private var last = 0L

    @Synchronized
    fun <T> run(body: () -> T): T {
        val wait = intervalMs - (System.currentTimeMillis() - last)
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            return body()
        } finally {
            last = System.currentTimeMillis()
        }
    }
}

/**
 * Somewhere a cache can survive a restart.
 *
 * The device this runs on is never switched off, but the SERVICE is: a reboot,
 * an update, Android reclaiming memory. Every one of those threw away every
 * album ever looked up, so the next play of a record the app already knew about
 * paid for MusicBrainz, Wikipedia, Pitchfork and Qobuz all over again.
 *
 * Deliberately dumb. It moves opaque strings for a namespace and knows nothing
 * about what is in them; expiry, eviction and encoding all stay in [TtlCache],
 * where they are tested. The Android shell supplies the file; everything else
 * gets [NONE] and behaves exactly as it did before this existed.
 */
interface CacheStore {

    fun load(namespace: String): Map<String, String>

    fun put(namespace: String, key: String, value: String)

    fun remove(namespace: String, key: String)

    companion object {
        /** Remembers nothing. The tests use it; so does any host with no disk. */
        val NONE: CacheStore = object : CacheStore {
            override fun load(namespace: String): Map<String, String> = emptyMap()
            override fun put(namespace: String, key: String, value: String) {}
            override fun remove(namespace: String, key: String) {}
        }
    }
}

/**
 * A bounded cache whose entries expire. Nothing here is worth a dependency.
 *
 * With [persist] set it also survives a restart. WITHOUT IT, NOTHING HERE
 * BEHAVES DIFFERENTLY FROM BEFORE — every branch that touches disk is guarded
 * on it being non-null, and the caches that should not be written (the art
 * bytes, Pitchfork's hourly index) simply do not pass one.
 */
class TtlCache<K, V>(
    private val ttlMs: Long,
    private val max: Int,
    /** How this cache's values are written down. Null keeps it in memory only. */
    private val persist: Persist<V>? = null
) {
    /**
     * One cache's shelf in the store, and how to get its values on and off it.
     *
     * [encode] may return null for a value that is not worth keeping — a
     * half-answer, something still in flight — and [decode] returns null for
     * anything it cannot read back, which is how a format change from an older
     * version is survived rather than crashed on.
     */
    class Persist<V>(
        val store: CacheStore,
        val namespace: String,
        val encode: (V) -> String?,
        val decode: (String) -> V?
    )

    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
            if (size <= max) return false
            // Evicted here means evicted there: the file tracks what is in
            // memory rather than growing for ever behind it.
            val p = persist
            if (p != null && eldest != null) {
                quietly { p.store.remove(p.namespace, eldest.key.toString()) }
            }
            return true
        }
    }

    @Volatile private var loaded = false

    /**
     * Read the shelf back, once, on first use.
     *
     * Lazily rather than in the constructor: these are built while the service
     * is starting, and startForeground() has about five seconds — see
     * CardService. Reading a file there is exactly the kind of work that window
     * has no room for.
     */
    private fun loadOnce() {
        val p = persist ?: return
        if (loaded) return
        loaded = true
        quietly {
            val now = System.currentTimeMillis()
            for ((key, text) in p.store.load(p.namespace)) {
                val split = text.indexOf('|')
                if (split <= 0) continue
                val at = text.substring(0, split).toLongOrNull() ?: continue
                if (now - at > ttlMs) continue
                @Suppress("UNCHECKED_CAST")
                p.decode(text.substring(split + 1))?.let { map[key as K] = Entry(it, at) }
            }
        }
    }

    @Synchronized
    fun peek(key: K): V? {
        loadOnce()
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) {
            map.remove(key)
            persist?.let { p -> quietly { p.store.remove(p.namespace, key.toString()) } }
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        loadOnce()
        val at = System.currentTimeMillis()
        map[key] = Entry(value, at)
        persist?.let { p ->
            quietly { p.encode(value)?.let { p.store.put(p.namespace, key.toString(), "$at|$it") } }
        }
    }

    /**
     * Note the deliberate absence of a lock around [compute]: a metadata fetch
     * takes seconds over the network, and holding the cache lock across it
     * would stall every other request. A duplicate fetch is cheaper than that.
     */
    fun get(key: K, compute: () -> V): V {
        peek(key)?.let { return it }
        val value = compute()
        put(key, value)
        return value
    }

    @Synchronized
    fun clear() = map.clear()

    /** How many entries are held, for a test that needs to see the shelf. */
    @Synchronized
    fun size(): Int {
        loadOnce()
        return map.size
    }
}

/**
 * Run something whose failure must not reach the caller.
 *
 * A CACHE MAY NEVER BREAK A LOOKUP. A full disk, a file written by a version
 * that shaped it differently, a permission that changed under the app — every
 * one of those has to end as "we did not remember that one", never as a failed
 * card. Throwable rather than Exception for the reason the rest of this app
 * catches Throwable: a class that fails to initialise throws an Error.
 */
private inline fun quietly(body: () -> Unit) {
    try {
        body()
    } catch (t: Throwable) {
        Log.d("Cache", "cache step skipped: $t")
    }
}

/** okhttp tuned for the metadata hosts: short timeouts, nothing held open. */
fun metadataHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()
