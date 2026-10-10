package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityTestFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01ProfileBackedRecipientEstablishmentKeyLookup
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerSource
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-C exit tests: C1–C8 MEDIA_KEY_PACKAGE origin/publication (PR-3).
 */
class MeetingProfile01MediaKeyPackageOriginBridgeTest {
    @Test
    fun c1_eligiblePeerWithBinding_buildSendPublished() {
        val harness = newHarness(sessionId = "c1-session")
        seedCreation(harness, eligiblePeers = setOf("M02"))

        val sends = mutableListOf<Pair<String, ByteArray>>()
        val outcome =
            harness.packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { moduleId, bytes ->
                    sends += moduleId to bytes.copyOf()
                    true
                },
            )

        assertEquals(MediaKeyPackageOriginOutcome.EMITTED, outcome)
        assertEquals(1, sends.size)
        assertEquals("M02", sends.single().first)
        assertEquals(Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE, factType(sends.single().second))
        assertEquals(1, harness.packageBridge.ledger().publishedIdentities(harness.sessionId).size)
    }

    @Test
    fun p1c_field_obs_origin_emitsStructuredPackageOriginWithMediaKeyEpoch() {
        val logs = mutableListOf<String>()
        val harness = newHarness(sessionId = "obs-origin-session", observabilityLogs = logs)
        seedCreation(harness, eligiblePeers = setOf("M02"))
        val creationBytes = harness.creationBridge.readSignedCreationFact(harness.sessionId)!!
        val creation =
            (
                Profile01WireCborDecoder.decodeCreationSession(
                    creationBytes,
                    com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement(
                        channelId = "obs",
                        masterKey = ByteArray(16),
                        masterSalt = ByteArray(12),
                        keyContextHint64 = ByteArray(8),
                    ),
                ) as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val conferenceIdHex = harness.sessionIndex.ensureConferenceIdHex(harness.sessionId)
        val digest =
            com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
                .computeFactDigest(
                    Profile01SignedFactEnvelope.parse(creationBytes)!!.fullCanonicalBytes,
                ).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )

        val origin =
            logs.single { it.startsWith("PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN ") }
        assertTrue(origin.contains("conferenceId=$conferenceIdHex"))
        assertTrue(origin.contains("mediaKeyEpoch=${creation.mediaKeyEpoch}"))
        assertTrue(origin.contains("recipientModuleId=M02"))
        assertTrue(origin.contains("recipientEstablishmentKeyVersion="))
        assertTrue(origin.contains("membershipFactDigest=$digest"))
        assertTrue(origin.contains("publicationOutcome=EMITTED"))
        assertFalse(origin.contains("masterKey="))
        assertFalse(origin.contains("wrappedPek="))
    }

    @Test
    fun cf7_emitRecipientKeyVersionMismatchFixture_doesNotTouchPositiveLedger_andLogsInjected() {
        val logs = mutableListOf<String>()
        val harness = newHarness(sessionId = "cf7-neg-session", observabilityLogs = logs)
        seedCreation(harness, eligiblePeers = setOf("M02"))
        val positiveSends = mutableListOf<ByteArray>()
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, bytes ->
                positiveSends += bytes.copyOf()
                true
            },
        )
        val positiveCount =
            harness.packageBridge.ledger().publishedIdentities(harness.sessionId).size
        assertEquals(1, positiveCount)

        val negativeSends = mutableListOf<ByteArray>()
        val outcome =
            harness.packageBridge.emitRecipientKeyVersionMismatchFixture(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                negativeFixtureId = "negfixture001",
                publishToPeer = { moduleId, bytes ->
                    assertEquals("M02", moduleId)
                    negativeSends += bytes.copyOf()
                    true
                },
            )
        assertEquals(MediaKeyPackageNegativeFixtureOutcome.EMITTED, outcome)
        assertEquals(1, negativeSends.size)
        assertEquals(
            positiveCount,
            harness.packageBridge.ledger().publishedIdentities(harness.sessionId).size,
        )
        assertFalse(positiveSends.single().contentEquals(negativeSends.single()))

        val fixtureLine =
            logs.single { it.startsWith("PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE ") }
        assertTrue(fixtureLine.contains("fixtureType=RECIPIENT_KEY_VERSION_MISMATCH"))
        assertTrue(fixtureLine.contains("negativeFixtureId=negfixture001"))
        assertTrue(fixtureLine.contains("injected=true"))
        assertTrue(fixtureLine.contains("expectedRecipientEstablishmentKeyVersion="))
        assertTrue(fixtureLine.contains("wireRecipientKeyVersion="))
        assertTrue(fixtureLine.contains("publicationOutcome=EMITTED"))

        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(negativeSends.single())
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val expected =
            fixtureLine
                .split(' ')
                .first { it.startsWith("expectedRecipientEstablishmentKeyVersion=") }
                .substringAfter('=')
                .toLong()
        val wireV =
            fixtureLine
                .split(' ')
                .first { it.startsWith("wireRecipientKeyVersion=") }
                .substringAfter('=')
                .toLong()
        assertEquals(wireV, wire.recipientKeyVersion)
        assertNotEquals(expected, wire.recipientKeyVersion)
    }

    @Test
    fun c2_duplicateTriggerForCurrentIdentity_noDuplicateSend() {
        val harness = newHarness(sessionId = "c2-session")
        seedCreation(harness, eligiblePeers = setOf("M02"))

        val sends = mutableListOf<ByteArray>()
        val publish: (String, ByteArray) -> Boolean = { _, bytes ->
            sends += bytes.copyOf()
            true
        }
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = publish,
        )
        val second =
            harness.packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = publish,
            )

        assertEquals(MediaKeyPackageOriginOutcome.SKIPPED_NO_PENDING_PUBLICATION, second)
        assertEquals(1, sends.size)
    }

    @Test
    fun c3_firstSendFails_notPublished_laterTriggerSucceeds() {
        val harness = newHarness(sessionId = "c3-session")
        seedCreation(harness, eligiblePeers = setOf("M02"))

        val first =
            harness.packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { _, _ -> false },
            )
        assertEquals(MediaKeyPackageOriginOutcome.SKIPPED_NO_PENDING_PUBLICATION, first)
        assertTrue(harness.packageBridge.ledger().publishedIdentities(harness.sessionId).isEmpty())

        val captured = mutableListOf<ByteArray>()
        val second =
            harness.packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { _, bytes ->
                    captured += bytes.copyOf()
                    true
                },
            )
        assertEquals(MediaKeyPackageOriginOutcome.EMITTED, second)
        assertEquals(1, captured.size)
        assertEquals(1, harness.packageBridge.ledger().publishedIdentities(harness.sessionId).size)
    }

    @Test
    fun c4_establishmentVersionRotates_newPackageSent() {
        val harness = newHarness(sessionId = "c4-session")
        seedCreation(harness, eligiblePeers = setOf("M02"))

        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )

        val rotatedVersion = Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION + 1
        val rotatedKey = generateRsaKeyPair()
        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(
            harness.establishmentStore,
            Profile01EstablishmentAuthorityTestFixtures.establishmentSnapshot(
                establishmentKeyVersion = rotatedVersion,
            ).let { snapshot ->
                snapshot.copy(
                    bindingsByKey =
                        mapOf(
                            AcceptedLocalEstablishmentTrustState.BindingKey(HOST, rotatedVersion) to
                                ModuleEstablishmentBinding(
                                    moduleId = HOST,
                                    establishmentKeyVersion = rotatedVersion,
                                    keyState = GenerationFactKeyState.ACTIVE,
                                    establishmentPublicKeySpki = rotatedKey.publicSpki,
                                    activatedAtRevision = 11L,
                                ),
                            AcceptedLocalEstablishmentTrustState.BindingKey(
                                Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                                rotatedVersion,
                            ) to
                                ModuleEstablishmentBinding(
                                    moduleId = Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                                    establishmentKeyVersion = rotatedVersion,
                                    keyState = GenerationFactKeyState.ACTIVE,
                                    establishmentPublicKeySpki = rotatedKey.publicSpki,
                                    activatedAtRevision = 11L,
                                ),
                        ),
                )
            },
        )

        val sends = mutableListOf<ByteArray>()
        val outcome =
            harness.packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { _, bytes ->
                    sends += bytes.copyOf()
                    true
                },
            )

        assertEquals(MediaKeyPackageOriginOutcome.REPLAYED, outcome)
        assertEquals(1, sends.size)
        assertEquals(2, harness.packageBridge.ledger().publishedIdentities(harness.sessionId).size)
    }

    @Test
    fun c5_membershipFactDigestChange_newPackageSent() {
        val harnessA = newHarness(sessionId = "c5-session-a")
        seedCreation(harnessA, eligiblePeers = setOf("M02"))
        harnessA.packageBridge.onEligiblePeers(
            sessionId = harnessA.sessionId,
            snapshot = harnessA.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )

        val harnessB = newHarness(sessionId = "c5-session-b")
        seedCreation(harnessB, eligiblePeers = setOf("M02"))
        val sends = mutableListOf<ByteArray>()
        harnessB.packageBridge.onEligiblePeers(
            sessionId = harnessB.sessionId,
            snapshot = harnessB.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, bytes ->
                sends += bytes.copyOf()
                true
            },
        )

        assertEquals(1, sends.size)
        val identityA = harnessA.packageBridge.ledger().publishedIdentities(harnessA.sessionId).single()
        val identityB = harnessB.packageBridge.ledger().publishedIdentities(harnessB.sessionId).single()
        assertNotEquals(identityA.membershipFactDigestHex, identityB.membershipFactDigestHex)
        assertNotEquals(identityA, identityB)

        val ledger = MediaKeyPackagePublicationLedger()
        val base =
            MediaKeyPackagePublicationIdentity(
                conferenceIdHex = identityA.conferenceIdHex,
                mediaKeyEpoch = 1L,
                recipientModuleId = "M02",
                recipientEstablishmentKeyVersion = 4L,
                membershipFactDigestHex = identityA.membershipFactDigestHex,
            )
        ledger.markPublished("session", base)
        assertFalse(ledger.isPublished("session", base.copy(mediaKeyEpoch = 2L)))
    }

    @Test
    fun c6_ineligiblePeer_zeroSend() {
        val harness = newHarness(sessionId = "c6-session")
        seedCreation(harness, eligiblePeers = emptySet())

        val sends = mutableListOf<String>()
        val outcome =
            harness.packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = emptySet(),
                publishToPeer = { moduleId, _ ->
                    sends += moduleId
                    true
                },
            )

        assertEquals(MediaKeyPackageOriginOutcome.SKIPPED_NO_PENDING_PUBLICATION, outcome)
        assertTrue(sends.isEmpty())
    }

    @Test
    fun c7_unknownEstablishmentBinding_explicitGapZeroSend() {
        val harness = newHarness(sessionId = "c7-session", populateEstablishment = false)
        seedCreation(harness, eligiblePeers = setOf("M02"))

        val logs = mutableListOf<String>()
        val packageBridge =
            MeetingProfile01MediaKeyPackageOriginBridge(
                sessionIndex = harness.sessionIndex,
                creationOrigin = harness.creationBridge,
                mediaKeyAuthority = harness.mediaKeyAuthority,
                establishmentLookup = harness.establishmentLookup,
                signerSource = Profile01SignedFactSignerSource.fixed(harness.signer),
                onLog = logs::add,
            )
        val sends = mutableListOf<String>()
        val outcome =
            packageBridge.onEligiblePeers(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { moduleId, _ ->
                    sends += moduleId
                    true
                },
            )

        assertEquals(MediaKeyPackageOriginOutcome.SKIPPED_NO_PENDING_PUBLICATION, outcome)
        assertTrue(sends.isEmpty())
        assertTrue(logs.any { it.contains("ORIGIN_SOURCE_GAP") && it.contains("UNKNOWN_ESTABLISHMENT_MODULE") })
    }

    @Test
    fun c8_twoPeers_recipientSpecificPackagesCannotCrossDecrypt() {
        val peerB = generateRsaKeyPair()
        val peerC = generateRsaKeyPair()
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        val version = Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION
        establishmentStore.atomicReplace(
            AcceptedLocalEstablishmentTrustState(
                taskProfileRevision = 10L,
                revisionIdentity = ByteArray(32),
                localModuleId = HOST,
                bindingsByKey =
                    mapOf(
                        bindingKey("M02", version, peerB.publicSpki),
                        bindingKey("M03", version, peerC.publicSpki),
                        bindingKey(HOST, version, peerB.publicSpki),
                    ),
            ),
        )
        val harness =
            newHarness(
                sessionId = "c8-session",
                establishmentStore = establishmentStore,
                populateEstablishment = false,
            )
        seedCreation(harness, eligiblePeers = setOf("M02", "M03"), members = listOf(HOST, "M02", "M03"))

        val sends = linkedMapOf<String, ByteArray>()
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.trioSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02", "M03"),
            publishToPeer = { moduleId, bytes ->
                sends[moduleId] = bytes.copyOf()
                true
            },
        )

        assertEquals(setOf("M02", "M03"), sends.keys)
        assertNotEquals(sends["M02"]!!.toList(), sends["M03"]!!.toList())

        val wireB =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(sends["M02"]!!)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        val wireC =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(sends["M03"]!!)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals("M02", wireB.recipientModuleId)
        assertEquals("M03", wireC.recipientModuleId)

        val decryptB =
            Profile01MediaKeyPackageDecryptSeam(
                Profile01Q5TestRecipientKeyEstablishmentSeam(
                    mapOf(
                        "M02" to version to peerB.privateKey,
                        "M03" to version to peerC.privateKey,
                    ),
                ),
            )
        assertTrue(
            decryptB.decrypt(wireB, "M02", version) is Profile01MediaKeyPackageDecryptResult.Ready,
        )
        assertTrue(
            decryptB.decrypt(wireB, "M03", version) is Profile01MediaKeyPackageDecryptResult.Rejected,
        )
        assertTrue(
            decryptB.decrypt(wireC, "M03", version) is Profile01MediaKeyPackageDecryptResult.Ready,
        )
        assertTrue(
            decryptB.decrypt(wireC, "M02", version) is Profile01MediaKeyPackageDecryptResult.Rejected,
        )
    }

    private data class Harness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val mediaKeyAuthority: com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val packageBridge: MeetingProfile01MediaKeyPackageOriginBridge,
        val establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        val establishmentLookup: Profile01ProfileBackedRecipientEstablishmentKeyLookup,
        val packageBuilder: Profile01MediaKeyPackageBuilder,
        val signer: com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSigner,
        val observabilityLogs: MutableList<String> = mutableListOf(),
    ) {
        fun duoSnapshot(): ConferenceTopologySnapshot = topologySnapshot(listOf(HOST, "M02"))

        fun trioSnapshot(): ConferenceTopologySnapshot = topologySnapshot(listOf(HOST, "M02", "M03"))

        private fun topologySnapshot(members: List<String>): ConferenceTopologySnapshot =
            ConferenceTopologySnapshot(
                conferenceId = sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 2L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = members,
                actualMediaEdges = emptySet(),
            )
    }

    private fun newHarness(
        sessionId: String,
        populateEstablishment: Boolean = true,
        establishmentStore: AcceptedLocalEstablishmentTrustStateStore = AcceptedLocalEstablishmentTrustStateStore(),
        observabilityLogs: MutableList<String> = mutableListOf(),
    ): Harness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, "CH-$sessionId", rosterEpoch = 1L)
        val mediaKeyAuthority =
            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority()
        val signer = testSigner()
        val creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                signerSource = Profile01SignedFactSignerSource.fixed(signer),
            )
        if (populateEstablishment) {
            Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(establishmentStore)
        }
        val establishmentLookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(establishmentStore)
        val packageBuilder = Profile01MediaKeyPackageBuilder(signer = signer)
        val packageBridge =
            MeetingProfile01MediaKeyPackageOriginBridge(
                sessionIndex = sessionIndex,
                creationOrigin = creationBridge,
                mediaKeyAuthority = mediaKeyAuthority,
                establishmentLookup = establishmentLookup,
                signerSource = Profile01SignedFactSignerSource.fixed(signer),
                onLog = observabilityLogs::add,
            )
        return Harness(
            sessionId = sessionId,
            sessionIndex = sessionIndex,
            mediaKeyAuthority = mediaKeyAuthority,
            creationBridge = creationBridge,
            packageBridge = packageBridge,
            establishmentStore = establishmentStore,
            establishmentLookup = establishmentLookup,
            packageBuilder = packageBuilder,
            signer = signer,
            observabilityLogs = observabilityLogs,
        )
    }

    private fun seedCreation(
        harness: Harness,
        eligiblePeers: Set<String>,
        members: List<String> = listOf(HOST, "M02"),
    ) {
        val snapshot =
            ConferenceTopologySnapshot(
                conferenceId = harness.sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = if (members.size > 1) 2L else 1L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = members,
                actualMediaEdges = emptySet(),
            )
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = snapshot,
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = eligiblePeers,
            publishToPeer = { _, _ -> true },
        )
    }

    private fun factType(signedFactBytes: ByteArray): Int? =
        Profile01WireCborDecoder.readFactType(signedFactBytes)

    private fun testSigner(): Profile01PersistedSignedFactSigner {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        return Profile01PersistedSignedFactSigner.fromPkcs8(
            pkcs8PrivateKey = keyPair.private.encoded,
            signerModuleId = HOST,
            signerKeyVersion = 1L,
        )!!
    }

    private data class RsaKeyMaterial(
        val privateKey: java.security.PrivateKey,
        val publicSpki: ByteArray,
    )

    private fun generateRsaKeyPair(): RsaKeyMaterial {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val privateKey = keyPair.private
        val public =
            java.security.KeyFactory.getInstance("RSA").generatePublic(
                java.security.spec.RSAPublicKeySpec(
                    (privateKey as RSAPrivateCrtKey).modulus,
                    privateKey.publicExponent,
                ),
            )
        return RsaKeyMaterial(privateKey = privateKey, publicSpki = public.encoded)
    }

    private fun bindingKey(
        moduleId: String,
        version: Long,
        publicSpki: ByteArray,
    ): Pair<AcceptedLocalEstablishmentTrustState.BindingKey, ModuleEstablishmentBinding> {
        val binding =
            ModuleEstablishmentBinding(
                moduleId = moduleId,
                establishmentKeyVersion = version,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = publicSpki.copyOf(),
                activatedAtRevision = 10L,
            )
        return AcceptedLocalEstablishmentTrustState.BindingKey(moduleId, version) to binding
    }

    companion object {
        private const val HOST = "M01"
    }
}
