package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0047 V-desk' D1–D7 — ordinary post-defer evaluability attribution (R2' / G1').
 */
class OrdinaryPostDeferEvaluabilityContractTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private var iceRestartCalls = 0
    private var canDispatch = true
    private var gateExecutable = true
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
        iceRestartCalls = 0
        canDispatch = true
        gateExecutable = true
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun buildController(
        iceRestartTimeoutMs: Long = 80L,
        attemptBudgetMs: Long = 400L
    ) = ConferenceEdgeRecoveryController(
        localModuleId = "M01",
        debounceMs = 20L,
        iceRestartTimeoutMs = iceRestartTimeoutMs,
        attemptBudgetMs = attemptBudgetMs,
        observationWindowMs = 100L,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ ->
            iceRestartCalls++
            false
        },
        canDispatchRecoveryMediaAction = { _, _ -> canDispatch },
        probeIceRestartGate = { _, _ ->
            if (gateExecutable) {
                IceRestartGateProbe(executable = true)
            } else {
                IceRestartGateProbe(
                    executable = false,
                    blockReason = IceRestartGateBlockReason.OFFER_AWAITING_ANSWER,
                    signalingState = "HAVE_LOCAL_OFFER"
                )
            }
        }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun driveOrdinaryRecoveryOpened(remote: String = "M03", gateOpen: Boolean = true) {
        gateExecutable = gateOpen
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(50)
        assertTrue(controller.edgeObligationOpen("sess-1", remote))
    }

    private fun driveNegotiationSettlingDefer(remote: String = "M03") {
        gateExecutable = false
        decisionLogs.clear()
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(50)
    }

    @Test
    fun adr0047_d1_d2_ordinaryOpenBindsIntentAndOwnerClass() {
        driveOrdinaryRecoveryOpened()
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ATTEMPT_OPENED") && it.contains("pathway=BEGIN_RECOVERY")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("ORDINARY_POST_DEFER_EVALUABILITY_INTENT_BOUND") &&
                    it.contains("ownerClass=CONTROLLER_EPISODE_ORDINARY") &&
                    it.contains("obligationGen=") &&
                    it.contains("attempt=")
            }
        )
        assertFalse(decisionLogs.any { it.contains("SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND") })
    }

    @Test
    fun adr0047_d3_d5_negotiationBudgetExhaustManifestsOrdinaryAttribution() {
        val intentId = controller.debugCreateDeferredNegotiationIntent(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            admissionSeq = 1L
        )
        assertTrue(intentId != null)
        assertTrue(decisionLogs.any { it.contains("ORDINARY_POST_DEFER_EVALUABILITY_INTENT_BOUND") })
        assertTrue(decisionLogs.any { it.contains("deferredReason=NEGOTIATION_SETTLING") })
        decisionLogs.clear()
        Thread.sleep(150)
        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_BUDGET_EXHAUSTED") })
        assertTrue(
            decisionLogs.any {
                it.contains("ORDINARY_POST_DEFER_EVALUABILITY_MANIFESTED") &&
                    it.contains("deferExitCategory=NEGOTIATION_BUDGET_EXHAUST") &&
                    it.contains("ownerClass=CONTROLLER_EPISODE_ORDINARY")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("ORDINARY_EPISODE_EVALUABILITY_ARMED") ||
                    it.contains("ORDINARY_EPISODE_EVALUABILITY_PENDING") ||
                    it.contains("ORDINARY_EPISODE_EVALUABILITY_RETAINED") ||
                    it.contains("RECOVERY_WATCHDOG_STARTED")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("ORDINARY_POST_DEFER_EVALUABILITY_MANIFESTED") &&
                    it.contains("SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND")
            }
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M03"))
    }

    @Test
    fun adr0047_d4_caseBClassMustNotLeaveHollowWithoutManifest() {
        controller.debugCreateDeferredNegotiationIntent(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            admissionSeq = 1L
        )
        Thread.sleep(150)
        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_BUDGET_EXHAUSTED") })
        assertTrue(
            "post-defer manifest required for S4' floor",
            decisionLogs.any { it.contains("ORDINARY_POST_DEFER_EVALUABILITY_MANIFESTED") }
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M03"))
    }

    @Test
    fun adr0047_d6_diagnosticOnlyDoesNotSatisfyManifest() {
        controller.debugCreateDeferredNegotiationIntent(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            admissionSeq = 1L
        )
        Thread.sleep(150)
        val manifestLogs = decisionLogs.filter {
            it.contains("ORDINARY_POST_DEFER_EVALUABILITY_MANIFESTED")
        }
        assertTrue(manifestLogs.isNotEmpty())
        assertFalse(
            manifestLogs.any {
                it.contains("DIAGNOSTIC_ONLY") || it.contains("OWNERSHIP_LOST")
            }
        )
    }

    @Test
    fun adr0047_d7_orthogonalitySmoke() {
        driveOrdinaryRecoveryOpened()
        assertFalse(decisionLogs.any { it.contains("RESIDENCY_CLEARED") })
        assertFalse(decisionLogs.any { it.contains("markRecovered") })
    }

    @Test
    fun adr0047_successorPathDoesNotBindOrdinaryIntent() {
        controller = ConferenceEdgeRecoveryController(
            localModuleId = "M02",
            debounceMs = 20L,
            iceRestartTimeoutMs = 80L,
            attemptBudgetMs = 500L,
            observationWindowMs = 100L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ ->
                iceRestartCalls++
                false
            },
            canDispatchRecoveryMediaAction = { _, _ -> canDispatch },
            probeIceRestartGate = { _, _ ->
                if (gateExecutable) IceRestartGateProbe(executable = true)
                else IceRestartGateProbe(
                    executable = false,
                    blockReason = IceRestartGateBlockReason.OFFER_AWAITING_ANSWER,
                    signalingState = "HAVE_LOCAL_OFFER"
                )
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        Thread.sleep(150)
        assertTrue(controller.edgeObligationClosed("sess-1", "M02"))
        nowMs += 5L
        decisionLogs.clear()
        gateExecutable = true

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = true
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            resurrectionEvidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertTrue(decisionLogs.any { it.contains("SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND") })
        assertFalse(decisionLogs.any { it.contains("ORDINARY_POST_DEFER_EVALUABILITY_INTENT_BOUND") })
    }
}
