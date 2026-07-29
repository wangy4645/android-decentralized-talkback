package com.talkback.core.session

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceJoinLatencyTrackerTest {

    private val tracker = ConferenceJoinLatencyTracker()
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        tracker.resetForTest { logs += it }
    }

    @Test
    fun fullMesh_emitsSummaryOncePerJoinedBump() {
        val t0 = 1_000L
        tracker.onInviteAccepted("s1", "CH-01", "participant", t0)
        tracker.onSessionCreated("s1", t0 + 100)
        tracker.onJoinedCountChanged("s1", 2, "CH-01", t0 + 200)
        tracker.onPeerIceConnected("s1", "M01", t0 + 500)
        tracker.onFullMeshReached("s1", joinedCount = 2, connectedCount = 2, t0 + 600)

        tracker.onJoinedCountChanged("s1", 3, "CH-01", t0 + 1_000)
        tracker.onPeerIceChecking("s1", "M03", t0 + 1_100)
        tracker.onPeerIceConnected("s1", "M03", t0 + 12_000)
        tracker.onFullMeshReached("s1", joinedCount = 3, connectedCount = 3, t0 + 12_100)

        val summaries = logs.filter { it.contains("fullMeshMs=") }
        assertEquals(2, summaries.size)
        assertTrue(summaries[1].contains("fullMeshMs=11100"))
    }

    @Test
    fun peerMarkers_loggedOnIceTransitions() {
        tracker.onInviteAccepted("s1", "CH-01", "participant", 0L)
        tracker.onJoinedCountChanged("s1", 3, "CH-01", 100L)
        tracker.onPeerConnectionCreated("s1", "M03", 200L)
        tracker.onPeerIceChecking("s1", "M03", 300L)
        tracker.onPeerIceConnected("s1", "M03", 1_200L)

        assertTrue(logs.any { it.contains("T4_ICE_CHECKING") && it.contains("peer=M03") })
        assertTrue(logs.any { it.contains("T5_ICE_CONNECTED") && it.contains("peer=M03") })
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
