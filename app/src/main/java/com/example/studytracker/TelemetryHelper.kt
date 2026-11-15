package com.example.studytracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
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
        // Replace with your Firebase Realtime Database URL (No trailing slash or .json)
        const val FIREBASE_BASE_URL = "https://studytracker-b3c55-default-rtdb.firebaseio.com"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Generates a unique folder name per tablet (e.g., "SM_X200_98f1a2")
    fun getUniqueTabletId(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val cleanModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "_")
        return "${cleanModel}_${androidId.take(6)}"
    }

    // Verify if the user granted special Usage Access in Settings
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // 1. Installed User and System Applications
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

    // 2. Daily Screen Time per application (Past 24 Hours)
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

    // 3. App Open/Close Timestamps (Past 24 Hours, excluding Launcher noise)
    fun getRecentAppEvents(hoursBack: Long = 24): List<AppUsageSession> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.HOURS.toMillis(hoursBack)

        val usageEvents = usm.queryEvents(startTime, endTime)
        val eventList = mutableListOf<AppUsageSession>()
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            // Skip home screen launcher and system navigation clutter
            if (event.packageName == "com.sec.android.app.launcher" ||
                event.packageName == "com.android.systemui"
            ) {
                continue
            }

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
        return eventList.asReversed() // Most recent first
    }

    // Local report string used when viewing directly inside the app
    fun buildFormattedTextReport(): String {
        val sb = StringBuilder()
        sb.append("📱 TABLET ID: ${getUniqueTabletId()}\n")
        sb.append("Last Synced: ${dateFormat.format(Date())}\n\n")

        if (!hasUsageStatsPermission()) {
            sb.append("⚠️ USAGE STATS PERMISSION NOT GRANTED.\n")
            sb.append("Tap Button 1 above and enable Usage Access.\n\n")
        } else {
            sb.append("=== 1. SCREEN TIME (LAST 24H) ===\n")
            getDailyScreenTime().take(50).forEach {
                sb.append("• ${it.packageName}: ${it.totalMinutes} mins\n")
            }

            sb.append("\n=== 2. APP TIMESTAMPS (LAST 24H) ===\n")
            getRecentAppEvents().take(200).forEach {
                sb.append("• [${it.timestamp}] ${it.packageName} (${it.eventType})\n")
            }
        }

        sb.append("\n=== 3. BROWSER DOMAINS VISITED ===\n")
        if (BrowserUrlService.loggedDomains.isEmpty()) {
            sb.append("No domains logged yet.\n")
        } else {
            BrowserUrlService.loggedDomains.take(100).forEach {
                sb.append("• $it\n")
            }
        }

        sb.append("\n=== 4. INSTALLED USER APPS ===\n")
        getInstalledApps().filter { !it.isSystemApp }.forEach {
            sb.append("• ${it.appName} (${it.packageName})\n")
        }

        return sb.toString()
    }

    // Network upload to Firebase Realtime Database
    // Replace syncReportToFirebase in TelemetryHelper.kt with this version:
    fun syncReportToFirebase(
        tamperAlert: String = "OK - Normal",
        accessibilityActive: Boolean = true,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        thread(start = true) {
            try {
                if (FIREBASE_BASE_URL.contains("YOUR-PROJECT-ID")) {
                    onComplete?.invoke(false)
                    return@thread
                }

                val deviceFolder = getUniqueTabletId()
                val cleanBaseUrl = FIREBASE_BASE_URL.trimEnd('/')
                val usageGranted = hasUsageStatsPermission()

                // Automatically flag if Usage Access was revoked after setup
                val finalStatus = when {
                    tamperAlert != "OK - Normal" -> tamperAlert
                    !usageGranted -> "⚠️ ALERT: Usage Access Permission Revoked!"
                    !accessibilityActive -> "⚠️ ALERT: Accessibility Service Disabled!"
                    else -> "🟢 ACTIVE"
                }

                val payload = JSONObject().apply {
                    put("tabletId", deviceFolder)
                    put("status", finalStatus)
                    put("lastUpdated", dateFormat.format(Date()))
                    put("lastUpdatedEpoch", System.currentTimeMillis())
                    put("usagePermissionActive", usageGranted)
                    put("accessibilityActive", accessibilityActive)
                    put("screenTimeLast24h", JSONArray(getDailyScreenTime().take(100).map {
                        "${it.packageName}: ${it.totalMinutes} mins"
                    }))
                    put("recentAppEvents", JSONArray(getRecentAppEvents().take(300).map {
                        "[${it.timestamp}] ${it.packageName} -> ${it.eventType}"
                    }))
                    put("visitedDomains", JSONArray(BrowserUrlService.loggedDomains.take(200)))
                    put("installedUserApps", JSONArray(getInstalledApps().filter { !it.isSystemApp }.map {
                        "${it.appName} (${it.packageName})"
                    }))
                }

                val url = URL("$cleanBaseUrl/tablets/$deviceFolder.json")
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
