package com.railhelper.ticket

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TaskRunnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("抢票任务运行中", "正在保持后台任务"))
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        PythonBridge.initialize(applicationContext)
        monitorJob?.cancel()
        monitorJob = scope.launch {
            PythonBridge.call("resume_running_tasks")
            while (isActive) {
                val count = PythonBridge.call("get_running_task_count")
                    .dataObject()
                    ?.optInt("count", 0) ?: 0
                updateNotification(count)
                if (count <= 0) {
                    stopSelf()
                    break
                }
                delay(15_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        super.onDestroy()
    }

    private fun updateNotification(count: Int) {
        val text = if (count > 0) "正在运行 $count 个抢票任务" else "没有运行中的任务"
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification("12306抢票助手", text))
    }

    private fun notification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ticket_tasks"
        private const val NOTIFICATION_ID = 1206
        private const val ACTION_START = "com.railhelper.ticket.START"
        private const val ACTION_STOP = "com.railhelper.ticket.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TaskRunnerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
