package com.example.studytracker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class BrowserUrlService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPeriodicSyncTime = 0L

    companion object {
        val loggedDomains: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    private val browserPackages = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.brave.browser_nightly",
        "com.brave.browser_beta",
        "com.opera.browser",
        "com.duckduckgo.mobile.android"
    )

    private val urlBarIds = listOf(
        "com.android.chrome:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.sec.android.app.sbrowser:id/url_bar_text",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.microsoft.emmx:id/url_bar",
        "com.brave.browser:id/url_bar",
        "com.brave.browser_nightly:id/url_bar",
        "com.brave.browser_beta:id/url_bar",
        "com.duckduckgo.mobile.android:id/omnibarTextInput"
    )

    private val domainRegex = Regex(
        "^(https?://)?([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(/.*)?$"
    )

    private val syncRunnable = object : Runnable {
        override fun run() {
            triggerSync()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(syncRunnable)
        handler.post(syncRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = System.currentTimeMillis()
        if (now - lastPeriodicSyncTime >= 5 * 60 * 1000L) {
            triggerSync()
        }

        val pkg = event?.packageName?.toString() ?: ""
        val isBrowser = browserPackages.contains(pkg) ||
                pkg.contains("browser") ||
                pkg.contains("chrome")

        if (!isBrowser) return

        val rootNode = rootInActiveWindow ?: event?.source ?: return

        for (viewId in urlBarIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString()?.trim() ?: ""
                    if (looksLikeUrl(text)) {
                        logCapturedUrl(pkg, text)
                        return
                    }
                }
            }
        }

        scanNodesForUrl(pkg, rootNode, 0)
    }

    private fun scanNodesForUrl(pkg: String, node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 12) return

        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()?.trim() ?: ""

        if (text.isNotEmpty()) {
            val isUrlField = viewId.contains("url") ||
                    viewId.contains("location_bar") ||
                    viewId.contains("omnibar") ||
                    className.contains("EditText") ||
                    className.contains("TextView")

            if (isUrlField && looksLikeUrl(text)) {
                logCapturedUrl(pkg, text)
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                scanNodesForUrl(pkg, child, depth + 1)
            }
        }
    }

    private fun looksLikeUrl(input: String): Boolean {
        if (input.contains(" ") || input.length < 4 || input.length > 300) return false
        val clean = input.removePrefix("https://").removePrefix("http://")
        return domainRegex.matches(clean) && !clean.endsWith(".apk") && !clean.endsWith(".pdf")
    }

    private fun logCapturedUrl(pkg: String, url: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val shortBrowser = pkg.substringAfterLast('.')
        val entry = "[$time] ($shortBrowser) $url"

        if (loggedDomains.isEmpty() || !loggedDomains[0].endsWith(url)) {
            loggedDomains.add(0, entry)
            if (loggedDomains.size > 200) loggedDomains.removeAt(loggedDomains.lastIndex)
            triggerSync()
        }
    }

    private fun triggerSync() {
        lastPeriodicSyncTime = System.currentTimeMillis()
        TelemetryHelper(applicationContext).syncReportToFirebase()
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(syncRunnable)
        // Fire an immediate emergency packet before Android closes the service
        TelemetryHelper(applicationContext).syncReportToFirebase(
            tamperAlert = "🚨 CRITICAL: Accessibility Service Turned OFF by User!",
            accessibilityActive = false
        )
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        handler.removeCallbacks(syncRunnable)
        super.onDestroy()
    }
}
