package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress

/**
 * Materializes Profile01 member bindings into shadow multicast wiring after registry
 * convergence — including late peer local SOURCE replay (G2 M03 TX seam).
 */
class Profile01ShadowMemberBindingMaterializer(
    private val factPort: ConferenceSessionMediaFactPort,
    private val ingress: Profile01ConferenceMediaFactIngress,
    private val localModuleId: () -> String,
    private val readSignedLocalSource: (sessionId: String) -> ByteArray?,
    private val retryLocalSourceBuild: (sessionId: String) -> Unit = {},
) {
    fun onCreationWireApplied(sessionId: String) {
        materializeLocalIfReady(sessionId)
    }

    fun onSourceDeclarationWireApplied(
        sessionId: String,
        moduleId: String,
    ) {
        replayIntoShadowWiring(sessionId, moduleId)
    }

    fun onLocalSourceBuilt(
        sessionId: String,
        moduleId: String,
        signedFactBytes: ByteArray,
    ) {
        if (moduleId != localModuleId()) return
        ingestIfNeeded(sessionId, moduleId, signedFactBytes)
        replayIntoShadowWiring(sessionId, moduleId)
    }

    fun materializeLocalIfReady(sessionId: String) {
        retryLocalSourceBuild(sessionId)
        val local = localModuleId()
        val signed = readSignedLocalSource(sessionId)
        if (signed != null) {
            ingestIfNeeded(sessionId, local, signed)
        }
        replayIntoShadowWiring(sessionId, local)
    }

    private fun ingestIfNeeded(
        sessionId: String,
        moduleId: String,
        signedFactBytes: ByteArray,
    ) {
        if (factPort.memberBinding(sessionId, moduleId) != null) return
        val result = ingress.ingestSourceDeclarationSignedFact(signedFactBytes)
        val publishOutcome = result.ingress?.publishOutcome
        if (
            publishOutcome != ControlFactPublishOutcome.ACCEPTED &&
            publishOutcome != ControlFactPublishOutcome.IDEMPOTENT
        ) {
            return
        }
    }

    private fun replayIntoShadowWiring(
        sessionId: String,
        moduleId: String,
    ) {
        if (factPort.memberBinding(sessionId, moduleId) == null) return
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        if (moduleId == localModuleId()) {
            Profile01ShadowRuntimeObservability.logShadowTxBindingInstalled(sessionId, moduleId)
        }
    }
}
