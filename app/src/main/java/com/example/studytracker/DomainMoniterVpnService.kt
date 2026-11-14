package com.example.studytracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Collections
import kotlin.concurrent.thread

class DomainMonitorVpnService : Service() {

    @Volatile private var isRunning = false

    companion object {
        val loggedDomains: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (!isRunning) {
            isRunning = true
            thread(start = true) { runPeriodicCloudSync() }
        }
        return START_STICKY
    }

    private fun runPeriodicCloudSync() {
        val helper = TelemetryHelper(applicationContext)
        while (isRunning) {
            helper.syncReportToFirebase()
            try {
                Thread.sleep(5 * 60 * 1000L) // Syncs every 5 minutes
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "study_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Study Background Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Study Accountability Active")
            .setContentText("Syncing screen time, app usage & browser activity every 5 mins.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
