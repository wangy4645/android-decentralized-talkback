package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityTestFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerSource
import com.talkback.core.conference.session.profile01.wire.Profile01ProfileBackedRecipientEstablishmentKeyLookup
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-C-R1 exit tests: post-bind MEDIA_KEY_PACKAGE republish obligation.
 */
class MeetingProfile01MediaKeyPackagePostBindRepublishTest {
    @Test
    fun r1_preBindPublished_peerNotBound_doesNotMarkPostBindDelivered() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r1-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        val preBindBytes = mutableListOf<ByteArray>()
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, bytes ->
                preBindBytes += bytes.copyOf()
                true
            },
        )
        val identity = harness.packageBridge.ledger().publishedIdentities(harness.sessionId).single()
        assertTrue(harness.packageBridge.ledger().isPublished(harness.sessionId, identity))
        assertFalse(harness.packageBridge.postBindLedger().isPostBindDelivered(harness.sessionId, identity))
    }

    @Test
    fun r2_peerEligible_exactlyOnePostBindRepublish() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r2-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        val postBindSends = mutableListOf<ByteArray>()
        val outcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, bytes ->
                    postBindSends += bytes.copyOf()
                    true
                },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.EMITTED, outcome)
        assertEquals(1, postBindSends.size)
        val identity = harness.packageBridge.ledger().publishedIdentities(harness.sessionId).single()
        assertTrue(harness.packageBridge.postBindLedger().isPostBindDelivered(harness.sessionId, identity))
    }

    @Test
    fun r3_republishUsesFreshCiphertext() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r3-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        val preBindBytes = mutableListOf<ByteArray>()
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, bytes ->
                preBindBytes += bytes.copyOf()
                true
            },
        )
        val postBindBytes = mutableListOf<ByteArray>()
        harness.packageBridge.onPeerPackageConsumptionEligible(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            recipientModuleId = "M02",
            publishToPeer = { _, bytes ->
                postBindBytes += bytes.copyOf()
                true
            },
        )
        assertEquals(1, preBindBytes.size)
        assertEquals(1, postBindBytes.size)
        assertFalse(preBindBytes.single().contentEquals(postBindBytes.single()))
    }

    @Test
    fun r4_sendFailure_obligationRemainsOpen() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r4-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        val identity = harness.packageBridge.ledger().publishedIdentities(harness.sessionId).single()
        val outcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ -> false },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.SEND_FAILED, outcome)
        assertFalse(harness.packageBridge.postBindLedger().isPostBindDelivered(harness.sessionId, identity))

        val retrySends = mutableListOf<ByteArray>()
        val retryOutcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, bytes ->
                    retrySends += bytes.copyOf()
                    true
                },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.EMITTED, retryOutcome)
        assertEquals(1, retrySends.size)
        assertTrue(harness.packageBridge.postBindLedger().isPostBindDelivered(harness.sessionId, identity))
    }

    @Test
    fun r5_duplicateEligibleEvent_atMostOneSuccessfulPostBindPublication() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r5-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        var sendCount = 0
        val first =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        val second =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ ->
                    sendCount++
                    true
                },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.EMITTED, first)
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.SKIPPED_ALREADY_DELIVERED, second)
        assertEquals(1, sendCount)
    }

    @Test
    fun r6_staleIdentityAfterCreationChange_skipsRepublish() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r6-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        val staleIdentity = harness.packageBridge.ledger().publishedIdentities(harness.sessionId).single()

        harness.creationBridge.clearSession(harness.sessionId)
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))

        val outcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ -> true },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NO_PRE_BIND_PUBLICATION, outcome)
        assertFalse(harness.packageBridge.postBindLedger().isPostBindDelivered(harness.sessionId, staleIdentity))
    }

    @Test
    fun r7_noPreBindPublication_skipsPostBindRepublish() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r7-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        val outcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.duoSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ -> true },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.SKIPPED_NO_PRE_BIND_PUBLICATION, outcome)
        assertTrue(harness.packageBridge.postBindLedger().deliveredIdentities(harness.sessionId).isEmpty())
    }

    @Test
    fun r8_recipientScoped_m04LookupFailureDoesNotSatisfyM02() {
        val harness = MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness("r8-session")
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02", "M04"))
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.trioSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        val m02Identity = harness.packageBridge.ledger().publishedIdentities(harness.sessionId).single()

        val m02Outcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.trioSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ -> true },
            )
        val m04Outcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.trioSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M04",
                publishToPeer = { _, _ -> true },
            )
        val m02Retry =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.trioSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M02",
                publishToPeer = { _, _ -> true },
            )

        assertEquals(MediaKeyPackagePostBindRepublishOutcome.EMITTED, m02Outcome)
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.SKIPPED_LOOKUP, m04Outcome)
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.SKIPPED_ALREADY_DELIVERED, m02Retry)
        assertTrue(harness.packageBridge.postBindLedger().isPostBindDelivered(harness.sessionId, m02Identity))
        assertTrue(harness.packageBridge.postBindLedger().deliveredIdentities(harness.sessionId).size == 1)
    }

    @Test
    fun postBindRepublish_logsStructuredObs() {
        val logs = mutableListOf<String>()
        val harness =
            MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.newHarness(
                sessionId = "obs-post-bind",
                observabilityLogs = logs,
            )
        MeetingProfile01MediaKeyPackageOriginBridgeTestSupport.seedCreation(harness, setOf("M02"))
        harness.packageBridge.onEligiblePeers(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M02"),
            publishToPeer = { _, _ -> true },
        )
        harness.packageBridge.onPeerPackageConsumptionEligible(
            sessionId = harness.sessionId,
            snapshot = harness.duoSnapshot(),
            localModuleId = HOST,
            isMembershipAuthority = true,
            recipientModuleId = "M02",
            publishToPeer = { _, _ -> true },
        )
        val republish =
            logs.single { it.startsWith("PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH ") }
        assertTrue(republish.contains("publicationOutcome=EMITTED"))
        val origin = logs.single { it.contains("publicationOutcome=POST_BIND_EMITTED") }
        assertTrue(origin.contains("recipientModuleId=M02"))
    }

    companion object {
        private const val HOST = "M01"
    }
}

/** Shared harness helpers for post-bind tests (mirrors origin bridge test fixtures). */
internal object MeetingProfile01MediaKeyPackageOriginBridgeTestSupport {
    data class Harness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val mediaKeyAuthority: com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val packageBridge: MeetingProfile01MediaKeyPackageOriginBridge,
        val establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        val establishmentLookup: Profile01ProfileBackedRecipientEstablishmentKeyLookup,
        val packageBuilder: Profile01MediaKeyPackageBuilder,
    ) {
        fun duoSnapshot(): ConferenceTopologySnapshot =
            ConferenceTopologySnapshot(
                conferenceId = sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 2L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = listOf(HOST, "M02"),
                actualMediaEdges = emptySet(),
            )

        fun trioSnapshot(): ConferenceTopologySnapshot =
            ConferenceTopologySnapshot(
                conferenceId = sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 2L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = listOf(HOST, "M02", "M04"),
                actualMediaEdges = emptySet(),
            )
    }

    fun newHarness(
        sessionId: String,
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
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(establishmentStore)
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
        )
    }

    fun seedCreation(
        harness: Harness,
        eligiblePeers: Set<String>,
    ) {
        val snapshot =
            ConferenceTopologySnapshot(
                conferenceId = harness.sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 2L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = listOf(HOST, "M02"),
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

    private const val HOST = "M01"
}
