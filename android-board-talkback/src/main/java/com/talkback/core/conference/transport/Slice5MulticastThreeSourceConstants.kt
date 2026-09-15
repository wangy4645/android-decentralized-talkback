package com.talkback.core.conference.transport

object Slice5MulticastThreeSourceConstants {
    const val LOG_TAG = "SLICE5_MC_THREE"
    const val PROBE_NAME = "PHASE1_SLICE5_MULTICAST_THREE_SOURCE"

    const val ALIGNED_MEDIA_SLOT: Int = 0x6000
    const val DEFAULT_BASE_SEQ: Int = 0x3000

    val RECEIVER_SOURCES: List<String> = listOf("S1", "S2", "S3")
    val SENDER_SOURCE_BY_DEVICE: Map<String, String> =
        mapOf(
            "M01" to "S1",
            "M02" to "S2",
            "M03" to "S3",
        )
}

enum class Slice5SlotMode {
    LIVE_SLOT,
    ALIGNED_SLOT,
}
