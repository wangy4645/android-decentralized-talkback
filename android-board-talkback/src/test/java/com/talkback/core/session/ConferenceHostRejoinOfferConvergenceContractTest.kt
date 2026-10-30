package com.talkback.core.session



import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue

import org.junit.Test



class ConferenceHostRejoinOfferConvergenceContractTest {



    private fun ctx(

        lineage: String? = "CR5",

        handoff: Boolean = false,

        phase: ConferenceAdmissionPhase? = ConferenceAdmissionPhase.NEGOTIATING,

        connected: Boolean = false,

        outstandingPending: Boolean = true,

        rejoin: Boolean = true,

        pcGeneration: Long = 7L,

        peerEligible: Boolean = true,

    ) = ConferenceHostRejoinOfferConvergenceContract.HostOfferEdgeContext(

        hostLocalOfferLineageId = lineage,

        pcGeneration = pcGeneration,

        admissionHandoffActive = handoff,

        admissionPhase = phase,

        peerConferenceConnected = connected,

        hostOutstandingOfferPending = outstandingPending,

        peerEligibleForHostOfferRealization = peerEligible,

        rejoin = rejoin,

    )



    @Test

    fun firstRejoin_noOutstandingLineage_createsNew() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(lineage = null, phase = null, handoff = false),

            ),

        )

    }



    @Test

    fun repeatedRealization_rejoinFalse_sameNegotiation_coalesces() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.COALESCE_TO_PENDING,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(rejoin = false, handoff = false, phase = ConferenceAdmissionPhase.NEGOTIATING),

            ),

        )

    }



    @Test

    fun firstInviteBurst_nullAdmissionWithOutstanding_coalesces() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.COALESCE_TO_PENDING,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(

                    rejoin = false,

                    handoff = false,

                    phase = null,

                    outstandingPending = true,

                    lineage = "CR2",

                ),

            ),

        )

    }



    @Test

    fun staleReadyWithoutConnection_createsNew_notCoalesce() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(

                    rejoin = true,

                    handoff = false,

                    phase = ConferenceAdmissionPhase.READY,

                    connected = false,

                    outstandingPending = true,

                    lineage = "CR2",

                ),

            ),

        )

    }



    @Test

    fun peerLeftAfterMembership_notEligible_createsNewEvenWithOutstanding() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(

                    peerEligible = false,

                    lineage = "CR2",

                    phase = ConferenceAdmissionPhase.READY,

                    outstandingPending = true,

                ),

            ),

        )

    }



    @Test

    fun terminatedAdmission_invalidatesStaleLineage() {

        assertFalse(

            ConferenceHostRejoinOfferConvergenceContract.hostOfferNegotiationStillValid(

                ctx(phase = ConferenceAdmissionPhase.TERMINATED, lineage = "CR2"),

            ),

        )

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(phase = ConferenceAdmissionPhase.TERMINATED, lineage = "CR2"),

            ),

        )

    }



    @Test

    fun sameGenerationRepeatedRejoinWhileNegotiating_coalesces() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.COALESCE_TO_PENDING,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(handoff = true, phase = ConferenceAdmissionPhase.ACCEPTING),

            ),

        )

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.COALESCE_TO_PENDING,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(handoff = false, phase = ConferenceAdmissionPhase.NEGOTIATING),

            ),

        )

    }



    @Test

    fun completedRound_allowsNewLineage() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(connected = true, phase = ConferenceAdmissionPhase.READY, handoff = false),

            ),

        )

    }



    @Test

    fun staleAnswerLate_mismatchResyncHeadOnce() {

        val first = ConferenceHostRejoinOfferConvergenceContract.decideLineageMismatchResync(

            answerOfferLineageId = "CR5",

            hostLocalOfferLineageId = "CR7",

            headOfferValid = true,

            lastResyncDedupeKey = null,

        )

        assertTrue(first.rejectStaleAnswer)

        assertTrue(first.shouldResyncHead)

        assertEquals("CR7", first.resyncOfferLineageId)

        assertEquals("CR5>CR7", first.dedupeKey)



        val second = ConferenceHostRejoinOfferConvergenceContract.decideLineageMismatchResync(

            answerOfferLineageId = "CR5",

            hostLocalOfferLineageId = "CR7",

            headOfferValid = true,

            lastResyncDedupeKey = "CR5>CR7",

        )

        assertTrue(second.rejectStaleAnswer)

        assertFalse(second.shouldResyncHead)

    }



    @Test

    fun currentAnswerMatch_doesNotResync() {

        val decision = ConferenceHostRejoinOfferConvergenceContract.decideLineageMismatchResync(

            answerOfferLineageId = "CR7",

            hostLocalOfferLineageId = "CR7",

            headOfferValid = true,

            lastResyncDedupeKey = null,

        )

        assertFalse(decision.rejectStaleAnswer)

        assertFalse(decision.shouldResyncHead)

    }



    @Test

    fun failedNegotiation_allowsNewLineage() {

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(phase = ConferenceAdmissionPhase.FAILED, handoff = false, lineage = "CR5"),

            ),

        )

        assertEquals(

            ConferenceHostRejoinOfferConvergenceContract.MergeHostRejoinOfferDecision.CREATE_NEW_LINEAGE,

            ConferenceHostRejoinOfferConvergenceContract.decideMergeHostRejoinOffer(

                ctx(phase = ConferenceAdmissionPhase.TERMINATED, handoff = false, lineage = "CR5"),

            ),

        )

    }



    @Test

    fun headOfferValidity_requiresLocalOffer() {

        assertTrue(

            ConferenceHostRejoinOfferConvergenceContract.headOfferStillValidForResync(

                hostLocalOfferLineageId = "CR7",

                localSignalingState = "HAVE_LOCAL_OFFER",

                localDescriptionType = "OFFER",

            ),

        )

        assertFalse(

            ConferenceHostRejoinOfferConvergenceContract.headOfferStillValidForResync(

                hostLocalOfferLineageId = "CR7",

                localSignalingState = "STABLE",

                localDescriptionType = "ANSWER",

            ),

        )

    }

}


