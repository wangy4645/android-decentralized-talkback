package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceBootstrapDeferralTest {

    @Test
    fun meetingStart_participantHostIceDown_defers() {
        assertTrue(
            ConferenceBootstrapDeferral.shouldDeferFullMesh(
                isParticipantConference = true,
                edgeAnyRecovering = false,
                edgeAnyFailedMediaRecovery = false,
                hostIceConnected = false
            )
        )
    }

    @Test
    fun r24a_failedMediaRecovery_doesNotDeferAsBootstrap() {
        assertFalse(
            ConferenceBootstrapDeferral.shouldDeferFullMesh(
                isParticipantConference = true,
                edgeAnyRecovering = false,
                edgeAnyFailedMediaRecovery = true,
                hostIceConnected = false
            )
        )
    }

    @Test
    fun r24a_edgeRecovering_doesNotDeferAsBootstrap() {
        assertFalse(
            ConferenceBootstrapDeferral.shouldDeferFullMesh(
                isParticipantConference = true,
                edgeAnyRecovering = true,
                edgeAnyFailedMediaRecovery = false,
                hostIceConnected = false
            )
        )
    }
}
