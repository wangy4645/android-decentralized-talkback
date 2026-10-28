package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMeshHostElectionBootstrapTest {
    @Test
    fun incompleteDirectory_usesMinModuleIdAmongVisibleSet() {
        val host = ChannelMeshHostElection.electHost(
            localModuleId = ModuleId("M03"),
            reachableModuleIds = listOf("M05"),
            healthByModule = emptyMap(),
        )
        assertEquals("M03", host.value)
    }

    @Test
    fun visibleSetIncludingM01_electsM01AsMin() {
        val host = ChannelMeshHostElection.electHost(
            localModuleId = ModuleId("M03"),
            reachableModuleIds = listOf("M01", "M02", "M04"),
            healthByModule = emptyMap(),
        )
        assertEquals("M01", host.value)
    }
}
