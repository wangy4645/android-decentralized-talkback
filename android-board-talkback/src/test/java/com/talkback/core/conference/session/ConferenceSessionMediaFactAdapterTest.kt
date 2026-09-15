package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceSessionMediaFactAdapterTest {
    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val port = ControlPlaneConferenceSessionMediaFactPort(registry)
    private val adapter = ConferenceSessionMediaFactAdapter

    @Test
    fun sameGenerationFact_mapsStably() {
        val session = sessionControl(conferenceId = "sess-1", membershipVersion = 5L, factGen = 1L)
        val member =
            memberControl(
                conferenceId = "sess-1",
                moduleId = "M-A",
                incarnation = 10L,
                membershipVersion = 5L,
                factGen = 1L,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, registry.publishSession(session))
        assertEquals(ControlFactPublishOutcome.ACCEPTED, registry.publishMember(member))
        assertEquals(ControlFactPublishOutcome.IDEMPOTENT, registry.publishSession(session))
        assertEquals(ControlFactPublishOutcome.IDEMPOTENT, registry.publishMember(member))

        val sessionFact = port.sessionFact("sess-1", session.channelId, rosterEpoch = 5L)
        val binding = port.memberBinding("sess-1", "M-A")
        assertNotNull(sessionFact)
        assertNotNull(binding)
        assertEquals(session.conferenceEpoch, sessionFact!!.generation)
        assertEquals("M-A", binding!!.sourceIdentity)
        assertEquals(10L, binding.incarnationId)
        assertEquals(member.ssrc, binding.ssrc)
        assertTrue(binding.sourceAdmissionKey48.contentEquals(member.sourceAdmissionKey48))
    }

    @Test
    fun successorRejoin_publishesReplacePair_withNewIncarnation() {
        val session = sessionControl(conferenceId = "sess-2", membershipVersion = 2L, factGen = 1L)
        val oldMember =
            memberControl(
                conferenceId = "sess-2",
                moduleId = "M-B",
                incarnation = 20L,
                ssrc = 0x22000020,
                membershipVersion = 2L,
                factGen = 1L,
                admissionSuffix = 0x21,
            )
        val newMember =
            memberControl(
                conferenceId = "sess-2",
                moduleId = "M-B",
                incarnation = 21L,
                ssrc = 0x22000021,
                membershipVersion = 2L,
                factGen = 2L,
                admissionSuffix = 0x2A,
            )
        registry.publishSession(session)
        registry.publishMember(oldMember)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, registry.publishMember(newMember))

        val pair = port.memberReplaceBinding("sess-2", "M-B")
        assertNotNull(pair)
        assertEquals(20L, pair!!.first.incarnationId)
        assertEquals(21L, pair.second.incarnationId)
        assertEquals(0x22000020, pair.first.ssrc)
        assertEquals(0x22000021, pair.second.ssrc)
        assertNull(port.memberReplaceBinding("sess-2", "M-B"))
        assertEquals(21L, port.memberBinding("sess-2", "M-B")!!.incarnationId)
    }

    @Test
    fun staleSupersededFact_doesNotReinstallOldSource() {
        val session = sessionControl(conferenceId = "sess-3", membershipVersion = 3L, factGen = 1L)
        val current =
            memberControl(
                conferenceId = "sess-3",
                moduleId = "M-C",
                incarnation = 30L,
                membershipVersion = 3L,
                factGen = 2L,
            )
        val stale =
            memberControl(
                conferenceId = "sess-3",
                moduleId = "M-C",
                incarnation = 29L,
                membershipVersion = 3L,
                factGen = 3L,
            )
        val supersededPublish =
            memberControl(
                conferenceId = "sess-3",
                moduleId = "M-C",
                incarnation = 31L,
                membershipVersion = 3L,
                factGen = 4L,
                superseded = true,
            )
        registry.publishSession(session)
        registry.publishMember(current)
        assertEquals(ControlFactPublishOutcome.REJECTED_STALE, registry.publishMember(stale))
        assertEquals(ControlFactPublishOutcome.REJECTED_SUPERSEDED, registry.publishMember(supersededPublish))
        assertEquals(30L, port.memberBinding("sess-3", "M-C")!!.incarnationId)
    }

    @Test
    fun adapter_doesNotDeriveSsrcFromModuleId() {
        val control =
            memberControl(
                conferenceId = "sess-4",
                moduleId = "HTUBB21B09220661",
                incarnation = 7L,
                ssrc = 0xAABBCCDD.toInt(),
                membershipVersion = 1L,
                factGen = 1L,
            )
        val binding = adapter.toMemberBinding(control)
        assertEquals(control.moduleId, binding.sourceIdentity)
        assertEquals(0xAABBCCDD.toInt(), binding.ssrc)
        assertFalse(binding.ssrc == control.moduleId.hashCode())
    }

    private fun sessionControl(
        conferenceId: String,
        membershipVersion: Long,
        factGen: Long,
        conferenceEpoch: Long = 1L,
    ): ConferenceMediaSessionControlFact =
        ConferenceMediaSessionControlFact(
            conferenceId = conferenceId,
            channelId = "ch-$conferenceId",
            conferenceEpoch = conferenceEpoch,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = 1L,
            endpoint =
                MediaGroupEndpointBinding(
                    multicastAddress = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
                    mediaPort = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
                    underlayScopeId = "ctrl-$conferenceId",
                ),
            networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            masterKey = Phase1MediaHarness.masterKey.copyOf(),
            masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
            keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
            factGeneration = factGen,
        )

    private fun memberControl(
        conferenceId: String,
        moduleId: String,
        incarnation: Long,
        membershipVersion: Long,
        factGen: Long,
        ssrc: Int = 0x22000000 + incarnation.toInt(),
        admissionSuffix: Int = 0x14,
        superseded: Boolean = false,
    ): ConferenceMediaMemberControlFact {
        val key = Phase1MediaHarness.threeSourceFixtures.first().sourceAdmissionKey48.copyOf()
        key[5] = admissionSuffix.toByte()
        return ConferenceMediaMemberControlFact(
            conferenceId = conferenceId,
            moduleId = moduleId,
            membershipIncarnationId = incarnation,
            ssrc = ssrc,
            sourceAdmissionKey48 = key,
            mediaKeyEpoch = 1L,
            membershipVersion = membershipVersion,
            factGeneration = factGen,
            superseded = superseded,
        )
    }
}
