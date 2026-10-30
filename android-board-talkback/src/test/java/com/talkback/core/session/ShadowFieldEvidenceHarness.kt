package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId

/**
 * Phase 4-7 harness — field shadow evidence cards (harness simulates device lifecycle).
 */
class ShadowFieldEvidenceHarness {
    val collector = ShadowFieldEvidenceCollector()
    private val source = TalkbackSessionRuntimeObservationSource { sessionHolder }

    @Volatile
    private var sessionHolder: TalkbackSession? = null

    fun conferenceSession(
        roster: List<String> = ShadowFieldEvidenceContract.TEN_NODE_ROSTER,
        sessionId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID
    ): TalkbackSession {
        val local = EndpointAddress(ModuleId(roster.first()), EndpointId("E01"))
        val remotes = roster.filter { it != roster.first() }
        return TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-F7").apply {
            accepted = true
            groupMembers = remotes.mapIndexed { index, remote ->
                EndpointAddress(ModuleId(remote), EndpointId("E0${index + 2}"))
            }
            remotes.forEach { remote ->
                meshCompletedModules.add(remote)
                participant(remote).media = MediaState.CONNECTING
            }
            remotes.take((remotes.size * 0.6).toInt().coerceAtLeast(1)).forEach { remote ->
                participant(remote).media = MediaState.CONNECTED
            }
            anchorModuleId = ModuleId(roster.first())
            anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
            meshGeneration = 1L
        }.also { sessionHolder = it }
    }

    fun sessionTimeline(): String = source.sessionTimelineSignature()

    fun runFieldCard(
        runCard: ShadowFieldRunCard,
        fieldRunId: String,
        session: TalkbackSession,
        participants: List<String>,
        timeline: List<CoordinatorObservationEventKind>,
        remoteSelector: (Int, CoordinatorObservationEventKind) -> String? = { _, kind ->
            defaultRemote(kind)
        },
        mutateBeforeRun: (TalkbackSession) -> Unit = {}
    ): ShadowFieldEvidenceReport {
        mutateBeforeRun(session)
        val timelineBefore = sessionTimeline()
        collector.beginRun(
            runCard = runCard,
            fieldRunId = fieldRunId,
            session = session,
            participants = participants,
            timelineBefore = timelineBefore
        )
        timeline.forEachIndexed { index, kind ->
            collector.publisher.emitObservation(
                session = session,
                kind = kind,
                moduleId = participants.first(),
                remoteModuleId = remoteSelector(index, kind),
                mediaState = mediaForKind(kind)
            )
        }
        return collector.finalizeRun(
            sessionTimelineAfter = sessionTimeline(),
            observationSource = FieldObservationSource.HARNESS
        )!!
    }

    fun runSteadyStateCard(
        session: TalkbackSession,
        participants: List<String>,
        steadyEventCount: Int = 120
    ): ShadowFieldEvidenceReport {
        val timeline = buildList {
            add(CoordinatorObservationEventKind.JOIN_ACCEPTED)
            repeat(steadyEventCount) {
                add(CoordinatorObservationEventKind.MEDIA_STATE_CHANGED)
            }
        }
        return runFieldCard(
            runCard = ShadowFieldRunCard.F7B_STEADY_STATE,
            fieldRunId = "f7b-steady-${session.id}",
            session = session,
            participants = participants,
            timeline = timeline
        )
    }

    fun runTenNodeLifecycle(
        session: TalkbackSession,
        roster: List<String> = ShadowFieldEvidenceContract.TEN_NODE_ROSTER
    ): ShadowFieldEvidenceReport {
        val remotes = roster.filter { it != roster.first() }
        val timeline = buildList {
            add(CoordinatorObservationEventKind.JOIN_ACCEPTED)
            remotes.forEach { _ ->
                add(CoordinatorObservationEventKind.ICE_CONNECTED)
            }
            add(CoordinatorObservationEventKind.MEDIA_STATE_CHANGED)
            add(CoordinatorObservationEventKind.MEMBER_LEFT)
            add(CoordinatorObservationEventKind.JOIN_ACCEPTED)
            add(CoordinatorObservationEventKind.ICE_CONNECTED)
        }
        return runFieldCard(
            runCard = ShadowFieldRunCard.F7E_MULTI_PARTICIPANT_LIFECYCLE,
            fieldRunId = "f7e-ten-node-${session.id}",
            session = session,
            participants = roster,
            timeline = timeline,
            remoteSelector = { index, kind ->
                when (kind) {
                    CoordinatorObservationEventKind.ICE_CONNECTED ->
                        remotes.getOrNull(index - 1) ?: remotes.first()
                    CoordinatorObservationEventKind.MEMBER_LEFT -> remotes[2]
                    else -> defaultRemote(kind)
                }
            },
            mutateBeforeRun = { it.meshGeneration = 2L }
        )
    }

    private fun defaultRemote(kind: CoordinatorObservationEventKind): String? = when (kind) {
        CoordinatorObservationEventKind.MEMBER_LEFT,
        CoordinatorObservationEventKind.ICE_CONNECTED,
        CoordinatorObservationEventKind.ICE_DISCONNECTED,
        CoordinatorObservationEventKind.MEDIA_STATE_CHANGED,
        CoordinatorObservationEventKind.RECOVERY_STARTED,
        CoordinatorObservationEventKind.RECOVERY_FINISHED ->
            "M05"
        else -> null
    }

    private fun mediaForKind(kind: CoordinatorObservationEventKind): MediaState? = when (kind) {
        CoordinatorObservationEventKind.MEDIA_STATE_CHANGED -> MediaState.CONNECTED
        CoordinatorObservationEventKind.ICE_DISCONNECTED -> MediaState.RECONNECTING
        else -> null
    }
}
