package com.talkback.core.conference.transport

object Slice5bMulticastSoakConstants {
    const val LOG_TAG = "SLICE5B_MC_SOAK"
    const val PROBE_NAME = "PHASE1_SLICE5B_MULTICAST_THREE_SOURCE_SOAK"
    const val DEFAULT_BASE_SEQ: Int = 0x3000
    const val DEFAULT_SOAK_SEC: Int = 120

    val RECEIVER_SOURCES: List<String> = listOf("S1", "S2", "S3")
    val SENDER_SOURCE_BY_DEVICE: Map<String, String> =
        mapOf(
            "M01" to "S1",
            "M02" to "S2",
            "M03" to "S3",
        )
}
