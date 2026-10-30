package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import java.util.concurrent.ConcurrentHashMap

/**
 * PR-PA-SR-B1-R1 — narrow pending SOURCE build obligations blocked on membership eligibility.
 */
data class SourceOriginEligibilityObligation(
    val sessionId: String,
    val conferenceIdHex: String,
    val localModuleId: String,
    val sourceInstanceId: ByteArray,
    val authoritySourceGeneration: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
)

enum class SourceOriginObligationRetainOutcome {
    RETAINED,
    DEDUPED,
}

enum class SourceOriginObligationReconcileOutcome {
    NO_PENDING,
    VALID,
    DISCARDED_STALE,
}

class SourceOriginEligibilityObligationStore {
    private val pendingByKey = ConcurrentHashMap<String, SourceOriginEligibilityObligation>()

    fun retain(obligation: SourceOriginEligibilityObligation): SourceOriginObligationRetainOutcome {
        val key = obligationKey(obligation)
        val existing = pendingByKey[key]
        if (existing != null && existing.matches(obligation)) {
            return SourceOriginObligationRetainOutcome.DEDUPED
        }
        pendingByKey[key] = obligation.copy(sourceInstanceId = obligation.sourceInstanceId.copyOf())
        return SourceOriginObligationRetainOutcome.RETAINED
    }

    fun consume(
        sessionId: String,
        localModuleId: String,
        authoritySourceGeneration: Long,
    ) {
        pendingByKey.remove(obligationKey(sessionId, localModuleId, authoritySourceGeneration))
    }

    fun reconcile(
        sessionId: String,
        localModuleId: String,
        authoritySourceGeneration: Long,
        sourceInstanceId: ByteArray,
        conferenceIdHex: String,
        convergence: Profile01MembershipGeneration?,
    ): SourceOriginObligationReconcileOutcome {
        val key = obligationKey(sessionId, localModuleId, authoritySourceGeneration)
        val pending = pendingByKey[key] ?: return SourceOriginObligationReconcileOutcome.NO_PENDING
        if (!pending.matchesIdentity(
                sessionId = sessionId,
                conferenceIdHex = conferenceIdHex,
                localModuleId = localModuleId,
                authoritySourceGeneration = authoritySourceGeneration,
                sourceInstanceId = sourceInstanceId,
                convergence = convergence,
            )
        ) {
            pendingByKey.remove(key)
            return SourceOriginObligationReconcileOutcome.DISCARDED_STALE
        }
        return SourceOriginObligationReconcileOutcome.VALID
    }

    fun hasPending(
        sessionId: String,
        localModuleId: String,
        authoritySourceGeneration: Long,
    ): Boolean = pendingByKey.containsKey(obligationKey(sessionId, localModuleId, authoritySourceGeneration))

    fun clearSession(sessionId: String) {
        pendingByKey.keys.removeIf { it.startsWith("$sessionId|") }
    }

    fun discardOtherGenerations(
        sessionId: String,
        localModuleId: String,
        authoritySourceGeneration: Long,
    ) {
        val prefix = "$sessionId|$localModuleId|"
        val keepKey = obligationKey(sessionId, localModuleId, authoritySourceGeneration)
        pendingByKey.keys.removeIf { key -> key.startsWith(prefix) && key != keepKey }
    }

    internal fun pendingCount(): Int = pendingByKey.size

    private fun obligationKey(obligation: SourceOriginEligibilityObligation): String =
        obligationKey(obligation.sessionId, obligation.localModuleId, obligation.authoritySourceGeneration)

    private fun obligationKey(
        sessionId: String,
        localModuleId: String,
        authoritySourceGeneration: Long,
    ): String = "$sessionId|$localModuleId|$authoritySourceGeneration"

    private fun SourceOriginEligibilityObligation.matches(other: SourceOriginEligibilityObligation): Boolean =
        sessionId == other.sessionId &&
            conferenceIdHex == other.conferenceIdHex &&
            localModuleId == other.localModuleId &&
            authoritySourceGeneration == other.authoritySourceGeneration &&
            membershipVersion == other.membershipVersion &&
            mediaKeyEpoch == other.mediaKeyEpoch &&
            sourceInstanceId.contentEquals(other.sourceInstanceId)

    private fun SourceOriginEligibilityObligation.matchesIdentity(
        sessionId: String,
        conferenceIdHex: String,
        localModuleId: String,
        authoritySourceGeneration: Long,
        sourceInstanceId: ByteArray,
        convergence: Profile01MembershipGeneration?,
    ): Boolean {
        if (this.sessionId != sessionId ||
            this.conferenceIdHex != conferenceIdHex ||
            this.localModuleId != localModuleId ||
            this.authoritySourceGeneration != authoritySourceGeneration ||
            !this.sourceInstanceId.contentEquals(sourceInstanceId)
        ) {
            return false
        }
        if (membershipVersion != UNKNOWN_MEMBERSHIP_VERSION) {
            val currentVersion = convergence?.membershipVersion
            if (currentVersion == null || currentVersion != membershipVersion) {
                return false
            }
        }
        if (mediaKeyEpoch != UNKNOWN_MEDIA_KEY_EPOCH) {
            val currentEpoch = convergence?.mediaKeyEpoch
            if (currentEpoch == null || currentEpoch != mediaKeyEpoch) {
                return false
            }
        }
        return true
    }

    companion object {
        const val UNKNOWN_MEMBERSHIP_VERSION = -1L
        const val UNKNOWN_MEDIA_KEY_EPOCH = -1L
    }
}
