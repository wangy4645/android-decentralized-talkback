package com.talkback.core.session

import com.talkback.core.model.RecoveryHandlerOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C0 characterization: [RecoveryCompletionPolicy] production close gate on main.
 *
 * Assertion discipline (ADR-0022 Appendix E):
 * - **INVARIANT** — permanent contract; removal requires ADR amendment.
 * - **CURRENT_BEHAVIOR** — pins injected/unwired seams; change only via numbered resolution (§E.18).
 */
class RecoveryCompletionPolicyTest {

    private val logs = mutableListOf<String>()

    @After
    fun tearDown() {
        RecoveryAttemptOwner.resetForTest()
    }

    private fun record(
        phase: EdgeRecoveryPhase,
        deliveryPhase: RecoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.NONE,
        lineageId: String? = "L1",
        mediaRestored: Boolean = false,
        outcome: RecoveryHandlerOutcome? = null,
        attemptId: Long = 1L,
        obligationGeneration: Long = 1L
    ): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-pr52", "M02")
        return EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = attemptId,
            recoveryStartedAtMs = 0L,
            obligationGeneration = obligationGeneration,
            recoveryOfferDeliveryPhase = deliveryPhase,
            recoveryOfferLineageId = lineageId,
            mediaRestored = mediaRestored,
            deliveryConfirmedOutcome = outcome
        )
    }

    private fun snapshot(mediaRoute: Boolean = true) = EdgeReachabilitySnapshot(
        linkReady = true,
        peerDiscovered = true,
        peerSignalingReachable = true,
        mediaRouteConnected = mediaRoute,
        authorityReachable = true
    )

    /** Injects evaluator result directly; not the production unwired membership seam (§E.18). */
    private fun attachControlFact(record: EdgeRecoveryRecord, membershipConverged: Boolean = true) {
        record.controlReconciliationFact = ControlReconciliationEvaluator.evaluate(
            record = record,
            membershipEpochConverged = membershipConverged,
            clock = { 100L }
        )
    }

    private fun host(
        record: EdgeRecoveryRecord,
        currentOverride: EdgeRecoveryRecord? = null
    ): RecoveryCompletionPolicy.MutationHost {
        val current = currentOverride ?: record
        return object : RecoveryCompletionPolicy.MutationHost {
            override fun currentRecord(key: ConferenceEdgeKey) = current
            override fun clock(): Long = 100L
            override fun log(message: String) {
                logs.add(message)
            }
            override fun cancelDebounce(key: ConferenceEdgeKey) = Unit
            override fun cancelWatchdog(key: ConferenceEdgeKey) = Unit
            override fun cancelDeadline(key: ConferenceEdgeKey) = Unit
            override fun logPhaseTransition(
                record: EdgeRecoveryRecord,
                oldPhase: EdgeRecoveryPhase,
                newPhase: EdgeRecoveryPhase,
                reason: String
            ) = Unit
            override fun expireDeferredIceRestartIntent(record: EdgeRecoveryRecord, reason: String) = Unit
            override fun notifyAttemptLineageObservation(record: EdgeRecoveryRecord, reason: String) = Unit
            override fun notifyChanged(sessionId: String) = Unit
            override fun logObligationCloseRequested(
                record: EdgeRecoveryRecord,
                reason: ObligationCloseReason,
                closeEvidence: String?
            ) = Unit
        }
    }

    /** INVARIANT: full predicate path may close as RECOVERED when control fact is reconciled. */
    @Test
    fun invariant_allPredicatesSatisfied_policyMarksRecovered() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true
        )
        RecoveryAttemptOwner.resetForTest { }
        RecoveryAttemptOwner.openAttempt(r, RecoveryAttemptState.ATTEMPT_NEGOTIATING, "TEST:A")
        attachControlFact(r)
        val observation = RecoveryCompletionPolicy.evaluate(
            r, snapshot(), iceConnected = true, mediaUnavailableAdvisory = false, hasUncoveredDeferredIntent = false
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.RECOVERED, observation.candidate)
        val applied = RecoveryCompletionPolicy.markRecovered(host(r), r, "ICE_CONNECTED")
        assertTrue(applied)
        assertEquals(EdgeRecoveryPhase.RECOVERED, r.phase)
        assertEquals(ObligationCloseReason.RECOVERED, r.obligationCloseReason)
        assertTrue(logs.any { it.contains("RECOVERY_OBLIGATION_CLOSED") })
    }

    /** INVARIANT: control reconciliation pending blocks RECOVERED even when delivery is satisfied. */
    @Test
    fun invariant_controlNotReconciled_waitingNoClose() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            outcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        attachControlFact(r)
        val observation = RecoveryCompletionPolicy.evaluate(
            r, snapshot(), iceConnected = true, mediaUnavailableAdvisory = false, hasUncoveredDeferredIntent = false
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.WAITING, observation.candidate)
        assertEquals(
            CompletionObservationProjection.WaitingReason.CONTROL_RECONCILIATION_PENDING,
            observation.waitingReason
        )
    }

    /** INVARIANT: terminal attempt failure must not yield RECOVERED candidate. */
    @Test
    fun invariant_attemptFailed_mustNotRecover() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true
        )
        RecoveryAttemptOwner.resetForTest { }
        RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:C")
        val observation = RecoveryCompletionPolicy.evaluate(
            r, snapshot(), iceConnected = true, mediaUnavailableAdvisory = false, hasUncoveredDeferredIntent = false
        )
        assertFalse(observation.candidate == CompletionObservationProjection.CompletionCandidate.RECOVERED)
    }

    /** INVARIANT: EXECUTED path — ALREADY_SATISFIED delivery still requires full predicate before close. */
    @Test
    fun invariant_alreadySatisfiedFullPredicatePathToRecovered() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true,
            outcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        RecoveryAttemptOwner.resetForTest { logs.add(it) }
        RecoveryAttemptOwner.openAttempt(r, RecoveryAttemptState.ATTEMPT_NEGOTIATING, "TEST:E")
        attachControlFact(r)
        val observation = RecoveryCompletionPolicy.evaluate(
            r, snapshot(), iceConnected = true, mediaUnavailableAdvisory = false, hasUncoveredDeferredIntent = false
        )
        RecoveryCompletionPolicy.logCompletionDecision(host(r), observation, RecoveryReevaluateTrigger.DELIVERY_CONFIRMED)
        assertEquals(CompletionObservationProjection.CompletionCandidate.RECOVERED, observation.candidate)
        assertEquals(RecoveryHandlerOutcome.ALREADY_SATISFIED, observation.deliveryConfirmedOutcome)
        val applied = RecoveryCompletionPolicy.markRecovered(host(r), r, "ICE_CONNECTED")
        assertTrue(applied)
        assertTrue(logs.any { it.contains("RECOVERY_COMPLETION_DECISION") && it.contains("candidate=RECOVERED") })
        assertEquals(ObligationCloseReason.RECOVERED, r.obligationCloseReason)
    }

    /** INVARIANT: post-dispatch restart requires media observed strictly after dispatch (EXECUTED != RECOVERED). */
    @Test
    fun invariant_postDispatchMediaObservedAtNotAfterDispatch_cannotRecover() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true
        )
        r.iceRestartIssued = true
        r.restartDispatchAtMs = 2_000L
        r.mediaRestoredObservedAtMs = 2_000L
        attachControlFact(r)
        val applied = RecoveryCompletionPolicy.markRecovered(host(r), r, "ICE_CONNECTED")
        assertFalse(applied)
        assertTrue(logs.any { it.contains("RECOVERY_COMPLETION_HELD") })
        assertNull(r.obligationCloseReason)
    }

    /** INVARIANT: NEGOTIATION deferred domain — ICE_CONNECTED evidence cannot close obligation (B3). */
    @Test
    fun invariant_negotiationDeferred_cannotCloseOnIceConnected() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true
        )
        r.deferredReason = DeferredReason.NEGOTIATION_SETTLING
        r.iceRestartIntentId = "intent-42"
        attachControlFact(r)
        val applied = RecoveryCompletionPolicy.markRecovered(host(r), r, "ICE_CONNECTED")
        assertFalse(applied)
        assertTrue(logs.any { it.contains("RECOVERY_COMPLETION_HELD") && it.contains("domain=NEGOTIATION") })
    }

    /** INVARIANT: stale obligation generation on terminal write is rejected (dual-key fence). */
    @Test
    fun invariant_staleObligationGeneration_rejectsMarkRecovered() {
        logs.clear()
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true,
            obligationGeneration = 2L
        )
        attachControlFact(r)
        val applied = RecoveryCompletionPolicy.markRecovered(
            host(r, currentOverride = r.copy(obligationGeneration = 3L)),
            r,
            "ICE_CONNECTED"
        )
        assertFalse(applied)
        assertTrue(logs.any { it.contains("IGNORE_STALE_TERMINAL_FACT") })
    }

    /** INVARIANT: terminal writers live only in RecoveryCompletionPolicy (single-writer seam). */
    @Test
    fun invariant_terminalWritersOnlyInCompletionPolicy() {
        val sessionDir = File("src/main/java/com/talkback/core/session")
        val offenders = sessionDir.listFiles { f ->
            f.extension == "kt" && f.name != "RecoveryCompletionPolicy.kt"
        }?.filter { file ->
            val text = file.readText()
            text.contains("fun closeObligation") ||
                text.contains("fun markRecovered") ||
                text.contains("fun markFailedFinal")
        } ?: emptyList()
        assertTrue("Terminal writer definitions outside Policy: ${offenders.map { it.name }}", offenders.isEmpty())
        val controller = sessionDir.resolve("ConferenceEdgeRecoveryController.kt").readText()
        assertFalse(controller.contains("private fun markRecovered"))
        assertFalse(controller.contains("private fun closeObligation"))
    }
}
