package com.talkback.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOwnershipGateTest {
    private val gate = EngineOwnershipGate()

    @Test
    fun conferenceReleasing_blocksGroupMutations() {
        gate.onConferenceEngineProvisioned("M02")
        gate.beginConferenceRelease("M02")

        assertFalse(gate.mayGroupMutateEngine("M02", hasActiveConferenceEntry = true))
    }

    @Test
    fun conferenceReleased_allowsGroupMutations() {
        gate.onConferenceEngineProvisioned("M02")
        gate.beginConferenceRelease("M02")
        gate.completeConferenceRelease("M02", success = true)

        assertTrue(gate.mayGroupMutateEngine("M02", hasActiveConferenceEntry = false))
        assertTrue(gate.mayProvisionAfterRelease("M02"))
    }

    @Test
    fun conferenceReleaseFailed_retainsOwnershipAndBlocksGroup() {
        gate.onConferenceEngineProvisioned("M02")
        gate.beginConferenceRelease("M02")
        gate.completeConferenceRelease("M02", success = false)

        assertFalse(gate.mayGroupMutateEngine("M02", hasActiveConferenceEntry = false))
        assertFalse(gate.mayProvisionAfterRelease("M02"))
    }
}
