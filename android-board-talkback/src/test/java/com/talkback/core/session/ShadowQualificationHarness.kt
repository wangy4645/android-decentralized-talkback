package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId

/**
 * Phase 4-6 harness — shadow qualification evidence cards (observe only, no control).
 */
class ShadowQualificationHarness {
    val publisher = CoordinatorObservationPublisher()
    val aggregator = ShadowQualificationAggregator()
    private val source = TalkbackSessionRuntimeObservationSource { sessionHolder }

    @Volatile
    private var sessionHolder: TalkbackSession? = null

    fun eightNodeSession(sessionId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val remotes = MeetingRuntimeObservationFixture.EIGHT_MEMBERS.filter { it != "M01" }
        return TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-Q6").apply {
            accepted = true
            groupMembers = remotes.mapIndexed { index, remote ->
                EndpointAddress(ModuleId(remote), EndpointId("E0${index + 2}"))
            }
            remotes.forEach { remote ->
                meshCompletedModules.add(remote)
                participant(remote).media = MediaState.CONNECTING
            }
            remotes.take(5).forEach { remote ->
                participant(remote).media = MediaState.CONNECTED
            }
            anchorModuleId = ModuleId("M01")
            anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
            meshGeneration = 1L
        }.also { sessionHolder = it }
    }

    fun sessionTimeline(): String = source.sessionTimelineSignature()

    fun runCard(
        session: TalkbackSession,
        card: ShadowQualificationCard,
        timeline: List<CoordinatorObservationEventKind>,
        mutateBeforeRun: (TalkbackSession) -> Unit = {}
    ): ShadowQualificationReport {
        mutateBeforeRun(session)
        val timelineBefore = sessionTimeline()
        publisher.enabled = true
        publisher.attachSession(session.id)
        val startedAt = System.currentTimeMillis()
        timeline.forEach { kind ->
            publisher.emitObservation(
                session = session,
                kind = kind,
                moduleId = "M01",
                remoteModuleId = remoteForKind(kind),
                mediaState = mediaForKind(kind)
            )
        }
        val timelineAfter = sessionTimeline()
        val events = publisher.mirroredEvents(session.id)
        val points = publisher.qualificationObservationPoints(session.id)
        val report = aggregator.aggregate(
            sessionId = session.id,
            card = card,
            points = points,
            mirroredEvents = events,
            observerAttachCount = 1,
            listenerCountAtEnd = publisher.listenerCount(),
            reportSignatureStable = timelineBefore == timelineAfter,
            observerLifetimeMs = System.currentTimeMillis() - startedAt,
            memoryCounterEstimate = events.size.toLong() + points.size.toLong()
        )
        return report
    }

    fun runLongRunCard(
        session: TalkbackSession,
        eventCount: Int = 2_000
    ): ShadowQualificationReport {
        publisher.enabled = true
        publisher.attachSession(session.id)
        val timelineBefore = sessionTimeline()
        val startedAt = System.currentTimeMillis()
        val kinds = listOf(
            CoordinatorObservationEventKind.JOIN_ACCEPTED,
            CoordinatorObservationEventKind.ICE_CONNECTED,
            CoordinatorObservationEventKind.MEDIA_STATE_CHANGED,
            CoordinatorObservationEventKind.RECOVERY_STARTED,
            CoordinatorObservationEventKind.MEMBER_LEFT,
            CoordinatorObservationEventKind.RECOVERY_FINISHED,
            CoordinatorObservationEventKind.ICE_DISCONNECTED,
            CoordinatorObservationEventKind.SESSION_TERMINATED
        )
        repeat(eventCount) { index ->
            publisher.emitObservation(
                session = session,
                kind = kinds[index % kinds.size],
                remoteModuleId = "M0${(index % 7) + 2}",
                mediaState = if (index % 2 == 0) MediaState.CONNECTED else MediaState.RECONNECTING
            )
        }
        val events = publisher.mirroredEvents(session.id)
        val points = publisher.qualificationObservationPoints(session.id)
        val report = aggregator.aggregate(
            sessionId = session.id,
            card = ShadowQualificationCard.Q6E_LONG_RUN,
            points = points,
            mirroredEvents = events,
            observerAttachCount = 1,
            listenerCountAtEnd = publisher.listenerCount(),
            reportSignatureStable = sessionTimeline() == timelineBefore,
            observerLifetimeMs = System.currentTimeMillis() - startedAt,
            memoryCounterEstimate = events.size.toLong() + points.size.toLong()
        )
        publisher.detachSession(session.id)
        return report
    }

    fun deterministicSignatureForTimeline(
        session: TalkbackSession,
        timeline: List<CoordinatorObservationEventKind>
    ): String {
        val first = runCard(session, ShadowQualificationCard.Q6A_NORMAL_MEETING, timeline)
        publisher.clear()
        publisher.enabled = true
        val second = runCard(session, ShadowQualificationCard.Q6A_NORMAL_MEETING, timeline)
        return "${first.projectionSummary.deterministicSignature}::${second.projectionSummary.deterministicSignature}"
    }

    private fun remoteForKind(kind: CoordinatorObservationEventKind): String? = when (kind) {
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
