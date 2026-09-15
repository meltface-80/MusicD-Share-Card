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
 * **AN EMPTY LIST IS NOT AN ANSWER UNTIL THE PERMISSION IS KNOWN**, and BOTH
 * WAYS ANDROID CAN SAY NO ARE HANDLED because which one it takes could not be
 * verified from where this was written. An earlier version of this comment
 * stated flatly that an unpermitted caller gets an empty list rather than an
 * exception; that was asserted, not checked, and it is the kind of claim this
 * repository is supposed to refuse. A `SecurityException` is caught AND an
 * empty list is disambiguated, so the report is right under either.
 *
 * AND THE CALL DECIDES, NOT THE SETTING. The first cut read
 * `enabled_notification_listeners` and used its own reading of it as the
 * permission — so when it disagreed with a phone whose owner had just granted
 * access, there was nothing to say which of the two was wrong. The setting is
 * still read, to EXPLAIN rather than to decide.
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
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? MediaSessionManager
        if (manager == null) {
            DeviceAudio.Report.UNSUPPORTED
        } else {
            val listener = ComponentName(context, MediaAccess::class.java)
            /*
             * ASK THE SYSTEM, DO NOT INFER FROM A SETTING.
             *
             * The first cut decided the permission by reading
             * `enabled_notification_listeners` and only then called. That is
             * this app's READING of the grant standing in for the grant, and
             * when it disagreed with a phone whose owner had just granted it
             * there was no way to tell which of the two was wrong. The call
             * itself is the authority: a SecurityException is the system
             * turning it down, and anything else means it did not.
             *
             * The setting is still read, but only to EXPLAIN — never to decide.
             */
            val sessions = try {
                manager.getActiveSessions(listener)
            } catch (e: SecurityException) {
                return DeviceAudio.Report(
                    DeviceAudio.Access.DENIED, emptyList(), note(context, listener, refused = true)
                )
            }
            when {
                sessions.isNotEmpty() -> DeviceAudio.Report(
                    DeviceAudio.Access.GRANTED,
                    sessions.mapNotNull { describe(context, it) }
                )
                // Nothing came back and nothing was refused. THIS is the case
                // the whole type exists for, and the setting is what separates
                // a quiet phone from a grant that never landed.
                listeners(context).contains(context.packageName) -> DeviceAudio.Report(
                    DeviceAudio.Access.GRANTED, emptyList(), note(context, listener, refused = false)
                )
                else -> DeviceAudio.Report(
                    DeviceAudio.Access.DENIED, emptyList(), note(context, listener, refused = false)
                )
            }
        }
    } catch (e: Throwable) {
        DeviceAudio.Report.UNSUPPORTED
    }

    /**
     * What was looked for and what the system holds, for the report.
     *
     * The words are [DeviceAudio.listenerNote]'s — including the decision to
     * count other listeners rather than name them — because that is a judgement
     * and judgements live in the module with tests.
     */
    private fun note(context: Context, listener: ComponentName, refused: Boolean): String {
        val packages = listeners(context)
        return DeviceAudio.listenerNote(
            component = listener.flattenToString(),
            listed = packages.contains(context.packageName),
            total = packages.size,
            refused = refused
        )
    }

    /**
     * Every package the system currently counts as an enabled listener.
     *
     * ONE PARSE, TWO CALLERS. Deciding with one reading of this setting and
     * explaining with another is how a report ends up contradicting the thing
     * it is reporting on.
     *
     * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` is hidden from the SDK,
     * so the key is spelled out — it is the documented name and has been stable
     * since Jelly Bean. The value is flattened ComponentNames separated by ':'.
     */
    private fun listeners(context: Context): List<String> = runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it.trim())?.packageName }
    }.getOrDefault(emptyList())

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
