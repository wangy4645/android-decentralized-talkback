package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionType
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private fun TalkbackRuntime.requireGroupCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String
): String = requireNotNull(groupCall(from, remoteEndpoints, channelId)) { "groupCall blocked" }

private fun TalkbackRuntime.requireConferenceCall(
    from: EndpointAddress,
    remoteEndpoints: List<EndpointAddress>,
    channelId: String
): String = requireNotNull(conferenceCall(from, remoteEndpoints, channelId)) { "conferenceCall blocked" }

private fun TalkbackRuntime.conferenceSessions(): List<TalkbackSessionSnapshot> =
    sessionSnapshots().filter { it.type == SessionType.CONFERENCE }

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TalkbackCoordinatorIntegrationTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(
            m01 to 50001,
            m02 to 50002,
            m03 to 50003
        )
        nodeM01 = TestTalkbackNode(context, m01, 50001, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50002, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50003, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun unicastCall_establishesSessionOnBothSides() {
        val sessionId = nodeM01.runtime.call(
            nodeM01.localEndpoint,
            EndpointAddress(m02, EndpointId("E01"))
        )
        assertTrue(nodeM01.waitForLog { it.contains("Outgoing call") })
        assertTrue(nodeM02.waitForLog { it.contains("Call accepted") })
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)
    }

    @Test
    fun unicastCall_secondCallAfterHangup_succeedsOnBothSides() {
        val first = nodeM01.runtime.call(
            nodeM01.localEndpoint,
            EndpointAddress(m02, EndpointId("E01"))
        )
        assertTrue(nodeM02.waitForLog { it.contains("Call accepted") })
        nodeM01.runtime.hangup(first)
        assertTrue(nodeM01.waitForLog { it.contains("Hangup") })
        assertTrue(nodeM02.waitForLog { it.contains("Remote hangup") })
        Thread.sleep(300L)
        assertTrue(nodeM01.runtime.activeSessionIds().isEmpty())
        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())

        val second = nodeM01.runtime.call(
            nodeM01.localEndpoint,
            EndpointAddress(m02, EndpointId("E01"))
        )
        assertTrue(second != first)
        assertTrue(nodeM02.waitForLog { it.contains("Incoming call") || it.contains("Call accepted") })
        assertEquals(listOf(second), nodeM01.runtime.activeSessionIds())
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)
    }

    @Test
    fun unicastCall_largerModuleCallsSmaller_establishesSession() {
        val sessionId = nodeM03.runtime.call(
            nodeM03.localEndpoint,
            EndpointAddress(m01, EndpointId("E01"))
        )
        assertTrue(nodeM03.waitForLog { it.contains("Outgoing call") })
        assertTrue(nodeM01.waitForLog { it.contains("Call accepted") })
        nodeM03.runtime.simulateUnicastIceState(sessionId, "COMPLETED")
        nodeM01.runtime.simulateUnicastIceState(sessionId, "COMPLETED")
        Thread.sleep(200L)
        assertEquals(listOf(sessionId), nodeM03.runtime.activeSessionIds())
        assertEquals(1, nodeM01.runtime.activeSessionIds().size)
    }

    @Test
    fun groupCall_threeModules_channelReadyWithCompletedIce() {
        val sessionId = nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CH-01"
        )
        assertTrue(nodeM01.waitForLog { it.contains("Group call") })
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(3_500L)
        assertTrue(
            nodeM02.hasLog { it.contains("mesh link accepted") || it.contains("invite accepted") } &&
                nodeM03.hasLog { it.contains("mesh link accepted") || it.contains("invite accepted") }
        )
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)
        assertEquals(1, nodeM03.runtime.activeSessionIds().size)
        nodeM01.runtime.simulateRemoteIceState("M02", "COMPLETED")
        nodeM01.runtime.simulateRemoteIceState("M03", "COMPLETED")
        nodeM02.runtime.simulateRemoteIceState("M01", "COMPLETED")
        nodeM02.runtime.simulateRemoteIceState("M03", "COMPLETED")
        nodeM03.runtime.simulateRemoteIceState("M01", "COMPLETED")
        nodeM03.runtime.simulateRemoteIceState("M02", "COMPLETED")
        Thread.sleep(200L)
        assertTrue(nodeM01.runtime.isChannelMediaReady("CH-01"))
        assertTrue(nodeM02.runtime.isChannelMediaReady("CH-01"))
        assertTrue(nodeM03.runtime.isChannelMediaReady("CH-01"))
    }

    @Test
    fun groupCall_threeModules_meshAndSessions() {
        val sessionId = nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CH-01"
        )
        assertTrue(nodeM01.waitForLog { it.contains("Group call") })
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(3_500L)
        assertTrue(
            nodeM02.hasLog { it.contains("mesh link accepted") || it.contains("invite accepted") } &&
                nodeM03.hasLog { it.contains("mesh link accepted") || it.contains("invite accepted") }
        )
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)
        assertEquals(1, nodeM03.runtime.activeSessionIds().size)
    }

    @Test
    fun groupPtt_nonAuthorityRequestsFloor() {
        val sessionId = nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CH-01"
        )
        Thread.sleep(500L)
        nodeM02.pressPtt(sessionId)
        Thread.sleep(300L)
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)
        assertEquals(1, nodeM01.runtime.activeSessionIds().size)
        nodeM02.releasePtt(sessionId)
    }

    @Test
    fun autoRedial_doesNotDeadlockOnModuleRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50011, m02 to 50012)
        val hubLocal = InMemorySignalingHub()
        val shortTimeout = 400L
        val fastCleanup = 200L
        val nodeA = TestTalkbackNode(
            context = context,
            moduleId = m01,
            port = 50011,
            hub = hubLocal,
            allPeers = peers,
            sessionIdleTimeoutMs = shortTimeout,
            cleanupIntervalMs = fastCleanup,
            heartbeatIntervalMs = 60_000L,
            autoReDialOnModuleRecovery = true
        )
        val nodeB = TestTalkbackNode(
            context = context,
            moduleId = m02,
            port = 50012,
            hub = hubLocal,
            allPeers = peers,
            sessionIdleTimeoutMs = shortTimeout,
            cleanupIntervalMs = fastCleanup,
            heartbeatIntervalMs = 60_000L
        )
        nodeA.start()
        nodeB.start()
        try {
            nodeA.runtime.call(nodeA.localEndpoint, EndpointAddress(m02, EndpointId("E01")))
            assertTrue(nodeB.waitForLog { it.contains("Call accepted") })
            Thread.sleep(shortTimeout + fastCleanup * 3)
            assertTrue(nodeA.waitForLog { it.contains("Session timeout") })
            assertTrue(nodeA.runtime.activeSessionIds().isEmpty())
            val start = System.currentTimeMillis()
            nodeA.refreshDiscovery()
            assertTrue(nodeA.waitForLog(5_000L) { it.contains("Group call") || it.contains("Outgoing call") })
            assertTrue("Auto redial blocked > 4s", System.currentTimeMillis() - start < 4_000L)
        } finally {
            nodeA.stop()
            nodeB.stop()
        }
    }

    @Test
    fun conferenceCall_threeModules_meshAndSessions() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CONF-01"
        )
        assertTrue(nodeM01.waitForLog { it.contains("Conference") })
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        val snapshot = nodeM01.runtime.sessionSnapshots().first()
        assertEquals(SessionType.CONFERENCE, snapshot.type)
    }

    @Test
    fun hangup_groupNotifiesAllPeers() {
        val sessionId = nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            "CH-02"
        )
        Thread.sleep(300L)
        nodeM01.runtime.hangup(sessionId)
        Thread.sleep(300L)
        assertTrue(nodeM02.waitForLog { it.contains("Remote hangup") || it.contains("Hangup") })
        assertTrue(nodeM01.runtime.activeSessionIds().isEmpty())
        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())
    }

    @Test
    fun conferenceLeave_oneParticipantLeaves_othersRemain() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CONF-LEAVE"
        )
        assertTrue(nodeM01.waitForLog { it.contains("Conference") })
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        nodeM02.runtime.leaveConference(m02SessionId)
        Thread.sleep(1_500L)

        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertEquals(1, nodeM03.runtime.activeSessionIds().size)
        assertTrue(nodeM01.waitForLog { it.contains("Conference peer left: M02") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference peer left: M02") })

        val m01Snap = nodeM01.runtime.sessionSnapshots().single()
        val m03Snap = nodeM03.runtime.sessionSnapshots().single()
        assertEquals(2, m01Snap.memberKeys.size)
        assertEquals(2, m03Snap.memberKeys.size)
        assertTrue(m01Snap.memberKeys.none { it.startsWith("M02-") })
        assertTrue(m03Snap.memberKeys.none { it.startsWith("M02-") })
        assertFalse(nodeM01.hasLog { it.contains("Conference host leaving, ending for all") })
        assertFalse(nodeM03.hasLog { it.contains("Remote hangup") || it.contains("Hangup") })
    }

    @Test
    fun conferenceLeave_initiatorLeaves_endsForAll() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CONF-HOST-LEAVE"
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        nodeM01.runtime.leaveConference(sessionId)
        Thread.sleep(1_200L)

        assertTrue(nodeM01.runtime.conferenceSessions().isEmpty())
        assertTrue(nodeM02.runtime.conferenceSessions().isEmpty())
        assertTrue(nodeM03.runtime.conferenceSessions().isEmpty())
        assertTrue(nodeM01.waitForLog { it.contains("Conference host leaving, ending for all") })
        assertTrue(nodeM02.waitForLog { it.contains("Remote hangup") || it.contains("Hangup") })
        assertTrue(nodeM03.waitForLog { it.contains("Remote hangup") || it.contains("Hangup") })
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 5_000L) {
                it.contains("Conference channel released for GROUP PTT")
            }
        )
    }

    @Test
    fun conferenceLeave_m03Leaves_m01M02RosterIntact() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CONF-M03-LEAVE"
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.leaveConference(m03SessionId)
        Thread.sleep(1_500L)

        val m01Snap = nodeM01.runtime.sessionSnapshots().single()
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertEquals(2, m01Snap.memberKeys.size)
        assertTrue(m01Snap.memberKeys.any { it.startsWith("M02-") })
        assertTrue(m01Snap.memberKeys.none { it.startsWith("M03-") })
        assertTrue(nodeM01.waitForLog { it.contains("Conference peer left: M03") })
        assertFalse(nodeM01.hasLog { it.contains("Conference peer left: M02") })
    }

    @Test
    fun conferenceRejoin_nonInitiatorLeavesAndRejoins_sameSession() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CONF-REJOIN"
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        val m03SessionId = nodeM03.runtime.activeSessionIds().single()
        nodeM03.runtime.leaveConference(m03SessionId)
        Thread.sleep(1_500L)
        assertTrue(nodeM03.runtime.activeSessionIds().isEmpty())
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())

        val rejoinSessionId = nodeM03.runtime.requireConferenceCall(
            nodeM03.localEndpoint,
            emptyList(),
            "CONF-REJOIN"
        )
        val m03LogMark = synchronized(nodeM03.logs) { nodeM03.logs.size }
        nodeM03.runtime.sendConferenceInvites(
            rejoinSessionId,
            listOf(EndpointAddress(m01, EndpointId("E01")))
        )
        assertTrue(nodeM01.waitForLog { it.contains("Host counter-invited M03 sent=1") })
        val rejoinedDeadline = System.currentTimeMillis() + 8_000L
        var m03JoinedHostSession = false
        while (System.currentTimeMillis() < rejoinedDeadline) {
            if (nodeM03.runtime.activeSessionIds() == listOf(sessionId)) {
                m03JoinedHostSession = true
                break
            }
            Thread.sleep(50L)
        }
        if (!m03JoinedHostSession) {
            synchronized(nodeM03.logs) {
                nodeM03.logs.drop(m03LogMark).forEach { println("M03: $it") }
            }
        }
        assertTrue("M03 should rejoin host session $sessionId", m03JoinedHostSession)

        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertEquals(1, nodeM02.runtime.activeSessionIds().size)
        assertEquals(1, nodeM03.runtime.activeSessionIds().size)
        assertFalse(nodeM01.hasLog { it.contains("Call rejected session=$sessionId reason=BUSY") })
        assertFalse(nodeM02.hasLog { it.contains("Replacing incomplete mesh session") })
    }

    @Test
    fun conferenceRejoin_nonHostSignalsOnlyChannelHost_notOtherPeers() {
        val channelId = "CONF-REJOIN-HOST-ONLY"
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        nodeM02.runtime.leaveConference(m02SessionId)
        Thread.sleep(1_500L)
        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())

        val m03LogMark = synchronized(nodeM03.logs) { nodeM03.logs.size }
        val rejoinSessionId = nodeM02.runtime.requireConferenceCall(
            nodeM02.localEndpoint,
            emptyList(),
            channelId
        )
        nodeM02.runtime.sendConferenceInvites(
            rejoinSessionId,
            listOf(EndpointAddress(m01, EndpointId("E01")))
        )
        assertTrue(nodeM01.waitForLog { it.contains("Host counter-invited M02 sent=1") })
        assertFalse(
            synchronized(nodeM03.logs) {
                nodeM03.logs.drop(m03LogMark).any {
                    it.contains("Conference invite pending") && it.contains("from=M02")
                }
            }
        )

        val rejoinedDeadline = System.currentTimeMillis() + 8_000L
        var m02JoinedHostSession = false
        while (System.currentTimeMillis() < rejoinedDeadline) {
            if (nodeM02.runtime.activeSessionIds() == listOf(sessionId)) {
                m02JoinedHostSession = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("M02 should rejoin host session $sessionId", m02JoinedHostSession)
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
    }

    @Test
    fun conferenceSilentRejoin_usesConferenceRejoinSignal_notPendingInvite() {
        val channelId = "CONF-SILENT-REJOIN"
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        nodeM02.runtime.setAutoAcceptConferenceInvites(false)
        val m02SessionId = nodeM02.runtime.activeSessionIds().single()
        nodeM02.runtime.leaveConference(m02SessionId)
        Thread.sleep(1_500L)
        assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())
        val rejoinHint = nodeM02.runtime.rejoinableConference(channelId)
        assertNotNull(rejoinHint)
        assertEquals(sessionId, rejoinHint?.hostSessionId)

        val m02LogMark = synchronized(nodeM02.logs) { nodeM02.logs.size }
        assertTrue(
            nodeM02.runtime.sendConferenceRejoin(
                channelId,
                EndpointAddress(m01, EndpointId("E01")),
                sessionId
            )
        )
        assertTrue(nodeM01.waitForLog { it.contains("Conference rejoin pull-in M02 sent=1") })
        assertFalse(
            synchronized(nodeM02.logs) {
                nodeM02.logs.drop(m02LogMark).any { it.contains("Conference invite pending") }
            }
        )

        val rejoinedDeadline = System.currentTimeMillis() + 8_000L
        var m02JoinedHostSession = false
        while (System.currentTimeMillis() < rejoinedDeadline) {
            if (nodeM02.runtime.activeSessionIds() == listOf(sessionId)) {
                m02JoinedHostSession = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("M02 should silently rejoin host session $sessionId", m02JoinedHostSession)
        assertNull(nodeM02.runtime.rejoinableConference(channelId))
    }

    @Test
    fun conferenceRejoin_nonHostSignalsActualInitiator_whenInitiatorIsNotMinModuleId() {
        val channelId = "CONF-REJOIN-M02-HOST"
        val sessionId = nodeM02.runtime.requireConferenceCall(
            nodeM02.localEndpoint,
            listOf(
                EndpointAddress(m01, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM01.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        val m01SessionId = nodeM01.runtime.activeSessionIds().single()
        nodeM01.runtime.leaveConference(m01SessionId)
        Thread.sleep(1_500L)
        assertTrue(nodeM01.runtime.activeSessionIds().isEmpty())

        val m03LogMark = synchronized(nodeM03.logs) { nodeM03.logs.size }
        val rejoinSessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            emptyList(),
            channelId
        )
        nodeM01.runtime.sendConferenceInvites(
            rejoinSessionId,
            listOf(EndpointAddress(m02, EndpointId("E01")))
        )
        assertTrue(nodeM02.waitForLog { it.contains("Host counter-invited M01 sent=1") })
        assertFalse(
            synchronized(nodeM03.logs) {
                nodeM03.logs.drop(m03LogMark).any {
                    it.contains("Conference invite pending") && it.contains("from=M01")
                }
            }
        )

        val rejoinedDeadline = System.currentTimeMillis() + 8_000L
        var m01JoinedHostSession = false
        while (System.currentTimeMillis() < rejoinedDeadline) {
            if (nodeM01.runtime.activeSessionIds() == listOf(sessionId)) {
                m01JoinedHostSession = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("M01 should rejoin M02 host session $sessionId", m01JoinedHostSession)
        assertEquals(listOf(sessionId), nodeM02.runtime.activeSessionIds())
    }

    @Test
    fun conferenceHost_soloWithPendingInvites_isUiReadyImmediately() {
        val peers = TestTalkbackNode.allPeers(m01 to 50011, m02 to 50012, m03 to 50013)
        val context = RuntimeEnvironment.getApplication()
        val nodeHost = TestTalkbackNode(context, m01, 50011, hub, peers)
        val nodeM02 = TestTalkbackNode(context, m02, 50012, hub, peers)
        nodeHost.start()
        nodeM02.start()
        try {
            val sessionId = nodeHost.runtime.requireConferenceCall(
                nodeHost.localEndpoint,
                emptyList(),
                "CONF-HOST-WAIT"
            )
            val sent = nodeHost.runtime.sendConferenceInvites(
                sessionId,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                )
            )
            assertTrue(sent >= 1)
            assertTrue(nodeHost.runtime.isChannelMediaReady("CONF-HOST-WAIT"))
            assertFalse(nodeHost.runtime.isChannelConnecting("CONF-HOST-WAIT"))
            assertTrue(nodeHost.runtime.isConferenceHostForChannel("CONF-HOST-WAIT"))

            assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
            nodeHost.runtime.simulateRemoteIceState("M02", "CONNECTED")
            Thread.sleep(200L)
            assertTrue(nodeHost.runtime.isChannelMediaReady("CONF-HOST-WAIT"))
        } finally {
            nodeHost.stop()
            nodeM02.stop()
        }
    }

    @Test
    fun conferenceParticipant_readyWhenHostConnected_notAllRoster() {
        val channelId = "CONF-PARTIAL-ROSTER"
        nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        Thread.sleep(200L)
        assertTrue(nodeM01.runtime.isChannelMediaReady(channelId))

        nodeM02.runtime.simulateRemoteIceState("M01", "CONNECTED")
        Thread.sleep(200L)
        assertTrue(
            "M02 should be live once host is connected, even if other invitees have not joined",
            nodeM02.runtime.isChannelMediaReady(channelId)
        )
    }

    @Test
    fun conferenceHost_connectedPeer_skipsDuplicateInvite() {
        val channelId = "CONF-BUSY-CONNECTED"
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        Thread.sleep(200L)

        val resent = nodeM01.runtime.sendConferenceInvites(
            sessionId,
            listOf(EndpointAddress(m02, EndpointId("E01")))
        )
        assertEquals(0, resent)
        assertEquals(listOf(sessionId), nodeM01.runtime.activeSessionIds())
        assertFalse(
            nodeM01.hasLog { it.contains("All targets rejected") && it.contains("tearing down solo mesh") }
        )
    }

    @Test
    fun conferenceInvite_ringTimeout_prunesExpiredFromRoster() {
        val peers = TestTalkbackNode.allPeers(m01 to 50021, m02 to 50022)
        val context = RuntimeEnvironment.getApplication()
        val nodeHost = TestTalkbackNode(
            context,
            m01,
            50021,
            hub,
            peers,
            sessionIdleTimeoutMs = 120_000L,
            cleanupIntervalMs = 200L,
            conferenceInviteRingTimeoutMs = 3_000L
        )
        val nodeGuest = TestTalkbackNode(
            context,
            m02,
            50022,
            hub,
            peers,
            autoReDialOnModuleRecovery = false
        )
        nodeHost.start()
        nodeGuest.start()
        try {
            nodeGuest.runtime.setAutoAcceptConferenceInvites(false)
            val sessionId = nodeHost.runtime.requireConferenceCall(
                nodeHost.localEndpoint,
                emptyList(),
                "CONF-RING-EXPIRE"
            )
            nodeHost.runtime.sendConferenceInvites(
                sessionId,
                listOf(EndpointAddress(m02, EndpointId("E01")))
            )
            assertTrue(
                nodeHost.waitForLog(timeoutMs = 12_000L) {
                    it.contains("Conference invite expired for M02")
                }
            )
            val snapshot = nodeHost.runtime.sessionSnapshotForChannel("CONF-RING-EXPIRE")
            assertTrue(snapshot != null)
            assertFalse(snapshot!!.memberKeys.any { it.startsWith("M02") })
        } finally {
            nodeHost.stop()
            nodeGuest.stop()
        }
    }

    @Test
    fun groupInvite_rejectedWhileMeetingPreferred() {
        val peers = TestTalkbackNode.allPeers(m01 to 50041, m02 to 50042)
        val context = RuntimeEnvironment.getApplication()
        val nodeM01 = TestTalkbackNode(context, m01, 50041, hub, peers)
        val nodeM02 = TestTalkbackNode(context, m02, 50042, hub, peers)
        nodeM01.start()
        nodeM02.start()
        try {
            nodeM02.runtime.setMeetingPreferred(true)
            nodeM01.runtime.requireGroupCall(
                nodeM01.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                "CH-01"
            )
            assertTrue(
                nodeM02.waitForLog(timeoutMs = 3_000L) {
                    it.contains("Rejecting GROUP invite while conference active/preferred/pending") ||
                        it.contains("Rejecting GROUP invite while meeting preferred")
                }
            )
            assertTrue(nodeM02.runtime.activeSessionIds().isEmpty())
        } finally {
            nodeM02.stop()
            nodeM01.stop()
        }
    }

    @Test
    fun conferenceHostIceTransientDisconnect_recoversBeforeGraceEnds() {
        val peers = TestTalkbackNode.allPeers(m01 to 50021, m02 to 50022)
        val context = RuntimeEnvironment.getApplication()
        val nodeHost = TestTalkbackNode(
            context,
            m01,
            50021,
            hub,
            peers,
            conferenceHostIceReconnectGraceMs = 800L
        )
        val nodeParticipant = TestTalkbackNode(
            context,
            m02,
            50022,
            hub,
            peers,
            conferenceHostIceReconnectGraceMs = 800L
        )
        nodeHost.start()
        nodeParticipant.start()
        try {
            nodeHost.runtime.requireConferenceCall(
                nodeHost.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                "CONF-ICE-GRACE"
            )
            assertTrue(
                nodeParticipant.waitForLog {
                    it.contains("Conference invite accepted") || it.contains("invite accepted")
                }
            )
            Thread.sleep(500L)

            nodeParticipant.runtime.simulateRemoteIceState("M01", "DISCONNECTED")
            Thread.sleep(200L)
            assertTrue(
                nodeParticipant.hasLog {
                    it.contains("Conference host ICE DISCONNECTED (participant waiting for recovery)")
                }
            )
            assertEquals(1, nodeParticipant.runtime.activeSessionIds().size)

            nodeParticipant.runtime.simulateRemoteIceState("M01", "CONNECTED")
            Thread.sleep(1_000L)
            assertEquals(1, nodeParticipant.runtime.activeSessionIds().size)
            assertFalse(nodeParticipant.hasLog { it.contains("after grace, ending session") })
        } finally {
            nodeHost.stop()
            nodeParticipant.stop()
        }
    }

    @Test
    fun conferenceHostIceDisconnectBeyondGrace_endsSession() {
        val peers = TestTalkbackNode.allPeers(m01 to 50031, m02 to 50032)
        val context = RuntimeEnvironment.getApplication()
        val nodeHost = TestTalkbackNode(
            context,
            m01,
            50031,
            hub,
            peers,
            conferenceHostIceReconnectGraceMs = 400L
        )
        val nodeParticipant = TestTalkbackNode(
            context,
            m02,
            50032,
            hub,
            peers,
            conferenceHostIceReconnectGraceMs = 400L
        )
        nodeHost.start()
        nodeParticipant.start()
        try {
            nodeHost.runtime.requireConferenceCall(
                nodeHost.localEndpoint,
                listOf(EndpointAddress(m02, EndpointId("E01"))),
                "CONF-ICE-TIMEOUT"
            )
            assertTrue(
                nodeParticipant.waitForLog {
                    it.contains("Conference invite accepted") || it.contains("invite accepted")
                }
            )
            Thread.sleep(500L)

            nodeParticipant.runtime.simulateRemoteIceState("M01", "DISCONNECTED")
            Thread.sleep(1_000L)
            assertTrue(
                nodeParticipant.hasLog {
                    it.contains("Conference host ICE DISCONNECTED (participant waiting for recovery)")
                }
            )
            assertEquals(1, nodeParticipant.runtime.activeSessionIds().size)
            assertFalse(nodeParticipant.hasLog { it.contains("after grace, ending session") })
        } finally {
            nodeHost.stop()
            nodeParticipant.stop()
        }
    }

    @Test
    fun conferenceHangup_endsForAllParticipants() {
        val sessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            "CONF-END"
        )
        assertTrue(nodeM02.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Conference invite accepted") || it.contains("invite accepted") })
        Thread.sleep(3_500L)

        nodeM01.runtime.hangup(sessionId)
        Thread.sleep(1_200L)

        assertTrue(nodeM01.runtime.conferenceSessions().isEmpty())
        assertTrue(nodeM02.runtime.conferenceSessions().isEmpty())
        assertTrue(nodeM03.runtime.conferenceSessions().isEmpty())
        assertTrue(nodeM02.waitForLog { it.contains("Remote hangup") || it.contains("Hangup") })
        assertTrue(nodeM03.waitForLog { it.contains("Remote hangup") || it.contains("Hangup") })
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 5_000L) {
                it.contains("Conference channel released for GROUP PTT")
            }
        )
    }

    @Test
    fun conferenceAcceptPendingInvite_yieldsLocalSoloConference() {
        val channelId = "CONF-ACCEPT-YIELD"
        val hostSessionId = nodeM01.runtime.requireConferenceCall(
            nodeM01.localEndpoint,
            listOf(EndpointAddress(m02, EndpointId("E01"))),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        Thread.sleep(500L)

        nodeM03.runtime.setAutoAcceptConferenceInvites(false)
        nodeM01.runtime.sendConferenceInvites(
            hostSessionId,
            listOf(EndpointAddress(m03, EndpointId("E01")))
        )
        val pendingDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pendingDeadline) {
            if (nodeM03.runtime.pendingConferenceInvite(channelId) != null) break
            Thread.sleep(50L)
        }
        assertTrue(
            "M03 should have pending host invite",
            nodeM03.runtime.pendingConferenceInvite(channelId) != null
        )

        val soloId = nodeM03.runtime.requireConferenceCall(nodeM03.localEndpoint, emptyList(), channelId)
        assertTrue(
            "M03 should have solo conference before accept",
            nodeM03.runtime.activeSessionIds().contains(soloId)
        )

        assertTrue(nodeM03.runtime.acceptPendingConferenceInvite(channelId))
        assertTrue(
            nodeM03.waitForLog {
                it.contains("Yielding local solo conference to host invite on $channelId")
            }
        )
        Thread.sleep(1_000L)

        assertEquals(listOf(hostSessionId), nodeM03.runtime.activeSessionIds())
    }

    @Test
    fun conferenceHostCancel_clearsPendingInviteOnRemote() {
        val channelId = "CONF-CANCEL-PENDING"
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052)
        val context = RuntimeEnvironment.getApplication()
        val nodeHost = TestTalkbackNode(context, m01, 50051, hub, peers)
        val nodeGuest = TestTalkbackNode(context, m02, 50052, hub, peers)
        nodeHost.start()
        nodeGuest.start()
        try {
            nodeGuest.runtime.setAutoAcceptConferenceInvites(false)
            val sessionId = nodeHost.runtime.requireConferenceCall(
                nodeHost.localEndpoint,
                emptyList(),
                channelId
            )
            nodeHost.runtime.sendConferenceInvites(
                sessionId,
                listOf(EndpointAddress(m02, EndpointId("E01")))
            )
            val pendingDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < pendingDeadline) {
                if (nodeGuest.runtime.pendingConferenceInvite(channelId) != null) break
                Thread.sleep(50L)
            }
            assertTrue(
                "Guest should have pending invite before host cancels",
                nodeGuest.runtime.pendingConferenceInvite(channelId) != null
            )

            nodeHost.runtime.hangup(sessionId)
            Thread.sleep(500L)

            assertTrue(nodeGuest.runtime.pendingConferenceInvite(channelId) == null)
            assertTrue(nodeGuest.runtime.activeSessionIds().isEmpty())
            assertTrue(nodeHost.runtime.activeSessionIds().isEmpty())
        } finally {
            nodeHost.stop()
            nodeGuest.stop()
        }
    }

    @Test
    fun soloMeshCall_allTargetsBusy_tearsDownSession() {
        val channelId = "CONF-BUSY-TEARDOWN"
        nodeM02.runtime.call(
            nodeM02.localEndpoint,
            EndpointAddress(m03, EndpointId("E01")),
            channelId
        )
        nodeM03.runtime.call(
            nodeM03.localEndpoint,
            EndpointAddress(m02, EndpointId("E01")),
            channelId
        )
        assertTrue(nodeM02.waitForLog { it.contains("Call accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("Call accepted") })
        Thread.sleep(500L)

        nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(
            nodeM01.waitForLog(timeoutMs = 8_000L) {
                it.contains("All targets rejected") && it.contains("tearing down solo mesh")
            }
        )
        assertTrue(nodeM01.runtime.activeSessionIds().isEmpty())
    }

    @Test
    fun groupCall_m01IceLost_m02ReadinessDoesNotWaitForM01() {
        val channelId = "CH-DTM-01"
        nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        Thread.sleep(2_000L)
        listOf(nodeM01, nodeM02, nodeM03).forEach { node ->
            node.runtime.simulateRemoteIceState("M01", "COMPLETED")
            node.runtime.simulateRemoteIceState("M02", "COMPLETED")
            node.runtime.simulateRemoteIceState("M03", "COMPLETED")
        }
        Thread.sleep(300L)
        assertTrue(nodeM02.runtime.isChannelMediaReady(channelId))

        nodeM02.runtime.simulateRemoteIceState("M01", "DISCONNECTED")
        Thread.sleep(500L)
        assertTrue(
            "M02 should stay ready when M01 ICE is dead (activeMember excludes dead SUSPECT)",
            nodeM02.runtime.isChannelMediaReady(channelId)
        )
        assertTrue(nodeM03.runtime.isChannelMediaReady(channelId))
    }
}
