package com.talkback.core.session

import com.talkback.core.media.MediaTopologyPolicy
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTopologyTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val m04 = ModuleId("M04")
    private val m05 = ModuleId("M05")
    private val m06 = ModuleId("M06")
    private val m07 = ModuleId("M07")

    @Test
    fun forMemberCount_switchesAtSix() {
        assertEquals(MeshTopology, MediaTopology.forMemberCount(5))
        assertEquals(AnchorTopology, MediaTopology.forMemberCount(6))
        assertEquals(AnchorTopology, MediaTopology.forMemberCount(8))
    }

    @Test
    fun mesh_joinTargets_matchGroupMeshPlanner() {
        val all = setOf(m01, m02, m03, m04, m05)
        val expected = GroupMeshPlanner.joinTargets(m03, m01, all)
        val actual = MeshTopology.joinTargets(m03, m01, m01, all)
        assertEquals(expected, actual)
    }

    @Test
    fun anchor_nonAnchorMember_onlyLinksToAnchor() {
        val all = setOf(m01, m02, m03, m04, m05, m06)
        val peers = AnchorTopology.transmitPeerIds(m06, m01, all)
        assertEquals(setOf("M01"), peers)
    }

    @Test
    fun anchor_anchorMember_linksToAllOthers() {
        val all = setOf(m01, m02, m03, m04, m05, m06)
        val peers = AnchorTopology.transmitPeerIds(m01, m01, all)
        assertEquals(setOf("M02", "M03", "M04", "M05", "M06"), peers)
    }

    @Test
    fun anchor_joinTargets_onlyOnAnchor() {
        val all = setOf(m01, m02, m03, m04, m05, m06)
        assertTrue(AnchorTopology.joinTargets(m02, m03, m01, all).isEmpty())
        assertEquals(
            listOf(m02, m04, m05, m06),
            AnchorTopology.joinTargets(m01, m03, m01, all)
        )
    }

    @Test
    fun policy_threshold_isSix() {
        assertEquals(6, MediaTopologyPolicy.SFU_LITE_THRESHOLD_MODULES)
        assertEquals(8, MediaTopologyPolicy.DEFAULT_MAX_GROUP_MODULES)
    }
}
