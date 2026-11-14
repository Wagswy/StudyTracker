package com.example.studytracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class InstalledApp(val appName: String, val packageName: String, val isSystemApp: Boolean)
data class AppScreenTime(val packageName: String, val totalMinutes: Long)
data class AppUsageSession(val packageName: String, val eventType: String, val timestamp: String)

class TelemetryHelper(private val context: Context) {

    companion object {
        // PASTE YOUR FIREBASE REALTIME DATABASE URL BELOW (keep "/tablet_report.json" at the end)
        const val FIREBASE_DB_URL = "https://YOUR-PROJECT-ID-default-rtdb.firebaseio.com/tablet_report.json"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // 1. Installed User Apps
    fun getInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return packages.map { appInfo ->
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            InstalledApp(
                appName = pm.getApplicationLabel(appInfo).toString(),
                packageName = appInfo.packageName,
                isSystemApp = isSystem
            )
        }.sortedBy { it.appName }
    }

    // 2. Daily Screen Time (Last 24 Hours)
    fun getDailyScreenTime(): List<AppScreenTime> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.DAYS.toMillis(1)

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        return stats
            .filter { it.totalTimeInForeground > 0 }
            .map {
                AppScreenTime(
                    packageName = it.packageName,
                    totalMinutes = TimeUnit.MILLISECONDS.toMinutes(it.totalTimeInForeground)
                )
            }
            .filter { it.totalMinutes > 0 }
            .sortedByDescending { it.totalMinutes }
    }

    // 3. App Open/Close Timestamps (Last 6 Hours)
    fun getRecentAppEvents(hoursBack: Long = 6): List<AppUsageSession> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.HOURS.toMillis(hoursBack)

        val usageEvents = usm.queryEvents(startTime, endTime)
        val eventList = mutableListOf<AppUsageSession>()
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            ) {
                val typeLabel = if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    "OPENED"
                } else {
                    "CLOSED"
                }
                eventList.add(
                    AppUsageSession(
                        packageName = event.packageName,
                        eventType = typeLabel,
                        timestamp = dateFormat.format(Date(event.timeStamp))
                    )
                )
            }
        }
        return eventList.asReversed()
    }

    // Build formatted text report for on-screen display
    fun buildFormattedTextReport(): String {
        val sb = StringBuilder()
        sb.append("Last Synced: ${dateFormat.format(Date())}\n\n")

        if (!hasUsageStatsPermission()) {
            sb.append("⚠️ USAGE STATS PERMISSION NOT GRANTED.\n")
            sb.append("Tap Button 1 above and enable Usage Access.\n\n")
        } else {
            sb.append("=== 1. SCREEN TIME (LAST 24H) ===\n")
            getDailyScreenTime().take(15).forEach {
                sb.append("• ${it.packageName}: ${it.totalMinutes} mins\n")
            }

            sb.append("\n=== 2. APP TIMESTAMPS (LAST 6H) ===\n")
            getRecentAppEvents().take(20).forEach {
                sb.append("• [${it.timestamp}] ${it.packageName} (${it.eventType})\n")
            }
        }

        sb.append("\n=== 3. BROWSER DOMAINS VISITED ===\n")
        if (DomainMonitorVpnService.loggedDomains.isEmpty()) {
            sb.append("No domains logged yet. Start VPN and browse.\n")
        } else {
            DomainMonitorVpnService.loggedDomains.take(30).forEach {
                sb.append("• $it\n")
            }
        }

        sb.append("\n=== 4. INSTALLED USER APPS ===\n")
        getInstalledApps().filter { !it.isSystemApp }.forEach {
            sb.append("• ${it.appName} (${it.packageName})\n")
        }

        return sb.toString()
    }

    // Push structured JSON report to Firebase Realtime Database
    fun syncReportToFirebase(onComplete: ((Boolean) -> Unit)? = null) {
        thread(start = true) {
            try {
                if (FIREBASE_DB_URL.contains("YOUR-PROJECT-ID")) {
                    onComplete?.invoke(false)
                    return@thread
                }

                val payload = JSONObject().apply {
                    put("lastUpdated", dateFormat.format(Date()))
                    put("usagePermissionActive", hasUsageStatsPermission())
                    put("screenTimeLast24h", JSONArray(getDailyScreenTime().take(20).map {
                        "${it.packageName}: ${it.totalMinutes} mins"
                    }))
                    put("recentAppEvents", JSONArray(getRecentAppEvents().take(30).map {
                        "[${it.timestamp}] ${it.packageName} -> ${it.eventType}"
                    }))
                    put("visitedDomains", JSONArray(DomainMonitorVpnService.loggedDomains.take(50)))
                    put("installedUserApps", JSONArray(getInstalledApps().filter { !it.isSystemApp }.map {
                        "${it.appName} (${it.packageName})"
                    }))
                }

                val url = URL(FIREBASE_DB_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val success = conn.responseCode in 200..299
                conn.disconnect()
                onComplete?.invoke(success)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete?.invoke(false)
            }
        }
    }
}
