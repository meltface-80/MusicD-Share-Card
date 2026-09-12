package com.musicd.sharecard.android

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer.
 *
 * Ported from MusicD Remote Lite, which does the same job the same way.
 *
 * AN APP CANNOT REPLACE ITSELF. Only the system installer may, and it always
 * shows the user what it is about to do — so this is the whole of the Android
 * half of an update: point the installer at a file and get out of the way.
 * Everything before it (noticing a new version, downloading, checking the
 * digest) is plain Kotlin in :core and is tested there.
 *
 * Two things make this fail in ways worth naming, because both look like a bug
 * in this app rather than what they are:
 *
 *  - **A different signing key.** Android refuses to install over an app signed
 *    with another certificate and says only "App not installed" — no reason.
 *    Every build CI produced before the release key existed carried a throwaway
 *    debug key, so those have to be uninstalled once. `Updater` refuses to
 *    start such a download at all and says why instead.
 *  - **"Install unknown apps" not granted.** The installer asks for it the
 *    first time and comes straight back here if the user declines.
 *
 * The file goes through a content:// URI because a file:// path into this app's
 * own storage is refused outright — the installer is a different process and
 * needs a grant it can act on. The authority is the one the share sheet already
 * uses; `file_paths.xml` names the second directory.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** The one directory an update is downloaded into; see file_paths.xml. */
    fun downloadDir(context: Context): File = File(context.cacheDir, "updates")

    fun install(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.shares", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Started from the Service, so it needs a task of its own.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Rethrown rather than swallowed: Updater catches it and puts the
            // reason on the page, which would otherwise sit on "installing…"
            // for ever with nothing to explain it.
            Log.e(TAG, "could not open the installer", e)
            throw e
        }
    }
}
