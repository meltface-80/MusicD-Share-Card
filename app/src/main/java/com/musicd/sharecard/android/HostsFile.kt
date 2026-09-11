package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.Log
import com.musicd.sharecard.SeedHosts
import java.io.File

/**
 * Reads the seed-host file. The deciding is in [SeedHosts], in :core, where it
 * is tested; this is the three lines of platform call around it.
 *
 * A PLAIN TEXT FILE rather than a settings screen or a broadcast receiver. It
 * sits in the app's own external files directory, so it can be edited with a
 * file manager on the device itself — this runs on a FiiO R7, which has a screen
 * and a keyboard — or pushed over adb, and it needs no new exported component to
 * reach it:
 *
 *   adb push hosts.txt /sdcard/Android/data/com.musicd.sharecard/files/hosts.txt
 */
object HostsFile {

    private const val TAG = "HostsFile"

    fun read(context: Context): List<String> {
        val file = File(context.getExternalFilesDir(null), SeedHosts.FILE_NAME)
        val raw = try {
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            // Unreadable is the same as absent: discovery is still the normal
            // path, and this is only the fallback for when it cannot work.
            Log.w(TAG, "could not read ${file.path}: ${e.message}")
            ""
        }
        val hosts = SeedHosts.parse(raw)
        if (hosts.isNotEmpty()) Log.i(TAG, "starting with hosts from the file: $hosts")
        return hosts
    }
}
