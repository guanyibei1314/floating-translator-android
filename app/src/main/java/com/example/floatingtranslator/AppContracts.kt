package com.example.floatingtranslator

/**
 * Actions and extras for the explicit Intents this app sends to its own
 * non-exported ScreenCaptureService.
 *
 * Events flowing back out of the capture service do not appear here: they are
 * delivered in-process through [CaptureBus], so recognized screen text is never
 * packed into an Intent at all.
 */
object AppContracts {
    const val ACTION_START_CAPTURE_SESSION =
        "com.example.floatingtranslator.action.START_CAPTURE_SESSION"
    const val ACTION_REQUEST_TRANSLATION =
        "com.example.floatingtranslator.action.REQUEST_TRANSLATION"
    const val ACTION_STOP_CAPTURE_SESSION =
        "com.example.floatingtranslator.action.STOP_CAPTURE_SESSION"
    const val EXTRA_RESULT_CODE = "projection_result_code"
    const val EXTRA_DATA = "projection_data"
    const val EXTRA_CAPTURE_REQUESTED = "capture_requested"
    const val EXTRA_REQUEST_ID = "capture_request_id"
}
