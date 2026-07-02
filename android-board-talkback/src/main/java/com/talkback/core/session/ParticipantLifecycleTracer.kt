package com.talkback.core.session

import com.talkback.core.model.ModuleId
import com.talkback.core.util.TalkbackLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Permanent instrumentation for conference participant recovery pipelines.
 *
 * Grep prefixes:
 * - PARTICIPANT_TIMELINE — stage-oriented lifecycle events
 * - PARTICIPANT_MUTATION — invite/media writes (who wrote what, in order)
 * - PROJECTOR_MEMBER — per-remote visibility verdict (emitted on change only)
 */
object ParticipantLifecycleTracer {

    enum class Stage {
        PRUNE,
        SNAPSHOT,
        SYNC,
        RECONCILE_RECEIVED,
        RECONCILE_DEFERRED,
        RECONCILE_APPLIED,
        ICE,
        PROJECTOR
    }

    enum class Source {
        Reducer,
        SyncParticipants,
        ParticipantManager,
        MediaRuntime,
        Coordinator,
        Projector
    }

    private val mutationSeq = AtomicLong(0)
    private val lastProjectorSignature = ConcurrentHashMap<String, String>()
    private var logSink: ((String) -> Unit)? = null

    fun resetForTests() {
        mutationSeq.set(0)
        lastProjectorSignature.clear()
        logSink = {}
    }

    private fun log(message: String) {
        (logSink ?: { TalkbackLog.i(it) })(message)
    }

    fun recordMutation(
        sessionId: String,
        target: String,
        source: Source,
        reason: String,
        inviteBefore: InviteState?,
        mediaBefore: MediaState?,
        inviteAfter: InviteState,
        mediaAfter: MediaState
    ) {
        if (inviteBefore == inviteAfter && mediaBefore == mediaAfter) return
        val seq = mutationSeq.incrementAndGet()
        log(
            "PARTICIPANT_MUTATION seq=$seq sid=$sessionId target=$target " +
                "source=$source reason=$reason " +
                "invite=${inviteBefore ?: "n/a"}->$inviteAfter " +
                "media=${mediaBefore ?: "n/a"}->$mediaAfter"
        )
    }

    fun recordTimeline(
        sessionId: String,
        target: String,
        stage: Stage,
        reason: String,
        source: Source,
        manager: ConferenceParticipantManager,
        localModuleId: ModuleId,
        participantExists: Boolean? = null,
        created: Boolean = false
    ) {
        val context = manager.participantContext(sessionId, target, localModuleId)
        val exists = participantExists ?: context.exists
        log(
            "PARTICIPANT_TIMELINE seq=${mutationSeq.get()} sid=$sessionId target=$target " +
                "stage=$stage reason=$reason source=$source " +
                "participantExists=$exists created=$created " +
                "invite=${context.invite} media=${context.media} " +
                "rosterSize=${context.rosterSize} participantCount=${context.participantCount} " +
                "left=${context.left} awaiting=${context.awaiting}"
        )
    }

    fun recordTimelineForRoster(
        sessionId: String,
        stage: Stage,
        reason: String,
        source: Source,
        manager: ConferenceParticipantManager,
        localModuleId: ModuleId
    ) {
        val remoteIds = manager.remoteModuleIds(sessionId, localModuleId)
        if (remoteIds.isEmpty()) {
            recordTimeline(
                sessionId = sessionId,
                target = "*",
                stage = stage,
                reason = reason,
                source = source,
                manager = manager,
                localModuleId = localModuleId,
                participantExists = false,
                created = false
            )
            return
        }
        remoteIds.forEach { moduleId ->
            recordTimeline(
                sessionId = sessionId,
                target = moduleId,
                stage = stage,
                reason = reason,
                source = source,
                manager = manager,
                localModuleId = localModuleId
            )
        }
    }

    fun recordProjectorMembers(
        sessionId: String,
        input: ConferenceParticipantProjector.Input,
        output: ConferenceParticipantProjector.Output
    ) {
        val explanations = ConferenceParticipantProjector.explainMemberVisibility(input)
        val signature = buildString {
            append("v=${output.visibleParticipantCount}")
            append("|a=${output.awaitingAdditionalParticipants}")
            explanations.forEach { line ->
                append('|')
                append(line.moduleId)
                append('=')
                append(line.visible)
                append(':')
                append(line.reason)
            }
        }
        val previous = lastProjectorSignature.put(sessionId, signature)
        if (previous == signature) return
        explanations.forEach { line ->
            log(
                "PROJECTOR_MEMBER seq=${mutationSeq.get()} sid=$sessionId target=${line.moduleId} " +
                    "visible=${line.visible} reason=${line.reason} " +
                    "invite=${line.invite} media=${line.media} " +
                    "visibleCount=${output.visibleParticipantCount} rosterSize=${input.roster.size} " +
                    "awaiting=${output.awaitingAdditionalParticipants}"
            )
        }
    }
}

data class ParticipantContextSnapshot(
    val exists: Boolean,
    val invite: InviteState,
    val media: MediaState,
    val rosterSize: Int,
    val participantCount: Int,
    val left: Boolean,
    val awaiting: Boolean
)

fun ConferenceParticipantManager.participantContext(
    sessionId: String,
    moduleId: String,
    localModuleId: ModuleId
): ParticipantContextSnapshot {
    val snap = snapshot(sessionId, localModuleId)
    val view = snap.memberViews.firstOrNull { it.moduleId == moduleId }
    val leftIds = leftMemberEndpoints(sessionId)?.keys ?: emptySet()
    val exists = containsParticipant(sessionId, moduleId)
    val invite = if (exists) {
        participant(sessionId, moduleId).invite
    } else {
        view?.invite ?: InviteState.NONE
    }
    val media = if (exists) {
        participant(sessionId, moduleId).media
    } else {
        view?.media ?: MediaState.NONE
    }
    val awaiting = view != null &&
        moduleId != localModuleId.value &&
        moduleId !in leftIds &&
        (
            view.invite == InviteState.INVITING ||
                view.invite == InviteState.RINGING ||
                (view.invite == InviteState.ACCEPTED && view.media == MediaState.NONE)
            )
    return ParticipantContextSnapshot(
        exists = exists,
        invite = invite,
        media = media,
        rosterSize = snap.roster.size,
        participantCount = snap.memberViews.size,
        left = moduleId in leftIds,
        awaiting = awaiting
    )
}
