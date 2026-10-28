package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireMembershipFact
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01CreationAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01MediaGroupDescriptor
import com.talkback.core.conference.session.profile01.wire.Profile01PackageCrypto
import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildRequest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuildResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageBuilder
import com.talkback.core.conference.session.profile01.wire.Profile01MembershipAuthoritySnapshot
import com.talkback.core.conference.session.profile01.wire.Profile01PackageRecipientBinding
import com.talkback.core.conference.session.profile01.wire.Profile01P1DProductionIngressHarness
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01Q5CryptoVectorFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage
import com.talkback.core.conference.session.profile01.wire.toHexLower
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * IG-2 / IG-2A full-chain desk — production CREATION wire → epoch2 ACTIVE → E3-A epoch3 chain;
 * adjudicates IG2-1..5 and E3-B REQUIRED vs NOT NEEDED using existing production callbacks only.
 */
class MeetingProfile01E3AIG2FullChainDeskTest {
    private val supplementReadyCount = AtomicInteger(0)

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        supplementReadyCount.set(0)
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun ig2_fullChain_productionCallbacks_adjudicatesFirstBreak() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        val baselineGeneration = bootstrapIg2ProductionEpoch2Active(env, supplementReadyCount)
        runIg2E3APreconditionToSupplementActive(env)

        assertEquals(1, supplementReadyCount.get())
        assertNotNull(env.supplementRegistry.lookup(env.conferenceIdHex, 3L))

        val facts = collectIg2Facts(env, baselineGeneration)
        val firstFail = firstIg2Failure(facts)
        if (firstFail != null) {
            throw AssertionError("IG-2 desk first FAIL: $firstFail — ${describeFacts(facts)}")
        }
        when (adjudicateE3B(facts)) {
            E3BAdjudication.NOT_NEEDED -> Unit
            E3BAdjudication.REQUIRED ->
                throw AssertionError(
                    "IG-2 desk: E3-B ELIGIBLE — ${describeFacts(facts)} " +
                        "replacePending=${facts.memberReplacePending} " +
                        "memberReplaced=${facts.memberReplacedOutcome} " +
                        "memberMediaReady=${facts.memberMediaReadyOutcome}",
                )
            E3BAdjudication.NOT_APPLICABLE ->
                throw AssertionError(
                    "IG-2 desk: unexpected NOT_APPLICABLE after IG2-1..5 pass — ${describeFacts(facts)}",
                )
        }
    }

    @Test
    fun ig2_duplicateSupplementReadyReplay_idempotentCompletionChain() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        val baselineGeneration = bootstrapIg2ProductionEpoch2Active(env, supplementReadyCount)
        runIg2E3APreconditionToSupplementActive(env)

        val factsBefore = collectIg2Facts(env, baselineGeneration)
        assertTrue("IG2-1", factsBefore.ig2_1)
        assertTrue("IG2-2", factsBefore.ig2_2)

        val sessionEpochBefore = env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch
        val generationBefore =
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration
        val catalogIncarnationBefore =
            ConferenceSessionMediaBridge.currentIncarnation(IG2_SESSION_ID, env.localModuleId)
        val replaceCountBefore = memberReplacedHookCount()

        replayIg2ProductionSupplementReady(env, supplementReadyCount, mediaKeyEpoch = 3L)

        assertEquals(sessionEpochBefore, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
        assertEquals(
            generationBefore,
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,
        )
        assertEquals(
            catalogIncarnationBefore,
            ConferenceSessionMediaBridge.currentIncarnation(IG2_SESSION_ID, env.localModuleId),
        )
        assertEquals(replaceCountBefore, memberReplacedHookCount())
        assertEquals(2, supplementReadyCount.get())

        val factsAfter = collectIg2Facts(env, baselineGeneration)
        assertTrue("IG2-1 after replay", factsAfter.ig2_1)
        assertTrue("IG2-2 after replay", factsAfter.ig2_2)
    }

    private fun firstIg2Failure(facts: Ig2Facts): String? =
        when {
            !facts.ig2_1 -> "IG2-1 session@3 (sessionEpoch=${facts.sessionMediaKeyEpoch})"
            !facts.ig2_2 -> "IG2-2 commitment@3/sourceGen=${facts.sourceGeneration}"
            !facts.ig2_3 -> "IG2-3 registry@3 (${facts.registryEpoch}/${facts.registryIncarnation})"
            !facts.ig2_4 -> "IG2-4 binding@3 (${facts.bindingEpoch}/${facts.bindingIncarnation})"
            !facts.ig2_5 -> "IG2-5 TX fence (${facts.ig2_5FenceReason})"
            else -> null
        }

    private fun applyMembershipAndDrain(
        env: Ig2FullChainEnvironment,
        signedMembershipBytes: ByteArray,
    ) {
        val wire = decodeMembership(signedMembershipBytes)
        when (val applied = env.validator.membershipRegistry().applyMembership(wire)) {
            is Profile01MembershipApplyResult.Accepted,
            is Profile01MembershipApplyResult.Idempotent,
            -> Unit
            else -> error("membership apply failed: $applied")
        }
        val generation =
            env.ingress.currentMembershipGeneration(env.conferenceIdHex)
                ?: error("membership head missing")
        env.wireIngress.drainStagedPackagesAtMembershipHeadForTest(IG2_SESSION_ID, generation)
    }

    private fun collectIg2Facts(
        env: Ig2FullChainEnvironment,
        baselineGeneration: Long,
    ): Ig2Facts {
        val sessionControl = env.controlRegistry.session(env.conferenceIdHex)
        val commitment = env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)
        val registryMember = env.controlRegistry.member(env.conferenceIdHex, env.localModuleId)
        val binding = env.factPort.memberBinding(IG2_SESSION_ID, env.localModuleId)
        val expectedGeneration = baselineGeneration + 1L

        val ig2_1 = sessionControl?.mediaKeyEpoch == 3L
        val ig2_2 =
            commitment?.mediaKeyEpoch == 3L &&
                commitment.sourceGeneration == expectedGeneration
        val ig2_3 =
            registryMember?.mediaKeyEpoch == 3L &&
                registryMember.membershipIncarnationId == expectedGeneration
        val ig2_4 =
            binding?.mediaKeyEpoch == 3L &&
                binding.incarnationId == expectedGeneration
        val fence = txFenceReason(env)
        val ig2_5 = fence == null

        return Ig2Facts(
            ig2_1 = ig2_1,
            ig2_2 = ig2_2,
            ig2_3 = ig2_3,
            ig2_4 = ig2_4,
            ig2_5 = ig2_5,
            ig2_5FenceReason = fence,
            baselineGeneration = baselineGeneration,
            sessionMediaKeyEpoch = sessionControl?.mediaKeyEpoch,
            sourceGeneration = commitment?.sourceGeneration,
            registryEpoch = registryMember?.mediaKeyEpoch,
            registryIncarnation = registryMember?.membershipIncarnationId,
            bindingEpoch = binding?.mediaKeyEpoch,
            bindingIncarnation = binding?.incarnationId,
            memberReplacePending = env.factPort.memberReplacePending(IG2_SESSION_ID, env.localModuleId),
            memberReplacedOutcome = lastShadowOutcome(ShadowHook.MEMBER_REPLACED, env.localModuleId),
            memberMediaReadyOutcome = lastShadowOutcome(ShadowHook.MEMBER_MEDIA_READY, env.localModuleId),
        )
    }

    private enum class E3BAdjudication {
        REQUIRED,
        NOT_NEEDED,
        NOT_APPLICABLE,
    }

    private data class Ig2Facts(
        val ig2_1: Boolean,
        val ig2_2: Boolean,
        val ig2_3: Boolean,
        val ig2_4: Boolean,
        val ig2_5: Boolean,
        val ig2_5FenceReason: String?,
        val baselineGeneration: Long,
        val sessionMediaKeyEpoch: Long?,
        val sourceGeneration: Long?,
        val registryEpoch: Long?,
        val registryIncarnation: Long?,
        val bindingEpoch: Long?,
        val bindingIncarnation: Long?,
        val memberReplacePending: Boolean,
        val memberReplacedOutcome: ShadowOutcome?,
        val memberMediaReadyOutcome: ShadowOutcome?,
    )

    private fun adjudicateE3B(facts: Ig2Facts): E3BAdjudication {
        if (!facts.ig2_1 || !facts.ig2_2) {
            return E3BAdjudication.NOT_APPLICABLE
        }
        if (facts.ig2_3 && facts.ig2_4 && facts.ig2_5) {
            return E3BAdjudication.NOT_NEEDED
        }
        if (!facts.ig2_3 || !facts.ig2_4) {
            val replaceGap =
                facts.memberReplacePending ||
                    facts.memberMediaReadyOutcome == ShadowOutcome.DEFERRED_REPLACE_PENDING
            if (replaceGap) {
                return E3BAdjudication.REQUIRED
            }
        }
        return E3BAdjudication.NOT_APPLICABLE
    }

    private fun assertIg2Pass(
        facts: Ig2Facts,
        baselineGeneration: Long,
    ) {
        assertTrue("IG2-1", facts.ig2_1)
        assertTrue("IG2-2", facts.ig2_2)
        assertTrue("IG2-3", facts.ig2_3)
        assertTrue("IG2-4", facts.ig2_4)
        assertTrue("IG2-5 fence=${facts.ig2_5FenceReason}", facts.ig2_5)
        assertEquals(baselineGeneration + 1L, facts.sourceGeneration)
        assertFalse(facts.memberReplacePending)
    }

    private fun describeFacts(facts: Ig2Facts): String =
        "IG2-1=${facts.ig2_1} IG2-2=${facts.ig2_2} IG2-3=${facts.ig2_3} " +
            "IG2-4=${facts.ig2_4} IG2-5=${facts.ig2_5} " +
            "sessionEpoch=${facts.sessionMediaKeyEpoch} sourceGen=${facts.sourceGeneration} " +
            "registry=${facts.registryEpoch}/${facts.registryIncarnation} " +
            "binding=${facts.bindingEpoch}/${facts.bindingIncarnation} " +
            "txFence=${facts.ig2_5FenceReason}"

    private fun txFenceReason(env: Ig2FullChainEnvironment): String? =
        ig2ResolveTxFenceReason(env)

    private fun lastShadowOutcome(
        hook: ShadowHook,
        moduleId: String,
    ): ShadowOutcome? {
        val recent =
            MeetingProductMediaShadow.observability.snapshot()["recentEvents"] as Map<*, *>
        val raw = recent["$hook:$IG2_SESSION_ID:$moduleId"] as String?
        return raw?.let { runCatching { ShadowOutcome.valueOf(it) }.getOrNull() }
    }

    private fun memberReplacedHookCount(): Long {
        val hookCounts =
            MeetingProductMediaShadow.observability.snapshot()["hookCounts"] as Map<*, *>
        return (hookCounts[ShadowHook.MEMBER_REPLACED] as? Number)?.toLong() ?: 0L
    }

    private fun decodeMembership(bytes: ByteArray): Profile01WireMembershipFact =
        when (val decoded = Profile01WireCborDecoder.decodeMembership(bytes)) {
            is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                error("membership decode failed: ${decoded.reason}")
        }
}

internal const val IG2_SESSION_ID = "ig2-full-chain-session"
internal const val IG2_CHANNEL_ID = "ig2-test-channel"

internal fun createIg2FullChainEnvironment(
    supplementReadyCounter: AtomicInteger,
): Ig2FullChainEnvironment =
    buildIg2FullChainEnvironment(supplementReadyCounter)

internal fun bootstrapIg2ProductionEpoch2Active(
    env: Ig2FullChainEnvironment,
    supplementReadyCounter: AtomicInteger,
): Long {
    val creationBytes = env.buildCreationSignedFact()
    assertEquals(
        WireIngressOutcome.INCOMPLETE_PENDING,
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(creationBytes)),
    )
    assertTrue(
        env.creationOriginBridge.readSignedCreationFact(env.sessionId)!!.contentEquals(creationBytes),
    )
    assertTrue(env.incompleteApplyContinuation.pendingCount(env.conferenceIdHex) >= 1)

    val headAfterCreation = env.ingress.currentMembershipGeneration(env.conferenceIdHex)!!
    assertEquals(0L, headAfterCreation.membershipVersion)
    assertEquals(1L, headAfterCreation.mediaKeyEpoch)

    val epoch2 = env.buildEpoch2Artifacts()
    assertEquals(
        WireIngressOutcome.APPLIED,
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch2.signedMembershipBytes)),
    )
    assertEquals(
        WireIngressOutcome.APPLIED,
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch2.packageBytes)),
    )

    val sessionControl = env.controlRegistry.session(env.conferenceIdHex)
    assertNotNull(sessionControl)
    assertEquals(2L, sessionControl!!.mediaKeyEpoch)
    assertTrue(ConferenceSessionMediaBridge.hasSession(IG2_SESSION_ID))

    val commitment =
        env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)
            ?: error("epoch2 commitment missing")
    assertEquals(2L, commitment.mediaKeyEpoch)
    assertEquals(1L, commitment.sourceGeneration)

    val member = env.controlRegistry.member(env.conferenceIdHex, env.localModuleId)
    assertNotNull(member)
    assertEquals(2L, member!!.mediaKeyEpoch)
    assertEquals(1L, member.membershipIncarnationId)

    supplementReadyCounter.set(0)
    return commitment.sourceGeneration
}

internal fun runIg2E3APreconditionToSupplementActive(env: Ig2FullChainEnvironment) {
    val epoch3 = env.buildEpoch3Artifacts(includeFourthMember = false)
    env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch3.packageBytes))
    env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch3.signedMembershipBytes))
    env.maybeCommitAndPublishLocalConferenceSource("ig2_e3a_membership_replay")
}

internal fun replayIg2ProductionSupplementReady(
    env: Ig2FullChainEnvironment,
    supplementReadyCounter: AtomicInteger,
    mediaKeyEpoch: Long,
) {
    supplementReadyCounter.incrementAndGet()
    env.wireIngress.onSupplementReadyForConference(env.conferenceIdHex, mediaKeyEpoch)
    env.maybeCommitAndPublishLocalConferenceSource("media_supplement_ready_replay")
}

internal fun ig2ResolveTxFenceReason(env: Ig2FullChainEnvironment): String? {
    val binding = env.sessionIndex.bindingForSession(IG2_SESSION_ID) ?: return "NO_SESSION_BINDING"
    val conferenceId =
        env.sessionIndex.conferenceIdForSession(IG2_SESSION_ID) ?: return "NO_CONFERENCE_ID"
    val sessionControl = env.controlRegistry.session(conferenceId) ?: return "NO_SESSION_FACT"
    val wiring = ConferenceSessionMediaBridge.wiring
    if (wiring == null || !wiring.hasSession(IG2_SESSION_ID)) {
        return "NO_SHADOW_SESSION"
    }
    val snap = wiring.runtimeSnapshot(IG2_SESSION_ID)
    if (snap == null || !snap.transportScopeActive || snap.ingressBlocked) {
        return "TRANSPORT_NOT_READY"
    }
    val memberBinding =
        env.factPort.memberBinding(IG2_SESSION_ID, env.localModuleId) ?: return "NO_MEMBER_BINDING"
    val registryMember =
        env.controlRegistry.member(conferenceId, env.localModuleId) ?: return "NO_REGISTRY_MEMBER"
    val commitment =
        env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)
            ?: return "NO_LOCAL_SOURCE"
    if (memberBinding.mediaKeyEpoch != sessionControl.mediaKeyEpoch) {
        return "MEDIA_KEY_EPOCH_MISMATCH"
    }
    if (registryMember.mediaKeyEpoch != sessionControl.mediaKeyEpoch) {
        return "REGISTRY_EPOCH_MISMATCH"
    }
    if (registryMember.membershipIncarnationId != commitment.sourceGeneration) {
        return "SOURCE_GENERATION_MISMATCH"
    }
    val sessionFact =
        env.factPort.sessionFact(IG2_SESSION_ID, binding.channelId, sessionControl.membershipVersion)
            ?: return "SESSION_FACT_PORT_MISS"
    if (sessionFact.masterKey.isEmpty()) {
        return "SESSION_FACT_EMPTY"
    }
    return null
}

internal fun ig2CoherenceSnapshot(env: Ig2FullChainEnvironment): Ig2CoherenceSnapshot {
    val sessionControl = env.controlRegistry.session(env.conferenceIdHex)
    val commitment = env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)
    val registryMember = env.controlRegistry.member(env.conferenceIdHex, env.localModuleId)
    val binding = env.factPort.memberBinding(IG2_SESSION_ID, env.localModuleId)
    val head = env.ingress.currentMembershipGeneration(env.conferenceIdHex)
    return Ig2CoherenceSnapshot(
        sessionEpoch = sessionControl?.mediaKeyEpoch,
        registryEpoch = registryMember?.mediaKeyEpoch,
        registryIncarnation = registryMember?.membershipIncarnationId,
        bindingEpoch = binding?.mediaKeyEpoch,
        bindingIncarnation = binding?.incarnationId,
        sourceGeneration = commitment?.sourceGeneration,
        memberIds = head?.members?.map { it.moduleId } ?: emptyList(),
    )
}

internal data class Ig2CoherenceSnapshot(
    val sessionEpoch: Long?,
    val registryEpoch: Long?,
    val registryIncarnation: Long?,
    val bindingEpoch: Long?,
    val bindingIncarnation: Long?,
    val sourceGeneration: Long?,
    val memberIds: List<String>,
) {
    fun isCoherentAt(mediaKeyEpoch: Long): Boolean {
        val incarnation = sourceGeneration ?: return false
        return sessionEpoch == mediaKeyEpoch &&
            registryEpoch == mediaKeyEpoch &&
            bindingEpoch == mediaKeyEpoch &&
            registryIncarnation == incarnation &&
            bindingIncarnation == incarnation
    }
}

internal fun bootstrapIg2ProductionFourMemberEpoch2Active(
    env: Ig2FullChainEnvironment,
    supplementReadyCounter: AtomicInteger,
): Long {
    val creationBytes = env.buildCreationSignedFact()
    assertEquals(
        WireIngressOutcome.INCOMPLETE_PENDING,
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(creationBytes)),
    )
    assertTrue(
        env.creationOriginBridge.readSignedCreationFact(env.sessionId)!!.contentEquals(creationBytes),
    )

    val epoch2 = env.buildFourMemberEpoch2Artifacts()
    assertEquals(
        WireIngressOutcome.APPLIED,
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch2.signedMembershipBytes)),
    )
    assertEquals(
        WireIngressOutcome.APPLIED,
        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(epoch2.packageBytes)),
    )

    val sessionControl = env.controlRegistry.session(env.conferenceIdHex)
    assertNotNull(sessionControl)
    assertEquals(2L, sessionControl!!.mediaKeyEpoch)
    assertTrue(ConferenceSessionMediaBridge.hasSession(IG2_SESSION_ID))

    val head = env.ingress.currentMembershipGeneration(env.conferenceIdHex)!!
    assertEquals(listOf("M01", env.localModuleId, "M03", "M04"), head.members.map { it.moduleId })

    env.maybeCommitAndPublishLocalConferenceSource("r2_epoch2_active_bootstrap")
    assertNull(
        "R2-1 precondition: epoch2 TX must be READY before shrink",
        ig2ResolveTxFenceReason(env),
    )

    val commitment =
        env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)
            ?: error("epoch2 commitment missing")
    supplementReadyCounter.set(0)
    return commitment.sourceGeneration
}

internal fun ig2RunProductionShrinkRotation(
    env: Ig2FullChainEnvironment,
    shrinkArtifacts: Ig2FullChainEnvironment.EpochAdvanceArtifacts,
) {
    env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrinkArtifacts.packageBytes))
    env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrinkArtifacts.signedMembershipBytes))
    env.maybeCommitAndPublishLocalConferenceSource("r2_shrink_membership_replay")
}

/**
 * Production shrink with explicit transition snapshots: package wire → membership ingress →
 * package drain → membership replay. Captures the rotation ordering gap between session head
 * advance and successor binding install (Model C).
 */
internal fun ig2ProductionShrinkTransitionSteps(
    env: Ig2FullChainEnvironment,
    shrinkArtifacts: Ig2FullChainEnvironment.EpochAdvanceArtifacts,
    stepFactory: (String, Ig2FullChainEnvironment) -> Any,
): List<Any> {
    val steps = mutableListOf<Any>()

    env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrinkArtifacts.packageBytes))
    steps += stepFactory("after_shrink_package_wire", env)

    val generation =
        when (val result = env.ingress.ingestMembershipSignedFact(shrinkArtifacts.signedMembershipBytes)) {
            is Profile01MembershipIngressResult.Converged -> result.generation
            else -> error("membership ingress failed: $result")
        }
    env.sessionIndex.updateRosterEpoch(IG2_SESSION_ID, generation.membershipVersion)
    steps += stepFactory("after_membership_ingress_before_drain", env)

    env.wireIngress.drainStagedPackagesAtMembershipHeadForTest(IG2_SESSION_ID, generation)
    steps += stepFactory("after_shrink_package_drain", env)

    ig2SyncSessionRegistryAtMembershipHead(env)
    steps += stepFactory("after_shrink_registry_sync", env)

    ig2ReplayActiveMembershipWiringAtHead(env)
    steps += stepFactory("after_shrink_membership_replay", env)

    env.maybeCommitAndPublishLocalConferenceSource("r2_shrink_membership_replay")
    steps += stepFactory("after_shrink_local_source_committed", env)

    return steps
}

internal fun ig2ReplayActiveMembershipWiringAtHead(env: Ig2FullChainEnvironment) {
    val sessionBinding =
        env.sessionIndex.bindingForSession(IG2_SESSION_ID)
            ?: error("missing session binding for membership replay")
    Profile01ShadowSessionReplay.replaySessionStarted(
        sessionId = IG2_SESSION_ID,
        channelId = sessionBinding.channelId,
        membershipVersionForRead = sessionBinding.rosterEpoch,
    )
    ig2AuthoritativeMemberModuleIds(env, sessionBinding).sorted().forEach { moduleId ->
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(IG2_SESSION_ID, moduleId)
        env.sourceBridge.memberBindingMaterializer?.onSourceDeclarationWireApplied(IG2_SESSION_ID, moduleId)
            ?: ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(IG2_SESSION_ID, moduleId)
    }
}

internal fun ig2SyncSessionRegistryAtMembershipHead(env: Ig2FullChainEnvironment) {
    val sessionBinding =
        env.sessionIndex.bindingForSession(IG2_SESSION_ID)
            ?: error("missing session binding for registry sync")
    val conferenceId =
        env.sessionIndex.conferenceIdForSession(IG2_SESSION_ID)
            ?: error("missing conference id")
    val endpointAuthority =
        env.wireIngress.activeRotationSessionEndpointAuthorityForTest(
            sessionId = IG2_SESSION_ID,
            conferenceIdHex = conferenceId,
        ) ?: error("active rotation endpoint authority missing")
    env.ingress.tryMaterializeSessionAtMembershipHead(
        conferenceId = conferenceId,
        channelId = sessionBinding.channelId,
        networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        endpointAuthority = endpointAuthority,
    )
    val generation = env.ingress.currentMembershipGeneration(conferenceId)!!
    env.sessionIndex.updateRosterEpoch(IG2_SESSION_ID, generation.membershipVersion)
    require(
        env.ingress.isSessionControlFactAtMembershipHead(
            conferenceId = conferenceId,
            channelId = sessionBinding.channelId,
        ),
    ) {
        "session registry must match membership head before wiring replay"
    }
}

private fun ig2AuthoritativeMemberModuleIds(
    env: Ig2FullChainEnvironment,
    sessionBinding: MeetingProfile01ConferenceSessionIndex.Binding,
): Set<String> {
    val conferenceId =
        env.sessionIndex.conferenceIdForSession(IG2_SESSION_ID)
            ?: sessionBinding.conferenceId
            ?: return emptySet()
    val moduleIds = linkedSetOf<String>()
    env.ingress.currentMembershipGeneration(conferenceId)?.members?.forEach { moduleIds.add(it.moduleId) }
    sessionBinding.mediaConnectedModules.forEach { moduleIds.add(it) }
    moduleIds.add(env.localModuleId)
    return moduleIds
}

/** @deprecated use [ig2ReplayActiveMembershipWiringAtHead] */
internal fun ig2ReplayAfterMembershipWire(env: Ig2FullChainEnvironment) {
    ig2ReplayActiveMembershipWiringAtHead(env)
}

internal fun ig2ApplyMembershipToRegistryOnly(
    env: Ig2FullChainEnvironment,
    signedMembershipBytes: ByteArray,
): com.talkback.core.conference.session.profile01.Profile01MembershipGeneration {
    when (val result = env.ingress.ingestMembershipSignedFact(signedMembershipBytes)) {
        is Profile01MembershipIngressResult.Converged -> {
            env.sessionIndex.updateRosterEpoch(IG2_SESSION_ID, result.generation.membershipVersion)
            return result.generation
        }
        else -> error("membership ingress failed: $result")
    }
}

internal fun ig2ApplyMembershipAndDrain(
    env: Ig2FullChainEnvironment,
    signedMembershipBytes: ByteArray,
) {
    val wire =
        when (val decoded = Profile01WireCborDecoder.decodeMembership(signedMembershipBytes)) {
            is Profile01WireCborDecoder.DecodeResult.Ready -> decoded.value
            is Profile01WireCborDecoder.DecodeResult.Rejected ->
                error("membership decode failed: ${decoded.reason}")
        }
    when (val applied = env.validator.membershipRegistry().applyMembership(wire)) {
        is Profile01MembershipApplyResult.Accepted,
        is Profile01MembershipApplyResult.Idempotent,
        -> Unit
        else -> error("membership apply failed: $applied")
    }
    val generation =
        env.ingress.currentMembershipGeneration(env.conferenceIdHex)
            ?: error("membership head missing")
    env.wireIngress.drainStagedPackagesAtMembershipHeadForTest(IG2_SESSION_ID, generation)
}

private fun buildIg2FullChainEnvironment(
    supplementReadyCounter: AtomicInteger,
): Ig2FullChainEnvironment {
    val p1d = Profile01P1DProductionIngressHarness.create()
    val hostSigner = ig2TestSigner("M01", keyVersion = p1d.signer.signerKeyVersion)
    val localSigner = ig2TestSigner(p1d.localModuleId, keyVersion = 1L)
    val trustBoundary =
        Profile01GoldenVectorSignedFactTrustBoundary(
            hostSigner.publicKeys + localSigner.publicKeys,
        )

    val stagedContinuation = MeetingProfile01StagedMediaKeyPackageContinuation()
    val supplementRegistry = Profile01SessionMediaSupplementRegistry()
    val controlRegistry = ConferenceSessionMediaControlFactRegistry()
    val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(controlRegistry)
    val validator =
        Profile01ConferenceMediaFactValidator(trustBoundary).also {
            it.membershipRegistry().clear()
        }
    val baseWire = ig2DecodePackage(p1d.builtPackage.signedFactBytes)
    val incompleteApplyContinuation = MeetingProfile01IncompleteApplyContinuation()
    val deferRotationMemberMaterialization = AtomicBoolean(false)

    val ingress =
        Profile01ConferenceMediaFactIngress(
            validator = validator,
            publisherBridge = publisherBridge,
            mediaKeyDecrypt = p1d.ingress.mediaKeyDecryptSeam(),
            supplementRegistry = supplementRegistry,
            stagedPackageContinuation = stagedContinuation,
            onLog = {},
        )

    val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    sessionIndex.registerSession(IG2_SESSION_ID, IG2_CHANNEL_ID, rosterEpoch = 0L)
    sessionIndex.bindConferenceId(IG2_SESSION_ID, p1d.conferenceIdHex)
    sessionIndex.markMediaConnected(IG2_SESSION_ID, p1d.localModuleId)

    val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(controlRegistry, sessionIndex)
    ConferenceSessionMediaCoordinatorDelegate.factPort = factPort

    val localSourceAuthority = Profile01LocalConferenceSourceIdentityAuthority()
    val sourceBridge =
        MeetingProfile01SourceOriginBridge(
            sessionIndex = sessionIndex,
            mediaKeyAuthority = com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority(),
            supplementRegistry = supplementRegistry,
            membershipConvergence = validator.membershipRegistry(),
            publisher = MeetingProfile01SourceOriginPublisher(localSigner.signer),
        )
    val mediaKeyAuthority =
        com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority()
    val creationOriginBridge =
        MeetingProfile01CreationOriginBridge(
            sessionIndex = sessionIndex,
            mediaKeyAuthority = mediaKeyAuthority,
            publisher = null,
        )
    lateinit var wireIngress: MeetingProfile01FactWireIngress
    lateinit var ig2Env: Ig2FullChainEnvironment
    val materializer =
        Profile01ShadowMemberBindingMaterializer(
            factPort = factPort,
            ingress = ingress,
            localModuleId = { p1d.localModuleId },
            readSignedLocalSource = sourceBridge::readSignedSourceFact,
            retryLocalSourceBuild = { sid ->
                if (deferRotationMemberMaterialization.get()) {
                    return@Profile01ShadowMemberBindingMaterializer
                }
                val headEpoch =
                    sourceBridge.currentAuthoritativeMediaKeyEpoch(sid)
                        ?: return@Profile01ShadowMemberBindingMaterializer
                val commitment =
                    localSourceAuthority.commitLocalSource(
                        sessionId = sid,
                        moduleId = p1d.localModuleId,
                        mediaKeyEpoch = headEpoch,
                    )
                sourceBridge.onLocalConferenceSourceCommitted(
                    sessionId = sid,
                    localModuleId = p1d.localModuleId,
                    authoritySourceGeneration = commitment.sourceGeneration,
                    ssrc = commitment.ssrc,
                    sourceInstanceId = commitment.sourceInstanceId,
                    mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
                )
            },
        )
    sourceBridge.memberBindingMaterializer = materializer

    wireIngress =
        MeetingProfile01FactWireIngress(
            ingress = ingress,
            supplementRegistry = supplementRegistry,
            sessionIndex = sessionIndex,
            preBindRetention = MeetingProfile01PreBindFactRetention(),
            incompleteApplyContinuation = incompleteApplyContinuation,
            stagedPackageContinuation = stagedContinuation,
            networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
            readSignedCreationFact = creationOriginBridge::readSignedCreationFact,
            creationOriginBridge = creationOriginBridge,
            localModuleId = { p1d.localModuleId },
            localEstablishmentKeyVersion = { p1d.establishmentKeyVersion },
            memberBindingMaterializer = materializer,
        )
    val activeMembershipWiringCompletionCount = AtomicInteger(0)
    wireIngress.onActiveMembershipWiringReplayCompleted = completion@{ sessionId ->
        activeMembershipWiringCompletionCount.incrementAndGet()
        if (deferRotationMemberMaterialization.get()) {
            return@completion
        }
        val headEpoch =
            sourceBridge.currentAuthoritativeMediaKeyEpoch(sessionId)
                ?: return@completion
        val commitment =
            localSourceAuthority.commitLocalSource(
                sessionId = sessionId,
                moduleId = p1d.localModuleId,
                mediaKeyEpoch = headEpoch,
            )
        sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = sessionId,
            localModuleId = p1d.localModuleId,
            authoritySourceGeneration = commitment.sourceGeneration,
            ssrc = commitment.ssrc,
            sourceInstanceId = commitment.sourceInstanceId,
            mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
        )
    }

    ingress.onMediaSupplementReady = { conferenceId, mediaKeyEpoch ->
        supplementReadyCounter.incrementAndGet()
        wireIngress.onSupplementReadyForConference(conferenceId, mediaKeyEpoch)
        if (!ConferenceSessionMediaBridge.hasSession(IG2_SESSION_ID) &&
            !deferRotationMemberMaterialization.get()
        ) {
            val headEpoch = sourceBridge.currentAuthoritativeMediaKeyEpoch(IG2_SESSION_ID) ?: mediaKeyEpoch
            val commitment =
                localSourceAuthority.commitLocalSource(
                    sessionId = IG2_SESSION_ID,
                    moduleId = p1d.localModuleId,
                    mediaKeyEpoch = headEpoch,
                )
            sourceBridge.onLocalConferenceSourceCommitted(
                sessionId = IG2_SESSION_ID,
                localModuleId = p1d.localModuleId,
                authoritySourceGeneration = commitment.sourceGeneration,
                ssrc = commitment.ssrc,
                sourceInstanceId = commitment.sourceInstanceId,
                mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
            )
        }
    }

    return Ig2FullChainEnvironment(
        sessionId = IG2_SESSION_ID,
        conferenceIdHex = p1d.conferenceIdHex,
        localModuleId = p1d.localModuleId,
        establishmentKeyVersion = p1d.establishmentKeyVersion,
        hostSigner = hostSigner.signer,
        validator = validator,
        ingress = ingress,
        wireIngress = wireIngress,
        stagedContinuation = stagedContinuation,
        supplementRegistry = supplementRegistry,
        controlRegistry = controlRegistry,
        sessionIndex = sessionIndex,
        factPort = factPort,
        localSourceAuthority = localSourceAuthority,
        sourceBridge = sourceBridge,
        creationOriginBridge = creationOriginBridge,
        incompleteApplyContinuation = incompleteApplyContinuation,
        baseWire = baseWire,
        deferRotationMemberMaterialization = deferRotationMemberMaterialization,
        activeMembershipWiringCompletionCount = activeMembershipWiringCompletionCount,
    ).also { ig2Env = it }
}

private fun ig2DecodePackage(bytes: ByteArray): Profile01WireMediaKeyPackage =
    (
        Profile01WireCborDecoder.decodeMediaKeyPackage(bytes)
            as Profile01WireCborDecoder.DecodeResult.Ready
    ).value

private data class Ig2SignerBundle(
    val signer: Profile01PersistedSignedFactSigner,
    val publicKeys: Map<Pair<String, Long>, ByteArray>,
)

private fun ig2TestSigner(
    moduleId: String,
    keyVersion: Long,
): Ig2SignerBundle {
    val keyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    val signer =
        Profile01PersistedSignedFactSigner.fromPkcs8(
            pkcs8PrivateKey = keyPair.private.encoded,
            signerModuleId = moduleId,
            signerKeyVersion = keyVersion,
        )!!
    return Ig2SignerBundle(
        signer = signer,
        publicKeys = mapOf(moduleId to keyVersion to ig2EcPublicKeyX963(keyPair.public)),
    )
}

private fun ig2EcPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
    val ec = publicKey as ECPublicKey
    val x = ec.w.affineX.toByteArray()
    val y = ec.w.affineY.toByteArray()
    val x32 = x.copyOfRange(maxOf(0, x.size - 32), x.size)
    val y32 = y.copyOfRange(maxOf(0, y.size - 32), y.size)
    return byteArrayOf(0x04) + ByteArray(32 - x32.size) + x32 + ByteArray(32 - y32.size) + y32
}

internal class Ig2FullChainEnvironment(
    val sessionId: String,
    val conferenceIdHex: String,
    val localModuleId: String,
    val establishmentKeyVersion: Long,
    val hostSigner: Profile01PersistedSignedFactSigner,
    val validator: Profile01ConferenceMediaFactValidator,
    val ingress: Profile01ConferenceMediaFactIngress,
    val wireIngress: MeetingProfile01FactWireIngress,
    val stagedContinuation: MeetingProfile01StagedMediaKeyPackageContinuation,
    val supplementRegistry: Profile01SessionMediaSupplementRegistry,
    val controlRegistry: ConferenceSessionMediaControlFactRegistry,
    val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    val factPort: MeetingProfile01AwareConferenceSessionMediaFactPort,
    val localSourceAuthority: Profile01LocalConferenceSourceIdentityAuthority,
    val sourceBridge: MeetingProfile01SourceOriginBridge,
    val creationOriginBridge: MeetingProfile01CreationOriginBridge,
    val incompleteApplyContinuation: MeetingProfile01IncompleteApplyContinuation,
    private val baseWire: Profile01WireMediaKeyPackage,
    val deferRotationMemberMaterialization: AtomicBoolean,
    val activeMembershipWiringCompletionCount: AtomicInteger,
) {
    var creationSignedBytes: ByteArray = byteArrayOf()

    private val membershipPublisher = MeetingProfile01MembershipOriginPublisher(hostSigner)
    private val creationPublisher = MeetingProfile01CreationOriginPublisher(hostSigner)

    fun buildCreationSignedFact(): ByteArray {
        val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
        val secret = Profile01Q5CryptoVectorFixtures.hex(corpus.inputs.conferenceMediaSecretHex)
        val contextDigest =
            Profile01Q5CryptoVectorFixtures.hex(corpus.expected.membershipKeyContextDigestHex)
        val commitment =
            Profile01PackageCrypto.computeMediaKeyCommitment(secret, contextDigest)
        val snapshot =
            Profile01CreationAuthoritySnapshot(
                conferenceId = baseWire.conferenceId.copyOf(),
                conferenceEpoch = baseWire.conferenceEpoch,
                ownerModuleId = baseWire.ownerModuleId,
                mediaGroupDescriptor = Profile01MediaGroupDescriptor.productionDescriptor(),
                membershipView =
                    listOf(
                        Profile01WireMembershipMember("M01", ByteArray(16) { 1 }),
                        Profile01WireMembershipMember(localModuleId, ByteArray(16) { 2 }),
                    ),
                initialMembershipVersion = 0L,
                initialMediaKeyEpoch = 1L,
                mediaKeyCommitment = commitment.copyOf(),
                signerKeyVersion = hostSigner.signerKeyVersion,
            )
        return creationPublisher.publishCreation(snapshot)
    }

    /** Conflicting CREATION bytes for the same session/conference (different membership view). */
    fun buildConflictingCreationSignedFact(): ByteArray {
        val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
        val secret = Profile01Q5CryptoVectorFixtures.hex(corpus.inputs.conferenceMediaSecretHex)
        val contextDigest =
            Profile01Q5CryptoVectorFixtures.hex(corpus.expected.membershipKeyContextDigestHex)
        val commitment =
            Profile01PackageCrypto.computeMediaKeyCommitment(secret, contextDigest)
        val snapshot =
            Profile01CreationAuthoritySnapshot(
                conferenceId = baseWire.conferenceId.copyOf(),
                conferenceEpoch = baseWire.conferenceEpoch,
                ownerModuleId = baseWire.ownerModuleId,
                mediaGroupDescriptor = Profile01MediaGroupDescriptor.productionDescriptor(),
                membershipView =
                    listOf(
                        Profile01WireMembershipMember("M01", ByteArray(16) { 1 }),
                        Profile01WireMembershipMember(localModuleId, ByteArray(16) { 0x99.toByte() }),
                    ),
                initialMembershipVersion = 0L,
                initialMediaKeyEpoch = 1L,
                mediaKeyCommitment = commitment.copyOf(),
                signerKeyVersion = hostSigner.signerKeyVersion,
            )
        return creationPublisher.publishCreation(snapshot)
    }

    data class EpochAdvanceArtifacts(
        val signedMembershipBytes: ByteArray,
        val packageBytes: ByteArray,
        val mediaKeyEpoch: Long,
    )

    fun buildEpoch2Artifacts(): EpochAdvanceArtifacts =
        buildAdvanceArtifacts(includeFourthMember = false)

    fun buildEpoch3Artifacts(includeFourthMember: Boolean): EpochAdvanceArtifacts =
        buildAdvanceArtifacts(includeFourthMember = includeFourthMember)

    /** R2 — four-member epoch2 head (M01..M04) after CREATION pending @ epoch1. */
    fun buildFourMemberEpoch2Artifacts(): EpochAdvanceArtifacts {
        val current = ingress.currentMembershipGeneration(conferenceIdHex)!!
        val members =
            listOf(
                Profile01WireMembershipMember("M01", ByteArray(16) { 1 }),
                Profile01WireMembershipMember(localModuleId, ByteArray(16) { 2 }),
                Profile01WireMembershipMember("M03", ByteArray(16) { 0x33 }),
                Profile01WireMembershipMember("M04", ByteArray(16) { 0x44 }),
            )
        return buildAdvanceArtifactsAt(
            current = current,
            targetMembershipVersion = current.membershipVersion + 1L,
            targetMediaKeyEpoch = current.mediaKeyEpoch + 1L,
            members = members,
        )
    }

    /** R2 — ACTIVE-session shrink: remove M04, advance v+1 / epoch+1. */
    fun buildShrinkToThreeMemberEpoch3Artifacts(): EpochAdvanceArtifacts {
        val current = ingress.currentMembershipGeneration(conferenceIdHex)!!
        val members = current.members.filter { it.moduleId != "M04" }
        require(members.size == 3) {
            "shrink precondition requires 4-member head, got ${current.members.map { it.moduleId }}"
        }
        return buildAdvanceArtifactsAt(
            current = current,
            targetMembershipVersion = current.membershipVersion + 1L,
            targetMediaKeyEpoch = current.mediaKeyEpoch + 1L,
            members = members,
        )
    }

    private fun buildAdvanceArtifacts(includeFourthMember: Boolean): EpochAdvanceArtifacts {
        val current = ingress.currentMembershipGeneration(conferenceIdHex)!!
        val membershipView =
            buildList {
                addAll(current.members)
                if (includeFourthMember && current.members.none { it.moduleId == "M04" }) {
                    add(Profile01WireMembershipMember("M04", ByteArray(16) { 0x44 }))
                }
            }
        return buildAdvanceArtifactsAt(
            current = current,
            targetMembershipVersion = current.membershipVersion + 1L,
            targetMediaKeyEpoch = current.mediaKeyEpoch + 1L,
            members = membershipView,
        )
    }

    /** R2 field-isomorphic shrink: v+1 / epoch+1 / conferenceEpoch+1 (meshGeneration advance). */
    fun buildFieldIsomorphicShrinkToThreeMemberEpoch3(): EpochAdvanceArtifacts {
        val current = ingress.currentMembershipGeneration(conferenceIdHex)!!
        val members = current.members.filter { it.moduleId != "M04" }
        require(members.size == 3) {
            "field shrink precondition requires 4-member head, got ${current.members.map { it.moduleId }}"
        }
        return buildAdvanceArtifactsAt(
            current = current,
            targetMembershipVersion = current.membershipVersion + 1L,
            targetMediaKeyEpoch = current.mediaKeyEpoch + 1L,
            targetConferenceEpoch = current.conferenceEpoch + 1L,
            members = members,
        )
    }

    internal fun buildAdvanceArtifactsAt(
        current: com.talkback.core.conference.session.profile01.Profile01MembershipGeneration,
        targetMembershipVersion: Long,
        targetMediaKeyEpoch: Long,
        members: List<Profile01WireMembershipMember>,
        targetConferenceEpoch: Long = baseWire.conferenceEpoch,
    ): EpochAdvanceArtifacts {
        val membershipView = members
        val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
        val secret = Profile01Q5CryptoVectorFixtures.hex(corpus.inputs.conferenceMediaSecretHex)
        val contextDigest =
            Profile01Q5CryptoVectorFixtures.hex(corpus.expected.membershipKeyContextDigestHex)
        val commitment =
            Profile01PackageCrypto.computeMediaKeyCommitment(secret, contextDigest)
        val membershipSnapshot =
            Profile01MembershipAuthoritySnapshot(
                conferenceId = baseWire.conferenceId.copyOf(),
                conferenceEpoch = targetConferenceEpoch,
                ownerModuleId = baseWire.ownerModuleId,
                membershipVersion = targetMembershipVersion,
                previousMembershipDigest = current.generationFactDigest.copyOf(),
                members = membershipView,
                mediaKeyEpoch = targetMediaKeyEpoch,
                mediaKeyCommitment = commitment.copyOf(),
                signerKeyVersion = hostSigner.signerKeyVersion,
            )
        val signedMembershipBytes = membershipPublisher.publishMembership(membershipSnapshot)
        val membershipFactDigest =
            Profile01FactDigest.computeFactDigest(
                Profile01SignedFactEnvelope.parse(signedMembershipBytes)!!.fullCanonicalBytes,
            )
        val packageBuilt =
            Profile01MediaKeyPackageBuilder(hostSigner).build(
                Profile01MediaKeyPackageBuildRequest(
                    material =
                        com.talkback.core.conference.session.profile01.wire.Profile01ConferenceKeyMaterial(
                            conferenceId = baseWire.conferenceId.copyOf(),
                            conferenceEpoch = targetConferenceEpoch,
                            ownerModuleId = baseWire.ownerModuleId,
                            membershipVersion = targetMembershipVersion,
                            mediaKeyEpoch = targetMediaKeyEpoch,
                            conferenceMediaSecret = secret,
                            membershipKeyContextDigest = contextDigest,
                            mediaKeyCommitment = commitment,
                        ),
                    creationFactDigest = membershipFactDigest.copyOf(),
                    recipient =
                        Profile01PackageRecipientBinding(
                            recipientModuleId = localModuleId,
                            establishmentKeyVersion = establishmentKeyVersion,
                            establishmentPublicKeySpki =
                                Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki(),
                        ),
                ),
            ) as Profile01MediaKeyPackageBuildResult.Ready
        return EpochAdvanceArtifacts(
            signedMembershipBytes = signedMembershipBytes,
            packageBytes = packageBuilt.signedFactBytes,
            mediaKeyEpoch = targetMediaKeyEpoch,
        )
    }

    fun maybeCommitAndPublishLocalConferenceSource(reason: String): SourceOriginBuildOutcome {
        val headEpoch =
            sourceBridge.currentAuthoritativeMediaKeyEpoch(sessionId)
                ?: error("missing membership head for $reason")
        val commitment =
            localSourceAuthority.commitLocalSource(
                sessionId = sessionId,
                moduleId = localModuleId,
                mediaKeyEpoch = headEpoch,
            )
        return sourceBridge.onLocalConferenceSourceCommitted(
            sessionId = sessionId,
            localModuleId = localModuleId,
            authoritySourceGeneration = commitment.sourceGeneration,
            ssrc = commitment.ssrc,
            sourceInstanceId = commitment.sourceInstanceId,
            mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest,
        )
    }

    fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "ig2-wire-nonce-${bytes.contentHashCode()}",
            signature = "ig2-wire-signature",
        )
}
