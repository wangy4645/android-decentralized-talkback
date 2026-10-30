package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceAnchorDecisionObservabilityTest {

    @Test
    fun formatLine_create_initialAnchorPolicy() {
        val line = ConferenceAnchorDecisionObservability.formatLine(
            ConferenceAnchorDecisionObservability.Event(
                phase = ConferenceInitialAnchorPolicy.Phase.CREATE,
                initiatorModuleId = "M01",
                candidateAnchorId = "M01",
                currentAnchorId = null,
                admittedAnchorId = "M01",
                reason = ConferenceAnchorDecisionObservability.LogReason.INITIAL_ANCHOR_POLICY
            )
        )
        assertEquals(
            "ANCHOR_DECISION phase=CREATE initiator=M01 candidate=M01 currentAnchor=none " +
                "admitted=M01 reason=INITIAL_ANCHOR_POLICY",
            line
        )
        assertTrue(
            ConferenceAnchorDecisionObservability.passesP01gBaselineGate(
                lines = listOf(
                    line,
                    "[s1] CONFERENCE_TOPOLOGY_PUBLISHED reason=test anchor=M01 meshGen=1 edges=3"
                )
            )
        )
    }

    @Test
    fun formatLine_failover_includesScores() {
        val line = ConferenceAnchorDecisionObservability.formatLine(
            ConferenceAnchorDecisionObservability.eventForFailover(
                initiatorModuleId = "M01",
                failedAnchorId = "M01",
                nextAnchorId = "M02",
                scores = mapOf("M02" to 80L, "M03" to 50L)
            )
        )
        assertTrue(line.contains("reason=FAILOVER_RANKING"))
        assertTrue(line.contains("scores=M02=80,M03=50"))
        val parsed = ConferenceAnchorDecisionObservability.parseLine(line)
        assertEquals(ConferenceAnchorDecisionObservability.LogReason.FAILOVER_RANKING, parsed?.reason)
        assertEquals(80L, parsed?.scores?.get("M02"))
    }

    @Test
    fun eventForAdmission_preservedAnchor() {
        val input = AnchorAdmissionDecisionInput(
            conferenceId = "c1",
            members = listOf("M01", "M02", "M03", "M04"),
            hostModuleId = "M01",
            candidateAnchorId = "M01",
            threshold = 4,
            currentTopologyMode = ConferenceTopologyMode.ANCHOR,
            currentAnchorId = "M02"
        )
        val initial = ConferenceInitialAnchorPolicy.Output(
            candidateAnchorId = "M01",
            phase = ConferenceInitialAnchorPolicy.Phase.RUN,
            reason = ConferenceInitialAnchorPolicy.Reason.RANKING_CANDIDATE
        )
        val decision = AnchorAdmissionDecision.Anchor("M02")
        val event = ConferenceAnchorDecisionObservability.eventForAdmission(input, initial, decision)
        assertEquals(ConferenceAnchorDecisionObservability.LogReason.PRESERVED_ANCHOR, event?.reason)
        assertEquals("M02", event?.admittedAnchorId)
    }

    @Test
    fun desk4pBaselineRunCard_passesWithCreateDecisionAndTopologyPublish() {
        val lines = listOf(
            "[s1] ANCHOR_DECISION phase=CREATE initiator=M01 candidate=M01 currentAnchor=none " +
                "admitted=M01 reason=INITIAL_ANCHOR_POLICY admissionReason=invite_accept",
            "[s1] CONFERENCE_TOPOLOGY_PUBLISHED reason=invite_accept transition=MESH_TO_ANCHOR " +
                "mode=ANCHOR mediaEdgeCause=none anchor=M01 meshGen=1 edges=3"
        )
        val verdict = ConferenceAnchorDecisionObservability.evaluateDesk4pBaselineRunCard(lines)
        assertTrue(verdict.pass)
        assertEquals(null, verdict.failureReason)
    }

    @Test
    fun desk4pBaselineRunCard_failsOnCreateRankingCandidate() {
        val lines = listOf(
            "[s1] ANCHOR_DECISION phase=CREATE initiator=M01 candidate=M02 currentAnchor=none " +
                "admitted=M02 reason=RANKING_CANDIDATE admissionReason=invite_accept",
            "[s1] CONFERENCE_TOPOLOGY_PUBLISHED reason=invite_accept anchor=M02 meshGen=1 edges=3"
        )
        val verdict = ConferenceAnchorDecisionObservability.evaluateDesk4pBaselineRunCard(lines)
        assertFalse(verdict.pass)
        assertEquals("CREATE admission used RANKING_CANDIDATE", verdict.failureReason)
    }

    @Test
    fun desk4pBaselineRunCard_failsWhenSnapshotAnchorMismatch() {
        val lines = listOf(
            "[s1] ANCHOR_DECISION phase=CREATE initiator=M01 candidate=M01 currentAnchor=none " +
                "admitted=M01 reason=INITIAL_ANCHOR_POLICY admissionReason=invite_accept",
            "[s1] CONFERENCE_TOPOLOGY_PUBLISHED reason=invite_accept anchor=M02 meshGen=1 edges=3"
        )
        val verdict = ConferenceAnchorDecisionObservability.evaluateDesk4pBaselineRunCard(lines)
        assertFalse(verdict.pass)
        assertEquals("snapshot.anchorId=M02 expected=M01", verdict.failureReason)
    }

    @Test
    fun ia3_p01gBaselineGate_requiresCreateAnchorAndSnapshot() {
        val createLine = ConferenceAnchorDecisionObservability.formatLine(
            ConferenceAnchorDecisionObservability.Event(
                phase = ConferenceInitialAnchorPolicy.Phase.CREATE,
                initiatorModuleId = "M01",
                candidateAnchorId = "M01",
                currentAnchorId = null,
                admittedAnchorId = "M01",
                reason = ConferenceAnchorDecisionObservability.LogReason.INITIAL_ANCHOR_POLICY
            )
        )
        assertTrue(
            ConferenceAnchorDecisionObservability.passesP01gBaselineGate(
                lines = listOf(createLine),
                expectedInitiator = "M01",
                snapshotAnchorId = "M01"
            )
        )
    }

    @Test
    fun p01gBaselineGate_failsWhenCreateAnchorNotInitiator() {
        val line = ConferenceAnchorDecisionObservability.formatLine(
            ConferenceAnchorDecisionObservability.Event(
                phase = ConferenceInitialAnchorPolicy.Phase.CREATE,
                initiatorModuleId = "M01",
                candidateAnchorId = "M02",
                currentAnchorId = null,
                admittedAnchorId = "M02",
                reason = ConferenceAnchorDecisionObservability.LogReason.INITIAL_ANCHOR_POLICY
            )
        )
        assertFalse(ConferenceAnchorDecisionObservability.passesP01gBaselineGate(listOf(line)))
    }

    @Test
    fun parseLine_roundTrip() {
        val original = ConferenceAnchorDecisionObservability.formatLine(
            ConferenceAnchorDecisionObservability.Event(
                phase = ConferenceInitialAnchorPolicy.Phase.RUN,
                initiatorModuleId = "M01",
                candidateAnchorId = "M02",
                currentAnchorId = "M01",
                admittedAnchorId = "M02",
                reason = ConferenceAnchorDecisionObservability.LogReason.FAILOVER_RANKING,
                scores = mapOf("M02" to 1L)
            )
        )
        val parsed = ConferenceAnchorDecisionObservability.parseLine(original)
        assertEquals(ConferenceInitialAnchorPolicy.Phase.RUN, parsed?.phase)
        assertEquals("M02", parsed?.admittedAnchorId)
    }
}
