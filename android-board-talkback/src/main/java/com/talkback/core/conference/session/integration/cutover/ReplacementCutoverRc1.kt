package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly

/**
 * Replacement Cutover RC1 — GA default audible ownership transition.
 */
object ReplacementCutoverRc1 {
    /**
     * GA kill-switch for audible multicast cutover only.
     * Does not disable shadow multicast runtime / observability.
     * Default ON — release builds cut over automatically when readiness is satisfied.
     */
    @Volatile
    var multicastAudibleEnabled: Boolean = true

    @Volatile
    private var controller: AudibleOwnershipController? = null

    fun install(
        anchorPort: AnchorAudiblePort,
        wiringProvider: () -> ConferenceSessionMediaWiring? = { ConferenceSessionMediaBridge.wiring },
    ) {
        val multicastPort = WiringMulticastAudiblePort(wiringProvider)
        controller =
            AudibleOwnershipController(
                anchorPort = anchorPort,
                multicastPort = multicastPort,
                shadowSessionActive = { sessionId -> ConferenceSessionMediaBridge.hasSession(sessionId) },
                multicastAudibleEnabled = { multicastAudibleEnabled },
            )
    }

    fun controller(): AudibleOwnershipController? = controller

    fun armCutover(sessionId: String): CutoverOutcome =
        controller()?.armCutover(sessionId) ?: CutoverOutcome.REJECTED_PREREQUISITE

    fun executeCutover(sessionId: String): CutoverOutcome =
        controller()?.executeCutover(sessionId) ?: CutoverOutcome.REJECTED_PREREQUISITE

    fun rollback(
        sessionId: String,
        trigger: RollbackTrigger = RollbackTrigger.MANUAL,
    ): CutoverOutcome =
        controller()?.rollback(sessionId, trigger) ?: CutoverOutcome.REJECTED_PREREQUISITE

    fun currentState(): AudibleOwnershipState? = controller()?.state

    fun onReplacementRuntimeFatal(sessionId: String) {
        controller()?.onReplacementRuntimeFatal(sessionId)
    }

    fun onSessionTeardown(sessionId: String): CutoverOutcome =
        controller()?.onSessionTeardown(sessionId) ?: CutoverOutcome.REJECTED_PREREQUISITE
}

internal class WiringMulticastAudiblePort(
    private val wiringProvider: () -> ConferenceSessionMediaWiring?,
) : MulticastAudiblePort {
    override fun fenceProductionPlayout(sessionId: String) {
        audibleSeam(sessionId)?.fenceProductionPlayout()
    }

    override fun acquireProductionAudioTrack(sessionId: String): Boolean =
        audibleSeam(sessionId)?.acquireProductionAudioTrack() == true

    override fun releaseProductionAudioTrack(sessionId: String) {
        audibleSeam(sessionId)?.releaseProductionAudioTrack()
    }

    override fun isProductionAudioTrackActive(sessionId: String): Boolean =
        audibleSeam(sessionId)?.isProductionAudioTrackActive() == true

    private fun audibleSeam(sessionId: String): AudiblePlayoutOwnershipSeam? =
        wiringProvider()?.audiblePlayoutSeam(sessionId)
}

internal fun ConferenceMulticastRealMediaAssembly.audiblePlayoutSeam(): AudiblePlayoutOwnershipSeam? =
    playoutSeam as? AudiblePlayoutOwnershipSeam
