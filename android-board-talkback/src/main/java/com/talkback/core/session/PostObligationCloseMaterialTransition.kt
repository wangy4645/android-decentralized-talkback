package com.talkback.core.session

/**
 * #175 Q5: material edge transitions that reopen post-close admission decision surface.
 * Detection lives outside [PostObligationCloseConvergence] — transport events feed the controller.
 */
internal object PostObligationCloseMaterialTransition {

    const val ICE_CONNECTED_TRIGGER = "ICE_CONNECTED"

    /** Post-close failed-media residency window — decision surface may reopen on material evidence. */
    fun isPostCloseResidencyActive(record: EdgeRecoveryRecord): Boolean =
        record.obligationClosedAtMs != null &&
            !record.edgeObligationOpen() &&
            record.phase.isFailedMediaRecovery()

    /**
     * ICE to CONNECTED is material (#175 Q5); CHECKING / heartbeat alone are not.
     * Caller MUST invoke from transport CONNECTED handler only.
     */
    fun isIceConnectedMaterialForPostClose(
        record: EdgeRecoveryRecord,
        iceConnected: Boolean
    ): Boolean = iceConnected && isPostCloseResidencyActive(record)
}
