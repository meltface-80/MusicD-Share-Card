package com.musicd.sharecard.android

import android.service.notification.NotificationListenerService

/**
 * EXISTS TO BE GRANTED, AND DOES NOTHING ELSE.
 *
 * `MediaSessionManager.getActiveSessions` will only answer a caller that is an
 * enabled notification listener, and the way Android expresses that is a
 * declared [NotificationListenerService] the user switches on by hand in
 * Settings. The ComponentName of THIS class is what gets passed to that call.
 *
 * **IT DELIBERATELY READS NO NOTIFICATIONS.** `onNotificationPosted` and
 * `onNotificationRemoved` are not overridden, so nothing in this app ever sees
 * the contents of anybody's messages — the permission is broad and what is
 * actually wanted from it is narrow. A listener that started reading them would
 * be a different app with a different privacy question, and the empty body is
 * the statement that this one is not that.
 *
 * NOTHING HERE IS TESTED. There is no device in this repository and no
 * instrumentation test; this compiles in CI and that is the whole of it.
 */
class MediaAccess : NotificationListenerService()
