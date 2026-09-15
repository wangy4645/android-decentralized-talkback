package com.talkback.core.conference.transport

object LiveSlotTimingDiagnosticConstants {
    const val LOG_TAG = "LIVE_SLOT_TIMING"
    const val PROBE_NAME = "PHASE1_LIVE_SLOT_TIMING_DIAGNOSTIC"
    const val DEFAULT_BASE_SEQ: Int = 0x3000
    const val DEFAULT_DURATION_SEC: Int = 90
    const val TRAIL_CAP: Int = 500

    val RECEIVER_SOURCES: List<String> = listOf("S1", "S2", "S3")
    val SENDER_SOURCE_BY_DEVICE: Map<String, String> =
        mapOf(
            "M01" to "S1",
            "M02" to "S2",
            "M03" to "S3",
        )
}
