package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1b-4: authority routes all edge mutations through [ConferenceMediaEdgeAdmissionContract]. */
class ConferenceTopologyAuthorityMediaEdgeAdmissionTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")

    @Test
    fun topologyProjection_firstPublish_hasTopologyProjectionCause() {
        val authority = ConferenceTopologyAuthority()
        val result = authority.publishTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M02",
                anchorId = "M01",
                members = roster4
            )
        )
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        assertEquals(
            MediaEdgeAdmissionCause.TOPOLOGY_PROJECTION,
            (result as ConferenceTopologyAuthority.PublishResult.Published).mediaEdgeCause
        )
    }

    @Test
    fun iceDrivenAdmission_rejectedAtContract_notInstalled() {
        val authority = ConferenceTopologyAuthority()
        val snapshot = authority.currentSnapshot("c1")
            ?: (authority.publishTopologyProjection(
                AnchorAdmissionInput("c1", "M01", "M01", roster4)
            ) as ConferenceTopologyAuthority.PublishResult.Published).snapshot
        val rejected = ConferenceMediaEdgeAdmissionContract.rejectIceDrivenAdmission(
            snapshot,
            IceEdgeObservation("M01", "M02", iceConnected = true)
        )
        assertTrue(rejected.reason.contains("cannot mutate ActualMediaEdgeSet"))
        assertEquals(snapshot, authority.currentSnapshot("c1"))
    }
}
