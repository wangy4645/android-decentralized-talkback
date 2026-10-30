package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IA-1 desk contract: Conference CREATE initial anchor = initiator; RUN unchanged.
 */
class ConferenceInitialAnchorPolicyTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val threshold4 = 4

    private fun policyInput(
        initiator: String = "M01",
        members: List<String> = roster4,
        threshold: Int = threshold4,
        currentMode: ConferenceTopologyMode? = null,
        currentAnchorId: String? = null,
        rankingCandidate: String? = "M02"
    ) = ConferenceInitialAnchorPolicy.Input(
        initiatorModuleId = initiator,
        members = members,
        threshold = threshold,
        currentTopologyMode = currentMode,
        currentAnchorId = currentAnchorId,
        rankingCandidateAnchorId = rankingCandidate
    )

    private fun admissionInput(
        initiator: String = "M01",
        members: List<String> = roster4,
        threshold: Int = threshold4,
        currentMode: ConferenceTopologyMode? = null,
        currentAnchorId: String? = null,
        rankingCandidate: String? = "M02"
    ): AnchorAdmissionDecisionInput {
        val resolved = ConferenceInitialAnchorPolicy.resolve(
            policyInput(
                initiator = initiator,
                members = members,
                threshold = threshold,
                currentMode = currentMode,
                currentAnchorId = currentAnchorId,
                rankingCandidate = rankingCandidate
            )
        )
        return AnchorAdmissionDecisionInput(
            conferenceId = "c1",
            members = members,
            hostModuleId = initiator,
            candidateAnchorId = resolved.candidateAnchorId,
            threshold = threshold,
            currentTopologyMode = currentMode,
            currentAnchorId = currentAnchorId
        )
    }

    @Test
    fun case1_create_firstAdmission_anchorIsInitiator() {
        val resolved = ConferenceInitialAnchorPolicy.resolve(
            policyInput(currentMode = ConferenceTopologyMode.MESH, currentAnchorId = null)
        )
        assertEquals(ConferenceInitialAnchorPolicy.Phase.CREATE, resolved.phase)
        assertEquals(ConferenceInitialAnchorPolicy.Reason.INITIAL_ANCHOR_POLICY, resolved.reason)
        assertEquals("M01", resolved.candidateAnchorId)

        val decision = ConferenceAnchorAdmissionPolicy.decide(
            admissionInput(currentMode = ConferenceTopologyMode.MESH, currentAnchorId = null)
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M01"), decision)
    }

    @Test
    fun case2_create_rankingWouldPickM02_anchorStaysInitiator() {
        val m02HighScore = mapOf(
            "M01" to AnchorHealthSnapshot(
                onlineSinceMs = 0L,
                charging = false,
                batteryPercent = 50,
                updatedMs = 0L
            ),
            "M02" to AnchorHealthSnapshot(
                onlineSinceMs = 0L,
                charging = true,
                batteryPercent = 100,
                updatedMs = 0L
            ),
            "M03" to AnchorHealthSnapshot.unknown(0L),
            "M04" to AnchorHealthSnapshot.unknown(0L)
        )
        val rankingPrimary = AnchorRanking.elect(
            roster4.map { ModuleId(it) },
            m02HighScore,
            nowMs = 0L
        )?.primary?.value
        assertEquals("M02", rankingPrimary)

        val resolved = ConferenceInitialAnchorPolicy.resolve(
            policyInput(
                currentMode = ConferenceTopologyMode.MESH,
                currentAnchorId = null,
                rankingCandidate = rankingPrimary
            )
        )
        assertEquals("M01", resolved.candidateAnchorId)
        assertEquals(ConferenceInitialAnchorPolicy.Reason.INITIAL_ANCHOR_POLICY, resolved.reason)

        val decision = ConferenceAnchorAdmissionPolicy.decide(
            admissionInput(
                currentMode = ConferenceTopologyMode.MESH,
                currentAnchorId = null,
                rankingCandidate = rankingPrimary
            )
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M01"), decision)
    }

    @Test
    fun case3_existingAnchor_preservedOverInitiatorAndRanking() {
        assertFalse(
            ConferenceInitialAnchorPolicy.isCreateFirstAnchorAdmission(
                policyInput(
                    currentMode = ConferenceTopologyMode.ANCHOR,
                    currentAnchorId = "M02",
                    rankingCandidate = "M01"
                )
            )
        )

        val decision = ConferenceAnchorAdmissionPolicy.decide(
            admissionInput(
                initiator = "M01",
                currentMode = ConferenceTopologyMode.ANCHOR,
                currentAnchorId = "M02",
                rankingCandidate = "M01"
            )
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M02"), decision)
    }

    @Test
    fun case4_groupRankingUnchanged_conferencePolicyDoesNotReplaceElect() {
        val members = listOf(ModuleId("M01"), ModuleId("M02"), ModuleId("M03"), ModuleId("M04"))
        val health = mapOf(
            "M01" to AnchorHealthSnapshot(onlineSinceMs = 0L, charging = false, batteryPercent = 50, updatedMs = 0L),
            "M02" to AnchorHealthSnapshot(onlineSinceMs = 0L, charging = true, batteryPercent = 100, updatedMs = 0L),
            "M03" to AnchorHealthSnapshot.unknown(0L),
            "M04" to AnchorHealthSnapshot.unknown(0L)
        )
        assertEquals("M02", AnchorRanking.elect(members, health, nowMs = 0L)?.primary?.value)

        val runResolved = ConferenceInitialAnchorPolicy.resolve(
            policyInput(
                currentMode = ConferenceTopologyMode.ANCHOR,
                currentAnchorId = null,
                rankingCandidate = "M02"
            )
        )
        assertEquals(ConferenceInitialAnchorPolicy.Phase.RUN, runResolved.phase)
        assertEquals("M02", runResolved.candidateAnchorId)
        assertTrue(
            ConferenceInitialAnchorPolicy.isCreateFirstAnchorAdmission(
                policyInput(currentMode = ConferenceTopologyMode.MESH)
            )
        )
    }
}
