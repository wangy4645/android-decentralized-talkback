package com.talkback.core.conference.session.integration.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudibleOwnershipControllerTest {
    private lateinit var anchor: FakeAnchorAudiblePort
    private lateinit var multicast: FakeMulticastAudiblePort
    private lateinit var controller: AudibleOwnershipController

    @Before
    fun setUp() {
        anchor = FakeAnchorAudiblePort(anchorActive = true)
        multicast = FakeMulticastAudiblePort()
        controller =
            AudibleOwnershipController(
                anchorPort = anchor,
                multicastPort = multicast,
                shadowSessionActive = { it == SESSION },
                multicastAudibleEnabled = { true },
            )
    }

    @Test
    fun cutoverHandoff_enforcesSingleOwner() {
        assertEquals(CutoverOutcome.APPLIED, controller.armCutover(SESSION))
        assertEquals(CutoverOutcome.APPLIED, controller.executeCutover(SESSION))
        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, controller.state)
        assertFalse(anchor.anchorActive)
        assertTrue(multicast.productionActive)
        assertTrue(controller.verifySingleOwner(SESSION))
    }

    @Test
    fun rollback_restoresAnchorOwnership() {
        controller.armCutover(SESSION)
        controller.executeCutover(SESSION)
        assertEquals(CutoverOutcome.APPLIED, controller.rollback(SESSION, RollbackTrigger.MANUAL))
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, controller.state)
        assertTrue(anchor.anchorActive)
        assertFalse(multicast.productionActive)
        assertTrue(controller.verifySingleOwner(SESSION))
    }

    @Test
    fun cutover_rejectsWhenMulticastAudibleDisabled() {
        val disabled =
            AudibleOwnershipController(
                anchorPort = anchor,
                multicastPort = multicast,
                shadowSessionActive = { true },
                multicastAudibleEnabled = { false },
            )
        assertEquals(CutoverOutcome.REJECTED_PILOT_DISABLED, disabled.armCutover(SESSION))
    }

    @Test
    fun cutover_failsWhenProductionAcquireFails() {
        multicast.acquireShouldFail = true
        controller.armCutover(SESSION)
        assertEquals(CutoverOutcome.REJECTED_HANDOFF_FAILURE, controller.executeCutover(SESSION))
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, controller.state)
        assertTrue(anchor.anchorActive)
        assertFalse(multicast.productionActive)
    }

    @Test
    fun fatalFailure_triggersRollbackFromMulticast() {
        controller.armCutover(SESSION)
        controller.executeCutover(SESSION)
        controller.onReplacementRuntimeFatal(SESSION)
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, controller.state)
        assertTrue(anchor.anchorActive)
        assertFalse(multicast.productionActive)
    }

    @Test
    fun sessionTeardown_releasesMulticastWithoutAnchorReacquire() {
        controller.armCutover(SESSION)
        controller.executeCutover(SESSION)
        anchor.anchorActive = false
        assertEquals(CutoverOutcome.APPLIED, controller.onSessionTeardown(SESSION))
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, controller.state)
        assertFalse(multicast.productionActive)
        assertFalse(anchor.anchorActive) // session ending — no reacquire required
    }

    private class FakeAnchorAudiblePort(
        var anchorActive: Boolean,
    ) : AnchorAudiblePort {
        override fun releaseAnchorOwnership(sessionId: String): Boolean {
            anchorActive = false
            return true
        }

        override fun acquireAnchorOwnership(sessionId: String): Boolean {
            anchorActive = true
            return true
        }

        override fun isAnchorAudibleActive(sessionId: String): Boolean = anchorActive
    }

    private class FakeMulticastAudiblePort : MulticastAudiblePort {
        var productionActive: Boolean = false
        var acquireShouldFail: Boolean = false

        override fun fenceProductionPlayout(sessionId: String) {
            productionActive = false
        }

        override fun acquireProductionAudioTrack(sessionId: String): Boolean {
            if (acquireShouldFail) return false
            productionActive = true
            return true
        }

        override fun releaseProductionAudioTrack(sessionId: String) {
            productionActive = false
        }

        override fun isProductionAudioTrackActive(sessionId: String): Boolean = productionActive
    }

    companion object {
        private const val SESSION = "session-rc1"
    }
}
