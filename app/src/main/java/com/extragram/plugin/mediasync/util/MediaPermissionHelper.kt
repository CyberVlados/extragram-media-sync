package com.extragram.plugin.mediasync.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.extragram.plugin.mediasync.MediaNotificationListenerService

object MediaPermissionHelper {

    /**
     * Checks if the NotificationListenerService has been granted access by the user.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val expectedComponent = ComponentName(context, MediaNotificationListenerService::class.java)
        val flatString = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return flatString.split(":").any {
            ComponentName.unflattenFromString(it) == expectedComponent
        }
    }

    /**
     * Creates an Intent to navigate the user directly to the Notification Access screen in Android Settings.
     */
    fun createNotificationAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
