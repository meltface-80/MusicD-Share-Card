package com.musicd.sharecard.android

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import com.musicd.sharecard.device.DeviceAudio

/**
 * ASK ANDROID WHAT THIS PHONE IS PLAYING.
 *
 * The thinnest possible half of the probe: read the sessions, copy them into
 * [DeviceAudio]'s plain data classes, and decide nothing. Every judgement about
 * what those values MEAN — whether a cover could be fetched, whether an empty
 * list is a silent phone or a refused permission, what to tell somebody to do
 * about it — lives in `:core`, which is the only module with tests. That split
 * is the repository's oldest rule and it is the entire reason this file is
 * short.
 *
 * **AN EMPTY LIST IS NOT AN ANSWER UNTIL THE PERMISSION IS KNOWN.** Android
 * does not refuse an unpermitted caller; it hands back nothing, which reads
 * exactly like a phone with no music on it. So the grant is checked FIRST and
 * carried separately, and [DeviceAudio] prints the two cases in different
 * words.
 *
 * NOTHING HERE IS TESTED. `MediaSessionManager` cannot be reached from a JVM
 * and there is no device in this repository — CI compiles it, and the first
 * real run on a phone is the verification. Read /api/debug.
 */
object DeviceSessions {

    /**
     * What the shell hands [DeviceAudio].
     *
     * Throwable, not Exception, at every level: this is reached from
     * `/api/debug`, which is the page somebody opens BECAUSE something is
     * already wrong, and a probe that can take the report down is worse than
     * no probe. A class that fails to initialise throws an Error.
     */
    fun read(context: Context): DeviceAudio.Report = try {
        if (!granted(context)) {
            DeviceAudio.Report.DENIED
        } else {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager
            if (manager == null) {
                DeviceAudio.Report.UNSUPPORTED
            } else {
                val controllers = manager.getActiveSessions(
                    ComponentName(context, MediaAccess::class.java)
                )
                DeviceAudio.Report(
                    DeviceAudio.Access.GRANTED,
                    controllers.mapNotNull { describe(context, it) }
                )
            }
        }
    } catch (e: SecurityException) {
        // The grant was revoked between the check and the call, or the listener
        // is declared and not enabled. Either way it is the permission.
        DeviceAudio.Report.DENIED
    } catch (e: Throwable) {
        DeviceAudio.Report.UNSUPPORTED
    }

    /**
     * Whether this app is an enabled notification listener.
     *
     * Read from the setting rather than inferred from an empty result, which is
     * the whole point: those two are indistinguishable at the call itself.
     * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` is hidden from the SDK,
     * so the key is spelled out — it is the documented name and has been stable
     * since Jelly Bean.
     */
    private fun granted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val me = context.packageName
        return enabled.split(':').any { entry ->
            entry.isNotBlank() &&
                ComponentName.unflattenFromString(entry.trim())?.packageName == me
        }
    }

    private fun describe(context: Context, controller: MediaController): DeviceAudio.Session? = try {
        val metadata = controller.metadata
        DeviceAudio.Session(
            packageName = controller.packageName.orEmpty(),
            label = label(context, controller.packageName),
            state = stateName(controller.playbackState),
            title = string(metadata, MediaMetadata.METADATA_KEY_TITLE),
            artist = string(metadata, MediaMetadata.METADATA_KEY_ARTIST)
                .ifEmpty { string(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST) },
            album = string(metadata, MediaMetadata.METADATA_KEY_ALBUM),
            artUri = string(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                .ifEmpty { string(metadata, MediaMetadata.METADATA_KEY_ART_URI) },
            artBitmap = bitmapSize(metadata)
        )
    } catch (e: Throwable) {
        // One app misbehaving must not cost the whole report.
        null
    }

    private fun string(metadata: MediaMetadata?, key: String): String =
        runCatching { metadata?.getString(key).orEmpty() }.getOrDefault("")

    /**
     * The size of an attached cover, or empty.
     *
     * Only the DIMENSIONS, never the bitmap: this is a diagnostics line, and a
     * card cannot draw these bytes anyway without the shell serving them — see
     * [DeviceAudio.artNote] for why that distinction is the open question.
     */
    private fun bitmapSize(metadata: MediaMetadata?): String = runCatching {
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art == null) "" else "${art.width}x${art.height}"
    }.getOrDefault("")

    /** Android's own label for the app, where it can still be resolved. */
    private fun label(context: Context, packageName: String?): String = runCatching {
        if (packageName.isNullOrBlank()) return ""
        val packages = context.packageManager
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("")

    private fun stateName(state: PlaybackState?): String = when (val code = state?.state) {
        null -> ""
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_NONE -> "NONE"
        // Bound rather than reached for again: `null ->` matched the SUBJECT,
        // which says nothing about `state` being non-null here.
        else -> "state $code"
    }
}
