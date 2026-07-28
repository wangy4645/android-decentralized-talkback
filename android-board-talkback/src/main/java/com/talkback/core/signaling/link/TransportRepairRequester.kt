package com.talkback.core.signaling.link

/** R28-L.1.4: transport-owned qualification repair entry; Recovery MUST NOT implement. */
interface TransportRepairRequester {
    fun requestQualificationRepair(reason: QualificationFailureReason)
}

enum class QualificationFailureReason {
    QUALIFICATION_TIMEOUT,
    SOCKET_ERROR,
    NETWORK_CHANGED,
    MANUAL_RECONNECT
}
