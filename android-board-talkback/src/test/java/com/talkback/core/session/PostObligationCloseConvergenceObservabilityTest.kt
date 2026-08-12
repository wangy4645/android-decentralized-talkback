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
 * G-175-OBS-1..4 — post-obligation close convergence observability (#175 contract v0.1).
 *
 * Red scaffold: asserts markers not yet emitted by production.
 * Field anchor: session dfa3294e — iceConnected=false, receivePathLive=true at close.
 */
class PostObligationCloseConvergenceObservabilityTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private var iceRestartCalls = 0
    private var iceConnected = false
    private var receivePathLive = false
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
        iceRestartCalls = 0
        iceConnected = false
        receivePathLive = false
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
        localModuleId = "M03",
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
        isReceivePathLive = { _, _ -> receivePathLive },
        canDispatchRecoveryMediaAction = { _, _ -> true },
        probeIceRestartGate = { _, _ -> IceRestartGateProbe(executable = true) },
        membershipEpochProbe = membershipConvergedProbe()
    )

    private fun membershipConvergedProbe() = object : MembershipEpochConvergenceProbe {
        override fun probe(
            record: EdgeRecoveryRecord,
            channelId: String,
            conferenceSessionId: String
        ): MembershipEpochProbeResult =
            MembershipEpochProbeResult.Checked(
                authorityId = "M01",
                expectedEpoch = 1L,
                observedEpoch = 1L,
                converged = true
            )
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    /** M03→M02 observer edge: FAILED_MEDIA → OBLIGATION_DEADLINE close (field-like). */
    private fun driveObligationDeadlineClosed(
        sessionId: String = "sess-g175",
        remote: String = "M02"
    ) {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(controller.edgeObligationOpen(sessionId, remote))
        Thread.sleep(150)
        assertTrue(
            "harness: obligation must close on OBLIGATION_DEADLINE",
            controller.edgeObligationClosed(sessionId, remote)
        )
        assertFalse(controller.edgeObligationOpen(sessionId, remote))
        assertEquals(
            ObligationCloseReason.OBLIGATION_DEADLINE,
            controller.obligationCloseReason(sessionId, remote)
        )
    }

    private fun assertPostCloseEval(sessionId: String = "sess-g175", remote: String = "M02") {
        assertTrue(
            "G-175-OBS-1: missing RECOVERY_POST_OBLIGATION_CLOSE_EVAL after OBLIGATION_CLOSED",
            decisionLogs.any {
                it.contains("RECOVERY_POST_OBLIGATION_CLOSE_EVAL") &&
                    it.contains("session=$sessionId") &&
                    it.contains("edge=$remote")
            }
        )
    }

    private fun assertPostCloseAdmissionDecision(
        sessionId: String = "sess-g175",
        remote: String = "M02",
        decision: String,
        reason: String? = null
    ) {
        val match = decisionLogs.filter {
            it.contains("RECOVERY_POST_CLOSE_ADMISSION_DECISION") &&
                it.contains("session=$sessionId") &&
                it.contains("edge=$remote") &&
                it.contains("decision=$decision")
        }
        assertTrue(
            "G-175: missing RECOVERY_POST_CLOSE_ADMISSION_DECISION decision=$decision",
            match.isNotEmpty()
        )
        if (reason != null) {
            assertTrue(
                "G-175: admission decision missing reason=$reason",
                match.any { it.contains("reason=$reason") }
            )
        }
    }

    /** G-175-OBS-1: close must emit post-obligation close eval marker. */
    @Test
    fun g175_obs1_obligationClosed_emitsPostObligationCloseEval() {
        receivePathLive = true
        driveObligationDeadlineClosed()
        assertTrue(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_CLOSED") })
        assertPostCloseEval()
    }

    /**
     * G-175-OBS-2 (P0): unsatisfied fresh snapshot → explicit NO_ADMISSION.
     * Captures field silent-deadlock: CLOSED → CLEAR_HELD → (nothing).
     */
    @Test
    fun g175_obs2_unsatisfiedSnapshot_emitsNoAdmissionDecision() {
        receivePathLive = true
        iceConnected = false
        driveObligationDeadlineClosed()

        assertPostCloseEval()
        val evalLog = decisionLogs.last {
            it.contains("RECOVERY_POST_OBLIGATION_CLOSE_EVAL")
        }
        assertTrue(
            "G-175-OBS-2: eval must snapshot iceConnected=false",
            evalLog.contains("iceConnected=false")
        )
        assertTrue(
            "G-175-OBS-2: eval must snapshot receivePathLive=true (#175 field)",
            evalLog.contains("receivePathLive=true")
        )
        assertTrue(
            "G-175-OBS-2: eval must report UNSATISFIED",
            evalLog.contains("result=UNSATISFIED")
        )
        assertPostCloseAdmissionDecision(decision = "NO_ADMISSION", reason = "edge_unsatisfied")
    }

    /** G-175-OBS-3: ADR-0045 CLEAR_HELD must not substitute for or imply successor admission. */
    @Test
    fun g175_obs3_clearHeld_doesNotImplyAdmitSuccessor() {
        receivePathLive = true
        iceConnected = false
        driveObligationDeadlineClosed()

        assertTrue(
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RESIDENCY_CLEAR_HELD") &&
                    it.contains("e4_snapshot_unsatisfied")
            }
        )
        assertFalse(
            "G-175-OBS-3: CLEAR_HELD must not co-occur with ADMIT_SUCCESSOR on close path",
            decisionLogs.any { it.contains("ADMIT_SUCCESSOR_OBLIGATION_EPISODE") }
        )
        assertPostCloseAdmissionDecision(decision = "NO_ADMISSION", reason = "edge_unsatisfied")
    }

    /**
     * G-175-OBS-4: post-close ICE CONNECTED material transition → re-evaluate → admission decision.
     */
    @Test
    fun g175_obs4_postCloseIceConnected_emitsReevaluateThenAdmissionDecision() {
        receivePathLive = false
        iceConnected = false
        driveObligationDeadlineClosed()
        decisionLogs.clear()

        receivePathLive = true
        iceConnected = true
        controller.onIceConnected("sess-g175", "M02")

        assertTrue(
            "G-175-OBS-4: post-close ICE CONNECTED must emit RECOVERY_REEVALUATE",
            decisionLogs.any {
                it.contains("RECOVERY_REEVALUATE") && it.contains("edge=M02")
            }
        )
        assertTrue(
            "G-175-OBS-4: material transition must emit admission decision",
            decisionLogs.any {
                it.contains("RECOVERY_POST_CLOSE_ADMISSION_DECISION") &&
                    it.contains("edge=M02") &&
                    (it.contains("decision=RESIDENCY_CLEAR_ADMITTED") ||
                        it.contains("decision=ADMIT_SUCCESSOR") ||
                        it.contains("decision=NO_ADMISSION"))
            }
        )
    }
}
