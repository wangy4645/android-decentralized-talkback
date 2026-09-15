package com.talkback.core.conference.session

import com.talkback.core.conference.wire.WireIngressResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-plane port → wiring integration (adapter boundary; no coordinator).
 */
class ConferenceSessionMediaControlPlaneWiringTest {
    @Test
    fun controlPlaneFacts_driveWiring_replaceAndRejectStale() {
        val registry = ConferenceSessionMediaControlFactRegistry()
        val port = ControlPlaneConferenceSessionMediaFactPort(registry)
        val wiring = ConferenceSessionMediaWiring.forHarness()

        val session =
            ConferenceMediaSessionControlFact(
                conferenceId = "sess-cp-1",
                channelId = "ch-1",
                conferenceEpoch = 9L,
                membershipVersion = 4L,
                mediaKeyEpoch = 1L,
                endpoint = SessionMediaWiringHarness.sessionFact("sess-cp-1").endpoint,
                networkInterfaceName = SessionMediaWiringHarness.sessionFact("sess-cp-1").networkInterfaceName,
                masterKey = SessionMediaWiringHarness.sessionFact("sess-cp-1").masterKey.copyOf(),
                masterSalt = SessionMediaWiringHarness.sessionFact("sess-cp-1").masterSalt.copyOf(),
                keyContextHint64 = SessionMediaWiringHarness.sessionFact("sess-cp-1").keyContextHint64.copyOf(),
                factGeneration = 1L,
            )
        registry.publishSession(session)
        val sessionFact = port.sessionFact("sess-cp-1", "ch-1", rosterEpoch = 4L)!!
        assertTrue(wiring.startSession(sessionFact))

        val memberA =
            ConferenceMediaMemberControlFact(
                conferenceId = "sess-cp-1",
                moduleId = "M-A",
                membershipIncarnationId = 100L,
                ssrc = 0x22000100,
                sourceAdmissionKey48 = SessionMediaWiringHarness.memberBinding("M-A").sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = 1L,
                membershipVersion = 4L,
                factGeneration = 1L,
            )
        registry.publishMember(memberA)
        assertTrue(wiring.installMember("sess-cp-1", port.memberBinding("sess-cp-1", "M-A")!!))

        val memberB =
            ConferenceMediaMemberControlFact(
                conferenceId = "sess-cp-1",
                moduleId = "M-B",
                membershipIncarnationId = 200L,
                ssrc = 0x22000200,
                sourceAdmissionKey48 =
                    SessionMediaWiringHarness.memberBinding("M-B", admissionKeySuffix = 0x22)
                        .sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = 1L,
                membershipVersion = 4L,
                factGeneration = 1L,
            )
        val memberB2 =
            ConferenceMediaMemberControlFact(
                conferenceId = "sess-cp-1",
                moduleId = "M-B",
                membershipIncarnationId = 201L,
                ssrc = 0x22000201,
                sourceAdmissionKey48 =
                    SessionMediaWiringHarness.memberBinding("M-B", admissionKeySuffix = 0x2B)
                        .sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = 1L,
                membershipVersion = 4L,
                factGeneration = 2L,
            )
        registry.publishMember(memberB)
        assertTrue(wiring.installMember("sess-cp-1", port.memberBinding("sess-cp-1", "M-B")!!))

        assertEquals(ControlFactPublishOutcome.ACCEPTED, registry.publishMember(memberB2))
        val replace = port.memberReplaceBinding("sess-cp-1", "M-B")!!
        assertTrue(wiring.replaceMember("sess-cp-1", replace.first, replace.second))

        val stalePacket = SessionMediaWiringHarness.protectedPacket(replace.first)
        SessionMediaWiringHarness.assertRejected(wiring.admitDatagram("sess-cp-1", stalePacket))

        val livePacket = SessionMediaWiringHarness.protectedPacket(replace.second)
        SessionMediaWiringHarness.assertAccepted(wiring.admitDatagram("sess-cp-1", livePacket))

        assertEquals(
            ControlFactPublishOutcome.REJECTED_STALE,
            registry.publishMember(
                memberB.copy(
                    membershipIncarnationId = 200L,
                    factGeneration = 3L,
                ),
            ),
        )

        assertTrue(wiring.stopSession("sess-cp-1", sessionFact.generation))
    }
}
