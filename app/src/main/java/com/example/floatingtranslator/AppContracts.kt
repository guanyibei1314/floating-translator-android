package com.example.floatingtranslator

object AppContracts {
    const val ACTION_CAPTURE_FINISHED =
        "com.example.floatingtranslator.action.CAPTURE_FINISHED"
    const val ACTION_START_CAPTURE_SESSION =
        "com.example.floatingtranslator.action.START_CAPTURE_SESSION"
    const val ACTION_REQUEST_TRANSLATION =
        "com.example.floatingtranslator.action.REQUEST_TRANSLATION"
    const val ACTION_STOP_CAPTURE_SESSION =
        "com.example.floatingtranslator.action.STOP_CAPTURE_SESSION"
    const val ACTION_CAPTURE_PERMISSION_NEEDED =
        "com.example.floatingtranslator.action.CAPTURE_PERMISSION_NEEDED"
    const val ACTION_CAPTURE_SESSION_STARTED =
        "com.example.floatingtranslator.action.CAPTURE_SESSION_STARTED"
    const val ACTION_CAPTURE_SESSION_STOPPED =
        "com.example.floatingtranslator.action.CAPTURE_SESSION_STOPPED"
    const val ACTION_CAPTURE_PERMISSION_CANCELLED =
        "com.example.floatingtranslator.action.CAPTURE_PERMISSION_CANCELLED"
    const val ACTION_TRANSLATION_STATUS =
        "com.example.floatingtranslator.action.TRANSLATION_STATUS"
    const val INTERNAL_CAPTURE_RESULT_PERMISSION =
        "com.example.floatingtranslator.permission.INTERNAL_CAPTURE_RESULT"
    const val EXTRA_RESULT_CODE = "projection_result_code"
    const val EXTRA_DATA = "projection_data"
    const val EXTRA_CAPTURED = "capture_completed"
    const val EXTRA_CAPTURE_REQUESTED = "capture_requested"
    const val EXTRA_SESSION_ID = "capture_session_id"
    const val EXTRA_REQUEST_ID = "capture_request_id"
    const val EXTRA_FRAME_SEQUENCE = "capture_frame_sequence"
    const val EXTRA_SOURCE_LINES = "translation_source_lines"
    const val EXTRA_TRANSLATED_LINES = "translation_translated_lines"
    const val EXTRA_ERROR_MESSAGE = "translation_error_message"
    const val EXTRA_STATUS_MESSAGE = "translation_status_message"
}
