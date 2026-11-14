package com.example.studytracker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.Collections

class DomainMonitorVpnService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        val loggedDomains: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            TelemetryHelper(applicationContext).syncReportToFirebase()
            handler.postDelayed(this, 5 * 60 * 1000L) // Every 5 minutes
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        handler.removeCallbacks(syncRunnable)
        handler.post(syncRunnable)
        return START_STICKY
    }

    // Automatically restart the service if the user swipes the app away from Recent Apps
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, DomainMonitorVpnService::class.java).apply {
            setPackage(packageName)
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(applicationContext, 1, restartIntent, flag)
        } else {
            PendingIntent.getService(applicationContext, 1, restartIntent, flag)
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 5000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
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
        handler.removeCallbacks(syncRunnable)
        super.onDestroy()
    }
}
