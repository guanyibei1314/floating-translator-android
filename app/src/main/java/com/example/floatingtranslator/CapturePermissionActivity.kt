package com.example.floatingtranslator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class CapturePermissionActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var requestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        requestId = intent.getLongExtra(AppContracts.EXTRA_REQUEST_ID, 0L)

        if (savedInstanceState == null) {
            requestProjectionPermission()
        }
    }

    private fun requestProjectionPermission() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            notifyPermissionCancelled()
            Toast.makeText(this, getString(R.string.capture_cancelled), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = AppContracts.ACTION_START_CAPTURE_SESSION
            putExtra(AppContracts.EXTRA_RESULT_CODE, resultCode)
            putExtra(AppContracts.EXTRA_DATA, data)
            putExtra(AppContracts.EXTRA_CAPTURE_REQUESTED, true)
            putExtra(AppContracts.EXTRA_REQUEST_ID, requestId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: RuntimeException) {
            notifyPermissionCancelled()
            Toast.makeText(this, getString(R.string.capture_permission_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        finish()
    }

    private fun notifyPermissionCancelled() {
        CaptureBus.permissionCancelled()
    }

    companion object {
        private const val REQUEST_PROJECTION = 4101
    }
}
