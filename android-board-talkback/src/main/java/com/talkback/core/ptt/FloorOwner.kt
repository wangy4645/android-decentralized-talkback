package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointPriority

enum class FloorCommitResult {
    APPLIED,
    DISCARDED
}

data class FloorCommitOutcome(
    val result: FloorCommitResult,
    val discardReason: FloorCommitDiscardReason? = null
)

data class FloorGrantCompletion(
    val owner: EndpointAddress,
    val floorVersion: Long,
    val floorEpoch: Long,
    val priority: EndpointPriority,
    val alreadyGranted: Boolean = false,
    val token: FloorOperationToken? = null
)

/**
 * Floor Fact Owner (ADR-0007 R31/R32). [commitGrant] is the single mutation entry for grants.
 * V1 pass-through: always applies when [FloorGrantCompletion.alreadyGranted] is false.
 */
class FloorOwner(
    val floor: FloorState = FloorState(),
    val tokens: FloorOperationTokenRegistry = FloorOperationTokenRegistry()
) {
    fun createRequestToken(
        sessionId: String,
        requester: EndpointAddress,
        version: Long
    ): FloorOperationToken =
        tokens.create(FloorOperationIdentity(sessionId, requester.key), version)

    fun invalidateRequestToken(identity: FloorOperationIdentity): Boolean =
        tokens.invalidate(identity)

    fun recordRequestWithdrawal(identity: FloorOperationIdentity, version: Long) {
        tokens.recordWithdrawal(identity, version)
    }

    fun isRequestWithdrawn(identity: FloorOperationIdentity, version: Long): Boolean =
        tokens.isWithdrawn(identity, version)

    fun commitGrant(
        completion: FloorGrantCompletion,
        requesterIntent: FloorOperationIdentity? = null
    ): FloorCommitOutcome {
        if (requesterIntent != null) {
            validateRequesterIntent(requesterIntent, completion)?.let { reason ->
                return FloorCommitOutcome(FloorCommitResult.DISCARDED, reason)
            }
            tokens.lookup(requesterIntent)?.let { tokens.complete(it) }
        }
        if (!completion.alreadyGranted) {
            floor.applyGrant(
                completion.owner,
                completion.floorVersion,
                completion.floorEpoch,
                completion.priority
            )
        }
        return FloorCommitOutcome(FloorCommitResult.APPLIED)
    }

    private fun validateRequesterIntent(
        identity: FloorOperationIdentity,
        completion: FloorGrantCompletion
    ): FloorCommitDiscardReason? {
        val token = tokens.lookup(identity) ?: return FloorCommitDiscardReason.NO_TOKEN
        if (token.validity != OperationTokenValidity.VALID) {
            return FloorCommitDiscardReason.TOKEN_INVALID
        }
        if (completion.floorVersion < token.version) {
            return FloorCommitDiscardReason.VERSION_MISMATCH
        }
        return null
    }
}
