package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2-RCA2 Step 2b fixtures F1-F5: native SRD(ANSWER) admission invariant.
 */
class SrdAdmissionDecisionTest {

    private fun validAnswer() = SrdAdmissionInput(
        signalingState = "HAVE_LOCAL_OFFER",
        localDescriptionType = "OFFER",
        remoteDescriptionType = null,
        expectedPcGeneration = 5L,
        actualPcGeneration = 5L,
        outstandingOfferLineageId = "CR2",
        answerOfferLineageId = "CR2",
        latestAdmittedTaskId = 7L,
        taskId = 7L,
        localOfferIceUfrag = "hostUfrag",
        answerIceUfrag = "peerUfrag",
        answerIcePwdFingerprint = "abc123",
        previouslyAppliedRemoteIceUfrag = null,
        remoteDescriptionAlreadyApplied = false,
        outstandingOfferAgeMs = 180L,
        queuedIceCount = 3,
        iceIngressDuringSrd = true,
    )

    @Test
    fun f1_validAnswerOnOutstandingOfferIsAdmitted() {
        val decision = SrdAdmissionDecision.evaluate(validAnswer())
        assertTrue(decision.violations.toString(), decision.admit)
        assertTrue(decision.answerMatchesOutstandingOffer)
        assertTrue(decision.signalingStateAllowed)
        assertTrue(decision.iceCredentialMatches)
        assertEquals(3, decision.queuedIceCount)
        assertTrue(decision.iceIngressDuringSrd)
    }

    @Test
    fun f2_duplicateAnswerIsRejected() {
        val decision = SrdAdmissionDecision.evaluate(
            validAnswer().copy(
                signalingState = "STABLE",
                remoteDescriptionType = "ANSWER",
                remoteDescriptionAlreadyApplied = true,
            )
        )
        assertFalse(decision.admit)
        assertFalse(decision.answerMatchesOutstandingOffer)
        assertTrue(SrdAdmissionDecision.DUPLICATE_ANSWER in decision.violations)
        assertTrue(SrdAdmissionDecision.SIGNALING_STATE_INVALID in decision.violations)
    }

    @Test
    fun f3_staleGenerationAnswerIsRejected() {
        val decision = SrdAdmissionDecision.evaluate(
            validAnswer().copy(expectedPcGeneration = 5L, actualPcGeneration = 4L)
        )
        assertFalse(decision.admit)
        assertFalse(decision.pcGenerationCurrent)
        assertTrue(SrdAdmissionDecision.PC_GENERATION_STALE in decision.violations)
    }

    @Test
    fun f4_credentialMismatchIsRejectedEvenWhenLineageMatches() {
        val echoesOwnUfrag = SrdAdmissionDecision.evaluate(
            validAnswer().copy(answerIceUfrag = "hostUfrag")
        )
        assertFalse(echoesOwnUfrag.admit)
        assertTrue(echoesOwnUfrag.answerMatchesOutstandingOffer)
        assertTrue(SrdAdmissionDecision.SDP_CREDENTIAL_MISMATCH in echoesOwnUfrag.violations)

        val contradictsInstalledRemote = SrdAdmissionDecision.evaluate(
            validAnswer().copy(previouslyAppliedRemoteIceUfrag = "otherUfrag")
        )
        assertFalse(contradictsInstalledRemote.iceCredentialMatches)

        val missingCredentials = SrdAdmissionDecision.evaluate(
            validAnswer().copy(answerIceUfrag = null, answerIcePwdFingerprint = null)
        )
        assertFalse(missingCredentials.iceCredentialMatches)
    }

    @Test
    fun f5_supersededTaskIsRejected() {
        val decision = SrdAdmissionDecision.evaluate(
            validAnswer().copy(latestAdmittedTaskId = 9L, taskId = 7L)
        )
        assertFalse(decision.admit)
        assertFalse(decision.taskCurrent)
        assertTrue(SrdAdmissionDecision.SUPERSEDED_TASK in decision.violations)
    }

    @Test
    fun staleLineageAnswerIsRejected() {
        val decision = SrdAdmissionDecision.evaluate(
            validAnswer().copy(outstandingOfferLineageId = "CR2", answerOfferLineageId = "CR1")
        )
        assertFalse(decision.admit)
        assertTrue(SrdAdmissionDecision.STALE_OR_MISMATCHED_ANSWER in decision.violations)
    }

    @Test
    fun unknownCorrelationDoesNotManufactureViolations() {
        val decision = SrdAdmissionDecision.evaluate(
            validAnswer().copy(
                expectedPcGeneration = null,
                actualPcGeneration = null,
                latestAdmittedTaskId = null,
                taskId = null,
            )
        )
        assertTrue(decision.violations.toString(), decision.admit)
    }

    @Test
    fun formatFieldsCarriesEveryAdjudicatedField() {
        val fields = SrdAdmissionDecision.evaluate(validAnswer()).formatFields()
        listOf(
            "admit=true",
            "answerMatchesOutstandingOffer=true",
            "pcGenerationCurrent=true",
            "taskCurrent=true",
            "signalingStateAllowed=true",
            "iceCredentialMatches=true",
            "outstandingOfferAgeMs=180",
            "queuedIceCount=3",
            "iceIngressDuringSrd=true",
            "violations=NONE",
        ).forEach { assertTrue("$it missing from: $fields", fields.contains(it)) }
    }
}
