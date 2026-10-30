package com.talkback.core.conference.transport

object ReceiverScaling4SourceConstants {
    const val LOG_TAG = "RX_SCALING_4SRC"
    const val PROBE_NAME = "PHASE1_RECEIVER_SCALING_4SOURCE_OBSERVATION"
    const val EVIDENCE_CLASS = "RECEIVER_SCALING_OBSERVATION"
    const val TOPOLOGY_NOTE = "3 real network sources + 1 synthetic source"
    const val NOT_FOUR_DEVICE_FIELD_EVIDENCE = true

    const val DEFAULT_BASE_SEQ: Int = 0x4000
    const val SYNTHETIC_BASE_SEQ: Int = 0x5000
    const val DEFAULT_SOAK_SEC: Int = 120

    val NETWORK_SOURCES: List<String> = listOf("S1", "S2", "S3")
    const val SYNTHETIC_SOURCE: String = "S4"
    val RECEIVER_SOURCES: List<String> = NETWORK_SOURCES + SYNTHETIC_SOURCE

    val SENDER_SOURCE_BY_DEVICE: Map<String, String> =
        mapOf(
            "M01" to "S1",
            "M02" to "S2",
            "M03" to "S3",
        )
}
