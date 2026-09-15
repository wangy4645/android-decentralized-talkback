package com.talkback.core.conference.transport

object Slice4bMulticastMultiSourceConstants {
    const val LOG_TAG = "SLICE4B_MC_MULTI"
    const val PROBE_NAME = "PHASE1_SLICE4B_MULTICAST_MULTI_SOURCE"

    /** Shared media slot for cross-device alignment in the dual-sender probe. */
    const val ALIGNED_MEDIA_SLOT: Int = 0x6000

    val RECEIVER_SOURCES: List<String> = listOf("S1", "S2")
    val SENDER_SOURCE_BY_DEVICE: Map<String, String> =
        mapOf(
            "M01" to "S1",
            "M02" to "S2",
        )
}
