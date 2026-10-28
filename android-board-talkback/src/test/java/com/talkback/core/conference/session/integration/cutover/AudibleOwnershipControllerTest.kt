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
    fun cutoverHandoff_acquiresMulticastBeforeReleasingAnchor() {
        multicast.onAcquire = {
            assertTrue(anchor.anchorActive)
            assertEquals(0, anchor.releaseCount)
        }
        assertEquals(CutoverOutcome.APPLIED, controller.armCutover(SESSION))
        assertEquals(CutoverOutcome.APPLIED, controller.executeCutover(SESSION))
        assertEquals(AudibleOwnershipState.MULTICAST_ACTIVE, controller.state)
        assertFalse(anchor.anchorActive)
        assertEquals(1, anchor.releaseCount)
        assertEquals(0, multicast.fenceCount)
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
        assertEquals(0, anchor.releaseCount)
        assertEquals(0, multicast.fenceCount)
        assertFalse(multicast.productionActive)
    }

    @Test
    fun cutover_probeInactiveDoesNotReleaseAnchor() {
        multicast.probeShouldFail = true
        controller.armCutover(SESSION)
        assertEquals(CutoverOutcome.REJECTED_HANDOFF_FAILURE, controller.executeCutover(SESSION))
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, controller.state)
        assertTrue(anchor.anchorActive)
        assertEquals(0, anchor.releaseCount)
        assertEquals(1, multicast.releaseCount)
        assertFalse(multicast.productionActive)
    }

    @Test
    fun cutover_anchorReleaseFailureCleansMulticastAndKeepsAnchor() {
        anchor.releaseShouldFail = true
        controller.armCutover(SESSION)
        assertEquals(CutoverOutcome.REJECTED_HANDOFF_FAILURE, controller.executeCutover(SESSION))
        assertEquals(AudibleOwnershipState.ANCHOR_ACTIVE, controller.state)
        assertTrue(anchor.anchorActive)
        assertEquals(1, multicast.releaseCount)
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
        var releaseCount: Int = 0
        var releaseShouldFail: Boolean = false

        override fun releaseAnchorOwnership(sessionId: String): Boolean {
            releaseCount++
            if (releaseShouldFail) return false
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
        var probeShouldFail: Boolean = false
        var fenceCount: Int = 0
        var releaseCount: Int = 0
        var onAcquire: (() -> Unit)? = null

        override fun fenceProductionPlayout(sessionId: String) {
            fenceCount++
            productionActive = false
        }

        override fun acquireProductionAudioTrack(sessionId: String): Boolean {
            onAcquire?.invoke()
            if (acquireShouldFail) return false
            productionActive = !probeShouldFail
            return true
        }

        override fun releaseProductionAudioTrack(sessionId: String) {
            releaseCount++
            productionActive = false
        }

        override fun isProductionAudioTrackActive(sessionId: String): Boolean = productionActive
    }

    companion object {
        private const val SESSION = "session-rc1"
    }
}
