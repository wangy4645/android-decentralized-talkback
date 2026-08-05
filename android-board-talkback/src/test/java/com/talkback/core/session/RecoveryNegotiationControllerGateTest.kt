package com.talkback.core.session

import com.talkback.core.util.MediaRecoveryCausalTrace
import com.talkback.core.util.RecoveryNegotiationAuthority
import com.talkback.core.util.RecoveryNegotiationObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ADR-0037 Phase 3.2 Controller Gate - lifecycle integration (not pure authority rules).
 */
class RecoveryNegotiationControllerGateTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private val decisionLogs = mutableListOf<String>()
    private val observationLines = mutableListOf<String>()
    private val ingressLines = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-p32-gate"
    private val remoteModuleId = "M01"
    private val channelId = "CH-GATE"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        decisionLogs.clear()
        observationLines.clear()
        ingressLines.clear()
        RecoveryNegotiationObservation.resetForTest { observationLines.add(it) }
        MediaRecoveryCausalTrace.resetForTest { ingressLines.add(it) }
        controller = buildController(localModuleId = "M03")
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
        RecoveryNegotiationObservation.resetForTest(null)
        MediaRecoveryCausalTrace.resetForTest(null)
    }

    private fun buildController(localModuleId: String) = ConferenceEdgeRecoveryController(
        localModuleId = localModuleId,
        debounceMs = 20L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = 500L,
        observationWindowMs = 10_000L,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ ->
            iceRestartCalls++
            true
        },
        probeIceRestartGate = { _, _ ->
            if (canExecute) IceRestartGateProbe(executable = true)
            else IceRestartGateProbe(
                executable = false,
                blockReason = IceRestartGateBlockReason.ANSWERER_SETTLING,
                signalingState = "STABLE",
                localRole = "ANSWERER"
            )
        },
        onNegotiationGateDeferred = { _, _, bind -> bind(1L) }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun edgeRecord(): EdgeRecoveryRecord? {
        val field = ConferenceEdgeRecoveryController::class.java.getDeclaredField("edges")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val edges = field.get(controller) as ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>
        return edges[ConferenceEdgeKey(sessionId, remoteModuleId)]
    }

    private fun closeObligationKeepingEdge() {
        val record = edgeRecord() ?: return
        record.obligationClosedAtMs = nowMs
        record.obligationCloseReason = ObligationCloseReason.MEMBERSHIP_LEFT
    }

    private fun bootstrapCanonicalOwnerM01() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertEquals("M01", controller.negotiationOwnerModuleId(sessionId, remoteModuleId))
    }

    private fun deferNegotiationIntent(): String {
        canExecute = false
        bootstrapCanonicalOwnerM01()
        assertTrue(decisionLogs.any { it.contains("ICE_RESTART_DEFERRED") })
        val intentId = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(intentId)
        return intentId!!
    }

    private fun advanceScheduled(ms: Long) {
        nowMs += ms
        Thread.sleep(ms + 50L)
    }

    @Test
    fun gate1_ownerConflict_canonicalUnchanged_noAdopt_noReElection() {
        bootstrapCanonicalOwnerM01()
        val episodeId = edgeRecord()!!.recoveryAttemptId

        val wireResult = controller.validateInboundNegotiationOwner(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            wireOwnerModuleId = "M03",
            recoveryEpisodeId = episodeId
        )
        assertNotNull(wireResult)
        assertEquals(RecoveryNegotiationAuthority.WireOwnerValidation.CONFLICT, wireResult!!.validation)
        assertEquals("M01", wireResult.canonicalOwner)

        RecoveryNegotiationObservation.emitOwnerConflict(
            sessionId = sessionId,
            edgeModuleId = remoteModuleId,
            episodeId = episodeId,
            canonicalOwner = wireResult.canonicalOwner,
            wireOwner = wireResult.wireOwner ?: "M03",
            trigger = "INBOUND_RECOVERY_OFFER"
        )
        MediaRecoveryCausalTrace.recoveryOfferReceived(
            ctx = MediaRecoveryCausalTrace.Context(
                sessionId = sessionId,
                sessionTraceId = "trace-gate1",
                scope = com.talkback.core.webrtc.MediaBearerScope.CONFERENCE,
                remoteModuleId = remoteModuleId,
                remoteEndpointId = "E01",
                recoveryAttemptId = episodeId,
                obligationGeneration = 1L,
                conferenceGeneration = 1L,
                pcGeneration = 1L,
                transportGeneration = 1L,
                iceRestart = true
            ),
            decision = MediaRecoveryCausalTrace.OfferIngressDecision.DROP_OWNERSHIP_CONFLICT,
            joinIntent = "RECOVERY_REATTACH",
            localIceState = "DISCONNECTED",
            detail = "ownership_conflict"
        )

        controller.adoptInboundNegotiationOwner(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            ownerModuleId = "M03",
            trigger = "SHOULD_NOT_ADOPT_ON_CONFLICT"
        )

        assertEquals("M01", controller.negotiationOwnerModuleId(sessionId, remoteModuleId))
        assertEquals("M01", edgeRecord()?.canonicalNegotiationOwnerModuleId)
        assertFalse(decisionLogs.any { it.contains("RECOVERY_NEGOTIATION_OWNER_ADOPTED") })
        assertTrue(observationLines.any { it.startsWith("RECOVERY_NEGOTIATION_OWNER_CONFLICT") })
        assertTrue(ingressLines.any { it.contains("decision=DROP_OWNERSHIP_CONFLICT") })
        assertTrue(observationLines.all { !it.contains("negotiationEpoch=1") })
    }

    @Test
    fun gate2_glareAcceptRemote_blockedByGlare_releasesSlot_noMediaFailure() {
        val intentId = deferNegotiationIntent()
        assertFalse(
            controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase ==
                EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
        )

        controller.onNegotiationGlareAcceptRemote(sessionId, remoteModuleId, "GLARE_ACCEPT_REMOTE")

        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(
            observationLines.any {
                it.contains("state=BLOCKED_BY_GLARE") && it.contains("intentId=$intentId")
            }
        )
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_NEGOTIATION_INTENT_TERMINAL") &&
                    it.contains("terminalState=BLOCKED_BY_GLARE")
            }
        )
        assertFalse(decisionLogs.any { it.contains("FAILED_MEDIA_RECOVERY") })
        assertFalse(decisionLogs.any { it.contains("OWNER_BLOCKED") })

        advanceScheduled(600L)
        assertFalse(
            controller.attemptLineageObservation(sessionId, remoteModuleId)?.phase ==
                EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
        )
    }

    @Test
    fun gate3a_obligationClose_expiredTerminal_releasesSlot_newIntentAllocatable() {
        val firstIntent = deferNegotiationIntent()
        controller.cancelSession(sessionId, "session_cancelled")

        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_NEGOTIATION_INTENT_TERMINAL") &&
                    it.contains("intentId=$firstIntent") &&
                    it.contains("EXPIRED")
            }
        )
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))

        val followUpSession = "sess-p32-gate-follow"
        decisionLogs.clear()
        observationLines.clear()
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = followUpSession,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        val secondIntent = controller.pendingIceRestartIntentId(followUpSession, remoteModuleId)
        assertNotNull(secondIntent)
        assertNotEquals(firstIntent, secondIntent)
    }

    @Test
    fun gate3b_drainObligationClosed_expiredTerminal_releasesSlot() {
        val intentId = deferNegotiationIntent()
        closeObligationKeepingEdge()
        decisionLogs.clear()
        observationLines.clear()

        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_NEGOTIATION_INTENT_TERMINAL") &&
                    it.contains("intentId=$intentId") &&
                    it.contains("EXPIRED")
            }
        )
    }

    @Test
    fun gate3c_negotiationSettlingDefer_classifiesTensionOutcome() {
        deferNegotiationIntent()
        decisionLogs.clear()
        observationLines.clear()
        advanceScheduled(800L)

        val expiredTerminal = observationLines.any {
            it.contains("RECOVERY_NEGOTIATION_INTENT_TERMINAL") && it.contains("EXPIRED")
        }
        val failedMedia = decisionLogs.any { it.contains("FAILED_MEDIA_RECOVERY") }
        val ownerBlocked = decisionLogs.any { it.contains("OWNER_BLOCKED") }
        val stillDeferred = controller.pendingIceRestartIntentId(sessionId, remoteModuleId) != null

        val outcome = when {
            expiredTerminal -> "EXPIRED_TERMINAL"
            failedMedia || ownerBlocked -> "FAILED_MEDIA_RECOVERY_OR_OWNER_BLOCKED"
            stillDeferred -> "DEFERRED_DANGLING"
            else -> "UNKNOWN"
        }

        assertTrue(
            outcome in setOf(
                "EXPIRED_TERMINAL",
                "FAILED_MEDIA_RECOVERY_OR_OWNER_BLOCKED",
                "DEFERRED_DANGLING"
            )
        )
        if (outcome == "FAILED_MEDIA_RECOVERY_OR_OWNER_BLOCKED") {
            assertFalse(expiredTerminal)
        }
    }
}
