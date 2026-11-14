package com.example.studytracker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var telemetryHelper: TelemetryHelper
    private lateinit var outputText: TextView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetryHelper = TelemetryHelper(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnGrantUsage = Button(this).apply {
            text = "1. Grant Usage Access Permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        val btnStartVpn = Button(this).apply {
            text = "2. Start Domain Logger & Auto-Sync (VPN)"
            setOnClickListener { prepareAndStartVpn() }
        }

        val btnRefreshAndSync = Button(this).apply {
            text = "3. Refresh Screen & Sync to Cloud Now"
            setOnClickListener {
                renderAndSync()
            }
        }

        outputText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 24)
        }

        val scrollView = ScrollView(this).apply {
            addView(outputText)
        }

        rootLayout.addView(btnGrantUsage)
        rootLayout.addView(btnStartVpn)
        rootLayout.addView(btnRefreshAndSync)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        renderAndSync()
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, DomainMonitorVpnService::class.java)
        startService(serviceIntent)
        Toast.makeText(this, "VPN Logger & 5-Min Cloud Sync Started", Toast.LENGTH_SHORT).show()
    }

    private fun renderAndSync() {
        outputText.text = telemetryHelper.buildFormattedTextReport()
        telemetryHelper.syncReportToFirebase { success ->
            runOnUiThread {
                val msg = if (success) "Synced to Firebase Cloud!" else "Local view updated (Cloud URL not set or offline)"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
