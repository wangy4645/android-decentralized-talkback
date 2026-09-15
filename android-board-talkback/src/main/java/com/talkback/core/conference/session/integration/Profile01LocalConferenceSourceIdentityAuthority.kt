package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01MediaGroupDescriptor
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Commits authoritative local RTP source identity for Profile01 SOURCE origin.
 *
 * Identity is allocated when media material is ready — not derived from ICE state (B1-HC2).
 *
 * ADR-0058 lists `mediaKeyEpoch change` among the triggers that MUST produce a new
 * `sourceInstanceId` + new random SSRC + new Declaration, so an epoch advance is an
 * incarnation successor here, never a re-signed same-generation fact.
 */
class Profile01LocalConferenceSourceIdentityAuthority {
    data class Commitment(
        val ssrc: Int,
        val sourceInstanceId: ByteArray,
        val mediaGroupDescriptorDigest: ByteArray,
        val sourceGeneration: Long,
        val mediaKeyEpoch: Long,
    )

    private data class SessionModuleState(
        var current: Commitment? = null,
    )

    private val bySessionModule = ConcurrentHashMap<String, SessionModuleState>()
    private val random = SecureRandom()
    private val allocatedSsrcs = ConcurrentHashMap.newKeySet<Int>()

    fun currentCommitment(
        sessionId: String,
        moduleId: String,
    ): Commitment? = bySessionModule[key(sessionId, moduleId)]?.current?.let { copyCommitment(it) }

    /**
     * First commit for [sessionId]/[moduleId], or no-op when identity and epoch are unchanged.
     * An advance of [mediaKeyEpoch], or an explicit new SSRC / sourceInstanceId, commits a
     * successor incarnation and bumps [Commitment.sourceGeneration].
     */
    fun commitLocalSource(
        sessionId: String,
        moduleId: String,
        mediaKeyEpoch: Long,
        ssrc: Int? = null,
        sourceInstanceId: ByteArray? = null,
        mediaGroupDescriptorDigest: ByteArray? = null,
    ): Commitment {
        val descriptorDigest =
            mediaGroupDescriptorDigest?.copyOf()
                ?: Profile01FactDigest.descriptorDigest(Profile01MediaGroupDescriptor.productionDescriptor())
        val state = bySessionModule.getOrPut(key(sessionId, moduleId)) { SessionModuleState() }
        synchronized(state) {
            val existing = state.current
            val epochAdvanced = existing != null && mediaKeyEpoch > existing.mediaKeyEpoch
            val resolvedSsrc =
                ssrc
                    ?: existing?.ssrc?.takeUnless { epochAdvanced }
                    ?: allocateSsrc()
            val resolvedInstanceId =
                sourceInstanceId?.copyOf()
                    ?: existing?.sourceInstanceId?.takeUnless { epochAdvanced }?.copyOf()
                    ?: ByteArray(16).also { random.nextBytes(it) }
            if (existing != null &&
                !epochAdvanced &&
                existing.ssrc == resolvedSsrc &&
                existing.sourceInstanceId.contentEquals(resolvedInstanceId) &&
                existing.mediaGroupDescriptorDigest.contentEquals(descriptorDigest)
            ) {
                return copyCommitment(existing)
            }
            val committed =
                Commitment(
                    ssrc = resolvedSsrc,
                    sourceInstanceId = resolvedInstanceId,
                    mediaGroupDescriptorDigest = descriptorDigest,
                    sourceGeneration = (existing?.sourceGeneration ?: 0L) + 1L,
                    mediaKeyEpoch = maxOf(mediaKeyEpoch, existing?.mediaKeyEpoch ?: mediaKeyEpoch),
                )
            state.current = committed
            return copyCommitment(committed)
        }
    }

    /** ADR-0058: each new incarnation takes a new random SSRC, never a reused or predictable one. */
    private fun allocateSsrc(): Int {
        repeat(MAX_SSRC_ALLOCATION_ATTEMPTS) {
            val candidate = random.nextInt()
            if (candidate != 0 && allocatedSsrcs.add(candidate)) {
                return candidate
            }
        }
        error("unable to allocate a unique SSRC")
    }

    fun clearSession(sessionId: String) {
        bySessionModule.keys.removeIf { it.startsWith("$sessionId|") }
    }

    private fun key(sessionId: String, moduleId: String): String = "$sessionId|$moduleId"

    private fun copyCommitment(commitment: Commitment): Commitment =
        Commitment(
            ssrc = commitment.ssrc,
            sourceInstanceId = commitment.sourceInstanceId.copyOf(),
            mediaGroupDescriptorDigest = commitment.mediaGroupDescriptorDigest.copyOf(),
            sourceGeneration = commitment.sourceGeneration,
            mediaKeyEpoch = commitment.mediaKeyEpoch,
        )

    private companion object {
        const val MAX_SSRC_ALLOCATION_ATTEMPTS = 64
    }
}
