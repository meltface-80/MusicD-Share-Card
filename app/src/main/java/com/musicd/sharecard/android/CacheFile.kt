package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.meta.FileCacheStore
import java.io.File

/**
 * What the metadata lookups found, in the app's own storage.
 *
 * The coalesced writes, the lazy read and the survive-a-bad-file rule are all
 * in [FileCacheStore], in :core, where they are tested. `flush()` comes from
 * there too and is called from `CardService.onDestroy`, which is the last
 * chance to keep an album looked up seconds earlier.
 */
class CacheFile(context: Context) :
    FileCacheStore(File(context.filesDir, "cache"))
