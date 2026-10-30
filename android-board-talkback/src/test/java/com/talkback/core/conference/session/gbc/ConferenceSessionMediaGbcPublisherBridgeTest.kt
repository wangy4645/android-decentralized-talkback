package com.talkback.core.conference.session.gbc

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.ControlPlaneConferenceSessionMediaFactPort
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConferenceSessionMediaGbcPublisherBridgeTest {
    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
    private val port = ControlPlaneConferenceSessionMediaFactPort(registry)

    @Test
    fun sessionDeclaration_onlyAuthoritativeGeneration_publishes() {
        val decl = sessionDeclaration(disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, bridge.publishSessionDeclaration(decl))
        assertEquals(ControlFactPublishOutcome.IDEMPOTENT, bridge.publishSessionDeclaration(decl))
        assertNotNull(port.sessionFact(decl.conferenceId, decl.channelId, decl.membershipVersion))

        assertEquals(
            ControlFactPublishOutcome.REJECTED_NOT_AUTHORITATIVE,
            bridge.publishSessionDeclaration(
                decl.copy(
                    factGeneration = 2L,
                    disposition = ConferenceMediaDeclarationDisposition.PENDING,
                ),
            ),
        )
        assertEquals(
            ControlFactPublishOutcome.REJECTED_SUPERSEDED,
            bridge.publishSessionDeclaration(
                decl.copy(
                    factGeneration = 3L,
                    disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                ),
            ),
        )
    }

    @Test
    fun memberDeclaration_requiresCompleteBindingFields() {
        val session = sessionDeclaration()
        bridge.publishSessionDeclaration(session)

        val complete =
            memberDeclaration(
                conferenceId = session.conferenceId,
                moduleId = "M-A",
                incarnation = 10L,
                membershipVersion = session.membershipVersion,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, bridge.publishMemberDeclaration(complete))
        assertEquals(ControlFactPublishOutcome.IDEMPOTENT, bridge.publishMemberDeclaration(complete))

        assertEquals(
            ControlFactPublishOutcome.REJECTED_INCOMPLETE,
            bridge.publishMemberDeclaration(
                complete.copy(sourceAdmissionKey48 = ByteArray(5)),
            ),
        )
        assertEquals(
            ControlFactPublishOutcome.REJECTED_INCOMPLETE,
            bridge.publishMemberDeclaration(
                complete.copy(moduleId = ""),
            ),
        )
        assertEquals(
            ControlFactPublishOutcome.REJECTED_NOT_AUTHORITATIVE,
            bridge.publishMemberDeclaration(
                complete.copy(
                    factGeneration = 2L,
                    disposition = ConferenceMediaDeclarationDisposition.PENDING,
                ),
            ),
        )
    }

    @Test
    fun staleAndSupersededDeclarations_doNotOverwriteRegistry() {
        val session = sessionDeclaration(factGen = 1L)
        bridge.publishSessionDeclaration(session)

        val current =
            memberDeclaration(
                conferenceId = session.conferenceId,
                moduleId = "M-B",
                incarnation = 20L,
                membershipVersion = session.membershipVersion,
                factGen = 2L,
            )
        val successor =
            memberDeclaration(
                conferenceId = session.conferenceId,
                moduleId = "M-B",
                incarnation = 21L,
                ssrc = 0x22000021,
                membershipVersion = session.membershipVersion,
                factGen = 3L,
                admissionSuffix = 0x2B,
            )
        bridge.publishMemberDeclaration(current)
        bridge.publishMemberDeclaration(successor)

        assertEquals(
            ControlFactPublishOutcome.REJECTED_STALE,
            bridge.publishMemberDeclaration(
                current.copy(factGeneration = 4L),
            ),
        )
        assertEquals(
            ControlFactPublishOutcome.REJECTED_SUPERSEDED,
            bridge.publishMemberDeclaration(
                successor.copy(
                    factGeneration = 5L,
                    disposition = ConferenceMediaDeclarationDisposition.SUPERSEDED,
                ),
            ),
        )
        assertEquals(21L, port.memberBinding(session.conferenceId, "M-B")!!.incarnationId)
    }

    @Test
    fun staleSessionGeneration_doesNotOverwriteRegistry() {
        val session = sessionDeclaration(conferenceEpoch = 5L, factGen = 1L)
        bridge.publishSessionDeclaration(session)
        assertEquals(
            ControlFactPublishOutcome.REJECTED_STALE,
            bridge.publishSessionDeclaration(
                session.copy(
                    conferenceEpoch = 4L,
                    factGeneration = 2L,
                ),
            ),
        )
        assertEquals(5L, port.sessionFact(session.conferenceId, session.channelId, session.membershipVersion)!!.generation)
    }

    @Test
    fun gbcBridge_toPort_preservesAuthoritativeSsrcNotDerivedFromModuleId() {
        val session = sessionDeclaration()
        bridge.publishSessionDeclaration(session)
        val member =
            memberDeclaration(
                conferenceId = session.conferenceId,
                moduleId = "HTUBB21B09220661",
                incarnation = 7L,
                ssrc = 0xAABBCCDD.toInt(),
                membershipVersion = session.membershipVersion,
            )
        bridge.publishMemberDeclaration(member)
        val binding = port.memberBinding(session.conferenceId, member.moduleId)
        assertNotNull(binding)
        assertEquals(0xAABBCCDD.toInt(), binding!!.ssrc)
        assertEquals(member.moduleId, binding.sourceIdentity)
    }

    @Test
    fun withdrawSession_clearsRegistryFactsOnly() {
        val session = sessionDeclaration()
        bridge.publishSessionDeclaration(session)
        bridge.publishMemberDeclaration(
            memberDeclaration(
                conferenceId = session.conferenceId,
                moduleId = "M-X",
                incarnation = 1L,
                membershipVersion = session.membershipVersion,
            ),
        )
        bridge.withdrawSession(session.conferenceId)
        assertNull(port.sessionFact(session.conferenceId, session.channelId, session.membershipVersion))
        assertNull(port.memberBinding(session.conferenceId, "M-X"))
    }

    private fun sessionDeclaration(
        conferenceId: String = "gbc-sess-1",
        conferenceEpoch: Long = 1L,
        membershipVersion: Long = 3L,
        factGen: Long = 1L,
        disposition: ConferenceMediaDeclarationDisposition =
            ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
    ): AuthoritativeConferenceMediaSessionDeclaration =
        AuthoritativeConferenceMediaSessionDeclaration(
            conferenceId = conferenceId,
            channelId = "ch-$conferenceId",
            conferenceEpoch = conferenceEpoch,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = 1L,
            endpoint =
                MediaGroupEndpointBinding(
                    multicastAddress = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
                    mediaPort = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
                    underlayScopeId = "gbc-$conferenceId",
                ),
            networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            masterKey = Phase1MediaHarness.masterKey.copyOf(),
            masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
            keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
            factGeneration = factGen,
            disposition = disposition,
        )

    private fun memberDeclaration(
        conferenceId: String,
        moduleId: String,
        incarnation: Long,
        membershipVersion: Long,
        factGen: Long = 1L,
        ssrc: Int = 0x22000000 + incarnation.toInt(),
        admissionSuffix: Int = 0x14,
        disposition: ConferenceMediaDeclarationDisposition =
            ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
    ): AuthoritativeConferenceMediaMemberDeclaration {
        val key = Phase1MediaHarness.threeSourceFixtures.first().sourceAdmissionKey48.copyOf()
        key[5] = admissionSuffix.toByte()
        return AuthoritativeConferenceMediaMemberDeclaration(
            conferenceId = conferenceId,
            moduleId = moduleId,
            membershipIncarnationId = incarnation,
            ssrc = ssrc,
            sourceAdmissionKey48 = key,
            mediaKeyEpoch = 1L,
            membershipVersion = membershipVersion,
            factGeneration = factGen,
            disposition = disposition,
        )
    }
}
