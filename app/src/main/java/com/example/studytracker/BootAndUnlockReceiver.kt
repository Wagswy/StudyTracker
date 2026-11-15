package com.example.studytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootAndUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT ||
            intent?.action == Intent.ACTION_BOOT_COMPLETED
        ) {
            // Directly sync the latest report to Firebase on boot or screen unlock
            TelemetryHelper(context.applicationContext).syncReportToFirebase()
        }
    }
}
