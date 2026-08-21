package com.example.floatingtranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.pm.ServiceInfo
import kotlin.math.abs
import kotlin.math.max

class FloatingTranslatorService : Service(), CaptureBus.Listener {
    private val windowManager: WindowManager by lazy {
        getSystemService(WindowManager::class.java)
            ?: error("Window manager is unavailable")
    }
    private var bubble: TextView? = null
    private var resultPanel: View? = null
    private var permissionFlowActive = false
    private var activeSessionId: Long? = null
    private var pendingRequestId: Long? = null
    private var nextRequestId = 0L

    override fun onCaptureResult(result: CaptureResult) {
        if (activeSessionId != result.sessionId || pendingRequestId != result.requestId) return
        pendingRequestId = null
        showTranslationResult(
            captured = result.captured,
            sourceLines = result.sourceLines,
            translatedLines = result.translatedLines,
            errorMessage = result.errorMessage,
        )
    }

    override fun onCapturePermissionNeeded(requestId: Long) {
        setBubbleVisible(true)
        activeSessionId = null
        if (requestId > 0) pendingRequestId = requestId
        if (!permissionFlowActive) {
            permissionFlowActive = true
            launchCapturePermission()
        }
    }

    override fun onCaptureSessionStarted(sessionId: Long) {
        activeSessionId = sessionId.takeIf { it >= 0L }
        permissionFlowActive = false
    }

    override fun onCaptureSessionStopped(sessionId: Long) {
        if (activeSessionId == null || activeSessionId == sessionId) {
            activeSessionId = null
            pendingRequestId = null
        }
        permissionFlowActive = false
        setBubbleVisible(true)
    }

    override fun onCapturePermissionCancelled() {
        permissionFlowActive = false
        pendingRequestId = null
        setBubbleVisible(true)
    }

    override fun onCreate() {
        super.onCreate()
        startOverlayForegroundService()
        CaptureBus.register(this)
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        CaptureBus.unregister(this)
        clearOwnClipboard()
        resultPanel?.let { removeViewSafely(it) }
        bubble?.let { removeViewSafely(it) }
        resultPanel = null
        bubble = null
        activeSessionId = null
        pendingRequestId = null
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOverlayForegroundService() {
        createOverlayNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, OVERLAY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_translate)
                .setContentTitle(getString(R.string.overlay_notification_title))
                .setContentText(getString(R.string.overlay_notification_text))
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_translate)
                .setContentTitle(getString(R.string.overlay_notification_title))
                .setContentText(getString(R.string.overlay_notification_text))
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OVERLAY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(OVERLAY_NOTIFICATION_ID, notification)
        }
    }

    private fun createOverlayNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            OVERLAY_CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun showBubble() {
        if (bubble != null) return
        val view = TextView(this).apply {
            text = getString(R.string.bubble_label)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = getDrawable(R.drawable.bg_bubble)
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.bubble_content_description)
            setOnTouchListener(createDragListener(this))
        }
        val params = WindowManager.LayoutParams(
            dp(58),
            dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(12)
            y = 0
        }
        bubble = view
        try {
            windowManager.addView(view, params)
        } catch (_: WindowManager.BadTokenException) {
            stopSelf()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun setBubbleVisible(visible: Boolean) {
        bubble?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun createDragListener(view: View): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        return View.OnTouchListener { _, event ->
            val params = view.layoutParams as? WindowManager.LayoutParams
                ?: return@OnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (abs(deltaX) > dp(6) || abs(deltaY) > dp(6)) moved = true
                    params.x = max(0, startX + deltaX.toInt())
                    params.y = startY + deltaY.toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: RuntimeException) {
                        stopSelf()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) requestTranslation()
                    true
                }

                else -> true
            }
        }
    }

    private fun requestTranslation() {
        val requestId = ++nextRequestId
        pendingRequestId = requestId
        removeResultPanel()
        // The overlay itself must not become part of the OCR frame.
        setBubbleVisible(false)
        try {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(AppContracts.ACTION_REQUEST_TRANSLATION)
                    .putExtra(AppContracts.EXTRA_REQUEST_ID, requestId),
            )
        } catch (_: RuntimeException) {
            pendingRequestId = null
            setBubbleVisible(true)
            Toast.makeText(
                this,
                getString(R.string.capture_permission_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun launchCapturePermission() {
        val intent = Intent(this, CapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AppContracts.EXTRA_REQUEST_ID, pendingRequestId ?: 0L)
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            permissionFlowActive = false
            setBubbleVisible(true)
            Toast.makeText(
                this,
                getString(R.string.capture_permission_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showTranslationResult(
        captured: Boolean,
        sourceLines: List<String>,
        translatedLines: List<String>,
        errorMessage: String?,
    ) {
        setBubbleVisible(true)
        removeResultPanel()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = getDrawable(R.drawable.bg_panel)
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = getString(R.string.result_title)
            textSize = 20f
            setTextColor(getColorCompat(R.color.ink))
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(
            heading,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val closeButton = Button(this).apply {
            text = getString(R.string.close)
            setTextColor(getColorCompat(R.color.muted_text))
            setBackgroundColor(Color.TRANSPARENT)
            filterTouchesWhenObscured = true
            setOnClickListener { removeResultPanel() }
        }
        header.addView(
            closeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.addView(header)

        val status = TextView(this).apply {
            text = when {
                !errorMessage.isNullOrBlank() -> errorMessage
                captured && sourceLines.isNotEmpty() -> getString(
                    R.string.capture_success_real,
                    sourceLines.size,
                )
                else -> getString(R.string.error_no_english_text)
            }
            textSize = 12f
            setTextColor(
                if (errorMessage.isNullOrBlank()) getColorCompat(R.color.muted_text)
                else getColorCompat(R.color.warning_text),
            )
            setPadding(0, 0, 0, dp(12))
        }
        panel.addView(status)

        val scroll = ScrollView(this)
        val pairs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val pairCount = max(sourceLines.size, translatedLines.size)
        if (pairCount == 0) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_result_detail)
                textSize = 15f
                setTextColor(getColorCompat(R.color.muted_text))
                setPadding(dp(12), dp(14), dp(12), dp(14))
            }
            pairs.addView(empty)
        } else {
            for (index in 0 until pairCount) {
                val source = sourceLines.getOrNull(index).orEmpty()
                val target = translatedLines.getOrNull(index).orEmpty()
                addTranslationPair(
                    pairs,
                    source.ifBlank { getString(R.string.source_missing) },
                    target.ifBlank { getString(R.string.translation_missing) },
                )
            }
        }
        scroll.addView(pairs)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260),
            ),
        )

        val note = TextView(this).apply {
            text = if (errorMessage.isNullOrBlank()) {
                getString(R.string.real_result_note)
            } else {
                getString(R.string.error_help_note)
            }
            textSize = 12f
            setTextColor(getColorCompat(R.color.muted_text))
            setPadding(0, dp(10), 0, dp(10))
        }
        panel.addView(note)

        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.END
        }
        val copyButton = Button(this).apply {
            text = getString(R.string.copy_translation)
            isEnabled = translatedLines.isNotEmpty()
            // A malicious overlay must not be able to drive the copy action.
            filterTouchesWhenObscured = true
            setOnClickListener { copyTranslation(sourceLines, translatedLines) }
        }
        actionRow.addView(copyButton)
        panel.addView(actionRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // The panel repeats text taken from another app's screen. Keep it
                // out of screenshots and other apps' screen recordings.
                WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(18)
        }
        resultPanel = panel
        try {
            windowManager.addView(panel, params)
        } catch (_: Exception) {
            resultPanel = null
        }
    }

    private fun addTranslationPair(container: LinearLayout, source: String, target: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_translation_row)
        }
        val sourceView = TextView(this).apply {
            text = source
            textSize = 15f
            setTextColor(getColorCompat(R.color.ink))
        }
        val targetView = TextView(this).apply {
            text = target
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColorCompat(R.color.blue))
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(sourceView)
        row.addView(targetView)
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            },
        )
    }

    private fun copyTranslation(sourceLines: List<String>, translatedLines: List<String>) {
        val text = sourceLines.indices.joinToString("\n") { index ->
            val source = sourceLines[index]
            val target = translatedLines.getOrNull(index).orEmpty()
            if (target.isBlank()) source else "$source\n$target"
        }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(getString(R.string.translation_label), text)
        // This text was lifted off whatever the user had on screen, so it may be
        // a one-time code or a private message. Marking it sensitive keeps
        // Android 13+ from rendering it in the clipboard preview toast.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * Drops our own translation clip when the overlay is dismissed, so screen
     * text does not outlive the session on the clipboard. Anything the user
     * copied since is left alone.
     */
    private fun clearOwnClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        try {
            val label = clipboard.primaryClipDescription?.label ?: return
            if (label != getString(R.string.translation_label)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: RuntimeException) {
            // Losing the clipboard race is not worth crashing the shutdown path.
        }
    }

    private fun removeResultPanel() {
        resultPanel?.let { removeViewSafely(it) }
        resultPanel = null
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // The window was already removed by the system.
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(colorRes: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(colorRes)
        } else {
            resources.getColor(colorRes)
        }
    }

    companion object {
        private const val OVERLAY_CHANNEL_ID = "floating_overlay"
        private const val OVERLAY_NOTIFICATION_ID = 6200

        fun start(context: Context) {
            context.startService(Intent(context, FloatingTranslatorService::class.java))
        }
    }
}
