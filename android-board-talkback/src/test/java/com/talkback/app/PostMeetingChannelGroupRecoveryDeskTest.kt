package com.talkback.app

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.governance.transition.MeetingMode
import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.SessionType
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 会后回频道 GROUP 恢复 — 桌面回归。
 * 夹具：M01 primary 建组，M03 主持会议并 hangup；M01 走远端退会后的生产恢复链。
 * 不主动调用 [TalkbackRuntime.reconcileGroupMesh]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PostMeetingChannelGroupRecoveryDeskTest {

    private val channelId = "CH-01"
    private val allMembers = listOf("M01", "M02", "M03", "M04", "M05")

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
        val peers = TestTalkbackNode.allPeers(m01 to 50501, m02 to 50502, m03 to 50503)
        nodeM01 = TestTalkbackNode(context, m01, 50501, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50502, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50503, hub, peers)
        listOf(nodeM01, nodeM02, nodeM03).forEach { node ->
            node.start()
            node.runtime.configureChannelMembership(channelId, allMembers)
        }
        Thread.sleep(200L)
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Test
    fun afterMeetingHangup_groupDestroyed_primaryCreatesExactlyOneMesh() {
        establishChannelGroupOnM01Primary()
        val conferenceId = startMeetingM03HostAndSettle()
        val baseline = captureM01HangupBaseline()
        val hangupLogMark = logSize(nodeM01)

        nodeM03.runtime.hangup(conferenceId)
        assertM01SawPostMeetingRelease(hangupLogMark)

        val observeStart = System.currentTimeMillis()
        val deadline = observeStart + 12_000L
        var primaryId: String? = null
        while (System.currentTimeMillis() < deadline) {
            waitNaturalRecoverySlice(150L)
            primaryId = m01AcceptedGroupSessionId()
            if (primaryId != null && memberJoinedGroup(nodeM02, primaryId)) break
        }
        val observationMs = System.currentTimeMillis() - observeStart
        if (primaryId == null || !memberJoinedGroup(nodeM02, primaryId!!)) {
            fail(buildM01PostHangupTimeline(baseline, hangupLogMark, observationMs))
        }

        val ids = setOf(
            requireNotNull(primaryId),
            requireNotNull(nodeM02.runtime.sessionSnapshotForChannel(channelId)?.sessionId),
        )
        assertEquals("M01 and M02 share one GROUP session", 1, ids.size)
    }

    @Test
    fun deferredBootstrapBarrier_meetingEndThenBarrierClears_recoversViaProductionPath() {
        establishChannelGroupOnM01Primary()
        val conferenceId = startMeetingM03HostAndSettle()
        nodeM01.runtime.testActivateHangupBootstrapBarrier(channelId)

        nodeM03.runtime.hangup(conferenceId)
        Thread.sleep(800L)
        assertNull(
            "M01 active accepted GROUP blocked by bootstrap defer",
            m01AcceptedGroupSessionId()
        )

        nodeM01.runtime.testClearHangupBootstrapBarrier(channelId)
        assertRecoveredGroupOnPrimaryWithin(12_000L)
        assertMemberJoinedPrimaryGroup(nodeM02)
    }

    @Test
    fun postMeetingRelease_groupSuspendedAccepted_primaryResumesOrRecreatesSingleMesh() {
        val groupId = establishChannelGroupOnM01Primary()
        assertTrue(nodeM01.runtime.testSuspendAcceptedGroupOnChannel(channelId))
        assertEquals(
            SessionDisposition.SUSPENDED,
            nodeM01.runtime.sessionPresenceSnapshot(groupId)?.disposition
        )

        nodeM01.runtime.testReleaseConferenceChannelForGroupPtt(channelId, "test_suspended_group")
        assertRecoveredGroupOnPrimaryWithin(12_000L)
        assertMemberJoinedPrimaryGroup(nodeM02)
    }

    @Test
    fun postMeetingRelease_groupSuspendedNotAccepted_doesNotCreateDuplicateMesh() {
        establishChannelGroupOnM01Primary()
        val pendingId = "pending-group-not-accepted"
        val injected = nodeM01.runtime.testInjectGroupInvite(
            callerModuleId = "M02",
            channelId = channelId,
            sessionId = pendingId,
            initiatorModuleId = "M02",
            fromPeer = TestTalkbackNode.peerTarget(nodeM02.port),
            memberModuleIds = allMembers
        )
        assertTrue(injected)
        Thread.sleep(200L)
        assertFalse("pending invite session not active on M01", pendingId in nodeM01.runtime.activeSessionIds())

        val mark = countLog(nodeM01) { it.contains("bootstrapAttemptReason=mesh_create") }
        nodeM01.runtime.testReleaseConferenceChannelForGroupPtt(channelId, "test_unaccepted_debris")
        waitNaturalRecovery(4_000L)
        val meshCreates = countLogSince(nodeM01, mark) {
            it.contains("bootstrapAttemptReason=mesh_create")
        }
        assertTrue("at most one bootstrap with unaccepted debris", meshCreates <= 1)
    }

    @Test
    fun explicitLeaveChannel_queuedRecoveryMustNotRecreateMesh() {
        establishChannelGroupOnM01Primary()
        val conferenceId = startMeetingM03HostAndSettle()
        val observeMark = nodeM01.runtime.testGroupRecoveryObserveEvents().size
        val hangupLogMark = logSize(nodeM01)

        nodeM03.runtime.hangup(conferenceId)
        assertM01SawPostMeetingRelease(hangupLogMark)

        val recoveryQueued = waitUntil(timeoutMs = 4_000L) {
            nodeM01.runtime.testGroupPttRecoveryPending(channelId) ||
                nodeM01.runtime.testGroupRecoveryObserveEvents()
                    .drop(observeMark)
                    .any { it.kind == "RECOVERY" && it.label == "REQUEST_SCHEDULED" }
        }
        assertTrue("M01 post-meeting recovery task should be queued", recoveryQueued)

        nodeM01.runtime.testLeaveChannelMembership(channelId)
        assertFalse("M01 left channel", nodeM01.runtime.testLocalMemberOfChannel(channelId))
        waitNaturalRecovery(8_000L)

        val activeOnChannel = nodeM01.runtime.activeSessionIds().any { id ->
            nodeM01.runtime.sessionSnapshots().any {
                it.sessionId == id && it.type == SessionType.GROUP && it.channelId == channelId
            }
        }
        assertFalse("M01 left channel must not create GROUP on channel", activeOnChannel)
    }

    @Test
    fun threeMeetingCycles_m03HostEachRound_m01PrimaryRecoversGroup() {
        repeat(3) { cycle ->
            establishChannelGroupOnM01Primary()
            val confId = startMeetingM03HostAndSettle()
            val reconcileBefore = nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount()

            val hangupMark = logSize(nodeM01)
            nodeM03.runtime.hangup(confId)
            assertM01SawPostMeetingRelease(hangupMark)

            assertRecoveredGroupOnPrimaryWithin(
                12_000L,
                "cycle=$cycle reconcileBefore=$reconcileBefore " +
                    m01RecoveryDiag()
            )

            if (cycle < 2) {
                val groupSnap = nodeM01.runtime.sessionSnapshotForChannel(channelId)
                assertNotNull(groupSnap)
                nodeM01.runtime.hangup(requireNotNull(groupSnap).sessionId)
                Thread.sleep(400L)
            }
        }
    }

    private fun establishChannelGroupOnM01Primary(): String {
        val remotes = listOf(
            EndpointAddress(m02, EndpointId("E01")),
            EndpointAddress(m03, EndpointId("E01"))
        )
        val sessionId = nodeM01.runtime.requireGroupCall(
            nodeM01.localEndpoint,
            remotes,
            channelId
        )
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM03.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        connectGroupAnchorIce(nodeM01, nodeM02, nodeM03, channelId)
        return sessionId
    }

    /** M03 主持；仅 host 须完成 MEETING_START（与 [TalkbackCoordinatorIntegrationTest] 一致）。 */
    private fun startMeetingM03HostAndSettle(): String {
        val invitees = listOf(
            EndpointAddress(m01, EndpointId("E01")),
            EndpointAddress(m02, EndpointId("E01"))
        )
        nodeM03.runtime.submitMeetingStartIntent(
            channelId,
            MeetingMode.MULTI_PARTY,
            invitees.map { it.endpointId }.toSet()
        )
        val sessionId = nodeM03.runtime.requireConferenceCall(
            nodeM03.localEndpoint,
            invitees,
            channelId
        )
        assertTrue(nodeM01.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })
        assertTrue(nodeM02.waitForLog(timeoutMs = 10_000L) { it.contains("invite accepted") })

        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            connectConferenceHostIce(nodeM03, nodeM01, nodeM02, hostModuleId = "M03")
            if (nodeM03.runtime.testGovernanceActiveTransitionTrigger(channelId) == null) {
                return sessionId
            }
            Thread.sleep(100L)
        }
        val stuckHost = nodeM03.runtime.testGovernanceActiveTransitionTrigger(channelId)
        val m01Gov = nodeM01.runtime.testGovernanceActiveTransitionTrigger(channelId)
        error("MEETING_START not settled on M03 host=$stuckHost m01Gov=$m01Gov")
    }

    private fun assertM01SawPostMeetingRelease(logMark: Int) {
        assertTrue(
            nodeM01.waitForLogSince(logMark, timeoutMs = 8_000L) {
                it.contains("Conference channel released for GROUP PTT ch=$channelId") ||
                    it.contains("CONFERENCE_TERMINATED ch=$channelId") ||
                    it.contains("MEETING_END_BEGIN")
            }
        )
    }

    private fun assertRecoveredGroupOnPrimaryWithin(timeoutMs: Long, detail: String = "") {
        val deadline = System.currentTimeMillis() + timeoutMs
        var primarySessionId: String? = null
        while (System.currentTimeMillis() < deadline) {
            waitNaturalRecoverySlice(150L)
            primarySessionId = m01AcceptedGroupSessionId()
            if (primarySessionId != null && memberJoinedGroup(nodeM02, primarySessionId)) break
        }
        assertNotNull(
            "M01 accepted GROUP on $channelId $detail ${m01RecoveryDiag()}",
            primarySessionId
        )
        assertTrue("M02 joined M01 GROUP $detail", memberJoinedGroup(nodeM02, primarySessionId!!))
        val ids = listOf(nodeM01, nodeM02, nodeM03).mapNotNull {
            it.runtime.sessionSnapshotForChannel(channelId)?.sessionId
        }.toSet()
        assertEquals("one canonical GROUP session id $detail", 1, ids.size)
    }

    private fun assertMemberJoinedPrimaryGroup(member: TestTalkbackNode) {
        val primaryId = m01AcceptedGroupSessionId()
        assertNotNull("M01 must have GROUP", primaryId)
        assertTrue("member joined primary GROUP", memberJoinedGroup(member, primaryId!!))
    }

    private fun m01AcceptedGroupSessionId(): String? {
        val snap = nodeM01.runtime.sessionSnapshotForChannel(channelId) ?: return null
        return snap.sessionId.takeIf { activeAcceptedGroup(nodeM01, it) }
    }

    private data class M01HangupBaseline(
        val reconcileInvocations: Int,
        val observeEventCount: Int,
        val recoveryPendingBeforeHangup: Boolean,
        val hangupBarrier: Boolean,
        val authorityState: String,
        val groupSnapshot: String,
        val conferenceActive: Boolean,
    )

    private fun captureM01HangupBaseline(): M01HangupBaseline {
        val rt = nodeM01.runtime
        return M01HangupBaseline(
            reconcileInvocations = rt.testReconcileGroupMeshInternalInvocationCount(),
            observeEventCount = rt.testGroupRecoveryObserveEvents().size,
            recoveryPendingBeforeHangup = rt.testGroupPttRecoveryPending(channelId),
            hangupBarrier = rt.testHangupBootstrapBarrierActive(channelId),
            authorityState = rt.testGroupAuthorityState(channelId),
            groupSnapshot = rt.testGroupSessionDeskSnapshot(channelId),
            conferenceActive = rt.testConferenceActiveOnChannel(channelId),
        )
    }

    private fun buildM01PostHangupTimeline(
        baseline: M01HangupBaseline,
        hangupLogMark: Int,
        observationMs: Long,
    ): String {
        val rt = nodeM01.runtime
        val reconcileTotal = rt.testReconcileGroupMeshInternalInvocationCount()
        val reconcileDelta = reconcileTotal - baseline.reconcileInvocations
        val recoveryPendingNow = rt.testGroupPttRecoveryPending(channelId)
        val newEvents = rt.testGroupRecoveryObserveEvents().drop(baseline.observeEventCount)

        val releaseLines = synchronized(nodeM01.logs) {
            nodeM01.logs.drop(hangupLogMark).filter {
                it.contains("Conference channel released") ||
                    it.contains("CONFERENCE_TERMINATED") ||
                    it.contains("MEETING_END_BEGIN")
            }
        }
        val channelReleasedObserved =
            newEvents.any { it.kind == "CHANNEL" && it.label == "CONFERENCE_RELEASED" } ||
                releaseLines.isNotEmpty()

        val recoveryRequests = newEvents.filter { it.kind == "RECOVERY" }
        val recoveryExecs = recoveryRequests.filter {
            it.label == "EXEC_STARTED" || it.label == "EXEC_EXIT" || it.label == "EXEC_INVOKING_RECONCILE"
        }
        val execStarted = recoveryExecs.any { it.label == "EXEC_STARTED" }
        val execInvokedReconcile = recoveryExecs.any { it.label == "EXEC_INVOKING_RECONCILE" }

        val reconcileExits = newEvents.filter { it.kind == "RECONCILE_EXIT" }
        val meshCreateLogDelta = countLogSince(nodeM01, hangupLogMark) {
            it.contains("bootstrapAttemptReason=mesh_create")
        }
        val meshCreateObserved = reconcileExits.any {
            it.label == "BOOTSTRAP_MESH_CREATE_OBSERVED"
        }

        val primaryId = m01AcceptedGroupSessionId()
        val m02Session = nodeM02.runtime.sessionSnapshotForChannel(channelId)?.sessionId
        val m02Joined = primaryId != null && memberJoinedGroup(nodeM02, primaryId)

        val sb = StringBuilder()
        sb.appendLine("M01 post-hangup recovery timeline (observationMs=$observationMs)")
        sb.appendLine("--- baseline (pre-hangup) ---")
        sb.appendLine("reconcileInvocations=${baseline.reconcileInvocations} group=${baseline.groupSnapshot}")
        sb.appendLine("authority=${baseline.authorityState} confActive=${baseline.conferenceActive}")
        sb.appendLine(
            "recoveryPendingBeforeHangup=${baseline.recoveryPendingBeforeHangup} " +
                "hangupBarrier=${baseline.hangupBarrier}"
        )
        sb.appendLine("--- channel release ---")
        sb.appendLine(
            if (channelReleasedObserved) "observed" else "UNKNOWN (no release log/observe in window)"
        )
        releaseLines.take(3).forEach { sb.appendLine("  log: $it") }
        sb.appendLine("--- recovery task (request vs execution) ---")
        sb.appendLine("recoveryPendingNow=$recoveryPendingNow")
        if (recoveryRequests.isEmpty()) {
            sb.appendLine("requests: UNKNOWN (no SCHEDULE_REQUESTED / DEDUPED / SCHEDULED after hangup)")
        } else {
            recoveryRequests.forEach { e ->
                sb.appendLine("  #${e.sequence} ${e.label} ${e.detail}")
            }
        }
        if (!execStarted) {
            sb.appendLine("execution: 截至 ${observationMs}ms 未观察到 EXEC_STARTED")
        } else if (!execInvokedReconcile) {
            sb.appendLine("execution: EXEC_STARTED but 未观察到 EXEC_INVOKING_RECONCILE (see EXEC_EXIT)")
        } else {
            sb.appendLine("execution: reconcile invoked from recovery task")
        }
        sb.appendLine("--- reconcile exits (post-hangup delta=$reconcileDelta, ordered) ---")
        if (reconcileExits.isEmpty()) {
            sb.appendLine("none (reconcileDelta=$reconcileDelta)")
        } else {
            reconcileExits.forEach { e ->
                sb.appendLine("  #${e.sequence} ${e.label} | ${e.detail}")
            }
        }
        sb.appendLine("--- bootstrap signal ---")
        sb.appendLine(
            "mesh_create observe=${meshCreateObserved} logDeltaSinceHangup=$meshCreateLogDelta"
        )
        sb.appendLine("--- final GROUP ---")
        sb.appendLine("M01 acceptedGroup=${primaryId ?: "none"} auth=${rt.testGroupAuthorityState(channelId)}")
        sb.appendLine(
            "groupSnap=${rt.testGroupSessionDeskSnapshot(channelId)} confActive=${rt.testConferenceActiveOnChannel(channelId)}"
        )
        sb.appendLine("M02 channelSession=$m02Session joinedPrimary=$m02Joined")
        return sb.toString()
    }

    private fun m01RecoveryDiag(): String {
        val reconciles = nodeM01.runtime.testReconcileGroupMeshInternalInvocationCount()
        val hasReleased = nodeM01.hasLog {
            it.contains("Conference channel released for GROUP PTT ch=$channelId")
        }
        val hasMeshCreate = nodeM01.hasLog { it.contains("bootstrapAttemptReason=mesh_create") }
        val hasWaiting = nodeM01.hasLog { it.contains("Waiting for primary") }
        val gov = nodeM01.runtime.testGovernanceActiveTransitionTrigger(channelId)
        return "diag{gov=$gov released=$hasReleased meshCreate=$hasMeshCreate waiting=$hasWaiting reconciles=$reconciles}"
    }

    private fun memberJoinedGroup(node: TestTalkbackNode, sessionId: String): Boolean {
        if (sessionId !in node.runtime.activeSessionIds()) return false
        val snap = node.runtime.sessionSnapshots().firstOrNull { it.sessionId == sessionId } ?: return false
        return snap.type == SessionType.GROUP && snap.memberKeys.size >= 2
    }

    private fun activeAcceptedGroup(node: TestTalkbackNode, sessionId: String): Boolean {
        if (sessionId !in node.runtime.activeSessionIds()) return false
        val disposition = node.runtime.sessionPresenceSnapshot(sessionId)?.disposition
        return disposition != SessionDisposition.TERMINATED &&
            disposition != SessionDisposition.SUSPENDED
    }

    private fun waitNaturalRecovery(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            waitNaturalRecoverySlice(100L)
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50L)
        }
        return predicate()
    }

    private fun waitNaturalRecoverySlice(sliceMs: Long) {
        listOf(nodeM01, nodeM02, nodeM03).forEach {
            it.refreshDiscovery()
            it.runtime.rebroadcastHello()
        }
        Thread.sleep(sliceMs)
    }

    private fun logSize(node: TestTalkbackNode): Int =
        synchronized(node.logs) { node.logs.size }

    private fun countLog(node: TestTalkbackNode, predicate: (String) -> Boolean): Int =
        synchronized(node.logs) { node.logs.count(predicate) }

    private fun countLogSince(node: TestTalkbackNode, fromIndex: Int, predicate: (String) -> Boolean): Int =
        synchronized(node.logs) { node.logs.drop(fromIndex).count(predicate) }
}

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