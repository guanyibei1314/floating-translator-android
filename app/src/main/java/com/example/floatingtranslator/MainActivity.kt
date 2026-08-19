package com.example.floatingtranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var overlayStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = findViewById(R.id.overlay_status)
        batteryStatus = findViewById(R.id.battery_status)
        startButton = findViewById(R.id.start_button)

        findViewById<Button>(R.id.overlay_button).setOnClickListener {
            openOverlaySettings()
        }
        findViewById<Button>(R.id.battery_button).setOnClickListener {
            openBackgroundSettings()
        }
        startButton.setOnClickListener {
            startTranslator()
        }
        findViewById<View>(R.id.stop_button).setOnClickListener {
            stopService(Intent(this, FloatingTranslatorService::class.java))
            stopService(Intent(this, ScreenCaptureService::class.java))
            Toast.makeText(this, getString(R.string.translator_stopped), Toast.LENGTH_SHORT).show()
            refreshPermissionState()
        }

        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        val overlayAllowed = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlayAllowed) {
            getString(R.string.permission_done)
        } else {
            getString(R.string.permission_not_done)
        }
        overlayStatus.setTextColor(
            if (overlayAllowed) getColorCompat(R.color.success_text)
            else getColorCompat(R.color.muted_text),
        )
        overlayStatus.setBackgroundResource(
            if (overlayAllowed) R.drawable.bg_status_ok else R.drawable.bg_status_neutral,
        )

        val batteryRelaxed = isIgnoringBatteryOptimizations()
        batteryStatus.text = if (batteryRelaxed) {
            getString(R.string.background_done)
        } else {
            getString(R.string.background_recommended)
        }
        batteryStatus.setTextColor(
            if (batteryRelaxed) getColorCompat(R.color.success_text)
            else getColorCompat(R.color.warning_text),
        )
        batteryStatus.setBackgroundResource(
            if (batteryRelaxed) R.drawable.bg_status_ok else R.drawable.bg_status_recommend,
        )

        startButton.isEnabled = overlayAllowed
        startButton.alpha = if (overlayAllowed) 1f else 0.52f
    }

    private fun startTranslator() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }

        FloatingTranslatorService.start(this)
        Toast.makeText(this, getString(R.string.translator_started), Toast.LENGTH_SHORT).show()
    }

    private fun openOverlaySettings() {
        val appUri = Uri.parse("package:" + packageName)
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openBackgroundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName),
                ),
            )
        }
        Toast.makeText(this, getString(R.string.background_settings_tip), Toast.LENGTH_LONG).show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return try {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } catch (_: SecurityException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(colorRes: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(colorRes)
        } else {
            resources.getColor(colorRes)
        }
    }
}
