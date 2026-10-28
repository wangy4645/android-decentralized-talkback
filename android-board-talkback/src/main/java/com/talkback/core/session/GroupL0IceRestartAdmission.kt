package com.talkback.core.session

/**
 * GROUP L0 ICE-restart execution gate. [IceRestartGateProbe] is capability truth;
 * this object decides whether Coordinator may actually dispatch a restart offer.
 */
internal object GroupL0IceRestartAdmission {
    enum class Decision {
        ALLOW,
        BLOCK_GATE,
        BLOCK_CHECKING_WINDOW,
    }

    fun evaluate(
        probeExecutable: Boolean,
        iceConnectionState: String?,
        checkingAgeMs: Long,
        timeoutMs: Long,
    ): Decision {
        if (!probeExecutable) return Decision.BLOCK_GATE
        val ice = iceConnectionState?.uppercase()
        if (ice == "CHECKING" || ice == "NEW") {
            if (checkingAgeMs < timeoutMs) return Decision.BLOCK_CHECKING_WINDOW
        }
        return Decision.ALLOW
    }
}
