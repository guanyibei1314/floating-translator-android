package com.example.floatingtranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.content.pm.ServiceInfo
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Owns the MediaProjection session and performs the real OCR/translation work.
 *
 * The session holds a MediaProjection and a single VirtualDisplay, but the
 * display has **no output surface** except while a translation request is in
 * flight. Android 14+ forbids calling createVirtualDisplay() more than once per
 * MediaProjection, so the display itself is long-lived; attaching and detaching
 * its surface is what gates the pixels. Between taps the display renders
 * nowhere, so no screen content reaches this process at all.
 *
 * Results stay in-process: recognized and translated strings go to the overlay
 * through CaptureBus, never through a broadcast.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var surfaceAttached = false
    private var textRecognizer: TextRecognizer? = null
    private var translator: Translator? = null

    private var sessionStopping = false
    private var sessionGeneration = 0L
    private var activeGeneration = 0L
    private var frameSequence = 0L
    private var pendingRequestId = 0L
    private var translationPending = false
    private var processingFrame = false
    private var processingRequestId = 0L
    private var processingGeneration = 0L
    private var processingBitmap: Bitmap? = null
    private var translationModelReady = false
    private var consecutiveTimeouts = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestTimeout = Runnable {
        if (translationPending && !sessionStopping) {
            val requestId = pendingRequestId
            translationPending = false
            pendingRequestId = 0L
            detachCaptureSurface()
            sendCaptureResult(
                captured = false,
                sessionId = activeGeneration,
                requestId = requestId,
                frame = frameSequence,
                errorMessage = getString(R.string.error_capture_timeout),
            )
            // One timeout can just be a slow first frame, so the session gets
            // another chance. Repeated timeouts mean it is wedged -- most likely
            // the display never resumed after its surface was re-attached -- so
            // drop it. The next tap then rebuilds the projection from scratch
            // (asking for consent again) instead of leaving a session that
            // silently never captures anything.
            consecutiveTimeouts += 1
            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                endSession(notifyPending = false)
            }
        }
    }

    private val processingTimeout = Runnable {
        if (processingFrame && !sessionStopping) {
            val generation = processingGeneration
            val requestId = processingRequestId
            finishProcessing(
                generation = generation,
                requestId = requestId,
                captured = false,
                errorMessage = getString(R.string.error_translation_timeout),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val captureIntent = intent ?: return START_NOT_STICKY
        return when (captureIntent.action) {
            AppContracts.ACTION_REQUEST_TRANSLATION -> {
                val requestId = captureIntent.getLongExtra(AppContracts.EXTRA_REQUEST_ID, 0L)
                queueTranslationRequest(requestId)
                if (projection == null) {
                    CaptureBus.permissionNeeded(requestId)
                    stopSelf()
                }
                START_NOT_STICKY
            }

            AppContracts.ACTION_START_CAPTURE_SESSION -> {
                startCaptureSession(captureIntent)
                START_NOT_STICKY
            }

            AppContracts.ACTION_STOP_CAPTURE_SESSION -> {
                endSession(notifyPending = false)
                START_NOT_STICKY
            }

            else -> START_NOT_STICKY
        }
    }

    private fun queueTranslationRequest(requestId: Long) {
        if (requestId <= 0L || sessionStopping) return
        // Screen pixels start flowing here and stop again as soon as one frame
        // has been taken (startFrameProcessing) or the request expires
        // (requestTimeout). Outside that window the display has no surface.
        if (virtualDisplay != null && !attachCaptureSurface()) {
            sendCaptureResult(
                captured = false,
                sessionId = activeGeneration,
                requestId = requestId,
                frame = frameSequence,
                errorMessage = getString(R.string.error_capture_start_failed),
            )
            return
        }
        translationPending = true
        pendingRequestId = requestId
        mainHandler.removeCallbacks(requestTimeout)
        mainHandler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS)
    }

    private fun startCaptureSession(captureIntent: Intent) {
        if (projection != null || virtualDisplay != null) {
            val requestId = captureIntent.getLongExtra(AppContracts.EXTRA_REQUEST_ID, 0L)
            if (!sessionStopping) queueTranslationRequest(requestId)
            return
        }

        val requestId = captureIntent.getLongExtra(AppContracts.EXTRA_REQUEST_ID, 0L)
        val resultCode = captureIntent.getIntExtra(
            AppContracts.EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED,
        )
        val data = try {
            readProjectionData(captureIntent)
        } catch (_: RuntimeException) {
            null
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            CaptureBus.permissionNeeded(requestId)
            stopSelf()
            return
        }

        sessionStopping = false
        sessionGeneration += 1
        activeGeneration = sessionGeneration
        frameSequence = 0L
        pendingRequestId = 0L
        translationPending = false
        processingFrame = false
        translationModelReady = false
        consecutiveTimeouts = 0
        val initialCaptureRequested = captureIntent.getBooleanExtra(
            AppContracts.EXTRA_CAPTURE_REQUESTED,
            true,
        )

        try {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build(),
            )

            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val manager = getSystemService(MediaProjectionManager::class.java)
                ?: throw IllegalStateException("MediaProjectionManager is unavailable")
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw SecurityException("MediaProjection permission was not accepted")

            val generation = activeGeneration
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (generation == activeGeneration && !sessionStopping) {
                        endSession(notifyPending = true)
                    }
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, mainHandler)
            if (!createCaptureDisplay(generation)) {
                failSession(getString(R.string.error_capture_start_failed))
                return
            }
        } catch (_: SecurityException) {
            failSession(getString(R.string.error_capture_permission))
            return
        } catch (_: IllegalStateException) {
            failSession(getString(R.string.error_capture_start_failed))
            return
        } catch (_: RuntimeException) {
            failSession(getString(R.string.error_capture_start_failed))
            return
        }

        CaptureBus.sessionStarted(activeGeneration)
        if (initialCaptureRequested) {
            queueTranslationRequest(requestId)
        } else {
            detachCaptureSurface()
        }
    }

    /**
     * Creates the one VirtualDisplay this session is allowed to have. Android 14+
     * throws a SecurityException on a second createVirtualDisplay() for the same
     * MediaProjection, so the display outlives individual requests and only its
     * surface is attached and detached.
     */
    @Suppress("DEPRECATION")
    private fun createCaptureDisplay(generation: Long): Boolean {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WindowManager::class.java) ?: return false
        windowManager.defaultDisplay.getRealMetrics(metrics)

        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        if (captureWidth <= 0 || captureHeight <= 0) return false

        val reader = newImageReader(generation) ?: return false
        return try {
            imageReader = reader
            virtualDisplay = projection?.createVirtualDisplay(
                "FloatingTranslatorCapture",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            surfaceAttached = virtualDisplay != null
            if (!surfaceAttached) closeImageReader()
            surfaceAttached
        } catch (_: RuntimeException) {
            closeImageReader()
            false
        }
    }

    private fun newImageReader(generation: Long): ImageReader? {
        return try {
            // A fresh reader per request: buffers acquired for an earlier request
            // can never be handed to a later one.
            ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener(
                    { reader -> onImageAvailable(reader, generation) },
                    mainHandler,
                )
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Points the session's display at a new reader so one frame can be taken. */
    private fun attachCaptureSurface(): Boolean {
        if (surfaceAttached) return true
        val display = virtualDisplay ?: return false
        val reader = newImageReader(activeGeneration) ?: return false
        return try {
            display.surface = reader.surface
            imageReader = reader
            surfaceAttached = true
            true
        } catch (_: RuntimeException) {
            closeReader(reader)
            false
        }
    }

    /**
     * Detaches the display's surface. The display stays alive but renders
     * nowhere, so the process stops receiving screen content entirely until the
     * next request attaches a new reader.
     */
    private fun detachCaptureSurface() {
        val display = virtualDisplay
        if (display != null && surfaceAttached) {
            try {
                display.surface = null
            } catch (_: RuntimeException) {
                // The display may already be released; the reader is closed below.
            }
        }
        surfaceAttached = false
        closeImageReader()
    }

    private fun closeImageReader() {
        val reader = imageReader
        imageReader = null
        closeReader(reader)
    }

    private fun closeReader(reader: ImageReader?) {
        if (reader == null) return
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (_: RuntimeException) {
            // The reader may already be closed by the system.
        }
        try {
            reader.close()
        } catch (_: RuntimeException) {
            // Nothing further to release on this reader.
        }
    }

    private fun onImageAvailable(reader: ImageReader, generation: Long) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        // Anything from a stopped session, an older generation, or a reader that
        // belonged to a previous request is discarded without being read.
        if (sessionStopping || generation != activeGeneration || reader !== imageReader) {
            closeImage(image)
            return
        }

        frameSequence += 1
        if (!translationPending || processingFrame) {
            closeImage(image)
            return
        }

        val requestId = pendingRequestId
        translationPending = false
        pendingRequestId = 0L
        mainHandler.removeCallbacks(requestTimeout)
        startFrameProcessing(image, generation, requestId)
    }

    private fun startFrameProcessing(image: Image, generation: Long, requestId: Long) {
        if (requestId <= 0L || processingFrame) {
            closeImage(image)
            detachCaptureSurface()
            return
        }

        processingFrame = true
        processingRequestId = requestId
        processingGeneration = generation
        consecutiveTimeouts = 0
        mainHandler.removeCallbacks(processingTimeout)
        mainHandler.postDelayed(processingTimeout, PROCESSING_TIMEOUT_MS)

        val bitmap = try {
            imageToBitmap(image)
        } catch (_: RuntimeException) {
            null
        } finally {
            closeImage(image)
        }
        // One frame is all this request needed. Stop the display from producing
        // any more before spending the next seconds in OCR and translation.
        detachCaptureSurface()

        if (bitmap == null) {
            finishProcessing(
                generation,
                requestId,
                captured = false,
                errorMessage = getString(R.string.error_frame_unavailable),
            )
            return
        }
        processingBitmap = bitmap

        val recognizer = textRecognizer
        if (recognizer == null) {
            finishProcessing(
                generation,
                requestId,
                captured = false,
                errorMessage = getString(R.string.error_ocr_unavailable),
            )
            return
        }

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    handleOcrResult(generation, requestId, result)
                }
                .addOnFailureListener {
                    finishProcessing(
                        generation,
                        requestId,
                        captured = false,
                        errorMessage = getString(R.string.error_ocr_failed),
                    )
                }
                .addOnCompleteListener {
                    releaseProcessingBitmap(bitmap)
                }
        } catch (_: RuntimeException) {
            releaseProcessingBitmap(bitmap)
            finishProcessing(
                generation,
                requestId,
                captured = false,
                errorMessage = getString(R.string.error_ocr_failed),
            )
        }
    }

    private fun handleOcrResult(generation: Long, requestId: Long, result: Text) {
        if (!isCurrentProcessing(generation, requestId)) return

        val lines = extractEnglishLines(result)
        if (lines.isEmpty()) {
            finishProcessing(
                generation,
                requestId,
                captured = true,
                errorMessage = getString(R.string.error_no_english_text),
            )
            return
        }

        downloadModelAndTranslate(generation, requestId, lines)
    }

    private fun extractEnglishLines(result: Text): List<String> {
        val resultLines = result.textBlocks
            .flatMap { block -> block.lines.map { line -> line.text.trim() } }
            .map { line -> line.replace(Regex("\\s+"), " ").trim() }
            .filter { line ->
                line.length >= 2 &&
                    line.any { character -> character in 'A'..'Z' || character in 'a'..'z' }
            }
            .distinct()

        val limited = ArrayList<String>()
        var totalCharacters = 0
        for (line in resultLines) {
            if (limited.size >= MAX_TRANSLATION_LINES) break
            if (totalCharacters + line.length > MAX_TRANSLATION_CHARACTERS) break
            limited += line
            totalCharacters += line.length
        }
        return limited
    }

    private fun downloadModelAndTranslate(
        generation: Long,
        requestId: Long,
        lines: List<String>,
    ) {
        if (!isCurrentProcessing(generation, requestId)) return
        val currentTranslator = translator
        if (currentTranslator == null) {
            finishProcessing(
                generation,
                requestId,
                captured = true,
                sourceLines = lines,
                errorMessage = getString(R.string.error_translation_unavailable),
            )
            return
        }

        if (translationModelReady) {
            translateNextLine(generation, requestId, lines, 0, ArrayList())
            return
        }

        try {
            currentTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    if (!isCurrentProcessing(generation, requestId)) return@addOnSuccessListener
                    translationModelReady = true
                    translateNextLine(generation, requestId, lines, 0, ArrayList())
                }
                .addOnFailureListener {
                    finishProcessing(
                        generation,
                        requestId,
                        captured = true,
                        sourceLines = lines,
                        errorMessage = getString(R.string.error_model_download),
                    )
                }
        } catch (_: RuntimeException) {
            finishProcessing(
                generation,
                requestId,
                captured = true,
                sourceLines = lines,
                errorMessage = getString(R.string.error_model_download),
            )
        }
    }

    private fun translateNextLine(
        generation: Long,
        requestId: Long,
        sourceLines: List<String>,
        index: Int,
        translatedLines: ArrayList<String>,
    ) {
        if (!isCurrentProcessing(generation, requestId)) return
        if (index >= sourceLines.size) {
            finishProcessing(
                generation = generation,
                requestId = requestId,
                captured = true,
                sourceLines = sourceLines,
                translatedLines = translatedLines,
            )
            return
        }

        val currentTranslator = translator
        if (currentTranslator == null) {
            finishProcessing(
                generation,
                requestId,
                captured = true,
                sourceLines = sourceLines,
                translatedLines = translatedLines,
                errorMessage = getString(R.string.error_translation_unavailable),
            )
            return
        }

        try {
            currentTranslator.translate(sourceLines[index])
                .addOnSuccessListener { translatedText ->
                    if (!isCurrentProcessing(generation, requestId)) return@addOnSuccessListener
                    translatedLines += translatedText.trim()
                    translateNextLine(
                        generation,
                        requestId,
                        sourceLines,
                        index + 1,
                        translatedLines,
                    )
                }
                .addOnFailureListener {
                    finishProcessing(
                        generation,
                        requestId,
                        captured = true,
                        sourceLines = sourceLines,
                        translatedLines = translatedLines,
                        errorMessage = getString(R.string.error_translation_failed),
                    )
                }
        } catch (_: RuntimeException) {
            finishProcessing(
                generation,
                requestId,
                captured = true,
                sourceLines = sourceLines,
                translatedLines = translatedLines,
                errorMessage = getString(R.string.error_translation_failed),
            )
        }
    }

    private fun isCurrentProcessing(generation: Long, requestId: Long): Boolean {
        return processingFrame &&
            !sessionStopping &&
            generation == activeGeneration &&
            generation == processingGeneration &&
            requestId == processingRequestId
    }

    private fun finishProcessing(
        generation: Long,
        requestId: Long,
        captured: Boolean,
        sourceLines: List<String> = emptyList(),
        translatedLines: List<String> = emptyList(),
        errorMessage: String? = null,
    ) {
        if (!isCurrentProcessing(generation, requestId)) return
        processingFrame = false
        processingRequestId = 0L
        processingGeneration = 0L
        mainHandler.removeCallbacks(processingTimeout)
        detachCaptureSurface()
        releaseProcessingBitmap()
        sendCaptureResult(
            captured = captured,
            sessionId = generation,
            requestId = requestId,
            frame = frameSequence,
            sourceLines = sourceLines,
            translatedLines = translatedLines,
            errorMessage = errorMessage,
        )
    }

    private fun sendCaptureResult(
        captured: Boolean,
        sessionId: Long,
        requestId: Long,
        frame: Long,
        sourceLines: List<String> = emptyList(),
        translatedLines: List<String> = emptyList(),
        errorMessage: String? = null,
    ) {
        CaptureBus.result(
            CaptureResult(
                sessionId = sessionId,
                requestId = requestId,
                captured = captured,
                sourceLines = sourceLines,
                translatedLines = translatedLines,
                errorMessage = errorMessage?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun endSession(notifyPending: Boolean) {
        if (sessionStopping) return
        val closingSessionId = activeGeneration
        val closingRequestId = pendingRequestId
        val hadPendingRequest = translationPending
        val processingRequest = processingRequestId
        val hadProcessingRequest = processingFrame
        sessionStopping = true
        activeGeneration = ++sessionGeneration
        mainHandler.removeCallbacks(requestTimeout)
        mainHandler.removeCallbacks(processingTimeout)
        translationPending = false
        pendingRequestId = 0L
        processingFrame = false
        processingRequestId = 0L
        processingGeneration = 0L
        releaseProcessingBitmap()

        CaptureBus.sessionStopped(closingSessionId)
        if (notifyPending) {
            val requestId = if (hadProcessingRequest) processingRequest else closingRequestId
            if ((hadProcessingRequest || hadPendingRequest) && requestId > 0L) {
                sendCaptureResult(
                    captured = false,
                    sessionId = closingSessionId,
                    requestId = requestId,
                    frame = frameSequence,
                    errorMessage = getString(R.string.error_capture_stopped),
                )
            }
        }
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failSession(errorMessage: String) {
        endSession(notifyPending = true)
        CaptureBus.permissionCancelled()
    }

    private fun releaseProcessingBitmap(bitmap: Bitmap? = null) {
        val current = processingBitmap
        if (bitmap != null && current !== bitmap) return
        processingBitmap = null
        try {
            // Overwrite the screen pixels before releasing the buffer, so they
            // are not sitting in freed heap memory for a later dump to find.
            if (current != null && !current.isRecycled && current.isMutable) {
                current.eraseColor(Color.TRANSPARENT)
            }
        } catch (_: RuntimeException) {
            // Scrubbing is best effort; the recycle below still runs.
        }
        try {
            current?.recycle()
        } catch (_: RuntimeException) {
            // The bitmap may already have been released by the ML Kit task.
        }
    }

    private fun releaseCapture() {
        // Cut the pixel path first, then tear down what produced it.
        detachCaptureSurface()

        val currentDisplay = virtualDisplay
        virtualDisplay = null
        try {
            currentDisplay?.release()
        } catch (_: RuntimeException) {
            // Continue releasing the remaining capture resources.
        }

        val currentProjection = projection
        projection = null
        val currentCallback = projectionCallback
        projectionCallback = null
        currentProjection?.let {
            try {
                currentCallback?.let { callback -> it.unregisterCallback(callback) }
            } catch (_: RuntimeException) {
                // The system may have already stopped the projection.
            }
            try {
                it.stop()
            } catch (_: RuntimeException) {
                // The system may have already stopped the projection.
            }
        }

        textRecognizer?.close()
        textRecognizer = null
        translator?.close()
        translator = null
        translationModelReady = false
        frameSequence = 0L
        captureWidth = 0
        captureHeight = 0
    }

    override fun onDestroy() {
        sessionStopping = true
        activeGeneration = ++sessionGeneration
        mainHandler.removeCallbacks(requestTimeout)
        mainHandler.removeCallbacks(processingTimeout)
        processingFrame = false
        processingRequestId = 0L
        processingGeneration = 0L
        releaseProcessingBitmap()
        releaseCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0 || image.width <= 0 || image.height <= 0) {
            return null
        }

        val rowPadding = (rowStride - pixelStride * image.width).coerceAtLeast(0)
        val paddedWidth = image.width + rowPadding / pixelStride
        val paddedBitmap = try {
            Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        } catch (_: RuntimeException) {
            return null
        }

        try {
            val buffer = plane.buffer.duplicate()
            buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(buffer)
        } catch (_: RuntimeException) {
            paddedBitmap.recycle()
            return null
        }

        val croppedBitmap = if (paddedWidth == image.width) {
            paddedBitmap
        } else {
            try {
                Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height).also {
                    paddedBitmap.recycle()
                }
            } catch (_: RuntimeException) {
                paddedBitmap.recycle()
                return null
            }
        }

        val largestDimension = maxOf(croppedBitmap.width, croppedBitmap.height)
        if (largestDimension <= MAX_OCR_DIMENSION) return croppedBitmap
        val scale = MAX_OCR_DIMENSION.toFloat() / largestDimension.toFloat()
        val scaled = try {
            Bitmap.createScaledBitmap(
                croppedBitmap,
                (croppedBitmap.width * scale).toInt().coerceAtLeast(1),
                (croppedBitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } catch (_: RuntimeException) {
            null
        }
        if (scaled != null && scaled !== croppedBitmap) croppedBitmap.recycle()
        return scaled ?: croppedBitmap
    }

    private fun closeImage(image: Image) {
        try {
            image.close()
        } catch (_: RuntimeException) {
            // The system may already have closed the frame.
        }
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AppContracts.EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(AppContracts.EXTRA_DATA)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            6202,
            Intent(this, ScreenCaptureService::class.java)
                .setAction(AppContracts.ACTION_STOP_CAPTURE_SESSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(getString(R.string.capture_notification_text))
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.stop_screen_capture),
                    stopIntent,
                )
                .build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(getString(R.string.capture_notification_text))
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.stop_screen_capture),
                    stopIntent,
                )
                .build()
        }
    }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 6201
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val MAX_CONSECUTIVE_TIMEOUTS = 2
        private const val PROCESSING_TIMEOUT_MS = 90_000L
        private const val MAX_OCR_DIMENSION = 2048
        private const val MAX_TRANSLATION_LINES = 24
        private const val MAX_TRANSLATION_CHARACTERS = 2_400
    }
}
