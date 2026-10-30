package com.talkback.core.conference.session.profile01

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01RejoinCatchUpHostDispatchGateTest {
    @Test
    fun groupAcceptIngress_neverDispatchesCatchUp() {
        assertFalse(Profile01RejoinCatchUpHostDispatchGate.mayDispatchOnGroupAcceptIngress())
    }

    @Test
    fun acceptFailClosed_blocksCatchUp() {
        assertFalse(Profile01RejoinCatchUpHostDispatchGate.mayDispatchAfterAcceptFailClosed())
    }

    @Test
    fun admissionMediaSettled_requiresNoApplyError() {
        assertFalse(Profile01RejoinCatchUpHostDispatchGate.mayDispatchAfterAdmissionMediaSettled(RuntimeException()))
        assertTrue(Profile01RejoinCatchUpHostDispatchGate.mayDispatchAfterAdmissionMediaSettled(null))
    }
}
