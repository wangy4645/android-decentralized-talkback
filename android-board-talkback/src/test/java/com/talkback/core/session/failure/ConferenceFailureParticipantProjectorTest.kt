package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceParticipantProjector
import com.talkback.core.session.ConferenceRuntimeProjector
import com.talkback.core.session.ConferenceSrdNativeDomainObservability
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
import com.talkback.core.session.MemberView
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceFailureParticipantProjectorTest {

    private val scope = ConferenceFailureGenerationScope(
        conferenceSessionId = "sess-1",
        meshGeneration = 1L,
    )

    private fun edgeFailed(remote: String) = ConferenceFailureTerminal.EdgeFailed(
        edgeKey = edgeKeyForRemote("sess-1", remote),
        runtimeDomainRef = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
        generationScope = scope,
    )

    private fun domainBlocked(impact: String, cause: String) =
        ConferenceFailureTerminal.DomainBlocked(
            attribution = ConferenceFailureDomainBlockedAttribution(
                impactEdgeKey = edgeKeyForRemote("sess-1", impact),
                runtimeDomainRef = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
                causeEdgeKey = edgeKeyForRemote("sess-1", cause),
                causeFact = ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE,
                generationScope = scope,
                causePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
            ),
        )

    @Test
    fun p1_edgeFailed_projectsToVisibleEdgeFailed() {
        val projection = ConferenceFailureParticipantProjector.project(edgeFailed("M03"))
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED, projection.displayState)
        assertFalse(projection.blockedByDomain)
        assertEquals("M03", projection.remoteModuleId)
    }

    @Test
    fun p2_domainBlocked_projectsToVisibleDomainBlocked_withAttribution() {
        val projection = ConferenceFailureParticipantProjector.project(domainBlocked("M04", "M03"))
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED, projection.displayState)
        assertTrue(projection.blockedByDomain)
        assertEquals(edgeKeyForRemote("sess-1", "M03"), projection.causeEdgeKey)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, projection.runtimeDomainRef)
    }

    @Test
    fun p3_domainBlockedDisplayState_differsFromEdgeFailed() {
        val edge = ConferenceFailureParticipantProjector.project(edgeFailed("M03")).displayState
        val blocked = ConferenceFailureParticipantProjector.project(domainBlocked("M04", "M03")).displayState
        assertNotEquals(edge, blocked)
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED, edge)
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED, blocked)
    }

    @Test
    fun p4_domainBlocked_doesNotForceConferenceDegraded() {
        val runtime = ConferenceRuntimeProjector.project(
            ConferenceRuntimeProjector.Input(
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 2,
                sessionAccepted = true,
                awaitingAdditionalParticipants = false,
                isConferenceHost = true,
            ),
        )
        assertFalse(runtime.conferenceDegraded)
    }

    @Test
    fun p5_failureOverlay_doesNotMutateTopologyCounts() {
        val m01 = ModuleId("M01")
        val baseInput = ConferenceParticipantProjector.Input(
            localModuleId = m01,
            localKey = "M01-E01",
            sessionAccepted = true,
            roster = listOf(
                EndpointAddress(m01, EndpointId("E01")),
                EndpointAddress(ModuleId("M02"), EndpointId("E01")),
                EndpointAddress(ModuleId("M04"), EndpointId("E01")),
            ),
            memberViews = listOf(
                MemberView("M02-E01", "M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                MemberView("M04-E01", "M04", InviteState.ACCEPTED, MediaState.CONNECTING),
            ),
        )
        val without = ConferenceParticipantProjector.project(baseInput)
        val withFailure = ConferenceParticipantProjector.project(
            baseInput.copy(
                failureTerminalsByModuleId = mapOf(
                    "M04" to domainBlocked("M04", "M03"),
                ),
            ),
        )
        assertEquals(without.joinedParticipantCount, withFailure.joinedParticipantCount)
        assertEquals(without.rosterParticipants, withFailure.rosterParticipants)
        assertEquals(without.pendingInviteeCount, withFailure.pendingInviteeCount)

        val m04 = withFailure.visibleParticipants.first { it.moduleId == "M04" }
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED, m04.displayState)
        assertTrue(m04.failureProjection!!.blockedByDomain)
        assertNotEquals(ConferenceParticipantDisplayState.VISIBLE_CONNECTING, m04.displayState)
    }
}
