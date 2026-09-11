package com.musicd.sharecard.meta

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

/** A bounded cache whose entries expire. Nothing here is worth a dependency. */
class TtlCache<K, V>(private val ttlMs: Long, private val max: Int) {
    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > max
    }

    @Synchronized
    fun peek(key: K): V? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) {
            map.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis())
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
}

/** okhttp tuned for the metadata hosts: short timeouts, nothing held open. */
fun metadataHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()
