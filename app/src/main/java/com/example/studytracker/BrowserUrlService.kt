package com.example.studytracker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrowserUrlService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventSyncTime = 0L

    private val urlBarIds = listOf(
        "com.android.chrome:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.microsoft.emmx:id/url_bar",
        "com.brave.browser:id/url_bar"
    )

    // Runs every 5 minutes on the main OS Accessibility loop (survives app closure)
    private val syncRunnable = object : Runnable {
        override fun run() {
            TelemetryHelper(applicationContext).syncReportToFirebase()
            handler.postDelayed(this, 5 * 60 * 1000L) // Schedule next run in 5 mins
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(syncRunnable)
        handler.post(syncRunnable) // Start 5-min sync immediately when connected
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Also trigger a sync whenever the user switches apps if 5 mins have passed
        val now = System.currentTimeMillis()
        if (now - lastEventSyncTime >= 5 * 60 * 1000L) {
            lastEventSyncTime = now
            TelemetryHelper(applicationContext).syncReportToFirebase()
        }

        val rootNode = rootInActiveWindow ?: return
        for (viewId in urlBarIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                val urlText = nodes[0].text?.toString()?.trim() ?: ""
                if (urlText.isNotEmpty() && urlText.contains(".") && !urlText.contains(" ")) {
                    logCapturedUrl(urlText)
                }
            }
        }
    }

    private fun logCapturedUrl(url: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $url"
        val list = DomainMonitorVpnService.loggedDomains
        if (list.isEmpty() || !list[0].endsWith(url)) {
            list.add(0, entry)
            if (list.size > 200) list.removeAt(list.lastIndex)
            // Immediately push to Firebase whenever a new website is visited!
            TelemetryHelper(applicationContext).syncReportToFirebase()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(syncRunnable)
        super.onDestroy()
    }
}
