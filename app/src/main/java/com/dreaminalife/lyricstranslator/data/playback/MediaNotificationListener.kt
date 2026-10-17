package com.dreaminalife.lyricstranslator.data.playback

import android.service.notification.NotificationListenerService

/**
 * Never reads notifications. Android only lets apps with an enabled notification listener
 * see other apps' media sessions, so this service exists to hold that permission.
 */
class MediaNotificationListener : NotificationListenerService()
