package com.musicd.sharecard.settings

import com.musicd.sharecard.Log
import com.musicd.sharecard.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [Settings] as one small JSON file, read once and written whole.
 *
 * HELD IN MEMORY AFTER THE FIRST READ, because this is asked on the path of
 * every request — which zones may answer, which services to link — and a file
 * read per request is a file read per card. Writes go straight through, since
 * there are a handful in the life of an install.
 *
 * A BAD FILE COSTS THE SETTINGS, NEVER THE APP. The same rule the caches keep:
 * a file from a version that shaped it differently, or a half-written one after
 * a power cut, means the defaults — which is a visible, recoverable state with
 * a screen to fix it in — rather than a server that will not start.
 *
 * THE DEFAULTS ARE NOT NOTHING, and losing this file is not harmless: with no
 * zones enabled the app shows its empty state and asks for them to be chosen
 * again. That is deliberate (see [Settings]) and it is why the write is atomic:
 * written beside and moved into place, so a kill halfway through leaves the
 * previous file rather than half of this one.
 */
open class FileSettingsStore(private val file: File) : SettingsStore {

    private val lock = Any()

    @Volatile
    private var held: Settings? = null

    override fun read(): Settings {
        held?.let { return it }
        return synchronized(lock) {
            held ?: decode().also { held = it }
        }
    }

    override fun write(settings: Settings) {
        synchronized(lock) {
            held = settings
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(encode(settings))
                if (!tmp.renameTo(file)) {
                    file.writeText(encode(settings))
                    tmp.delete()
                }
            } catch (t: Throwable) {
                // The choice still applies for as long as this process lives;
                // it simply will not survive a restart. Refusing here would
                // take the card down with it.
                Log.w(TAG, "could not write ${file.name}: $t")
            }
        }
    }

    private fun decode(): Settings = try {
        if (!file.isFile) Settings()
        else {
            val json = JSONObject(file.readText())
            Settings(
                disabledServices = strings(json.optJSONArray("disabledServices")),
                enabledZones = strings(json.optJSONArray("enabledZones"))
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read ${file.name}, using defaults: $t")
        Settings()
    }

    /**
     * `str` and not `optString`: Android's org.json answers the literal text
     * "null" for a JSON null, which would enable a zone called "null" and
     * disable a service of the same name. The same accessor rule the rest of
     * this app keeps, in the newest file that has to keep it.
     */
    private fun strings(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            if (array.isNull(i)) continue
            val holder = JSONObject().put("v", array.get(i))
            holder.str("v").trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    private fun encode(settings: Settings) = JSONObject()
        .put("disabledServices", JSONArray(settings.disabledServices.toList()))
        .put("enabledZones", JSONArray(settings.enabledZones.toList()))
        .toString()

    private companion object {
        const val TAG = "Settings"
    }
}
