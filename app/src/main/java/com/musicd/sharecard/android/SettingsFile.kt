package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.settings.FileSettingsStore
import java.io.File

/**
 * Which services and rooms are switched on, in the app's own storage.
 *
 * Beside the Roon token, the webhooks and the caches — the fourth thing this
 * app writes, and the only one a person edits directly. The format, the
 * defaults and the survive-a-bad-file rule are all in [FileSettingsStore], in
 * :core, where they are tested and where the container reads the same file.
 */
class SettingsFile(context: Context) :
    FileSettingsStore(File(context.filesDir, "settings.json"))
