package com.talkback.core.session

import com.talkback.core.session.failure.ConferenceFailureCauseFact
import com.talkback.core.session.failure.ConferenceFailureCausePhase
import com.talkback.core.session.failure.ConferenceFailureDomainBlockedAttribution
import com.talkback.core.session.failure.ConferenceFailureGenerationScope
import com.talkback.core.session.failure.ConferenceFailureTerminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH-B-6: Scenario C validation fixtures (Phase B).
 */
class ConferenceL4ScenarioCValidationTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val sessionId = "sess-1"

    private fun anchorSnapshot() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = sessionId,
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4,
            meshGeneration = 1L,
            anchorEpoch = 1L,
        )
    )

    private fun observations(vararg usable: Pair<String, Boolean>): Set<MediaEdgeUsabilityObservation> =
        usable.map { (remote, ok) ->
            MediaEdgeUsabilityObservation(MediaEdge("M01", remote), usable = ok)
        }.toSet()

    private fun domainBlocked(remote: String, causeRemote: String) =
        ConferenceFailureTerminal.DomainBlocked(
            attribution = ConferenceFailureDomainBlockedAttribution(
                impactEdgeKey = "$sessionId|$remote",
                runtimeDomainRef = "shared-factory",
                causeEdgeKey = "$sessionId|$causeRemote",
                causeFact = ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE,
                generationScope = ConferenceFailureGenerationScope(sessionId, 1L),
                causePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
            )
        )

    private fun adjudicate(
        snapshot: ConferenceTopologySnapshot,
        observations: Set<MediaEdgeUsabilityObservation>,
        failures: Map<String, ConferenceFailureTerminal> = emptyMap(),
        programRelayUsable: Boolean? = true,
        recovery: RecoveryProgressFact = RecoveryProgressFact(),
    ): ConferenceL4AdjudicationResult {
        val media = ConferenceL4MediaUsableContract.evaluate(
            ConferenceL4MediaUsableInput(
                snapshot = snapshot,
                localModuleId = "M01",
                sessionEstablished = true,
                mediaObservations = observations,
                failureTerminalsByModuleId = failures,
                programRelayUsable = programRelayUsable,
            )
        )
        return ConferenceL4Adjudicator.adjudicate(
            ConferenceL4AdjudicationInput(
                sessionEstablished = true,
                mediaUsableResult = media,
                recovery = recovery,
            )
        )
    }

    @Test
    fun c1_s3Class_edAndSingleImpact_roomOnline() {
        val snapshot = anchorSnapshot()
        val result = adjudicate(
            snapshot = snapshot,
            observations = observations("M02" to true, "M03" to false, "M04" to false),
            failures = mapOf(
                "M03" to ConferenceFailureTerminal.EdgeFailed(
                    edgeKey = "$sessionId|M03",
                    runtimeDomainRef = "shared-factory",
                    generationScope = ConferenceFailureGenerationScope(sessionId, 1L),
                ),
                "M04" to domainBlocked("M04", "M03"),
            ),
        )
        assertTrue(result.mediaUsable)
        assertEquals(ConferenceL4RoomState.ONLINE, result.l4RoomState)
        assertFalse(result.criticalTriggers.contains(ConferenceL4CriticalTrigger.C3_MULTI_IMPACT_SAME_CAUSE))
    }

    @Test
    fun c2_s4Class_multiImpactSameCause_roomDegraded() {
        val snapshot = anchorSnapshot()
        val result = adjudicate(
            snapshot = snapshot,
            observations = observations("M02" to true, "M03" to false, "M04" to false),
            failures = mapOf(
                "M03" to domainBlocked("M03", "M02"),
                "M04" to domainBlocked("M04", "M02"),
            ),
        )
        assertTrue(result.criticalTriggers.contains(ConferenceL4CriticalTrigger.C3_MULTI_IMPACT_SAME_CAUSE))
        assertFalse(result.mediaUsable)
        assertEquals(ConferenceL4RoomState.DEGRADED, result.l4RoomState)
    }

    @Test
    fun c3_failureCountDoesNotDriveRoomState() {
        val snapshot = anchorSnapshot()
        val result = adjudicate(
            snapshot = snapshot,
            observations = observations("M02" to true, "M03" to false, "M04" to false),
            failures = mapOf(
                "M03" to ConferenceFailureTerminal.EdgeFailed(
                    edgeKey = "$sessionId|M03",
                    runtimeDomainRef = "shared-factory",
                    generationScope = ConferenceFailureGenerationScope(sessionId, 1L),
                ),
                "M04" to ConferenceFailureTerminal.EdgeFailed(
                    edgeKey = "$sessionId|M04",
                    runtimeDomainRef = "shared-factory",
                    generationScope = ConferenceFailureGenerationScope(sessionId, 1L),
                ),
            ),
        )
        assertEquals(ConferenceL4RoomState.ONLINE, result.l4RoomState)
    }

    @Test
    fun phase3Shim_onlineOnlyWhenL4Online() {
        val snapshot = anchorSnapshot()
        val health = ConferenceHealthBinder.project(
            snapshot = snapshot,
            localModuleId = "M01",
            iceStateForModule = { remote ->
                when (remote) {
                    "M02" -> "CONNECTED"
                    else -> "DISCONNECTED"
                }
            },
            recoveryFacts = EdgeRecoveryFacts(),
            sessionEstablished = true,
            failureTerminalsByModuleId = mapOf(
                "M04" to domainBlocked("M04", "M03"),
            ),
            programRelayUsable = true,
        )!!
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = health)
        )
        assertEquals(ConferenceL4RoomState.ONLINE, ui.l4RoomState)
        assertEquals(ConferenceRoomFacing.ONLINE, ui.roomFacing)
        assertTrue(ui.roomOnline)
    }
}
