package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId

/**
 * Phase 4-5 harness — Coordinator observation publisher + shadow pipeline (no control path).
 */
class CoordinatorObservationPublisherHarness {
    val publisher = CoordinatorObservationPublisher()
    private val source = TalkbackSessionRuntimeObservationSource { sessionHolder }

    @Volatile
    private var sessionHolder: TalkbackSession? = null

    fun conferenceSession(sessionId: String = MeetingRuntimeObservationFixture.CONFERENCE_ID): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val remotes = MeetingRuntimeObservationFixture.EIGHT_MEMBERS.filter { it != "M01" }
        return TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-C5").apply {
            accepted = true
            groupMembers = remotes.mapIndexed { index, remote ->
                EndpointAddress(ModuleId(remote), EndpointId("E0${index + 2}"))
            }
            remotes.forEach { remote ->
                meshCompletedModules.add(remote)
                participant(remote).media = MediaState.CONNECTING
            }
            anchorModuleId = ModuleId("M01")
            anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
            meshGeneration = 1L
        }.also { sessionHolder = it }
    }

    fun mirrorTimeline(session: TalkbackSession, kinds: List<CoordinatorObservationEventKind>): List<CoordinatorObservationEvent> {
        publisher.enabled = true
        publisher.attachSession(session.id)
        kinds.forEach { kind ->
            publisher.emitObservation(
                session = session,
                kind = kind,
                moduleId = "M01",
                remoteModuleId = when (kind) {
                    CoordinatorObservationEventKind.MEMBER_LEFT,
                    CoordinatorObservationEventKind.ICE_CONNECTED,
                    CoordinatorObservationEventKind.ICE_DISCONNECTED,
                    CoordinatorObservationEventKind.MEDIA_STATE_CHANGED,
                    CoordinatorObservationEventKind.RECOVERY_STARTED,
                    CoordinatorObservationEventKind.RECOVERY_FINISHED ->
                        "M05"
                    else -> null
                },
                mediaState = when (kind) {
                    CoordinatorObservationEventKind.MEDIA_STATE_CHANGED -> MediaState.CONNECTED
                    CoordinatorObservationEventKind.ICE_DISCONNECTED -> MediaState.RECONNECTING
                    else -> null
                }
            )
        }
        return publisher.mirroredEvents(session.id)
    }

    fun sessionTimeline(): String = source.sessionTimelineSignature()

    fun simulateLongRun(
        session: TalkbackSession,
        eventCount: Int = 2_000
    ): LongRunShadowStats {
        publisher.enabled = true
        publisher.attachSession(session.id)
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
        val mirrored = publisher.mirroredEvents(session.id)
        val sequences = mirrored.map { it.sequence }
        return LongRunShadowStats(
            eventCount = mirrored.size,
            uniqueSequences = sequences.toSet().size,
            monotonic = sequences.zipWithNext().all { (left, right) -> right > left },
            listenerCount = publisher.listenerCount(),
            reportCount = publisher.shadowPipeline.reports(session.id).size
        )
    }
}

data class LongRunShadowStats(
    val eventCount: Int,
    val uniqueSequences: Int,
    val monotonic: Boolean,
    val listenerCount: Int,
    val reportCount: Int
)
