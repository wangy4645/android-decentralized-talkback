package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityTestFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01ProfileBackedRecipientEstablishmentKeyLookup
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedEstablishmentTrustStateListener
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 desk: establishment-module lifecycle vs membership fanout package delivery.
 *
 * Wired like production: store listener -> [Profile01SupplementDeliveryCoordinator] (not direct bridge replay).
 */
class Profile01EstablishmentModuleLifecycleDeskTest {
    @Test
    fun lifecycleInventory_inviteReplayBeforeEstablishmentModule_preBindGapAndEmptyLedger() {
        val logs = mutableListOf<String>()
        val harness = newQuadHarness(sessionId = "a2-inventory", observabilityLogs = logs)
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03", "M04"))

        assertTrue(
            harness.establishmentLookup.activeEstablishmentKey("M04")
                is Profile01EstablishmentKeyLookupResult.UnknownModule,
        )

        val sends = linkedMapOf<String, ByteArray>()
        runMembershipFanout(
            harness = harness,
            publishToPeer = { moduleId, bytes ->
                sends[moduleId] = bytes.copyOf()
                true
            },
        )

        assertEquals(setOf("M02", "M03"), sends.keys)
        assertFalse(sends.containsKey("M04"))
        assertTrue(
            logs.any {
                it.contains("target=M04") &&
                    it.contains("ORIGIN_SOURCE_GAP") &&
                    it.contains("UNKNOWN_ESTABLISHMENT_MODULE")
            },
        )
        assertTrue(harness.ledger.publishedIdentities(harness.sessionId).none { it.recipientModuleId == "M04" })
    }

    @Test
    fun fieldIsomorphicGreen_lateEstablishmentBinding_triggersLevelTriggeredPackageReplay() {
        val logs = mutableListOf<String>()
        val harness = newQuadHarness(sessionId = "a2-green-recovery", observabilityLogs = logs)
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03", "M04"))

        val preBindSends = linkedMapOf<String, ByteArray>()
        runMembershipFanout(
            harness = harness,
            publishToPeer = { moduleId, bytes ->
                preBindSends[moduleId] = bytes.copyOf()
                true
            },
        )
        assertEquals(setOf("M02", "M03"), preBindSends.keys)

        val replaySends = linkedMapOf<String, ByteArray>()
        harness.publishCapture = replaySends

        installEstablishmentBinding(harness, moduleId = "M04")

        assertTrue(
            harness.establishmentLookup.activeEstablishmentKey("M04")
                is Profile01EstablishmentKeyLookupResult.Found,
        )
        assertTrue(replaySends.containsKey("M04"))
        assertEquals(
            Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE,
            Profile01WireCborDecoder.readFactType(replaySends.getValue("M04")),
        )
        assertTrue(
            harness.ledger.publishedIdentities(harness.sessionId).any {
                it.recipientModuleId == "M04"
            },
        )
        assertTrue(
            logs.any {
                it.contains("target=M04") && it.contains("outcome=EMITTED")
            },
        )

        val postBindOutcome =
            harness.packageBridge.onPeerPackageConsumptionEligible(
                sessionId = harness.sessionId,
                snapshot = harness.quadSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                recipientModuleId = "M04",
                publishToPeer = { _, _ -> true },
            )
        assertEquals(MediaKeyPackagePostBindRepublishOutcome.EMITTED, postBindOutcome)
    }

    @Test
    fun fence_materializedBindingButPeerNotEligible_doesNotPublish() {
        val harness = newQuadHarness(sessionId = "a2-fence-not-eligible")
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03"))
        runMembershipFanout(harness) { _, _ -> true }

        harness.eligiblePeers = setOf("M02", "M03")
        val replaySends = linkedMapOf<String, ByteArray>()
        harness.publishCapture = replaySends

        installEstablishmentBinding(harness, moduleId = "M04")

        assertFalse(replaySends.containsKey("M04"))
        assertTrue(harness.ledger.publishedIdentities(harness.sessionId).none { it.recipientModuleId == "M04" })
    }

    @Test
    fun fence_duplicateMaterialization_isIdempotent() {
        val harness = newQuadHarness(sessionId = "a2-fence-idempotent")
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03", "M04"))
        runMembershipFanout(harness) { _, _ -> true }

        val replaySends = linkedMapOf<String, ByteArray>()
        harness.publishCapture = replaySends

        installEstablishmentBinding(harness, moduleId = "M04")
        assertEquals(1, replaySends.size)

        val snapshot = harness.establishmentStore.currentSnapshot()!!
        harness.establishmentStore.atomicReplace(snapshot)
        harness.flushDelayedRuns()

        assertEquals(1, replaySends.size)
        assertEquals(1, harness.ledger.publishedIdentities(harness.sessionId).count { it.recipientModuleId == "M04" })
    }

    private fun runMembershipFanout(
        harness: QuadHarness,
        publishToPeer: (String, ByteArray) -> Boolean,
    ) {
        harness.publishToPeer = publishToPeer
        harness.coordinator.scheduleDrain(harness.sessionId, "membership_fanout")
    }

    private fun installEstablishmentBinding(harness: QuadHarness, moduleId: String) {
        val snapshot =
            harness.establishmentStore.currentSnapshot()
                ?: error("establishment store must be seeded")
        val version = Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION
        val spki = Profile01EstablishmentAuthorityTestFixtures.establishmentPublicKeySpki
        val binding =
            ModuleEstablishmentBinding(
                moduleId = moduleId,
                establishmentKeyVersion = version,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = spki.copyOf(),
                activatedAtRevision = snapshot.taskProfileRevision,
            )
        harness.establishmentStore.atomicReplace(
            snapshot.copy(
                bindingsByKey =
                    snapshot.bindingsByKey +
                        (
                            AcceptedLocalEstablishmentTrustState.BindingKey(moduleId, version) to
                                binding
                        ),
            ),
        )
        harness.flushDelayedRuns()
    }

    private class QuadHarness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val packageBridge: MeetingProfile01MediaKeyPackageOriginBridge,
        val ledger: MediaKeyPackagePublicationLedger,
        val establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        val establishmentLookup: Profile01ProfileBackedRecipientEstablishmentKeyLookup,
        val coordinator: Profile01SupplementDeliveryCoordinator,
        private val delayedRuns: MutableList<() -> Unit>,
    ) {
        var eligiblePeers: Set<String> = setOf("M02", "M03", "M04")
        var publishToPeer: (String, ByteArray) -> Boolean = { _, _ -> true }
        var publishCapture: MutableMap<String, ByteArray> = linkedMapOf()

        fun quadSnapshot(): ConferenceTopologySnapshot =
            ConferenceTopologySnapshot(
                conferenceId = sessionId,
                rosterEpoch = 1L,
                anchorEpoch = 100L,
                anchorId = HOST,
                meshGeneration = 2L,
                topologyMode = ConferenceTopologyMode.ANCHOR,
                hostModuleId = HOST,
                members = listOf(HOST, "M02", "M03", "M04"),
                actualMediaEdges = emptySet(),
            )

        fun buildDrainContext(
            sessionId: String,
        ): Profile01SupplementDeliveryCoordinator.Profile01SupplementDrainContext? {
            if (sessionId != this.sessionId) return null
            val scope = coordinator.ensureSession(sessionId)
            return Profile01SupplementDeliveryCoordinator.Profile01SupplementDrainContext(
                scope = scope,
                snapshot = quadSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = eligiblePeers,
                publishToPeer = { moduleId, bytes ->
                    publishCapture[moduleId] = bytes.copyOf()
                    publishToPeer(moduleId, bytes)
                },
            )
        }

        fun flushDelayedRuns(maxRounds: Int = 3) {
            repeat(maxRounds) {
                if (delayedRuns.isEmpty()) return
                val batch = delayedRuns.toList()
                delayedRuns.clear()
                batch.forEach { it() }
            }
        }
    }

    private fun newQuadHarness(
        sessionId: String,
        observabilityLogs: MutableList<String> = mutableListOf(),
    ): QuadHarness {
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, "CH-$sessionId", rosterEpoch = 1L)
        val mediaKeyAuthority =
            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority()
        val signer = testSigner()
        val creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                publisher = MeetingProfile01CreationOriginPublisher(signer),
            )
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        populateM02M03EstablishmentOnly(establishmentStore)
        val establishmentLookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(establishmentStore)
        val packageBuilder = Profile01MediaKeyPackageBuilder(signer = signer)
        val ledger = MediaKeyPackagePublicationLedger()
        val packageBridge =
            MeetingProfile01MediaKeyPackageOriginBridge(
                sessionIndex = sessionIndex,
                creationOrigin = creationBridge,
                mediaKeyAuthority = mediaKeyAuthority,
                establishmentLookup = establishmentLookup,
                packageBuilder = packageBuilder,
                publicationLedger = ledger,
                recordPublicationOnSendSuccess = { _, _ -> },
                onLog = observabilityLogs::add,
            )
        val delayedRuns = mutableListOf<() -> Unit>()
        lateinit var harnessRef: QuadHarness
        val coordinator =
            Profile01SupplementDeliveryCoordinator(
                publicationLedger = ledger,
                packageBridge = packageBridge,
                schedule = { delayMs, block ->
                    if (delayMs == 0L) {
                        block()
                    } else {
                        delayedRuns += block
                    }
                },
                resolveDrainContext = { harnessRef.buildDrainContext(it) },
            )
        val harness =
            QuadHarness(
                sessionId = sessionId,
                sessionIndex = sessionIndex,
                creationBridge = creationBridge,
                packageBridge = packageBridge,
                ledger = ledger,
                establishmentStore = establishmentStore,
                establishmentLookup = establishmentLookup,
                coordinator = coordinator,
                delayedRuns = delayedRuns,
            )
        harnessRef = harness
        establishmentStore.addListener(
            AcceptedEstablishmentTrustStateListener { _, _, materialized ->
                coordinator.onEstablishmentPeersMaterialized(materialized, setOf(sessionId))
            },
        )
        return harness
    }

    private fun populateM02M03EstablishmentOnly(store: AcceptedLocalEstablishmentTrustStateStore) {
        val version = Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION
        val spki = Profile01EstablishmentAuthorityTestFixtures.establishmentPublicKeySpki
        fun binding(moduleId: String) =
            ModuleEstablishmentBinding(
                moduleId = moduleId,
                establishmentKeyVersion = version,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = spki.copyOf(),
                activatedAtRevision = 10L,
            )
        store.atomicReplace(
            AcceptedLocalEstablishmentTrustState(
                taskProfileRevision = 10L,
                revisionIdentity = ByteArray(32),
                localModuleId = HOST,
                bindingsByKey =
                    listOf(HOST, "M02", "M03").associate { moduleId ->
                        AcceptedLocalEstablishmentTrustState.BindingKey(moduleId, version) to
                            binding(moduleId)
                    },
            ),
        )
    }

    private fun seedQuadCreation(harness: QuadHarness, eligiblePeers: Set<String>) {
        harness.eligiblePeers = eligiblePeers
        harness.creationBridge.onTopologyPublished(
            sessionId = harness.sessionId,
            snapshot = harness.quadSnapshot(),
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

    companion object {
        private const val HOST = "M01"
    }
}
