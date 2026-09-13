package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.roon.FileTokenStore
import java.io.File

/**
 * Roon's pairing token, in the app's own private storage.
 *
 * ALL OF THE THINKING IS IN [FileTokenStore], IN :core, AND THAT IS THE WHOLE
 * CHANGE HERE. This class used to hold the file format, the corrupt-file
 * recovery and the pairing bookkeeping, in `app/`, where nothing but the
 * compiler ever sees it. The only Android-shaped part was ever this one line —
 * where `filesDir` is — and the Docker build needs the same file read the same
 * way. Two copies of a format is how one shell silently stops reading what the
 * other wrote.
 */
class RoonTokenFile(context: Context) :
    FileTokenStore(File(context.filesDir, "roon.json"))
