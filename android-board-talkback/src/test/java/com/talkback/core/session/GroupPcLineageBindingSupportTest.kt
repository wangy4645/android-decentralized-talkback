package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroupPcLineageBindingSupportTest {

    private fun session(): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        return TalkbackSession(id = "S1", type = SessionType.GROUP, local = local, channelId = "CH1")
    }

    @Test
    fun gplbEg1_offerBinding_currentCorrelation() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        val outcome =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM1", currentPcLineage = 1L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.CURRENT, outcome.result)
        assertNotNull(outcome.binding)
    }

    @Test
    fun gplbEg2_retryRetainsPriorBindingIdentity() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val first = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")
        val second = GroupPcLineageBindingSupport.binding(session, "M03", "GM2")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(GroupOfferBinding.BindingState.FENCED, first!!.state)
        assertEquals(GroupOfferBinding.BindingState.LIVE, second!!.state)
        assertEquals("GM1", first.offerLineageId)
        assertEquals("GM2", second.offerLineageId)
    }

    @Test
    fun gplbEg3_lateAcceptForFencedBinding_isStale() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val outcome =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM1", currentPcLineage = 2L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.STALE, outcome.result)
    }

    @Test
    fun gplbEg4_acceptForLiveBindingOnMatchingPc_isCurrent() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val outcome =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM2", currentPcLineage = 2L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.CURRENT, outcome.result)
    }

    @Test
    fun gplbEg5_unknownLineage_isUnknown() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val outcome =
            GroupPcLineageBindingSupport.correlateWireLineage(
                session,
                "M03",
                GroupPcLineageWire.UNKNOWN_LINEAGE,
                currentPcLineage = 2L,
            )
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.UNKNOWN, outcome.result)
        assertNull(outcome.binding)
    }

    @Test
    fun liveBindingForPcLineage_returnsActiveBinding() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val live = GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M03", 2L)
        assertEquals("GM2", live?.offerLineageId)
    }
}
