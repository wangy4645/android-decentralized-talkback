package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-2 directed regression: supplement delivery coordinator + production-style wiring.
 *
 * Run: ./gradlew :android-board-talkback:testDebugUnitTest -Pprofile01SupplementPr2Only --tests "com.talkback.core.conference.session.integration.Profile01SupplementDeliveryPr2Test"
 */
class Profile01SupplementDeliveryPr2Test {
    @Test
    fun productionPath_lateEstablishmentBinding_replaysViaStoreListenerAndCoordinator() {
        val harness = newProductionHarness(sessionId = "pr2-late-binding")
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03", "M04"))

        val preBindSends = linkedMapOf<String, ByteArray>()
        harness.runFanoutDrain { moduleId, bytes ->
            preBindSends[moduleId] = bytes.copyOf()
            true
        }
        assertEquals(setOf("M02", "M03"), preBindSends.keys)

        val replaySends = linkedMapOf<String, ByteArray>()
        harness.publishCapture = replaySends

        installEstablishmentBinding(harness, moduleId = "M04")
        harness.flushDelayedRuns()

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
    }

    @Test
    fun staggeredPeers_materializeAtDifferentTimes_eachPublishedOnce() {
        val harness = newProductionHarness(sessionId = "pr2-stagger")
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03", "M04"))
        harness.runFanoutDrain { _, _ -> true }

        val sends = linkedMapOf<String, Int>()
        harness.publishCapture = linkedMapOf()
        harness.publishToPeer = { moduleId, _ ->
            sends[moduleId] = (sends[moduleId] ?: 0) + 1
            true
        }

        installEstablishmentBinding(harness, moduleId = "M04")
        assertEquals(1, sends.getValue("M04"))

        val snapshot = harness.establishmentStore.currentSnapshot()!!
        val version = ESTABLISHMENT_KEY_VERSION + 1
        val spki = establishmentSpki()
        harness.establishmentStore.atomicReplace(
            snapshot.copy(
                bindingsByKey =
                    snapshot.bindingsByKey +
                        (
                            AcceptedLocalEstablishmentTrustState.BindingKey("M03", version) to
                                ModuleEstablishmentBinding(
                                    moduleId = "M03",
                                    establishmentKeyVersion = version,
                                    keyState = GenerationFactKeyState.ACTIVE,
                                    establishmentPublicKeySpki = spki.copyOf(),
                                    activatedAtRevision = snapshot.taskProfileRevision,
                                )
                        ),
            ),
        )
        assertEquals(1, sends.getValue("M04"))
        assertTrue(
            harness.ledger.publishedIdentities(harness.sessionId).any {
                it.recipientModuleId == "M03" && it.recipientEstablishmentKeyVersion == version
            },
        )
    }

    @Test
    fun alreadyPublished_doesNotOrdinaryResend_butNudgesUnackedObligation() {
        val harness = newProductionHarness(sessionId = "pr2-published-nudge")
        seedQuadCreation(harness, eligiblePeers = setOf("M02"))
        val nudged = mutableListOf<String>()
        harness.onPublishedWithoutAck = { _, peer -> nudged += peer }
        harness.publishToPeer = { _, _ -> true }
        harness.coordinator.scheduleDrain(harness.sessionId, "first")
        assertEquals(1, harness.ledger.publishedIdentities(harness.sessionId).size)
        assertTrue(nudged.isEmpty())
        var extraSends = 0
        harness.publishToPeer = { _, _ ->
            extraSends++
            true
        }
        harness.coordinator.scheduleDrain(harness.sessionId, "second")
        assertEquals(listOf("M02"), nudged)
        assertEquals(0, extraSends)
        assertEquals(1, harness.ledger.publishedIdentities(harness.sessionId).size)
    }

    @Test
    fun duplicateEstablishmentNotification_doesNotResend() {
        val harness = newProductionHarness(sessionId = "pr2-dup-notify")
        seedQuadCreation(harness, eligiblePeers = setOf("M02", "M03", "M04"))
        harness.runFanoutDrain { _, _ -> true }
        val sends = linkedMapOf<String, Int>()
        harness.publishToPeer = { moduleId, _ ->
            sends[moduleId] = (sends[moduleId] ?: 0) + 1
            true
        }
        installEstablishmentBinding(harness, moduleId = "M04")
        assertEquals(1, sends.getValue("M04"))
        val snapshot = harness.establishmentStore.currentSnapshot()!!
        harness.establishmentStore.atomicReplace(snapshot)
        assertEquals(1, sends.getValue("M04"))
        assertEquals(
            1,
            harness.ledger.publishedIdentities(harness.sessionId).count {
                it.recipientModuleId == "M04"
            },
        )
    }

    @Test
    fun bridge_staleExpectedIdentity_doesNotSendOrMark() {
        val harness = newProductionHarness(sessionId = "pr2-drift")
        seedQuadCreation(harness, eligiblePeers = setOf("M02"))
        val stale =
            harness.packageBridge.peekMediaKeyPackagePeerPublish(
                sessionId = harness.sessionId,
                snapshot = harness.quadSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                peerModuleId = "M02",
            ) as MediaKeyPackagePeerPublishOutcome.ReadyToSend
        bumpEstablishmentVersion(harness, "M02")
        var sent = false
        val outcome =
            harness.packageBridge.publishMediaKeyPackageToPeer(
                sessionId = harness.sessionId,
                snapshot = harness.quadSnapshot(),
                localModuleId = HOST,
                isMembershipAuthority = true,
                peerModuleId = "M02",
                expectedIdentity = stale.identity,
                publishToPeer = { _, _ ->
                    sent = true
                    true
                },
            )
        assertTrue(outcome is MediaKeyPackagePeerPublishOutcome.PendingIdentity)
        assertFalse(sent)
        assertFalse(harness.ledger.isPublished(harness.sessionId, stale.identity))
    }

    @Test
    fun clearSession_staleGeneration_doesNotMarkPublished() {
        val harness = newProductionHarness(sessionId = "pr2-clear")
        seedQuadCreation(harness, eligiblePeers = setOf("M02"))
        val staleScope = harness.coordinator.ensureSession(harness.sessionId)
        harness.coordinator.clearSession(harness.sessionId)
        harness.publishToPeer = { _, _ -> true }
        harness.coordinator.drain(staleScope, "stale", null)
        assertTrue(harness.ledger.publishedIdentities(harness.sessionId).isEmpty())
    }

    @Test
    fun sendFailure_schedulesRetry_thenMarksOnSuccess() {
        val harness = newProductionHarness(sessionId = "pr2-retry")
        seedQuadCreation(harness, eligiblePeers = setOf("M02"))
        val attempts = AtomicInteger(0)
        val delayed = mutableListOf<() -> Unit>()
        val retryCoordinator =
            Profile01SupplementDeliveryCoordinator(
                publicationLedger = harness.ledger,
                packageBridge = harness.packageBridge,
                schedule = { delayMs, block ->
                    if (delayMs > 0L) {
                        delayed += block
                    } else {
                        block()
                    }
                },
                resolveDrainContext = { harness.buildDrainContext(it) },
            )
        harness.publishToPeer = { _, _ ->
            if (attempts.incrementAndGet() == 1) false else true
        }
        retryCoordinator.scheduleDrain(harness.sessionId, "first")
        assertTrue(harness.ledger.publishedIdentities(harness.sessionId).isEmpty())
        assertEquals(1, delayed.size)
        delayed.single().invoke()
        assertEquals(1, harness.ledger.publishedIdentities(harness.sessionId).size)
    }

    @Test
    fun establishmentStore_listenerException_isolated_secondListenerStillRuns() {
        val store = AcceptedLocalEstablishmentTrustStateStore()
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)
        store.addListener(
            AcceptedEstablishmentTrustStateListener { _, _, _ ->
                first.incrementAndGet()
                throw RuntimeException("listener-boom")
            },
        )
        store.addListener(
            AcceptedEstablishmentTrustStateListener { _, _, _ ->
                second.incrementAndGet()
            },
        )
        populateM02Only(store)
        assertEquals(1, first.get())
        assertEquals(1, second.get())
    }

    private class ProductionHarness(
        val sessionId: String,
        val sessionIndex: MeetingProfile01ConferenceSessionIndex,
        val creationBridge: MeetingProfile01CreationOriginBridge,
        val packageBridge: MeetingProfile01MediaKeyPackageOriginBridge,
        val ledger: MediaKeyPackagePublicationLedger,
        val establishmentStore: AcceptedLocalEstablishmentTrustStateStore,
        val coordinator: Profile01SupplementDeliveryCoordinator,
        val delayedRuns: MutableList<() -> Unit>,
        var eligiblePeers: Set<String> = setOf("M02", "M03", "M04"),
    ) {
        var publishToPeer: (String, ByteArray) -> Boolean = { _, _ -> true }
        var publishCapture: MutableMap<String, ByteArray> = linkedMapOf()
        var onPublishedWithoutAck: (String, String) -> Unit = { _, _ -> }

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

        fun buildDrainContext(sessionId: String): Profile01SupplementDeliveryCoordinator.Profile01SupplementDrainContext? {
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

        fun runFanoutDrain(send: (String, ByteArray) -> Boolean) {
            publishToPeer = send
            coordinator.scheduleDrain(sessionId, "fanout")
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

    private fun newProductionHarness(sessionId: String): ProductionHarness {
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
            )
        lateinit var harnessRef: ProductionHarness
        val delayedRuns = mutableListOf<() -> Unit>()
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
                resolveDrainContext = { sessionId -> harnessRef.buildDrainContext(sessionId) },
                onPublishedWithoutAck = { sessionId, peer ->
                    harnessRef.onPublishedWithoutAck(sessionId, peer)
                },
            )
        val harness =
            ProductionHarness(
                sessionId = sessionId,
                sessionIndex = sessionIndex,
                creationBridge = creationBridge,
                packageBridge = packageBridge,
                ledger = ledger,
                establishmentStore = establishmentStore,
                coordinator = coordinator,
                delayedRuns = delayedRuns,
            )
        harnessRef = harness
        establishmentStore.addListener(
            AcceptedEstablishmentTrustStateListener { _, _, materialized ->
                coordinator.onEstablishmentPeersMaterialized(
                    materialized,
                    setOf(sessionId),
                )
            },
        )
        return harness
    }

    private fun installEstablishmentBinding(harness: ProductionHarness, moduleId: String) {
        val snapshot =
            harness.establishmentStore.currentSnapshot()
                ?: error("establishment store must be seeded")
        val version = ESTABLISHMENT_KEY_VERSION
        val spki = establishmentSpki()
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

    private fun bumpEstablishmentVersion(harness: ProductionHarness, moduleId: String) {
        val snapshot = harness.establishmentStore.currentSnapshot()!!
        val newVersion = ESTABLISHMENT_KEY_VERSION + 99
        val spki = establishmentSpki()
        val oldKey =
            snapshot.bindingsByKey.keys.first { it.moduleId == moduleId }
        val without = snapshot.bindingsByKey - oldKey
        harness.establishmentStore.atomicReplace(
            snapshot.copy(
                bindingsByKey =
                    without +
                        (
                            AcceptedLocalEstablishmentTrustState.BindingKey(moduleId, newVersion) to
                                ModuleEstablishmentBinding(
                                    moduleId = moduleId,
                                    establishmentKeyVersion = newVersion,
                                    keyState = GenerationFactKeyState.ACTIVE,
                                    establishmentPublicKeySpki = spki.copyOf(),
                                    activatedAtRevision = snapshot.taskProfileRevision,
                                )
                        ),
            ),
        )
    }

    private fun seedQuadCreation(harness: ProductionHarness, eligiblePeers: Set<String>) {
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

    private fun populateM02M03EstablishmentOnly(store: AcceptedLocalEstablishmentTrustStateStore) {
        val version = ESTABLISHMENT_KEY_VERSION
        val spki = establishmentSpki()
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

    private fun populateM02Only(store: AcceptedLocalEstablishmentTrustStateStore) {
        val version = ESTABLISHMENT_KEY_VERSION
        val spki = establishmentSpki()
        store.atomicReplace(
            AcceptedLocalEstablishmentTrustState(
                taskProfileRevision = 1L,
                revisionIdentity = ByteArray(32),
                localModuleId = HOST,
                bindingsByKey =
                    mapOf(
                        AcceptedLocalEstablishmentTrustState.BindingKey("M02", version) to
                            ModuleEstablishmentBinding(
                                moduleId = "M02",
                                establishmentKeyVersion = version,
                                keyState = GenerationFactKeyState.ACTIVE,
                                establishmentPublicKeySpki = spki.copyOf(),
                                activatedAtRevision = 1L,
                            ),
                    ),
            ),
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

    private fun establishmentSpki(): ByteArray =
        Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki()

    companion object {
        private const val HOST = "M01"
        private const val ESTABLISHMENT_KEY_VERSION = 4L
    }
}
