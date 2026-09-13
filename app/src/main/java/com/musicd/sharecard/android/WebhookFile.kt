package com.musicd.sharecard.android

import android.content.Context
import com.musicd.sharecard.webhook.FileWebhookStore
import java.io.File

/**
 * Discord webhooks, in the app's own private storage.
 *
 * `filesDir` is private to this app and not on shared storage — unlike
 * `hosts.txt`, which is deliberately world-editable because a speaker's IP
 * address is not a secret. A webhook URL is, so this file is not reachable by a
 * file manager or by adb pull on an unrooted device.
 *
 * The format, the PIN and the masking are all in [FileWebhookStore], in :core,
 * where they are tested and where the Docker build reads the same file.
 */
class WebhookFile(context: Context) :
    FileWebhookStore(File(context.filesDir, "webhooks.json"))
