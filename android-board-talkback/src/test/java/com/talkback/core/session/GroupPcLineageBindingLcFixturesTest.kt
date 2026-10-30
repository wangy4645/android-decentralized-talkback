package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0057 GPLB-LC fixtures LC-EG1..LC-EG5 (LC-EG6 = regression suite elsewhere). */
class GroupPcLineageBindingLcFixturesTest {

    private fun session(): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        return TalkbackSession(id = "S1", type = SessionType.GROUP, local = local, channelId = "CH1")
    }

    @Test
    fun lcEg1_bindingExistsBeforeCandidateCorrelation() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        val live = GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M03", 1L)
        assertEquals("GM1", live?.offerLineageId)
        assertEquals(GroupOfferBinding.BindingState.LIVE, live?.state)
    }

    @Test
    fun lcEg2_liveBindingResolvesLineageForPcGeneration() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val live = GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M03", 2L)
        assertEquals("GM2", live?.offerLineageId)
    }

    @Test
    fun lcEg3_groupAcceptMarksSignalingOnly_bindingStaysLive() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        val binding = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        GroupPcLineageBindingSupport.markSignalingAccepted(session, binding)
        val updated = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        assertTrue(updated.signalingAccepted)
        assertEquals(GroupOfferBinding.BindingState.LIVE, updated.state)
    }

    @Test
    fun lcEg4_postAcceptIceCorrelatesCurrentOnLiveBinding() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        val binding = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        GroupPcLineageBindingSupport.markSignalingAccepted(session, binding)
        val outcome =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM1", currentPcLineage = 1L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.CURRENT, outcome.result)
    }

    @Test
    fun lcEg5_retryFencesPriorLineage_postAcceptIceOnFencedIsStale() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        val first = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        GroupPcLineageBindingSupport.markSignalingAccepted(session, first)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM2", pcLineage = 2L)
        val fenced = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        assertEquals(GroupOfferBinding.BindingState.FENCED, fenced.state)
        assertTrue(fenced.signalingAccepted)
        val stale =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM1", currentPcLineage = 2L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.STALE, stale.result)
        val current =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM2", currentPcLineage = 2L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.CURRENT, current.result)
    }

    @Test
    fun releaseBinding_fencesCorrelation() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M03", "GM1", pcLineage = 1L)
        val binding = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        GroupPcLineageBindingSupport.releaseBinding(session, binding)
        val released = GroupPcLineageBindingSupport.binding(session, "M03", "GM1")!!
        assertEquals(GroupOfferBinding.BindingState.RELEASED, released.state)
        val outcome =
            GroupPcLineageBindingSupport.correlateWireLineage(session, "M03", "GM1", currentPcLineage = 1L)
        assertEquals(GroupPcLineageBindingSupport.CorrelationResult.STALE, outcome.result)
    }
}
