package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.InviteState
import com.talkback.core.session.ObligationCloseReason
import com.talkback.core.session.SessionType
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferencePruneIntegrationTest {
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
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        nodeM01 = TestTalkbackNode(context, m01, 50051, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50052, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50053, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
        Thread.sleep(300L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun conferencePrune_doesNotRemoveInvitedOnlyPeerWithStaleIce() {
        val channelId = "CONF-PRUNE-INVITED"
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(
            nodeM02.waitForLog(timeoutMs = 8_000L) {
                it.contains("Conference invite accepted") || it.contains("invite accepted")
            }
        )
        val before = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertTrue(before.memberKeys.any { it.startsWith("M03") })

        nodeM02.runtime.simulateRemoteIceState("M03", "CLOSED")
        nodeM02.runtime.testRunConferenceHealthCleanup(channelId)

        assertFalse(nodeM02.hasLog { it.contains("Pruning unhealthy conference peer M03") })
        val after = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertTrue(after.memberKeys.any { it.startsWith("M03") })
        val m03View = after.memberViews.firstOrNull { it.moduleId == "M03" }
        assertTrue(m03View == null || m03View.invite != InviteState.ACCEPTED)
    }

    @Test
    fun conferenceLateJoin_restoresRosterAfterMeshLinkAccepted() {
        val channelId = "CONF-LATE-JOIN"
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        nodeM03.runtime.setAutoAcceptConferenceInvites(true)
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })

        nodeM02.runtime.simulateRemoteIceState("M03", "CLOSED")
        nodeM02.runtime.testRunConferenceHealthCleanup(channelId)
        assertFalse(nodeM02.hasLog { it.contains("Pruning unhealthy conference peer M03") })

        nodeM03.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(500L)

        val snapshot = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(SessionType.CONFERENCE, snapshot.type)
        assertTrue(snapshot.memberKeys.any { it.startsWith("M03") })
    }

    @Test
    fun conferenceRosterSplit_lateJoinRestoresThreeMembers() {
        val channelId = "CONF-ROSTER-SPLIT"
        nodeM02.runtime.setAutoAcceptConferenceInvites(true)
        nodeM03.runtime.setAutoAcceptConferenceInvites(true)
        val sessionId = nodeM01.runtime.conferenceCall(
            nodeM01.localEndpoint,
            listOf(
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            channelId
        )
        assertNotNull(sessionId)
        assertTrue(nodeM02.waitForLog { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog { it.contains("invite accepted") })
        nodeM01.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM01.runtime.simulateRemoteIceState("M03", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M01", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(300L)

        val baseline = nodeM01.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(2, baseline.memberViews.size)
        assertTrue(baseline.memberKeys.any { it.startsWith("M02") })
        assertTrue(baseline.memberKeys.any { it.startsWith("M03") })

        nodeM02.runtime.simulateRemoteIceState("M03", "CLOSED")
        nodeM02.runtime.testRunConferenceHealthCleanup(channelId)
        val split = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals("roster must stay 2/3 during split", 2, split.memberViews.size)
        assertTrue(split.memberKeys.any { it.startsWith("M03") })
        assertFalse(nodeM02.hasLog { it.contains("Pruning unhealthy conference peer M03") })

        nodeM03.runtime.simulateRemoteIceState("M02", "CONNECTED")
        nodeM02.runtime.simulateRemoteIceState("M03", "CONNECTED")
        Thread.sleep(500L)

        val restored = nodeM02.runtime.sessionSnapshots().first { it.sessionId == sessionId }
        assertEquals(SessionType.CONFERENCE, restored.type)
        assertEquals(2, restored.memberViews.size)
        assertTrue(restored.memberKeys.any { it.startsWith("M03") })
    }

    @Test
    fun conferenceR29_participantHealthCleanup_doesNotMutateMembership() {
        val channelId = "CONF-R29-PARTICIPANT"
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        val host = TestTalkbackNode(
            context, m02, 50052, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val participant = TestTalkbackNode(
            context, m01, 50051, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val peer = TestTalkbackNode(
            context, m03, 50053, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        host.start()
        participant.start()
        peer.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            peer.runtime.setAutoAcceptConferenceInvites(true)
            participant.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(participant.waitForLog { it.contains("invite accepted") })
            assertTrue(peer.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, participant, peer, hostModuleId = "M02")
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            peer.runtime.simulateRemoteIceState("M01", "CONNECTED")
            Thread.sleep(500L)
            listOf(host, participant, peer).forEach {
                it.runtime.testSeedAuthorityDigestForChannel(channelId)
            }

            val baselineDeadline = System.currentTimeMillis() + 8_000L
            var baseline = participant.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            while (System.currentTimeMillis() < baselineDeadline) {
                baseline = participant.runtime.sessionSnapshots().first { it.sessionId == sessionId }
                if (baseline.memberKeys.any { it.startsWith("M03") }) break
                Thread.sleep(100L)
            }
            val baselineKeys = baseline.memberKeys.toSet()
            assertTrue(baselineKeys.any { it.startsWith("M03") })

            val degradeMark = synchronized(participant.logs) { participant.logs.size }
            participant.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
            assertTrue(
                participant.waitForLogSince(degradeMark, timeoutMs = 8_000L) {
                    (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                        it.contains("remote=M03")
                }
            )
            participant.runtime.testRunConferenceHealthCleanup(channelId)

            val after = participant.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertEquals(baselineKeys, after.memberKeys.toSet())
            assertFalse(participant.hasLog { it.contains("Pruning unhealthy conference peer M03") })
            assertFalse(
                participant.hasLog {
                    it.contains("RECOVERY_EDGE_CANCELLED") && it.contains("remote=M03") && it.contains("member_left")
                }
            )
            assertTrue(
                participant.hasLog { it.contains("RECOVERY_MEDIA_DEGRADED") && it.contains("remote=M03") }
            )
            val facts = participant.runtime.testEdgeRecoveryFacts(sessionId!!)
            assertTrue(facts.failedRemoteModuleIds.contains("M03"))
        } finally {
            participant.stop()
            host.stop()
            peer.stop()
        }
    }

    @Test
    fun conferenceR29E_hostDoesNotAuthorityPruneWhileObligationOpen() {
        // G-R28-H1 / G-R29-E1: FAILED_MEDIA_RECOVERY keeps obligation OPEN;
        // !isEdgeRecovering alone must not authorize AUTHORITY_PRUNE.
        val channelId = "CONF-R29-HOST-PRUNE"
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        val host = TestTalkbackNode(
            context, m02, 50052, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val participant = TestTalkbackNode(
            context, m01, 50051, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val peer = TestTalkbackNode(
            context, m03, 50053, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        host.start()
        participant.start()
        peer.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            participant.runtime.setAutoAcceptConferenceInvites(true)
            peer.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(participant.waitForLog { it.contains("invite accepted") })
            assertTrue(peer.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, participant, peer, hostModuleId = "M02")
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            peer.runtime.simulateRemoteIceState("M01", "CONNECTED")
            host.runtime.simulateRemoteIceState("M03", "CONNECTED")
            Thread.sleep(500L)

            val baseline = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(baseline.memberKeys.any { it.startsWith("M03") })

            val pruneMark = synchronized(host.logs) { host.logs.size }
            host.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
            assertTrue(
                host.waitForLogSince(pruneMark, timeoutMs = 8_000L) {
                    (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                        it.contains("remote=M03")
                }
            )
            assertTrue(host.runtime.testEdgeObligationOpen(sessionId!!, "M03"))
            assertFalse(host.runtime.testEdgeObligationClosed(sessionId, "M03"))
            assertFalse(host.runtime.testIsEdgeRecovering(sessionId, "M03"))

            host.runtime.testRunConferenceHealthCleanup(channelId)

            assertFalse(host.hasLog { it.contains("Pruning unhealthy conference peer M03") })
            assertFalse(host.hasLog { it.contains("source=AUTHORITY_PRUNE") })
            val after = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(after.memberKeys.any { it.startsWith("M03") })
            assertTrue(host.runtime.testEdgeObligationOpen(sessionId, "M03"))
        } finally {
            participant.stop()
            host.stop()
            peer.stop()
        }
    }

    @Test
    fun conferenceR28H2_materialReevalKeepsObligationOpenWithoutPrune() {
        // G-R28-H2 / G-R29-E2: inside observation window, material transition MUST
        // RECOVERY_REEVALUATE; obligation stays OPEN; host MUST NOT AUTHORITY_PRUNE.
        val channelId = "CONF-R28-H2-REEVAL"
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        val host = TestTalkbackNode(
            context, m02, 50052, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val participant = TestTalkbackNode(
            context, m01, 50051, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val peer = TestTalkbackNode(
            context, m03, 50053, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        host.start()
        participant.start()
        peer.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            participant.runtime.setAutoAcceptConferenceInvites(true)
            peer.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(participant.waitForLog { it.contains("invite accepted") })
            assertTrue(peer.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, participant, peer, hostModuleId = "M02")
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            peer.runtime.simulateRemoteIceState("M01", "CONNECTED")
            host.runtime.simulateRemoteIceState("M03", "CONNECTED")
            Thread.sleep(500L)

            val failMark = synchronized(host.logs) { host.logs.size }
            host.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
            assertTrue(
                host.waitForLogSince(failMark, timeoutMs = 8_000L) {
                    (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                        it.contains("remote=M03")
                }
            )
            assertTrue(host.runtime.testEdgeObligationOpen(sessionId!!, "M03"))
            val failedAttemptId = synchronized(host.logs) {
                host.logs.drop(failMark)
                    .last {
                        (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                            it.contains("remote=M03")
                    }
                    .substringAfter("attempt=")
                    .substringBefore(' ')
                    .toLong()
            }

            val reevalMark = synchronized(host.logs) { host.logs.size }
            host.runtime.simulateRemoteIceState("M03", "CONNECTED")
            assertTrue(
                host.waitForLogSince(reevalMark, timeoutMs = 5_000L) {
                    it.contains("RECOVERY_REEVALUATE") &&
                        it.contains("edge=M03") &&
                        it.contains("trigger=ROUTE_CONVERGED")
                }
            )
            assertTrue(
                host.waitForLogSince(reevalMark, timeoutMs = 5_000L) {
                    (it.contains("decision=SUPERSEDED") && it.contains("edge=M03")) ||
                        (it.contains("RECOVERY_EDGE_RECOVERED") && it.contains("remote=M03")) ||
                        (it.contains("RECOVERY_DECISION") && it.contains("edge=M03") &&
                            it.contains("decision=DISPATCH_REATTACH"))
                }
            )
            val afterReevalHead = synchronized(host.logs) { host.logs.drop(reevalMark) }
            val edgeRecovered = afterReevalHead.any {
                it.contains("RECOVERY_EDGE_RECOVERED") && it.contains("remote=M03")
            }
            assertTrue(
                "obligation OPEN or edge RECOVERED after re-evaluate",
                host.runtime.testEdgeObligationOpen(sessionId, "M03") || edgeRecovered
            )
            if (!edgeRecovered) {
                assertFalse(host.runtime.testEdgeObligationClosed(sessionId, "M03"))
            }

            host.runtime.testRunConferenceHealthCleanup(channelId)
            assertFalse(host.hasLog { it.contains("Pruning unhealthy conference peer M03") })
            assertFalse(host.hasLog { it.contains("source=AUTHORITY_PRUNE") })
            assertTrue(
                host.runtime.testEdgeObligationOpen(sessionId, "M03") || edgeRecovered
            )

            val afterReeval = afterReevalHead
            assertFalse(
                "failed attempt id must not become active again",
                afterReeval.any {
                    it.contains("attempt=$failedAttemptId") &&
                        (
                            it.contains("RECOVERY_REATTACH_REQUESTED") ||
                                it.contains("RECOVERY_EDGE_STARTED") ||
                                it.contains("decision=DISPATCH_REATTACH")
                            )
                }
            )
            val after = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(after.memberKeys.any { it.startsWith("M03") })
        } finally {
            participant.stop()
            host.stop()
            peer.stop()
        }
    }

    @Test
    fun conferenceR29E_hostMayAuthorityPruneAfterObligationDeadline() {
        // G-R28-H3 / G-R29-E3: past controller deadline → CLOSED(OBLIGATION_DEADLINE)
        // → canAuthorityPrune may become true; non-deadline reasons stay off this path.
        val channelId = "CONF-R29-DEADLINE-PRUNE"
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        val host = TestTalkbackNode(
            context, m02, 50052, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 200L
        )
        val participant = TestTalkbackNode(
            context, m01, 50051, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 200L
        )
        val peer = TestTalkbackNode(
            context, m03, 50053, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 200L
        )
        host.start()
        participant.start()
        peer.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            participant.runtime.setAutoAcceptConferenceInvites(true)
            peer.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(participant.waitForLog { it.contains("invite accepted") })
            assertTrue(peer.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, participant, peer, hostModuleId = "M02")
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            peer.runtime.simulateRemoteIceState("M01", "CONNECTED")
            host.runtime.simulateRemoteIceState("M03", "CONNECTED")
            Thread.sleep(500L)

            val baseline = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(baseline.memberKeys.any { it.startsWith("M03") })

            val failMark = synchronized(host.logs) { host.logs.size }
            host.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
            assertTrue(
                host.waitForLogSince(failMark, timeoutMs = 8_000L) {
                    (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                        it.contains("remote=M03")
                }
            )
            assertNotNull(host.runtime.testObligationDeadlineAt(sessionId!!, "M03"))
            assertTrue(host.runtime.testEdgeObligationOpen(sessionId, "M03"))

            host.runtime.testRunConferenceHealthCleanup(channelId)
            assertFalse(host.hasLog { it.contains("Pruning unhealthy conference peer M03") })

            assertTrue(
                host.waitForLogSince(failMark, timeoutMs = 5_000L) {
                    it.contains("RECOVERY_OBLIGATION_CLOSED") &&
                        it.contains("remote=M03") &&
                        it.contains("reason=OBLIGATION_DEADLINE")
                }
            )
            assertTrue(host.runtime.testEdgeObligationClosed(sessionId, "M03"))
            assertEquals(
                ObligationCloseReason.OBLIGATION_DEADLINE,
                host.runtime.testObligationCloseReason(sessionId, "M03")
            )
            assertTrue(
                host.runtime.testObligationCloseReason(sessionId, "M03")!!.isPruneEligible()
            )

            host.runtime.testRunConferenceHealthCleanup(channelId)

            assertTrue(host.hasLog { it.contains("Pruning unhealthy conference peer M03") })
            assertTrue(host.hasLog { it.contains("source=AUTHORITY_PRUNE") })
            val after = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertFalse(after.memberKeys.any { it.startsWith("M03") })
        } finally {
            participant.stop()
            host.stop()
            peer.stop()
        }
    }

    @Test
    fun conferenceR29E4_authorityPruneConvergesJoinedCountAndRosterEpoch() {
        // G-R29-E4 / R29-E.3: after AUTHORITY_PRUNE commits and propagates,
        // host.joinedCount == participant.joinedCount AND same rosterEpoch.
        // Count-equal with divergent roster version = FAIL (host=2 / participant=3 false-pass).
        val channelId = "CONF-R29-E4-CONVERGE"
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        val host = TestTalkbackNode(
            context, m02, 50052, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 200L
        )
        val participant = TestTalkbackNode(
            context, m01, 50051, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 200L
        )
        val peer = TestTalkbackNode(
            context, m03, 50053, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 200L
        )
        host.start()
        participant.start()
        peer.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            participant.runtime.setAutoAcceptConferenceInvites(true)
            peer.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(participant.waitForLog { it.contains("invite accepted") })
            assertTrue(peer.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, participant, peer, hostModuleId = "M02")
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            peer.runtime.simulateRemoteIceState("M01", "CONNECTED")
            host.runtime.simulateRemoteIceState("M03", "CONNECTED")
            Thread.sleep(500L)

            val failMark = synchronized(host.logs) { host.logs.size }
            val participantMark = synchronized(participant.logs) { participant.logs.size }
            host.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
            assertTrue(
                host.waitForLogSince(failMark, timeoutMs = 8_000L) {
                    (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                        it.contains("remote=M03")
                }
            )
            assertTrue(
                host.waitForLogSince(failMark, timeoutMs = 5_000L) {
                    it.contains("RECOVERY_OBLIGATION_CLOSED") &&
                        it.contains("remote=M03") &&
                        it.contains("reason=OBLIGATION_DEADLINE")
                }
            )

            host.runtime.testRunConferenceHealthCleanup(channelId)
            assertTrue(host.hasLog { it.contains("source=AUTHORITY_PRUNE") })

            assertTrue(
                participant.waitForLogSince(participantMark, timeoutMs = 5_000L) {
                    it.contains("Conference peer left: M03") ||
                        it.contains("source=AUTHORITY_GROUP_LEAVE") && it.contains("remote=M03")
                }
            )

            val convergeDeadline = System.currentTimeMillis() + 5_000L
            var hostSnap = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            var participantSnap =
                participant.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            var hostEpoch = host.runtime.testConferenceMembershipEpoch(sessionId!!)
            var participantEpoch = participant.runtime.testConferenceMembershipEpoch(sessionId)
            while (System.currentTimeMillis() < convergeDeadline) {
                hostSnap = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
                participantSnap =
                    participant.runtime.sessionSnapshots().first { it.sessionId == sessionId }
                hostEpoch = host.runtime.testConferenceMembershipEpoch(sessionId)
                participantEpoch = participant.runtime.testConferenceMembershipEpoch(sessionId)
                val hostJoined = hostSnap.conferencePresenceProjection?.joinedCount
                    ?: hostSnap.joinedParticipantCount
                val participantJoined = participantSnap.conferencePresenceProjection?.joinedCount
                    ?: participantSnap.joinedParticipantCount
                if (
                    !hostSnap.memberKeys.any { it.startsWith("M03") } &&
                    !participantSnap.memberKeys.any { it.startsWith("M03") } &&
                    hostJoined == participantJoined &&
                    hostEpoch > 0L &&
                    hostEpoch == participantEpoch
                ) {
                    break
                }
                Thread.sleep(100L)
            }

            val hostJoined = hostSnap.conferencePresenceProjection?.joinedCount
                ?: hostSnap.joinedParticipantCount
            val participantJoined = participantSnap.conferencePresenceProjection?.joinedCount
                ?: participantSnap.joinedParticipantCount
            assertFalse(hostSnap.memberKeys.any { it.startsWith("M03") })
            assertFalse(participantSnap.memberKeys.any { it.startsWith("M03") })
            assertEquals(hostJoined, participantJoined)
            assertTrue("rosterEpoch must advance on authority prune", hostEpoch > 0L)
            assertEquals(
                "G-R29-E4: count-equal with divergent roster version is FAIL",
                hostEpoch,
                participantEpoch
            )
        } finally {
            participant.stop()
            host.stop()
            peer.stop()
        }
    }

    @Test
    fun conferenceR29_participantPreservesEdgeObligationDuringRecovery() {
        val channelId = "CONF-R29-EDGE-OBLIGATION"
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50051, m02 to 50052, m03 to 50053)
        val host = TestTalkbackNode(
            context, m02, 50052, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val participant = TestTalkbackNode(
            context, m01, 50051, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        val peer = TestTalkbackNode(
            context, m03, 50053, hub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L
        )
        host.start()
        participant.start()
        peer.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            peer.runtime.setAutoAcceptConferenceInvites(true)
            participant.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m01, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(participant.waitForLog { it.contains("invite accepted") })
            assertTrue(peer.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, participant, peer, hostModuleId = "M02")
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            peer.runtime.simulateRemoteIceState("M01", "CONNECTED")
            Thread.sleep(300L)

            val logMark = synchronized(participant.logs) { participant.logs.size }
            participant.runtime.simulateRemoteIceState("M03", "DISCONNECTED")
            assertTrue(
                participant.waitForLogSince(logMark, timeoutMs = 8_000L) {
                    it.contains("RECOVERY_EDGE_STARTED") && it.contains("remote=M03")
                }
            )
            participant.runtime.testRunConferenceHealthCleanup(channelId)
            assertFalse(
                participant.hasLog {
                    it.contains("RECOVERY_EDGE_CANCELLED") && it.contains("remote=M03") && it.contains("member_left")
                }
            )
            participant.runtime.simulateRemoteIceState("M03", "CONNECTED")
            assertTrue(
                participant.waitForLogSince(logMark, timeoutMs = 8_000L) {
                    it.contains("RECOVERY_REEVALUATE") && it.contains("edge=M03")
                }
            )
        } finally {
            participant.stop()
            host.stop()
            peer.stop()
        }
    }

    @Test
    fun conferenceR28H_failedResurrection_lateCheckingRevivesAttemptAndPreventsPrune() {
        // ADR-0022: FAILED is not terminal while obligation OPEN.
        // Soak regression (M01 host, M02 Wi-Fi loss): FAILED → HELLO + CHECKING must
        // REEVALUATE, supersede, and start Attempt N+1 — not silently wait for prune.
        val channelId = "CONF-R28H-RESURRECT"
        val context = RuntimeEnvironment.getApplication()
        val localHub = InMemorySignalingHub()
        val peers = TestTalkbackNode.allPeers(m01 to 50251, m02 to 50252, m03 to 50253)
        val host = TestTalkbackNode(
            context, m01, 50251, localHub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 60_000L,
            autoReDialOnModuleRecovery = false
        )
        val peerM02 = TestTalkbackNode(
            context, m02, 50252, localHub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 60_000L,
            autoReDialOnModuleRecovery = false
        )
        val peerM03 = TestTalkbackNode(
            context, m03, 50253, localHub, peers,
            meshNegotiationGraceMs = 0L,
            edgeRecoveryAttemptBudgetMs = 500L,
            edgeRecoveryObservationWindowMs = 60_000L,
            autoReDialOnModuleRecovery = false
        )
        host.start()
        peerM02.start()
        peerM03.start()
        Thread.sleep(300L)
        try {
            host.runtime.setAutoAcceptConferenceInvites(true)
            peerM02.runtime.setAutoAcceptConferenceInvites(true)
            peerM03.runtime.setAutoAcceptConferenceInvites(true)
            val sessionId = host.runtime.conferenceCall(
                host.localEndpoint,
                listOf(
                    EndpointAddress(m02, EndpointId("E01")),
                    EndpointAddress(m03, EndpointId("E01"))
                ),
                channelId
            )
            assertNotNull(sessionId)
            assertTrue(peerM02.waitForLog { it.contains("invite accepted") })
            assertTrue(peerM03.waitForLog { it.contains("invite accepted") })
            connectConferenceHostIce(host, peerM02, peerM03)
            host.runtime.simulateRemoteIceState("M03", "CONNECTED")
            Thread.sleep(500L)

            val failMark = synchronized(host.logs) { host.logs.size }
            host.runtime.simulateRemoteIceState("M02", "DISCONNECTED")
            assertTrue(
                host.waitForLogSince(failMark, timeoutMs = 12_000L) {
                    (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                        it.contains("remote=M02")
                }
            )
            assertTrue(host.runtime.testEdgeObligationOpen(sessionId!!, "M02"))
            assertFalse(host.runtime.testEdgeObligationClosed(sessionId, "M02"))
            val failedAttemptId = synchronized(host.logs) {
                host.logs.drop(failMark)
                    .last {
                        (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                            it.contains("remote=M02")
                    }
                    .substringAfter("attempt=")
                    .substringBefore(' ')
                    .toLong()
            }

            val reviveMark = synchronized(host.logs) { host.logs.size }
            // CHECKING alone is sufficient resurrection evidence (soak also had HELLO).
            host.runtime.simulateRemoteIceState("M02", "CHECKING")

            assertTrue(
                host.waitForLogSince(reviveMark, timeoutMs = 5_000L) {
                    it.contains("RECOVERY_REEVALUATE") && it.contains("edge=M02")
                }
            )
            val afterRevive = synchronized(host.logs) { host.logs.drop(reviveMark) }
            val newAttemptId = afterRevive.mapNotNull { line ->
                if (!line.contains("remote=M02") && !line.contains("edge=M02")) return@mapNotNull null
                Regex("attempt=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            }.maxOrNull()
            assertNotNull("late evidence must emit an attempt id for M02", newAttemptId)
            assertTrue(
                "late evidence must start Attempt N+1 (failed=$failedAttemptId, new=$newAttemptId)",
                newAttemptId!! > failedAttemptId
            )
            assertTrue(
                afterRevive.any {
                    (it.contains("RECOVERY_EDGE_STARTED") || it.contains("decision=SUPERSEDED")) &&
                        (it.contains("remote=M02") || it.contains("edge=M02"))
                }
            )

            assertTrue(host.runtime.testEdgeObligationOpen(sessionId, "M02"))
            val snapshot = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(snapshot.memberKeys.any { it.startsWith("M02") })
            val joined = snapshot.conferencePresenceProjection?.joinedCount
                ?: snapshot.joinedParticipantCount
            assertEquals(3, joined)

            host.runtime.testRunConferenceHealthCleanup(channelId)
            assertFalse(host.hasLog { it.contains("Pruning unhealthy conference peer M02") })
            assertFalse(host.hasLog { it.contains("source=AUTHORITY_PRUNE") && it.contains("remote=M02") })
            val afterCleanup = host.runtime.sessionSnapshots().first { it.sessionId == sessionId }
            assertTrue(afterCleanup.memberKeys.any { it.startsWith("M02") })
        } finally {
            host.stop()
            peerM02.stop()
            peerM03.stop()
        }
    }
}
