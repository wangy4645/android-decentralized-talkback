package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorTopologyJoinTargetsTest {
    @Test
    fun joinTargets_anchorInitiator_doesNotOfferJoin() {
        val m01 = ModuleId("M01")
        val m02 = ModuleId("M02")
        val m03 = ModuleId("M03")
        val members = setOf(m01, m02, m03)
        val targets = AnchorTopology.joinTargets(
            localModuleId = m01,
            initiatorModuleId = m01,
            anchorModuleId = m01,
            allMemberModuleIds = members
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun joinTargets_anchorNotInitiator_offersMissingMembers() {
        val m01 = ModuleId("M01")
        val m02 = ModuleId("M02")
        val m03 = ModuleId("M03")
        val targets = AnchorTopology.joinTargets(
            localModuleId = m02,
            initiatorModuleId = m01,
            anchorModuleId = m02,
            allMemberModuleIds = setOf(m01, m02, m03)
        )
        assertEquals(listOf(m03), targets)
    }
}
