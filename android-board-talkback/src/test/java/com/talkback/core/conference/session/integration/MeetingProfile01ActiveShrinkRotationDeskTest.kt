package com.talkback.core.conference.session.integration



import java.util.concurrent.atomic.AtomicInteger

import org.junit.After

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue

import org.junit.Before

import org.junit.Test

import com.talkback.core.conference.session.ConferenceSessionMediaBridge



/**

 * R2 / F1–F6 — ACTIVE-session shrink rotation continuity (production wire path).

 */

class MeetingProfile01ActiveShrinkRotationDeskTest {

    private val supplementReadyCount = AtomicInteger(0)



    @Before

    fun setUp() {

        MeetingProductMediaShadow.enabled = true

        supplementReadyCount.set(0)

        com.talkback.core.conference.session.ConferenceSessionMediaBridge.wiring =

            com.talkback.core.conference.session.ConferenceSessionMediaWiring.forHarness()

    }



    @After

    fun tearDown() {

        MeetingProductMediaShadow.enabled = false

        com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate.factPort = null

        com.talkback.core.conference.session.ConferenceSessionMediaBridge.wiring = null

    }



    @Test

    fun r2_activeShrinkRotation_satisfiesContinuityContract() {

        val env = createIg2FullChainEnvironment(supplementReadyCount)

        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)



        assertNull("F1/R2-1", ig2ResolveTxFenceReason(env))

        assertTrue(ig2CoherenceSnapshot(env).memberIds.contains("M04"))



        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()

        val steps =

            listOf(Ig2TransitionStep("pre_shrink", env)) +

                ig2ProductionShrinkTransitionSteps(env, shrink) { label, environment ->

                    Ig2TransitionStep(label, environment)

                }



        val postShrink = steps.filterIsInstance<Ig2TransitionStep>().last()

        assertNull("F3/F5: post-rotation TX READY", postShrink.fence)

        assertFalse("F6", postShrink.snapshot.memberIds.contains("M04"))

        assertEquals(listOf("M01", env.localModuleId, "M03"), postShrink.snapshot.memberIds)

        assertTrue("F4/F5/R2-5", postShrink.snapshot.isCoherentAt(3L))

        assertNull("F5", ig2ResolveTxFenceReason(env))

    }



    @Test

    fun r2_activeShrinkRotation_productionWire_idempotentReplay() {

        val env = createIg2FullChainEnvironment(supplementReadyCount)

        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)

        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()



        ig2RunProductionShrinkRotation(env, shrink)

        val fenceAfterFirst = ig2ResolveTxFenceReason(env)

        val snapshotAfterFirst = ig2CoherenceSnapshot(env)



        ig2RunProductionShrinkRotation(env, shrink)



        assertEquals(fenceAfterFirst, ig2ResolveTxFenceReason(env))

        assertEquals(snapshotAfterFirst, ig2CoherenceSnapshot(env))

    }



    /** Ordering 1 — membership first, supplement later (production pending owner). */

    @Test

    fun activeRotation_membershipFirstSupplementLater_completesExactlyOnce() {

        val env = createIg2FullChainEnvironment(supplementReadyCount)

        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)

        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()

        val completionBaseline = env.activeMembershipWiringCompletionCount.get()

        val baselineGeneration =

            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration



        assertEquals(

            WireIngressOutcome.APPLIED,

            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.signedMembershipBytes)),

        )

        assertTrue(env.wireIngress.hasPendingActiveMembershipWiringReplayForTest(env.conferenceIdHex))

        assertEquals(2L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))

        assertEquals(completionBaseline, env.activeMembershipWiringCompletionCount.get())



        assertEquals(

            WireIngressOutcome.APPLIED,

            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.packageBytes)),

        )



        assertFalse(env.wireIngress.hasPendingActiveMembershipWiringReplayForTest(env.conferenceIdHex))

        assertEquals(3L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))

        assertEquals(completionBaseline + 1, env.activeMembershipWiringCompletionCount.get())

        assertEquals(

            baselineGeneration + 1L,

            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,

        )

        assertNull(ig2ResolveTxFenceReason(env))

    }



    /** Ordering 2 — supplement staged first, membership later (single membership wire). */

    @Test

    fun activeRotation_supplementReadyBeforePending_completesExactlyOnce() {

        val env = createIg2FullChainEnvironment(supplementReadyCount)

        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)

        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()

        val completionBaseline = env.activeMembershipWiringCompletionCount.get()

        val baselineGeneration =

            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration



        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.packageBytes))

        assertTrue(env.wireIngress.stagedPackageCountForTest(env.conferenceIdHex) >= 1)



        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.signedMembershipBytes))



        assertFalse(env.wireIngress.hasPendingActiveMembershipWiringReplayForTest(env.conferenceIdHex))

        assertEquals(3L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))

        assertEquals(completionBaseline + 1, env.activeMembershipWiringCompletionCount.get())

        assertEquals(

            baselineGeneration + 1L,

            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,

        )

        assertNull(ig2ResolveTxFenceReason(env))

    }



    /** Ordering 3 — prerequisites incomplete: no rotation, no r4b completion. */

    @Test

    fun activeRotation_prerequisitesIncomplete_retainsPendingWithoutCompletion() {

        val env = createIg2FullChainEnvironment(supplementReadyCount)

        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)

        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()

        val completionBaseline = env.activeMembershipWiringCompletionCount.get()
        val sourceBaseline =
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration

        assertEquals(
            WireIngressOutcome.APPLIED,
            env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.signedMembershipBytes)),
        )

        assertTrue(env.wireIngress.hasPendingActiveMembershipWiringReplayForTest(env.conferenceIdHex))
        assertEquals(2L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))
        assertEquals(completionBaseline, env.activeMembershipWiringCompletionCount.get())
        assertEquals(
            sourceBaseline,
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,
        )
    }



    /** Ordering 4 — duplicate supplement-ready / membership replay stays idempotent. */

    @Test

    fun activeRotation_duplicateReadyAndMembershipReplay_noDuplicateCompletion() {

        val env = createIg2FullChainEnvironment(supplementReadyCount)

        bootstrapIg2ProductionFourMemberEpoch2Active(env, supplementReadyCount)

        val shrink = env.buildShrinkToThreeMemberEpoch3Artifacts()



        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.packageBytes))

        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.signedMembershipBytes))

        val completionAfterFirst = env.activeMembershipWiringCompletionCount.get()

        val sourceGenerationAfterFirst =

            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration



        env.wireIngress.onSupplementReadyForConference(env.conferenceIdHex, shrink.mediaKeyEpoch)

        env.wireIngress.onConferenceSignedFact(env.signedFactEnvelope(shrink.signedMembershipBytes))



        assertEquals(completionAfterFirst, env.activeMembershipWiringCompletionCount.get())

        assertEquals(

            sourceGenerationAfterFirst,

            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,

        )

        assertEquals(3L, ConferenceSessionMediaBridge.currentMediaKeyEpoch(IG2_SESSION_ID))

    }



    private data class Ig2TransitionStep(

        val label: String,

        val fence: String?,

        val snapshot: Ig2CoherenceSnapshot,

    ) {

        constructor(label: String, env: Ig2FullChainEnvironment) : this(

            label = label,

            fence = ig2ResolveTxFenceReason(env),

            snapshot = ig2CoherenceSnapshot(env),

        )

    }

}


