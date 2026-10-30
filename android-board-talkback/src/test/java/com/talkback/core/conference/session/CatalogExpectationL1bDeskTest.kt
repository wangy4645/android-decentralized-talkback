package com.talkback.core.conference.session

import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** L1b: expected members survive epoch clear; catalogEntries stays install count. */
class CatalogExpectationL1bDeskTest {
    private val sessionId = "l1b-expected-catalog"
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
    }

    @After
    fun tearDown() {
        wiring.stopSession(sessionId, generation = 2L)
        wiring.stopSession(sessionId, generation = 1L)
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun epochRotate_keepsExpectedPending_doesNotFakeInstall() {
        val fact = SessionMediaWiringHarness.sessionFact(sessionId, generation = 1L, mediaKeyEpoch = 1L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        assertTrue(
            wiring.expectPresentMembers(sessionId, listOf("M01", "M02", "M03", "M04"), "MEMBERSHIP_PRESENT"),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", mediaKeyEpoch = 1L)
        val local = SessionMediaWiringHarness.memberBinding("M03", mediaKeyEpoch = 1L)
        assertTrue(wiring.installMember(sessionId, m01))
        assertTrue(wiring.installMember(sessionId, local))

        var snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(2, snap.catalogEntries)
        assertEquals(4, snap.expectedMembers)
        assertEquals(2, snap.pendingSourceMembers)

        val rotated =
            fact.copy(generation = 2L, mediaKeyEpoch = 2L, masterKey = fact.masterKey.copyOf())
        assertTrue(wiring.rotateMediaKeyEpoch(sessionId, rotated))
        snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(0, snap.catalogEntries)
        assertEquals(4, snap.expectedMembers)
        assertEquals(4, snap.pendingSourceMembers)
        assertEquals(
            listOf("M01", "M02", "M03", "M04"),
            wiring.expectedCatalog(sessionId)!!.pendingModuleIds(),
        )

        val m01b = SessionMediaWiringHarness.memberBinding("M01", mediaKeyEpoch = 2L)
        assertTrue(wiring.installMember(sessionId, m01b))
        snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(1, snap.catalogEntries)
        assertEquals(4, snap.expectedMembers)
        assertEquals(3, snap.pendingSourceMembers)
    }
}
