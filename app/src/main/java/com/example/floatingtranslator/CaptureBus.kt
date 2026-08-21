package com.example.floatingtranslator

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** A finished translation request. Only ever handed to in-process listeners. */
data class CaptureResult(
    val sessionId: Long,
    val requestId: Long,
    val captured: Boolean,
    val sourceLines: List<String> = emptyList(),
    val translatedLines: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * In-process delivery for capture events.
 *
 * Every producer and consumer of these events (the overlay service, the capture
 * service and the permission activity) runs in the default process, so routing
 * them through sendBroadcast() would marshal recognized screen text out to
 * system_server for no benefit. Text recognized from the user's screen never
 * leaves this process now: it goes from ML Kit straight to the overlay that
 * displays it.
 *
 * All callbacks are delivered on the main thread, matching the threading the
 * previous BroadcastReceiver gave callers.
 */
object CaptureBus {
    interface Listener {
        fun onCapturePermissionNeeded(requestId: Long) = Unit
        fun onCapturePermissionCancelled() = Unit
        fun onCaptureSessionStarted(sessionId: Long) = Unit
        fun onCaptureSessionStopped(sessionId: Long) = Unit
        fun onCaptureResult(result: CaptureResult) = Unit
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun register(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun permissionNeeded(requestId: Long) = dispatch { it.onCapturePermissionNeeded(requestId) }

    fun permissionCancelled() = dispatch { it.onCapturePermissionCancelled() }

    fun sessionStarted(sessionId: Long) = dispatch { it.onCaptureSessionStarted(sessionId) }

    fun sessionStopped(sessionId: Long) = dispatch { it.onCaptureSessionStopped(sessionId) }

    fun result(result: CaptureResult) = dispatch { it.onCaptureResult(result) }

    private fun dispatch(block: (Listener) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach(block)
        } else {
            mainHandler.post { listeners.forEach(block) }
        }
    }
}
