package com.sonai.sonai.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build

class DndManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun setDndMode(enabled: Boolean) {
        if (notificationManager.isNotificationPolicyAccessGranted) {
            val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            notificationManager.setInterruptionFilter(filter)
        }
    }

    fun isAccessGranted(): Boolean = notificationManager.isNotificationPolicyAccessGranted
}
