package com.talkback.core.conference.session.integration

import com.talkback.core.session.ConferenceTopologySnapshot
import java.util.concurrent.ConcurrentHashMap

data class Profile01SupplementDeliveryScope(
    val sessionId: String,
    val generation: Long,
)

/**
 * Host-side MEDIA_KEY_PACKAGE delivery obligations (PR-1).
 *
 * [MediaKeyPackagePublicationLedger] records SENT only; this coordinator owns markPublished.
 */
class Profile01SupplementDeliveryCoordinator(
    private val publicationLedger: MediaKeyPackagePublicationLedger,
    private val packageBridge: MeetingProfile01MediaKeyPackageOriginBridge,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val resolveDrainContext: (sessionId: String) -> Profile01SupplementDrainContext?,
    private val onLog: (String) -> Unit = {},
) {
    data class Profile01SupplementDrainContext(
        val scope: Profile01SupplementDeliveryScope,
        val snapshot: ConferenceTopologySnapshot,
        val localModuleId: String,
        val isMembershipAuthority: Boolean,
        val eligiblePeerModuleIds: Set<String>,
        val publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    )

    private data class SessionState(
        @Volatile var generation: Long = 0L,
        val lock: Any = Any(),
        @Volatile var drainCoalesced: Boolean = false,
        @Volatile var fastRecoveryPollsRemaining: Int = 0,
    )

    private val sessionStates = ConcurrentHashMap<String, SessionState>()
    private val inFlight =
        ConcurrentHashMap<Pair<String, MediaKeyPackagePublicationIdentity>, Boolean>()

    fun currentScope(sessionId: String): Profile01SupplementDeliveryScope? {
        val state = sessionStates[sessionId] ?: return null
        return Profile01SupplementDeliveryScope(sessionId, state.generation)
    }

    fun ensureSession(sessionId: String): Profile01SupplementDeliveryScope {
        val state = sessionStates.computeIfAbsent(sessionId) { SessionState() }
        return Profile01SupplementDeliveryScope(sessionId, state.generation)
    }

    fun clearSession(sessionId: String) {
        val state = sessionStates.computeIfAbsent(sessionId) { SessionState() }
        synchronized(state.lock) {
            state.generation++
            state.drainCoalesced = false
            state.fastRecoveryPollsRemaining = 0
        }
        inFlight.keys.removeIf { it.first == sessionId }
    }

    fun scheduleDrain(
        sessionId: String,
        reason: String,
        hintPeerModuleIds: Set<String>? = null,
        delayMs: Long = 0L,
    ) {
        val scope = ensureSession(sessionId)
        if (delayMs > 0L) {
            schedule(delayMs) { drain(scope, reason, hintPeerModuleIds) }
            return
        }
        val state = sessionStates.computeIfAbsent(sessionId) { SessionState() }
        if (state.drainCoalesced) return
        state.drainCoalesced = true
        schedule(0L) {
            state.drainCoalesced = false
            drain(scope, reason, hintPeerModuleIds)
        }
    }

    fun onEstablishmentPeersMaterialized(
        materializedPeerModuleIds: Set<String>,
        activeConferenceSessionIds: Set<String>,
    ) {
        if (materializedPeerModuleIds.isEmpty() || activeConferenceSessionIds.isEmpty()) return
        activeConferenceSessionIds.forEach { sessionId ->
            scheduleDrain(
                sessionId = sessionId,
                reason = "establishment_materialized",
                hintPeerModuleIds = materializedPeerModuleIds,
            )
        }
    }

    fun drain(
        scope: Profile01SupplementDeliveryScope,
        reason: String,
        hintPeerModuleIds: Set<String>?,
    ) {
        if (!validateScope(scope)) return
        val context =
            resolveDrainContext(scope.sessionId)?.copy(scope = scope)
                ?: return

        val peers =
            if (hintPeerModuleIds != null) {
                context.eligiblePeerModuleIds.intersect(hintPeerModuleIds)
            } else {
                context.eligiblePeerModuleIds
            }
        if (peers.isEmpty()) return

        var anyPending = false
        peers
            .filter { it != context.localModuleId }
            .sorted()
            .forEach { peerModuleId ->
                if (!validateScope(context.scope)) return
                val evaluation = evaluatePeer(context, peerModuleId)
                when (evaluation) {
                    PeerEvaluation.AlreadySent -> Unit
                    is PeerEvaluation.Pending -> {
                        anyPending = true
                        maybeScheduleFastRecoveryPoll(scope)
                    }
                    is PeerEvaluation.ReadyToSend -> {
                        trySend(context, peerModuleId, evaluation.identity, reason)
                    }
                }
            }

        if (anyPending && hintPeerModuleIds != null) {
            scheduleDrain(scope.sessionId, reason = "${reason}_full", hintPeerModuleIds = null)
        }
    }

    private sealed class PeerEvaluation {
        data object AlreadySent : PeerEvaluation()

        data class Pending(
            val peerModuleId: String,
        ) : PeerEvaluation()

        data class ReadyToSend(
            val identity: MediaKeyPackagePublicationIdentity,
        ) : PeerEvaluation()
    }

    private fun evaluatePeer(
        context: Profile01SupplementDrainContext,
        peerModuleId: String,
    ): PeerEvaluation {
        val preview =
            packageBridge.peekMediaKeyPackagePeerPublish(
                sessionId = context.scope.sessionId,
                snapshot = context.snapshot,
                localModuleId = context.localModuleId,
                isMembershipAuthority = context.isMembershipAuthority,
                peerModuleId = peerModuleId,
            )
        return when (preview) {
            is MediaKeyPackagePeerPublishOutcome.ReadyToSend ->
                PeerEvaluation.ReadyToSend(preview.identity)
            is MediaKeyPackagePeerPublishOutcome.PendingIdentity -> {
                notePendingIdentityObserved(context.scope.sessionId)
                PeerEvaluation.Pending(peerModuleId)
            }
            is MediaKeyPackagePeerPublishOutcome.Skipped ->
                if (preview.reason == "ALREADY_PUBLISHED") {
                    PeerEvaluation.AlreadySent
                } else {
                    PeerEvaluation.Pending(peerModuleId)
                }
            is MediaKeyPackagePeerPublishOutcome.BuildFailed -> PeerEvaluation.Pending(peerModuleId)
            else -> PeerEvaluation.Pending(peerModuleId)
        }
    }

    private fun trySend(
        context: Profile01SupplementDrainContext,
        peerModuleId: String,
        expectedIdentity: MediaKeyPackagePublicationIdentity,
        reason: String,
    ) {
        val sessionId = context.scope.sessionId
        if (publicationLedger.isPublished(sessionId, expectedIdentity)) return

        val flightKey = sessionId to expectedIdentity
        if (inFlight.putIfAbsent(flightKey, true) != null) return

        try {
            val outcome =
                packageBridge.publishMediaKeyPackageToPeer(
                    sessionId = sessionId,
                    snapshot = context.snapshot,
                    localModuleId = context.localModuleId,
                    isMembershipAuthority = context.isMembershipAuthority,
                    peerModuleId = peerModuleId,
                    publishToPeer = context.publishToPeer,
                    recordPublicationOnSuccess = { _, _ -> },
                    expectedIdentity = expectedIdentity,
                )
            when (outcome) {
                is MediaKeyPackagePeerPublishOutcome.Sent -> {
                    markPublishedUnderSessionLock(context.scope, outcome.identity)
                    onLog(
                        "SUPPLEMENT_OBLIGATION_SENT session=$sessionId reason=$reason " +
                            "target=$peerModuleId establishmentKeyVersion=" +
                            "${outcome.identity.recipientEstablishmentKeyVersion}",
                    )
                }
                is MediaKeyPackagePeerPublishOutcome.SendFailed -> {
                    scheduleDrain(
                        sessionId = sessionId,
                        reason = "send_failed",
                        hintPeerModuleIds = setOf(peerModuleId),
                        delayMs = SEND_RETRY_DELAY_MS,
                    )
                }
                is MediaKeyPackagePeerPublishOutcome.PendingIdentity -> {
                    maybeScheduleFastRecoveryPoll(context.scope)
                }
                else -> Unit
            }
        } finally {
            inFlight.remove(flightKey)
        }
    }

    private fun markPublishedUnderSessionLock(
        scope: Profile01SupplementDeliveryScope,
        identity: MediaKeyPackagePublicationIdentity,
    ) {
        val state = sessionStates[scope.sessionId] ?: return
        synchronized(state.lock) {
            if (state.generation != scope.generation) return
            if (publicationLedger.isPublished(scope.sessionId, identity)) return
            publicationLedger.markPublished(scope.sessionId, identity)
        }
    }

    private fun validateScope(scope: Profile01SupplementDeliveryScope): Boolean {
        val state = sessionStates[scope.sessionId] ?: return false
        return state.generation == scope.generation
    }

    private fun maybeScheduleFastRecoveryPoll(scope: Profile01SupplementDeliveryScope) {
        val state = sessionStates.computeIfAbsent(scope.sessionId) { SessionState() }
        if (!validateScope(scope)) return
        if (state.fastRecoveryPollsRemaining <= 0) return
        state.fastRecoveryPollsRemaining--
        schedule(
            PENDING_FAST_RECOVERY_DELAY_MS,
        ) {
            drain(scope, reason = "pending_identity_fast_recovery", hintPeerModuleIds = null)
        }
    }

    fun notePendingIdentityObserved(sessionId: String) {
        val state = sessionStates.computeIfAbsent(sessionId) { SessionState() }
        if (state.fastRecoveryPollsRemaining < PENDING_FAST_RECOVERY_MAX_POLLS) {
            state.fastRecoveryPollsRemaining = PENDING_FAST_RECOVERY_MAX_POLLS
        }
    }

    companion object {
        const val PENDING_FAST_RECOVERY_DELAY_MS = 25L
        const val PENDING_FAST_RECOVERY_MAX_POLLS = 40
        private const val SEND_RETRY_DELAY_MS = 100L
    }
}
