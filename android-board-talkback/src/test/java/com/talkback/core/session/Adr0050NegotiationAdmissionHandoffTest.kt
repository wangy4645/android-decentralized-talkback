package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-0050 Option A: negotiation lease admits ICE restart without ownership transfer.
 */
class Adr0050NegotiationAdmissionHandoffTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val decisionLogs = mutableListOf<String>()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-0050"
    private val remoteModuleId = "M02"

    @Before
    fun setUp() {
        nowMs = 1_000L
        iceRestartCalls = 0
        decisionLogs.clear()
        controller = ConferenceEdgeRecoveryController(
            localModuleId = "LOCAL",
            debounceMs = 10L,
            iceRestartTimeoutMs = 5_000L,
            attemptBudgetMs = 120L,
            observationWindowMs = 150L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ ->
                iceRestartCalls++
                true
            },
            isIceConnected = { _, _ -> false },
            canDispatchRecoveryMediaAction = { _, _ -> true },
            probeIceRestartGate = { _, _ -> IceRestartGateProbe(executable = true) }
        )
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    @Test
    fun iceRestartOnly_remoteNegotiationOwner_leaseAdmitsDispatch() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remoteModuleId,
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        nowMs += 20L
        Thread.sleep(80)

        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_LEASE_GRANTED") })
        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_LEASE_ADMITTED") })
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ICE_RESTART_DISPATCHED") && it.contains("remote=$remoteModuleId")
            }
        )
        assertTrue(iceRestartCalls >= 1)
        assertFalse(decisionLogs.any { it.contains("NEGOTIATION_NON_OWNER_BLOCKED") })
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_NEGOTIATION_OWNER_BOOTSTRAP") &&
                    it.contains("owner=$remoteModuleId")
            }
        )
    }

    @Test
    fun leaseExpiry_clearsWithoutFailedMediaClass() {
        val record = EdgeRecoveryRecord(
            key = ConferenceEdgeKey(sessionId, remoteModuleId),
            phase = EdgeRecoveryPhase.RECOVERY_PENDING,
            channelId = "CH-1",
            recoveryAttemptId = 3L,
            recoveryStartedAtMs = 1_000L,
            mediaActionOwner = MediaActionOwner.HOST_RESTART,
            obligationGeneration = 1L
        )
        NegotiationAdmissionLease.grant(record, expiresAtMs = 1_100L)
        val expired = AtomicBoolean(false)
        assertFalse(
            NegotiationAdmissionLease.isValid(record, nowMs = 1_101L) {
                expired.set(true)
            }
        )
        assertTrue(expired.get())
        assertNull(record.negotiationLeaseAttemptId)
        assertNull(record.negotiationLeaseExpiresAtMs)
        assertEquals(EdgeRecoveryPhase.RECOVERY_PENDING, record.phase)
        assertFalse(record.phase.isFailedMediaRecovery())
    }

    @Test
    fun abortedOwner_notLeaseEligible() {
        assertFalse(
            NegotiationAdmissionLease.isEligibleMediaAction(
                owner = MediaActionOwner.ABORTED,
                obligationClosed = false
            )
        )
        assertFalse(
            NegotiationAdmissionLease.isEligibleMediaAction(
                owner = MediaActionOwner.PARTICIPANT_REATTACH,
                obligationClosed = false
            )
        )
        assertTrue(
            NegotiationAdmissionLease.isEligibleMediaAction(
                owner = MediaActionOwner.PENDING,
                obligationClosed = false
            )
        )
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )
}
