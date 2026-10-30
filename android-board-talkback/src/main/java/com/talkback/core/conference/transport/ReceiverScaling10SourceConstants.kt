package com.talkback.core.conference.transport

object ReceiverScaling10SourceConstants {
    const val LOG_TAG = "RX_SCALING_10SRC"
    const val PROBE_NAME = "PHASE1_RECEIVER_SCALING_10SOURCE_OBSERVATION"
    const val EVIDENCE_CLASS = "RECEIVER_SCALING_OBSERVATION"
    const val TOPOLOGY_NOTE = "3 real network sources + 7 synthetic sources"
    const val NOT_TEN_DEVICE_FIELD_EVIDENCE = true

    const val DEFAULT_BASE_SEQ: Int = 0x8000
    const val SYNTHETIC_BASE_SEQ: Int = 0x9000
    const val DEFAULT_SOAK_SEC: Int = 120
    const val FAIRNESS_ROTATE_MS: Long = 5_000L
    /** Sample jitter depth at this interval for growth / percentile stats. */
    const val JITTER_SAMPLE_MS: Long = 1_000L

    val NETWORK_SOURCES: List<String> = listOf("S1", "S2", "S3")
    val SYNTHETIC_SOURCES: List<String> =
        listOf("S4", "S5", "S6", "S7", "S8", "S9", "S10")
    val RECEIVER_SOURCES: List<String> = NETWORK_SOURCES + SYNTHETIC_SOURCES
    const val EXPECTED_SOURCE_COUNT: Int = 10

    val SENDER_SOURCE_BY_DEVICE: Map<String, String> =
        mapOf(
            "M01" to "S1",
            "M02" to "S2",
            "M03" to "S3",
        )
}
