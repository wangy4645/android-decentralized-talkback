package com.talkback.core.session

import com.talkback.core.util.SuppressSuccessorAttemptDebugInjection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * G-RESURRECT-0..5 — successor obligation admission (ADR-0022 §13.2.4 Gap-2).
 */
class SuccessorObligationAdmissionTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private var iceRestartCalls = 0
    private var canDispatch = true
    private var iceConnected = false
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
        iceRestartCalls = 0
        canDispatch = true
        iceConnected = false
        SuppressSuccessorAttemptDebugInjection.resetForTest()
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        SuppressSuccessorAttemptDebugInjection.resetForTest()
        scheduler.shutdownNow()
    }

    private fun buildController(
        observationWindowMs: Long = 100L,
        attemptBudgetMs: Long = 500L
    ) = ConferenceEdgeRecoveryController(
        localModuleId = "LOCAL",
        debounceMs = 20L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = attemptBudgetMs,
        observationWindowMs = observationWindowMs,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ ->
            iceRestartCalls++
            false
        },
        isIceConnected = { _, _ -> iceConnected },
        canDispatchRecoveryMediaAction = { _, _ -> canDispatch }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun reachabilitySnapshot(
        mediaRouteConnected: Boolean = false,
        authorityReachable: Boolean = true
    ) = EdgeReachabilitySnapshot(
        linkReady = true,
        peerDiscovered = true,
        peerSignalingReachable = true,
        mediaRouteConnected = mediaRouteConnected,
        authorityReachable = authorityReachable
    )

    private fun hostCapability(
        snapshot: EdgeReachabilitySnapshot = reachabilitySnapshot(),
        controlPlaneStarted: Boolean = false
    ) = projectRecoveryCapabilitySignature(
        snapshot,
        initiatesReattach = false,
        controlPlaneStarted = controlPlaneStarted
    )

    /** Drive host edge to FAILED_MEDIA 鈫?OBLIGATION_DEADLINE CLOSED. */
    private fun driveHostObligationDeadlineClosed(remote: String = "M02"): Long {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(controller.edgeObligationOpen("sess-1", remote))
        val gen1 = controller.obligationGeneration("sess-1", remote)!!
        assertEquals(1, iceRestartCalls)
        Thread.sleep(150)
        assertTrue(controller.edgeObligationClosed("sess-1", remote))
        assertFalse(controller.edgeObligationOpen("sess-1", remote))
        assertEquals(
            ObligationCloseReason.OBLIGATION_DEADLINE,
            controller.obligationCloseReason("sess-1", remote)
        )
        return gen1
    }

    private fun notifyReachability(
        trigger: RecoveryReevaluateTrigger,
        evidence: RecoveryResurrectionEvidence? = null,
        remote: String = "M02",
        snapshot: EdgeReachabilitySnapshot = reachabilitySnapshot()
    ) {
        val signature = hostCapability(snapshot)
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = remote,
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = trigger,
            resurrectionEvidence = evidence
        )
    }

    @Test
    fun gResurrect0_invalidEvidenceBinding_deniesWithoutGenBump() {
        val gen1 = driveHostObligationDeadlineClosed()
        nowMs += 10L
        decisionLogs.clear()

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.PEER_DISCOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertTrue(decisionLogs.any { it.contains("RECOVERY_INVALID_EVIDENCE_BINDING") })
        assertEquals(gen1, controller.obligationGeneration("sess-1", "M02"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M02"))
    }

    @Test
    fun gResurrect1_closedFreshEvidence_admitsSuccessorGenPlusOne() {
        val gen1 = driveHostObligationDeadlineClosed()
        val attemptBefore = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        nowMs += 5L
        decisionLogs.clear()

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        val gen2 = controller.obligationGeneration("sess-1", "M02")!!
        assertEquals(gen1 + 1L, gen2)
        val attemptAfter = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertTrue(attemptAfter > attemptBefore)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_OBLIGATION_OPENED") && it.contains("obligationGen=$gen2")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("pathway=NEW_OBLIGATION_EPISODE") ||
                    it.contains("ADMIT_SUCCESSOR_OBLIGATION_EPISODE")
            }
        )
    }

    @Test
    fun gResurrect2_closedStaleEvidence_isNoOp() {
        val gen1 = driveHostObligationDeadlineClosed()
        val closedAt = nowMs
        nowMs = closedAt + 20L
        decisionLogs.clear()

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = closedAt
            )
        )

        assertEquals(gen1, controller.obligationGeneration("sess-1", "M02"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M02"))
        assertFalse(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
    }

    @Test
    fun gResurrect3_openObligation_reevaluatesWithoutGenBump() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        val gen = controller.obligationGeneration("sess-1", "M02")!!
        val attempt = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        Thread.sleep(80)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        decisionLogs.clear()
        nowMs += 10L

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertEquals(gen, controller.obligationGeneration("sess-1", "M02"))
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REEVALUATE") })
        assertFalse(
            decisionLogs.any {
                it.contains("pathway=NEW_OBLIGATION_EPISODE") ||
                    it.contains("ADMIT_SUCCESSOR_OBLIGATION_EPISODE")
            }
        )
        val attemptAfter = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertTrue(attemptAfter >= attempt)
        assertEquals(gen, controller.obligationGeneration("sess-1", "M02"))
    }

    @Test
    fun gResurrect4_staleTerminalFact_rejectedByDualKey() {
        val gen1 = driveHostObligationDeadlineClosed()
        val staleAttemptId = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        nowMs += 5L
        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertEquals(gen1 + 1L, controller.obligationGeneration("sess-1", "M02")!!)
        val currentAttempt = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertFalse(
            controller.canMarkLineageRecovered(
                sessionId = "sess-1",
                remoteModuleId = "M02",
                factAttemptId = staleAttemptId,
                factObligationGeneration = gen1
            )
        )
        assertTrue(
            controller.canMarkLineageRecovered(
                sessionId = "sess-1",
                remoteModuleId = "M02",
                factAttemptId = currentAttempt,
                factObligationGeneration = gen1 + 1L
            )
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
    }

    @Test
    fun gResurrect5_successorExecutionStateClean_noInheritedIceRestartIssued() {
        canDispatch = true
        driveHostObligationDeadlineClosed()
        assertEquals(1, iceRestartCalls)

        canDispatch = false
        nowMs += 5L
        decisionLogs.clear()
        val restartsBeforeAdmit = iceRestartCalls

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertEquals(restartsBeforeAdmit, iceRestartCalls)
        assertFalse(controller.attemptLineageObservation("sess-1", "M02")!!.mediaRestored)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") ||
                    it.contains("owner=HOST_RESTART")
            }
        )

        canDispatch = true
        nowMs += 1L
        notifyReachability(
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED,
            snapshot = reachabilitySnapshot(authorityReachable = true)
        )
        assertEquals(restartsBeforeAdmit + 1, iceRestartCalls)
    }

    @Test
    fun gResurrect6_closedWithMediaRestoredResidual_stillAdmits() {
        // Incomplete ICE restart leaves mediaRestored=true; deadline must not make that permanent.
        controller = ConferenceEdgeRecoveryController(
            localModuleId = "LOCAL",
            debounceMs = 20L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 500L,
            observationWindowMs = 100L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ ->
                iceRestartCalls++
                iceConnected = true
                false
            },
            isIceConnected = { _, _ -> iceConnected },
            canDispatchRecoveryMediaAction = { _, _ -> canDispatch }
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
        val gen1 = controller.obligationGeneration("sess-1", "M02")!!
        assertTrue(controller.attemptLineageObservation("sess-1", "M02")!!.mediaRestored)

        // Watchdog (~220ms) → FAILED_MEDIA residency, then observation window (~100ms) → CLOSED.
        Thread.sleep(280)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        Thread.sleep(150)
        assertTrue(controller.edgeObligationClosed("sess-1", "M02"))
        assertEquals(
            ObligationCloseReason.OBLIGATION_DEADLINE,
            controller.obligationCloseReason("sess-1", "M02")
        )
        assertTrue(controller.attemptLineageObservation("sess-1", "M02")!!.mediaRestored)

        // Closed-edge ICE bookkeeping must not clear residual mediaRestored (bugbot scenario).
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(controller.attemptLineageObservation("sess-1", "M02")!!.mediaRestored)

        nowMs += 5L
        decisionLogs.clear()
        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertEquals(gen1 + 1L, controller.obligationGeneration("sess-1", "M02"))
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_OBLIGATION_OPENED") && it.contains("obligationGen=${gen1 + 1L}")
            }
        )
    }
@Test
    fun gResurrect7_lateMarkRecoveredAfterDeadline_ignored_freshEvidenceStillAdmits() {
        val gen1 = driveHostObligationDeadlineClosed()
        val attemptId = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertFalse(
            controller.canMarkLineageRecovered(
                sessionId = "sess-1",
                remoteModuleId = "M02",
                factAttemptId = attemptId,
                factObligationGeneration = gen1
            )
        )

        decisionLogs.clear()
        controller.applyMarkRecoveredForTest("sess-1", "M02", evidence = "ICE_CONNECTED")
        assertTrue(
            decisionLogs.any {
                it.contains("IGNORE_STALE_TERMINAL_FACT") &&
                    it.contains("reason=obligation_already_closed") &&
                    it.contains("closeReason=OBLIGATION_DEADLINE")
            }
        )
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertEquals(
            ObligationCloseReason.OBLIGATION_DEADLINE,
            controller.obligationCloseReason("sess-1", "M02")
        )

        nowMs += 5L
        decisionLogs.clear()
        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertEquals(gen1 + 1L, controller.obligationGeneration("sess-1", "M02"))
    }

    @Test
    fun suppressSuccessorAttempt_blocksAdmission_emitsApplied_keepsClosed() {
        val gen1 = driveHostObligationDeadlineClosed()
        val attemptBefore = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        SuppressSuccessorAttemptDebugInjection.arm(
            sessionId = "sess-1",
            targetModule = "M02",
            ttlMs = 60_000L,
            reason = "UT_ATTEMPT_4C_S",
            nowMs = nowMs,
            log = { decisionLogs.add(it) }
        )
        nowMs += 5L
        decisionLogs.clear()

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertFalse(controller.edgeObligationOpen("sess-1", "M02"))
        assertEquals(gen1, controller.obligationGeneration("sess-1", "M02"))
        assertEquals(
            attemptBefore,
            controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        )
        assertTrue(decisionLogs.any { it.contains("SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED") })
        assertTrue(decisionLogs.any { it.contains("HARNESS_SUCCESSOR_SUPPRESSION_APPLIED") })
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REACHABILITY_IGNORED") &&
                    it.contains("reason=suppress_successor_attempt")
            }
        )
        assertFalse(decisionLogs.any { it.contains("ADMIT_SUCCESSOR_OBLIGATION_EPISODE") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
        assertEquals(1, SuppressSuccessorAttemptDebugInjection.applyCount())
        assertFalse(decisionLogs.any { it.contains("SUCCESSOR_OBLIGATION_ADOPTED") })
        assertFalse(decisionLogs.any { it.contains("TRANSFERRED") })
    }

    // ---- E16 Phase-3 ActivationEvidence (SUCCESSOR_START) ----

    @Test
    fun e16_t1_admitThenSuccessorStartedBeforeDelivery() {
        driveHostObligationDeadlineClosed()
        nowMs += 5L
        decisionLogs.clear()
        canDispatch = false

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        val admitIdx = decisionLogs.indexOfFirst { it.contains("ADMIT_SUCCESSOR_OBLIGATION_EPISODE") }
        val startedIdx = decisionLogs.indexOfFirst { it.contains("RECOVERY_SUCCESSOR_STARTED") }
        val deliveryIdx = decisionLogs.indexOfFirst {
            it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") ||
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") ||
                it.contains("RECOVERY_MEDIA_ACTION_ASSIGNMENT")
        }
        assertTrue("ADMIT_SUCCESSOR missing", admitIdx >= 0)
        assertTrue("RECOVERY_SUCCESSOR_STARTED missing", startedIdx >= 0)
        assertTrue("delivery prelude missing", deliveryIdx >= 0)
        assertTrue("Activation must follow Admission", startedIdx > admitIdx)
        assertTrue("Activation must precede Delivery", startedIdx < deliveryIdx)
        assertTrue(
            decisionLogs[startedIdx].contains("activationKind=SUCCESSOR_START") &&
                decisionLogs[startedIdx].contains("pathway=NEW_OBLIGATION_EPISODE")
        )
        assertEquals(
            1,
            decisionLogs.count { it.contains("RECOVERY_SUCCESSOR_STARTED") }
        )
    }

    @Test
    fun e16_t0_deniedAdmission_doesNotEmitSuccessorStarted() {
        driveHostObligationDeadlineClosed()
        nowMs += 10L
        decisionLogs.clear()

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.PEER_DISCOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertTrue(decisionLogs.any { it.contains("RECOVERY_INVALID_EVIDENCE_BINDING") })
        assertFalse(decisionLogs.any { it.contains("ADMIT_SUCCESSOR_OBLIGATION_EPISODE") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_SUCCESSOR_STARTED") })
    }

    @Test
    fun e16_idempotent_oneSuccessorStartedPerEpisode() {
        driveHostObligationDeadlineClosed()
        nowMs += 5L
        decisionLogs.clear()

        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )

        assertEquals(1, decisionLogs.count { it.contains("RECOVERY_SUCCESSOR_STARTED") })

        // Delivery re-evaluate on open obligation must not re-emit Activation.
        nowMs += 5L
        val before = decisionLogs.count { it.contains("RECOVERY_SUCCESSOR_STARTED") }
        notifyReachability(
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            evidence = RecoveryResurrectionEvidence(
                kind = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                observedAtMs = nowMs
            )
        )
        assertEquals(
            before,
            decisionLogs.count { it.contains("RECOVERY_SUCCESSOR_STARTED") }
        )
    }
}