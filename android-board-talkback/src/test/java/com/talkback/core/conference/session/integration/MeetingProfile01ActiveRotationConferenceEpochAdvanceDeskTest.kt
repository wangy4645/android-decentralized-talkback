package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.profile01.Profile01SessionEndpointAuthority
import com.talkback.core.conference.session.profile01.SessionHeadSyncOutcome
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Field-isomorphic ACTIVE rotation when [Profile01MembershipGeneration.conferenceEpoch] advances
 * (production meshGeneration bump) while published session control remains at prior epoch.
 */
class MeetingProfile01ActiveRotationConferenceEpochAdvanceDeskTest {
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
    fun existingPublishedSession_rejectedWhenConferenceEpochAdvances() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)
        val priorConferenceEpoch =
            env.controlRegistry.session(env.conferenceIdHex)!!.conferenceEpoch

        val shrink = env.buildFieldIsomorphicShrinkToThreeMemberEpoch3()
        val head = ig2ApplyMembershipToRegistryOnly(env, shrink.signedMembershipBytes)
        assertEquals(priorConferenceEpoch + 1L, head.conferenceEpoch)
        stageHeadSupplement(env, head)

        assertEquals(
            SessionHeadSyncOutcome.REJECTED_INCOMPLETE,
            env.ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = IG2_CHANNEL_ID,
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                endpointAuthority = Profile01SessionEndpointAuthority.ExistingPublishedSession,
            ),
        )
        assertEquals(2L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)

        val authority =
            env.wireIngress.activeRotationSessionEndpointAuthorityForTest(
                sessionId = IG2_SESSION_ID,
                conferenceIdHex = env.conferenceIdHex,
            )
        assertTrue(authority is Profile01SessionEndpointAuthority.InitialCreationContext)
    }

    @Test
    fun fieldIsomorphic_conferenceEpochAdvance_productionWireRotatesToHead() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        val baselineGeneration =
            bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)
        val priorConferenceEpoch =
            env.controlRegistry.session(env.conferenceIdHex)!!.conferenceEpoch
        val shrink = env.buildFieldIsomorphicShrinkToThreeMemberEpoch3()
        val completionBaseline = env.activeMembershipWiringCompletionCount.get()

        ig2RunProductionShrinkRotation(env, shrink)

        val sessionControl = env.controlRegistry.session(env.conferenceIdHex)!!
        assertEquals(priorConferenceEpoch + 1L, sessionControl.conferenceEpoch)
        assertEquals(2L, sessionControl.membershipVersion)
        assertEquals(3L, sessionControl.mediaKeyEpoch)
        assertTrue(
            env.ingress.isSessionControlFactAtMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = IG2_CHANNEL_ID,
            ),
        )
        assertEquals(3L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))
        assertEquals(completionBaseline + 1, env.activeMembershipWiringCompletionCount.get())
        assertEquals(
            baselineGeneration + 1L,
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,
        )
        assertNull(ig2ResolveTxFenceReason(env))
        assertTrue(ig2CoherenceSnapshot(env).isCoherentAt(3L))
    }

    @Test
    fun fieldIsomorphic_conferenceEpochAdvance_missingCreationContext_failClosed() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)
        val shrink = env.buildFieldIsomorphicShrinkToThreeMemberEpoch3()
        val completionBaseline = env.activeMembershipWiringCompletionCount.get()
        val sourceBaseline =
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration

        env.creationOriginBridge.clearSession(IG2_SESSION_ID)

        assertEquals(
            WireIngressOutcome.APPLIED,
            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.signedMembershipBytes)),
        )
        val packageOutcome =
            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.packageBytes))
        assertTrue(
            packageOutcome == WireIngressOutcome.APPLIED ||
                packageOutcome == WireIngressOutcome.INCOMPLETE_PENDING,
        )

        assertEquals(2L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))
        assertEquals(2L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
        assertFalse(
            env.ingress.isSessionControlFactAtMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = IG2_CHANNEL_ID,
            ),
        )
        assertNull(
            env.wireIngress.activeRotationSessionEndpointAuthorityForTest(
                sessionId = IG2_SESSION_ID,
                conferenceIdHex = env.conferenceIdHex,
            ),
        )
        assertEquals(completionBaseline, env.activeMembershipWiringCompletionCount.get())
        assertEquals(
            sourceBaseline,
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,
        )
    }

    @Test
    fun sameConferenceEpoch_activeRotationUsesExistingPublishedSession() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)
        val priorConferenceEpoch =
            env.controlRegistry.session(env.conferenceIdHex)!!.conferenceEpoch
        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()
        val completionBaseline = env.activeMembershipWiringCompletionCount.get()

        ig2RunProductionShrinkRotation(env, shrink)

        val sessionControl = env.controlRegistry.session(env.conferenceIdHex)!!
        assertEquals(priorConferenceEpoch, sessionControl.conferenceEpoch)
        assertEquals(3L, sessionControl.mediaKeyEpoch)
        assertTrue(
            env.ingress.isSessionControlFactAtMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = IG2_CHANNEL_ID,
            ),
        )
        assertEquals(completionBaseline + 1, env.activeMembershipWiringCompletionCount.get())
        assertNull(ig2ResolveTxFenceReason(env))
    }

    @Test
    fun conflictingCreationFact_doesNotOverwriteExistingAuthority() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)
        val originalBytes = env.creationOriginBridge.readSignedCreationFact(IG2_SESSION_ID)!!

        assertEquals(
            WireIngressOutcome.REJECTED_VERIFY,
            env.wireIngress.onConferenceSignedFact(
                env.signedFactEnvelope(env.buildConflictingCreationSignedFact()),
            ),
        )
        assertTrue(
            env.creationOriginBridge.readSignedCreationFact(IG2_SESSION_ID)!!.contentEquals(originalBytes),
        )

        val shrink = env.buildFieldIsomorphicShrinkToThreeMemberEpoch3()
        ig2RunProductionShrinkRotation(env, shrink)

        assertEquals(3L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
        assertTrue(
            env.ingress.isSessionControlFactAtMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = IG2_CHANNEL_ID,
            ),
        )
    }

    private fun stageHeadSupplement(
        env: Ig2FullChainEnvironment,
        head: com.talkback.core.conference.session.profile01.Profile01MembershipGeneration,
    ) {
        env.supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = env.conferenceIdHex,
                conferenceEpoch = head.conferenceEpoch,
                membershipVersion = head.membershipVersion,
                mediaKeyEpoch = head.mediaKeyEpoch,
                masterKey = ByteArray(16) { 0x11 },
                masterSalt = ByteArray(12) { 0x22 },
                keyContextHint64 = ByteArray(8) { 0x33 },
                membershipKeyContextDigest = ByteArray(32) { 0x44 },
            ),
        )
    }
}
