package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceGroupAcceptDispatchSupportTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val localM01 = EndpointAddress(m01, EndpointId("E01"))

    @Test
    fun conferenceHost_usesHostRealizationAccept() {
        val session = TalkbackSession("S1", SessionType.CONFERENCE, localM01, "CH-01").apply {
            accepted = true
            initiatorModuleId = m01
        }
        assertTrue(ConferenceGroupAcceptDispatchSupport.usesHostRealizationAccept(session, m01))
    }

    @Test
    fun conferenceParticipant_usesPeerMeshAccept() {
        val session = TalkbackSession("S1", SessionType.CONFERENCE, localM01, "CH-01").apply {
            accepted = true
            initiatorModuleId = m02
        }
        assertFalse(ConferenceGroupAcceptDispatchSupport.usesHostRealizationAccept(session, m01))
    }

    @Test
    fun groupSession_neverUsesHostRealizationAccept() {
        val session = TalkbackSession("S1", SessionType.GROUP, localM01, "CH-01").apply {
            accepted = true
            initiatorModuleId = m01
        }
        assertFalse(ConferenceGroupAcceptDispatchSupport.usesHostRealizationAccept(session, m01))
    }
}
