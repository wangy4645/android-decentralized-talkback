package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01MembershipWireEvidence
import com.talkback.core.conference.session.profile01.Profile01MembershipWireEvidence.Phase
import com.talkback.core.conference.session.profile01.Profile01MembershipWireEvidence.Sufficiency
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2g-E1 Phase 1 — membership wire authority evidence seam (desk only; no production wiring).
 */
class Profile01A2gE1MembershipWireEvidenceDeskTest {
    private val sessionId = "94597c62-dc3d-4a2c-a719-50dfc0d9710b"
    private val channelId = "CH-01"
    private val hostModuleId = "M01"
    private val plannedRoster = listOf("M01", "M02", "M03", "M04")

    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var membershipConvergence: com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
    private lateinit var creationBridge: MeetingProfile01CreationOriginBridge
    private lateinit var membershipBridge: MeetingProfile01MembershipOriginBridge
    private lateinit var signer: Profile01PersistedSignedFactSigner
    private lateinit var hostPublicKeyX963: ByteArray

    @Before
    fun setUp() {
        sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        membershipConvergence =
            com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry()
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        hostPublicKeyX963 = ecPublicKeyX963(keyPair.public)
        signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = hostModuleId,
                signerKeyVersion = 1L,
            )!!
        creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority(),
                publisher = MeetingProfile01CreationOriginPublisher(signer),
                membershipConvergence = membershipConvergence,
            )
        membershipBridge =
            MeetingProfile01MembershipOriginBridge(
                sessionIndex = sessionIndex,
                creationOriginBridge = creationBridge,
                mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority(),
                membershipConvergence = membershipConvergence,
                publisher = MeetingProfile01MembershipOriginPublisher(signer),
            )
    }

    @Test
    fun a2gE1_fourMemberBootstrap_originSeam_memberIdsAndDigestBoundToWireDecode() {
        seedFieldCreation()
        val signedBytes = emitMembership(fieldFourMemberTopologySnapshot())
        val wire =
            (
                Profile01WireCborDecoder.decodeMembership(signedBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value

        val originCapture =
            Profile01MembershipWireEvidence.decodeAndCaptureOrigin(signedBytes, sessionId)
        assertNotNull(originCapture)
        assertEquals(plannedRoster, originCapture!!.memberIds)
        assertEquals(wire.factDigest.toHexLower(), originCapture.digestHex)
        assertEquals(wire.membershipVersion, originCapture.membershipVersion)
        assertEquals(wire.mediaKeyEpoch, originCapture.mediaKeyEpoch)
        assertTrue(originCapture.formatLogLine().contains("source=${Profile01MembershipWireEvidence.SOURCE_WIRE_DECODE}"))
        assertTrue(originCapture.memberIds.contains("M04"))

        val parsed = Profile01MembershipWireEvidence.parseLogLine(originCapture.formatLogLine())
        assertNotNull(parsed)
        assertEquals(originCapture.digestHex, parsed!!.digestHex)
        assertEquals(originCapture.memberIds, parsed.memberIds)
    }

    @Test
    fun a2gE1_threeMemberWire_originSeam_excludesM04_notTopologyMemberCount() {
        seedFieldCreation()
        val signedBytes = emitMembership(fieldThreeMemberTopologySnapshot())
        val originCapture =
            Profile01MembershipWireEvidence.decodeAndCaptureOrigin(signedBytes, sessionId)!!

        assertFalse(originCapture.memberIds.contains("M04"))
        assertEquals(listOf("M01", "M02", "M03"), originCapture.memberIds)

        val misleadingTopologyEmit =
            "PROFILE01_ORIGIN_EMIT session=$sessionId " +
                "factType=${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} outcome=EMITTED " +
                "conferenceId=${sessionIndex.conferenceIdForSession(sessionId)} " +
                "factDigest=${originCapture.digestHex} members=4 membershipVersion=1 mediaKeyEpoch=2"
        assertEquals(
            Sufficiency.EVIDENCE_INSUFFICIENT,
            Profile01MembershipWireEvidence.assessEvidenceSufficiency(listOf(misleadingTopologyEmit)),
        )
        assertEquals(
            Sufficiency.SUFFICIENT,
            Profile01MembershipWireEvidence.assessEvidenceSufficiency(
                listOf(misleadingTopologyEmit, originCapture.formatLogLine()),
            ),
        )
    }

    @Test
    fun a2gE1_ingressSeam_peerReceive_matchesOfflineDecode() {
        seedFieldCreation()
        val signedBytes = emitMembership(fieldFourMemberTopologySnapshot())
        val peerSessionId = "a2gE1-peer-session"
        val offlineWire =
            (
                Profile01WireCborDecoder.decodeMembership(signedBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value

        val ingressCapture =
            Profile01MembershipWireEvidence.decodeAndCaptureIngress(signedBytes, peerSessionId)!!
        assertEquals(Phase.INGRESS, ingressCapture.phase)
        assertEquals(offlineWire.factDigest.toHexLower(), ingressCapture.digestHex)
        assertEquals(
            Profile01MembershipWireEvidence.memberIdsFromWire(offlineWire),
            ingressCapture.memberIds,
        )

        val originCapture =
            Profile01MembershipWireEvidence.decodeAndCaptureOrigin(signedBytes, sessionId)!!
        assertEquals(originCapture.digestHex, ingressCapture.digestHex)
        assertEquals(originCapture.memberIds, ingressCapture.memberIds)

        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)!!
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator =
                    Profile01ConferenceMediaFactValidator(
                        Profile01GoldenVectorSignedFactTrustBoundary(
                            mapOf(hostModuleId to 1L to hostPublicKeyX963),
                        ),
                    ),
                publisherBridge =
                    com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge(
                        com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry(),
                    ),
                supplementRegistry =
                    com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry(),
            )
        val peerSessionIndex = MeetingProfile01ConferenceSessionIndex()
        peerSessionIndex.registerSession(peerSessionId, channelId, rosterEpoch = 0L)
        peerSessionIndex.bindConferenceId(peerSessionId, conferenceIdHex)
        val wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = ingress,
                supplementRegistry =
                    com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry(),
                sessionIndex = peerSessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                networkInterfaceName = { "lo" },
                localModuleId = { "M03" },
                localEstablishmentKeyVersion = { 1L },
            )
        ingress.ingestCreationSignedFact(
            creationBridge.readSignedCreationFact(sessionId)!!,
            com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement(
                channelId = channelId,
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            ),
            "lo",
        )
        val signal =
            SignalEnvelope(
                type = SignalType.CONFERENCE_SIGNED_FACT,
                from = EndpointAddress(ModuleId(hostModuleId), EndpointId("E01")),
                to = null,
                sessionId = peerSessionId,
                timestampMs = 1L,
                payload = Base64.getEncoder().encodeToString(signedBytes),
                nonce = "a2gE1-membership",
                signature = "a2gE1-membership",
            )
        assertEquals(WireIngressOutcome.APPLIED, wireIngress.onConferenceSignedFact(signal))

        val payloadBytes = Base64.getDecoder().decode(signal.payload.trim())
        val seamCapture =
            Profile01MembershipWireEvidence.decodeAndCaptureIngress(payloadBytes, peerSessionId)!!
        assertEquals(ingressCapture, seamCapture)
    }

    @Test
    fun a2gE1_negative_withoutMembershipWireLine_topologyEmitStillInsufficient() {
        val digestHex = "75bf51f9".padEnd(64, '0')
        val topologyOnlyLines =
            listOf(
                "PROFILE01_ORIGIN_EMIT session=$sessionId " +
                    "factType=${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} outcome=EMITTED " +
                    "conferenceId=8276afe7d0c4903f333edc86c1964d27 factDigest=$digestHex " +
                    "members=4 membershipVersion=1 mediaKeyEpoch=2",
                "WIRE_INGRESS session=$sessionId factType=${Profile01WireConstants.FACT_TYPE_MEMBERSHIP} outcome=APPLIED",
            )
        assertEquals(
            Sufficiency.EVIDENCE_INSUFFICIENT,
            Profile01MembershipWireEvidence.assessEvidenceSufficiency(topologyOnlyLines, digestHex),
        )
        assertEquals(
            Sufficiency.EVIDENCE_INSUFFICIENT,
            Profile01MembershipWireEvidence.assessEvidenceSufficiency(
                topologyOnlyLines,
                requiredDigestHex = "deadbeef".padEnd(64, '0'),
            ),
        )
    }

    private fun seedFieldCreation() {
        val outcome =
            creationBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = fieldHostOnlyTopologySnapshot(),
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = emptySet(),
                publishToPeer = { _, _ -> true },
            )
        assertEquals(OriginEmitOutcome.EMITTED, outcome)
        assertNotNull(sessionIndex.conferenceIdForSession(sessionId))
    }

    private fun emitMembership(snapshot: ConferenceTopologySnapshot): ByteArray {
        val emitted = arrayOf<ByteArray?>(null)
        val outcome =
            membershipBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = snapshot,
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02", "M03", "M04"),
                publishToPeer = { _, bytes ->
                    emitted[0] = bytes
                    true
                },
            )
        assertEquals(OriginEmitOutcome.EMITTED, outcome)
        return emitted[0]!!
    }

    private fun fieldHostOnlyTopologySnapshot(): ConferenceTopologySnapshot =
        ConferenceTopologySnapshot(
            conferenceId = sessionId,
            rosterEpoch = 1L,
            anchorEpoch = 0L,
            anchorId = null,
            meshGeneration = 0L,
            topologyMode = ConferenceTopologyMode.MESH,
            hostModuleId = hostModuleId,
            members = listOf(hostModuleId),
            actualMediaEdges = emptySet(),
        )

    private fun fieldFourMemberTopologySnapshot(): ConferenceTopologySnapshot =
        ConferenceTopologySnapshot(
            conferenceId = sessionId,
            rosterEpoch = 1L,
            anchorEpoch = 100L,
            anchorId = hostModuleId,
            meshGeneration = 1L,
            topologyMode = ConferenceTopologyMode.ANCHOR,
            hostModuleId = hostModuleId,
            members = plannedRoster,
            actualMediaEdges = emptySet(),
        )

    private fun fieldThreeMemberTopologySnapshot(): ConferenceTopologySnapshot =
        ConferenceTopologySnapshot(
            conferenceId = sessionId,
            rosterEpoch = 1L,
            anchorEpoch = 100L,
            anchorId = hostModuleId,
            meshGeneration = 1L,
            topologyMode = ConferenceTopologyMode.ANCHOR,
            hostModuleId = hostModuleId,
            members = listOf("M01", "M02", "M03"),
            actualMediaEdges = emptySet(),
        )

    private fun ecPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        val x = ec.w.affineX.toByteArray()
        val y = ec.w.affineY.toByteArray()
        val x32 = x.copyOfRange(maxOf(0, x.size - 32), x.size)
        val y32 = y.copyOfRange(maxOf(0, y.size - 32), y.size)
        return byteArrayOf(0x04) + ByteArray(32 - x32.size) + x32 + ByteArray(32 - y32.size) + y32
    }
}
