package com.example.studytracker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrowserUrlService : AccessibilityService() {

    private val urlBarIds = listOf(
        "com.android.chrome:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.microsoft.emmx:id/url_bar",
        "com.brave.browser:id/url_bar"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        }
    }

    override fun onInterrupt() {}
}
