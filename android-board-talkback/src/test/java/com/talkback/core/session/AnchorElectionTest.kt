package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnchorElectionTest {

    @Test
    fun anchor_picksMinModuleId() {
        val members = listOf(ModuleId("M03"), ModuleId("M01"), ModuleId("M02"))
        assertEquals(ModuleId("M01"), AnchorElection.anchor(members))
    }

    @Test
    fun nextAnchor_picksNextMin() {
        val members = setOf(ModuleId("M01"), ModuleId("M02"), ModuleId("M03"))
        assertEquals(ModuleId("M02"), AnchorElection.nextAnchor(members, ModuleId("M01")))
        assertEquals(ModuleId("M03"), AnchorElection.nextAnchor(members, ModuleId("M02")))
        assertNull(AnchorElection.nextAnchor(members, ModuleId("M03")))
    }

    @Test
    fun isAnchor_trueOnlyForMin() {
        val members = setOf(ModuleId("M01"), ModuleId("M02"))
        assertEquals(true, AnchorElection.isAnchor(ModuleId("M01"), members))
        assertEquals(false, AnchorElection.isAnchor(ModuleId("M02"), members))
    }

    @Test
    fun anchorModuleId_stringVariant() {
        assertEquals("M01", AnchorElection.anchorModuleId(listOf("M03", "M01", "M02")))
    }
}
