package com.talkback.core.util

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.governance.transition.TransitionId
import com.talkback.governance.transition.TransitionPhase
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionTerminalState
import com.talkback.governance.transition.TransitionTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupTransitionReadinessLogTest {

    @After
    fun tearDown() {
        GroupTransitionReadinessLog.resetForTest()
    }

    @Test
    fun orphanBelief_trueWhenLocalInitiatorIsNotBootstrapPrimaryAndNotAdmitted() {
        val session = groupSession(initiator = "M02", local = "M02")
        val belief = GroupTransitionReadinessLog.computeOrphanBelief(
            localModuleId = "M02",
            groupSession = session,
            resolvedBootstrapPrimaryModuleId = "M01",
            primaryMeshAdmissionObserved = false
        )
        assertTrue(belief)
    }

    @Test
    fun orphanBelief_falseWhenJoinedPrimaryMesh() {
        val session = groupSession(initiator = "M01", local = "M02")
        val belief = GroupTransitionReadinessLog.computeOrphanBelief(
            localModuleId = "M02",
            groupSession = session,
            resolvedBootstrapPrimaryModuleId = "M01",
            primaryMeshAdmissionObserved = true
        )
        assertFalse(belief)
    }

    @Test
    fun orphanBelief_falseWhenLocalIsBootstrapPrimary() {
        val session = groupSession(initiator = "M01", local = "M01")
        val belief = GroupTransitionReadinessLog.computeOrphanBelief(
            localModuleId = "M01",
            groupSession = session,
            resolvedBootstrapPrimaryModuleId = "M01",
            primaryMeshAdmissionObserved = true
        )
        assertFalse(belief)
    }

    @Test
    fun primaryMeshAdmissionObserved_whenInitiatorMatchesPrimary() {
        val session = groupSession(initiator = "M01", local = "M02")
        val admitted = GroupTransitionReadinessLog.computePrimaryMeshAdmissionObserved(
            groupSession = session,
            resolvedBootstrapPrimaryModuleId = "M01",
            localModuleId = "M02"
        )
        assertTrue(admitted)
    }

    @Test
    fun primaryResolve_incrementsNoMutationWhenStateStable() {
        val channelId = "CH-01"
        val snapshotA = snapshot(channelId, traceId = "aaaa", primary = "M01", joined = listOf("M01", "M02"))
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M01",
            reason = "test",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = snapshotA
        )
        GroupTransitionReadinessLog.onPrimaryResolve(channelId, "M01", "M01", snapshotA)
        GroupTransitionReadinessLog.onPrimaryResolve(channelId, "M01", "M01", snapshotA)
        assertEquals(0, GroupTransitionReadinessLog.primaryChangeCountForTest(channelId))
    }

    @Test
    fun primaryResolve_countsPrimaryChange() {
        val channelId = "CH-01"
        val baseline = snapshot(channelId, traceId = null, primary = "M01", joined = emptyList())
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M02",
            reason = "test",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = baseline
        )
        val first = snapshot(channelId, traceId = "aaaa", primary = "M01", joined = listOf("M01"))
        GroupTransitionReadinessLog.onPrimaryResolve(channelId, "M02", "M01", first)
        val changed = snapshot(channelId, traceId = "bbbb", primary = "M02", joined = listOf("M01", "M02"))
        GroupTransitionReadinessLog.onPrimaryResolve(channelId, "M02", "M02", changed)
        assertEquals(1, GroupTransitionReadinessLog.primaryChangeCountForTest(channelId))
    }

    @Test
    fun meetingEndBegin_assignsLineageAndBaseline() {
        val channelId = "CH-01"
        val snapshot = snapshot(channelId, traceId = null, primary = "M01", joined = emptyList())
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M01",
            reason = "host_hangup",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = snapshot
        )
        assertEquals(listOf("M01", "M02", "M03"), GroupTransitionReadinessLog.baselineMembers(channelId))
        assertTrue(!GroupTransitionReadinessLog.sessionLineageId(channelId).isNullOrBlank())
    }

    @Test
    fun terminalAuthority_primaryEmitsCanonicalDecision() {
        val channelId = "CH-01"
        val snap = snapshot(channelId, traceId = "aaaa", primary = "M01", joined = listOf("M01", "M02", "M03"))
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M01",
            reason = "test",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = snap
        )
        GroupTransitionReadinessLog.onTransitionTerminalReady(
            channelId = channelId,
            moduleId = "M01",
            record = meetingEndRecord(channelId),
            snapshot = snap
        )
        assertEquals(
            GroupTransitionReadinessLog.TerminalAuthority.CANONICAL,
            GroupTransitionReadinessLog.classifyTerminalAuthority("M01", snap)
        )
        assertTrue(GroupTransitionReadinessLog.canonicalDecisionEmittedForTest(channelId))
        assertTrue(!GroupTransitionReadinessLog.lastCanonicalDecisionIdForTest(channelId).isNullOrBlank())
    }

    @Test
    fun terminalAuthority_participantAdmittedInfersCanonicalApplied() {
        val channelId = "CH-01"
        val baseline = snapshot(channelId, traceId = null, primary = "M01", joined = emptyList(), moduleId = "M03")
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M03",
            reason = "test",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = baseline
        )
        val admitted = snapshot(
            channelId,
            traceId = "bbbb",
            primary = "M01",
            joined = listOf("M01", "M02", "M03"),
            moduleId = "M03",
            initiator = "M01"
        )
        assertEquals(
            GroupTransitionReadinessLog.TerminalAuthority.CANONICAL_APPLIED,
            GroupTransitionReadinessLog.classifyTerminalAuthority("M03", admitted)
        )
        GroupTransitionReadinessLog.onTransitionTerminalReady(
            channelId = channelId,
            moduleId = "M03",
            record = meetingEndRecord(channelId),
            snapshot = admitted
        )
        assertEquals(
            GroupTransitionReadinessLog.TerminalAuthority.CANONICAL_APPLIED,
            GroupTransitionReadinessLog.classifyTerminalAuthority("M03", admitted)
        )
    }

    @Test
    fun terminalAuthority_participantWithoutAdmissionIsLocalOperational() {
        val channelId = "CH-01"
        val snap = snapshot(
            channelId,
            traceId = "cccc",
            primary = "M01",
            joined = listOf("M01", "M03"),
            moduleId = "M03",
            initiator = "M03"
        )
        assertEquals(
            GroupTransitionReadinessLog.TerminalAuthority.LOCAL_OPERATIONAL,
            GroupTransitionReadinessLog.classifyTerminalAuthority("M03", snap)
        )
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M03",
            reason = "test",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = snap
        )
        GroupTransitionReadinessLog.onTransitionTerminalReady(
            channelId = channelId,
            moduleId = "M03",
            record = meetingEndRecord(channelId),
            snapshot = snap
        )
        assertFalse(GroupTransitionReadinessLog.canonicalDecisionEmittedForTest(channelId))
    }

    @Test
    fun localTerminalSelfLease_doesNotEmitCanonicalDecision() {
        val channelId = "CH-01"
        val snap = snapshot(channelId, traceId = "dddd", primary = "M01", joined = listOf("M01"), moduleId = "M03")
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = "M03",
            reason = "test",
            membershipBaseline = listOf("M01", "M02", "M03"),
            snapshot = snap
        )
        GroupTransitionReadinessLog.onLocalTerminalSelfLease(
            channelId = channelId,
            moduleId = "M03",
            lineageId = GroupTransitionReadinessLog.sessionLineageId(channelId),
            snapshot = snap
        )
        assertFalse(GroupTransitionReadinessLog.canonicalDecisionEmittedForTest(channelId))
    }

    private fun meetingEndRecord(channelId: String): TransitionRecord {
        return TransitionRecord(
            id = TransitionId(1L),
            channelId = channelId,
            trigger = TransitionTrigger.MEETING_END,
            phase = TransitionPhase.RECONCILING,
            startedAtMs = 900L,
            deadlineMs = 10_000L,
            terminal = TransitionTerminalState.READY,
            terminalAtMs = 1_000L
        )
    }

    private fun snapshot(
        channelId: String,
        traceId: String?,
        primary: String?,
        joined: List<String>,
        moduleId: String = "M01",
        initiator: String? = primary
    ): GroupTransitionReadinessLog.Snapshot {
        val session = GroupTransitionReadinessLog.SessionIdentityObservation(
            sessionLineageId = GroupTransitionReadinessLog.sessionLineageId(channelId),
            sessionTraceId = traceId,
            parentTraceId = GroupTransitionReadinessLog.parentTraceId(channelId),
            localSessionId = traceId?.let { "grp:$channelId" },
            initiatorModuleId = initiator,
            anchorModuleId = null,
            floorAuthorityModuleId = primary,
            resolvedBootstrapPrimaryModuleId = primary,
            membershipEpoch = 1L,
            baselineMembers = GroupTransitionReadinessLog.baselineMembers(channelId),
            orphanBelief = false,
            sessionRole = if (moduleId == primary) "HOST" else "PARTICIPANT"
        )
        return GroupTransitionReadinessLog.Snapshot(
            channelId = channelId,
            moduleId = moduleId,
            timestampMs = 1_000L,
            transition = GroupTransitionReadinessLog.TransitionObservation(
                state = "RECONCILING",
                trigger = "MEETING_END",
                transitionId = "1",
                startedAtMs = 900L,
                terminalReady = false
            ),
            session = session,
            bootstrap = GroupTransitionReadinessLog.BootstrapObservation(
                waitingForPrimary = false,
                resolvedPrimary = primary,
                bootstrapAttemptCount = 0,
                meshRecoveryState = "test"
            ),
            readiness = GroupTransitionReadinessLog.ReadinessObservation(
                membershipReady = joined.isNotEmpty(),
                transmitReady = false,
                terminalReady = false,
                joinedMembers = joined,
                activeMembers = joined,
                transmitRequiredPeers = emptySet(),
                transmitConnectedPeers = emptySet(),
                peerIceStates = emptyMap()
            ),
            receive = GroupTransitionReadinessLog.ReceiveCapabilityObservation(
                sampled = false,
                floorHolder = null,
                holderAudioReachable = null,
                holderMediaConnected = null,
                failureReason = null
            )
        )
    }

    private fun snapshot(
        channelId: String,
        traceId: String?,
        primary: String?,
        joined: List<String>
    ): GroupTransitionReadinessLog.Snapshot = snapshot(channelId, traceId, primary, joined, "M01", primary)

    private fun groupSession(initiator: String, local: String): TalkbackSession {
        val endpointSuffix = when (local) {
            "M01" -> "E01"
            "M02" -> "E02"
            else -> "E03"
        }
        val localEndpoint = EndpointAddress(ModuleId(local), EndpointId(endpointSuffix))
        return TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = localEndpoint,
            channelId = "CH-01"
        ).apply {
            accepted = true
            initiatorModuleId = ModuleId(initiator)
        }
    }
}
