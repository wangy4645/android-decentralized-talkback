package com.talkback.core.conference.capacity

/**
 * G-RES-3 E2 sender fan-out execution model (harness probe only until E2 adjudication).
 */
enum class Gres3SenderExecutionModel {
    /** E0: one shared DatagramSocket, sequential 9×send. */
    SHARED_SOCKET_SEQUENTIAL,

    /** E1: one long-lived DatagramSocket per target, sequential 9×send on pacing thread. */
    PER_TARGET_SOCKET_SEQUENTIAL,

    /** E3: one blocking native sendmmsg(9 datagrams) per slot (architecture probe only). */
    SENDMMSG_BATCH,

    /** E3b: one nonblocking native sendmmsg(MSG_DONTWAIT, 9 datagrams) per slot (remediation probe). */
    SENDMMSG_BATCH_NONBLOCKING,
    ;

    val socketCount: Int
        get() =
            when (this) {
                SHARED_SOCKET_SEQUENTIAL,
                SENDMMSG_BATCH,
                SENDMMSG_BATCH_NONBLOCKING,
                -> 1
                PER_TARGET_SOCKET_SEQUENTIAL -> Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT
            }

    val usesSendmmsgBatch: Boolean
        get() = this == SENDMMSG_BATCH || this == SENDMMSG_BATCH_NONBLOCKING

    companion object {
        const val ERRNO_EAGAIN: Int = 11

        fun parse(value: String?): Gres3SenderExecutionModel =
            when (value?.trim()?.uppercase()) {
                "E3B", "SENDMMSG_BATCH_NONBLOCKING", "SENDMMSG_DONTWAIT", "SENDMMSG_NONBLOCKING" ->
                    SENDMMSG_BATCH_NONBLOCKING
                "E3", "SENDMMSG_BATCH", "SENDMMSG" -> SENDMMSG_BATCH
                "E1", "PER_TARGET_SOCKET_SEQUENTIAL", "PER_TARGET" -> PER_TARGET_SOCKET_SEQUENTIAL
                "E0", "SHARED_SOCKET_SEQUENTIAL", "SHARED_SOCKET", null, "" -> SHARED_SOCKET_SEQUENTIAL
                else -> error("unknown executionModel: $value")
            }
    }
}
