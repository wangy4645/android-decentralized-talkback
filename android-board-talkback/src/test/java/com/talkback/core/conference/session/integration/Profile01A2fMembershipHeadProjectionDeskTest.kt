package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2f — membership-head projection RCA desk (session `94597c62…` field bootstrap).
 *
 * Field moment under test: topology planned roster already includes M04 at membership v1 emit,
 * but validator authority head at M04 source ingress is {M01,M02,M03}.
 */
class Profile01A2fMembershipHeadProjectionDeskTest {
    private val sessionId = "94597c62-dc3d-4a2c-a719-50dfc0d9710b"
    private val channelId = "CH-01"
    private val hostModuleId = "M01"
    private val plannedRoster = listOf("M01", "M02", "M03", "M04")

    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var membershipConvergence: Profile01MembershipConvergenceRegistry
    private lateinit var creationBridge: MeetingProfile01CreationOriginBridge
    private lateinit var membershipBridge: MeetingProfile01MembershipOriginBridge
    private lateinit var signer: Profile01PersistedSignedFactSigner
    private lateinit var hostPublicKeyX963: ByteArray

    @Before
    fun setUp() {
        sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        membershipConvergence = Profile01MembershipConvergenceRegistry()
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
                mediaKeyAuthority =
                    com.talkback.core.conference.session.profile01.wire
                        .Profile01ConferenceMediaKeyMaterialAuthority(),
                publisher = MeetingProfile01CreationOriginPublisher(signer),
                membershipConvergence = membershipConvergence,
            )
        membershipBridge =
            MeetingProfile01MembershipOriginBridge(
                sessionIndex = sessionIndex,
                creationOriginBridge = creationBridge,
                mediaKeyAuthority =
                    com.talkback.core.conference.session.profile01.wire
                        .Profile01ConferenceMediaKeyMaterialAuthority(),
                membershipConvergence = membershipConvergence,
                publisher = MeetingProfile01MembershipOriginPublisher(signer),
            )
    }

    /**
     * FIELD-ISOMORPHIC DESK RED (expected GREEN if production bootstrap is correct):
     * host-only creation (meshGen=0) then single-shot four-member topology (meshGen=1).
     */
    @Test
    fun a2f_fieldIsomorphic_singleShotFourMemberTopology_wireAndHeadIncludeM04() {
        seedFieldCreation()
        val emitted =
            emitMembership(fieldFourMemberTopologySnapshot())
        val decoded =
            (
                Profile01WireCborDecoder.decodeMembership(emitted)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)!!
        val head = membershipConvergence.current(conferenceIdHex)!!

        assertEquals(
            plannedRoster.sorted(),
            decoded.members.map { it.moduleId }.sorted(),
        )
        assertEquals(
            plannedRoster.sorted(),
            head.members.map { it.moduleId }.sorted(),
        )
        assertTrue(head.hasMember("M04"))
        assertEquals(1L, head.membershipVersion)
        assertEquals(2L, head.mediaKeyEpoch)
    }

    /**
     * M03/M04 differential control: incremental three-peer membership stops before M04.
     * Models field head shape {M01,M02,M03} without M04.
     */
    @Test
    fun a2f_differential_incrementalThreePeerHead_hasM03_notM04() {
        seedFieldCreation()
        emitMembership(fieldThreeMemberTopologySnapshot())
        val head = membershipConvergence.current(sessionIndex.conferenceIdForSession(sessionId)!!)!!

        assertTrue(head.hasMember("M03"))
        assertFalse(head.hasMember("M04"))
        assertEquals(listOf("M01", "M02", "M03"), head.members.map { it.moduleId }.sorted())
    }

    /**
     * Field failure shape: planned roster already four, head frozen at three, M04 source rejected.
     */
    @Test
    fun a2f_fieldFailureShape_plannedFourHeadThree_m04NotInAuthorityHead() {
        seedFieldCreation()
        emitMembership(fieldThreeMemberTopologySnapshot())
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)!!
        val head = membershipConvergence.current(conferenceIdHex)!!
        assertNotNull(head)
        assertFalse(head!!.hasMember("M04"))

        val validator =
            Profile01ConferenceMediaFactValidator(
                Profile01GoldenVectorSignedFactTrustBoundary(
                    mapOf(hostModuleId to 1L to hostPublicKeyX963),
                ),
            )
        when (
            val seeded =
                validator.membershipRegistry().seedFromCreation(
                    decodeCreation(conferenceIdHex),
                )
        ) {
            is com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult.Accepted ->
                Unit
            is com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult.Idempotent ->
                Unit
            else -> error("creation seed failed: $seeded")
        }
        val membershipBytes = membershipBridge.readSignedMembershipFact(sessionId)!!
        when (
            val applied =
                validator.membershipRegistry().applyMembership(
                    (
                        Profile01WireCborDecoder.decodeMembership(membershipBytes)
                            as Profile01WireCborDecoder.DecodeResult.Ready
                    ).value,
                )
        ) {
            is com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult.Accepted ->
                Unit
            is com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult.Idempotent ->
                Unit
            else -> error("membership apply failed: $applied")
        }
        val validatorHead = validator.membershipRegistry().current(conferenceIdHex)!!
        assertFalse(validatorHead.hasMember("M04"))
        assertTrue(validatorHead.hasMember("M03"))
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

    private fun decodeCreation(conferenceIdHex: String) =
        (
            Profile01WireCborDecoder.decodeCreationSession(
                creationBridge.readSignedCreationFact(sessionId)!!,
                com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement(
                    channelId = channelId,
                    masterKey = ByteArray(16),
                    masterSalt = ByteArray(12),
                    keyContextHint64 = ByteArray(8),
                ),
            ) as Profile01WireCborDecoder.DecodeResult.Ready
        ).value

    private fun ecPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        val x = ec.w.affineX.toByteArray()
        val y = ec.w.affineY.toByteArray()
        val x32 = x.copyOfRange(maxOf(0, x.size - 32), x.size)
        val y32 = y.copyOfRange(maxOf(0, y.size - 32), y.size)
        return byteArrayOf(0x04) + ByteArray(32 - x32.size) + x32 + ByteArray(32 - y32.size) + y32
    }
}
