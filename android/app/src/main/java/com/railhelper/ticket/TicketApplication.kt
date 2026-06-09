package com.railhelper.ticket

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TicketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TaskRunnerService.CHANNEL_ID,
                getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在运行的抢票任务"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
