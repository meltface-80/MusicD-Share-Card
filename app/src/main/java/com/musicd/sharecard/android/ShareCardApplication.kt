package com.musicd.sharecard.android

import android.app.Application

/**
 * Exists for one reason: to install the crash handler before anything else runs.
 *
 * Application.onCreate is the first code of this app the system executes — ahead
 * of the Activity and ahead of the Service — so a crash in either of those is
 * still caught. Installing it from the Activity would miss exactly the failures
 * that happen before the window opens, which is where this app's were.
 */
class ShareCardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
