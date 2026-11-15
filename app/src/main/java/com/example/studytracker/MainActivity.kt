package com.example.studytracker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var telemetryHelper: TelemetryHelper
    private lateinit var outputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetryHelper = TelemetryHelper(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnGrantUsage = Button(this).apply {
            text = "1. Grant Usage Access (Screen Time & Apps)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        val btnEnableAccessibility = Button(this).apply {
            text = "2. Enable Background Engine (Accessibility)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnRefreshAndSync = Button(this).apply {
            text = "3. Refresh Screen & Sync Now"
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
        rootLayout.addView(btnEnableAccessibility)
        rootLayout.addView(btnRefreshAndSync)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        renderAndSync()
    }

    private fun renderAndSync() {
        outputText.text = telemetryHelper.buildFormattedTextReport()
        telemetryHelper.syncReportToFirebase { success ->
            runOnUiThread {
                val msg = if (success) "Synced to Cloud!" else "Local view updated"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
