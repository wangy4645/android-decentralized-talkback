package com.talkback.core.conference.runtime

import com.talkback.core.conference.transport.ConferenceMulticastNetworkBinding
import com.talkback.core.conference.transport.MulticastLockPolicy

/**
 * Profile 03 Q7 resource constants (E2b-05).
 */
object MediaResourceConstants {
    const val MAX_JITTER_SOURCES: Int = 10
}

/**
 * C-E2B05-05: runtime evidence payload only — not Q12 Health authority.
 */
enum class RuntimeDegradationKind {
    AUDIOTRACK_WRITE_FAILURE,
    AUDIOTRACK_UNDERRUN,
    AUDIOTRACK_DEVICE_BUSY,
    RESOURCE_ALLOCATION_REJECTED,
    JITTER_CAP_EXHAUSTED,
    MULTICAST_LOCK_UNAVAILABLE,
    REBIND_DISRUPTION,
    /** P03 disposition recorded as evidence; MUST NOT alone flip Health. */
    LATE_FOR_PLAYOUT,
    /** PLC exhaustion observation; MUST NOT alone flip Health / fence. */
    PLC_EXHAUSTION,
}

data class RuntimeDegradationEvidence(
    val kind: RuntimeDegradationKind,
    val detail: String,
    val atMs: Long,
)

enum class JitterAllocPolicy {
    REJECT_NEW,
    RECLAIM_EXISTING_THEN_ALLOCATE,
}

enum class JitterAllocOutcome {
    ALLOCATED,
    REJECT_NEW,
    RECLAIM_EXISTING_THEN_ALLOCATE,
    NOT_ADMITTED,
}

data class JitterAllocResult(
    val outcome: JitterAllocOutcome,
    val reclaimedIdentity: String? = null,
)

/** Authority snapshot for R14 compare (C-E2B05-02). */
data class AuthoritySnapshot(
    val admittedIdentities: Set<String>,
    val incarnationByIdentity: Map<String, Long>,
    val fenceByIdentity: Map<String, ExecutionFenceState>,
    val membershipIdentity: String,
    val conferenceGeneration: Long,
    val anchorEpoch: Long,
)

/**
 * Local binding of the frozen MediaGroupEndpoint fields needed to join/re-join.
 * Not a new contract constant; implementation payload for C-IG-01 rebind.
 */
data class MediaGroupEndpointBinding(
    val multicastAddress: String,
    val mediaPort: Int,
    val underlayScopeId: String = "default",
)

data class TransportHandle(
    val id: String,
    val endpoint: MediaGroupEndpointBinding? = null,
    /** Legacy alias; prefer [networkBinding]. */
    val networkInterfaceName: String? = null,
    val networkBinding: ConferenceMulticastNetworkBinding? = null,
    val multicastLockPolicy: MulticastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
) {
    fun resolvedNetworkBinding(): ConferenceMulticastNetworkBinding? =
        networkBinding
            ?: networkInterfaceName?.let { ConferenceMulticastNetworkBinding.fromInterfaceName(it) }
}

/**
 * Conference media transport scope facts (not Source authority).
 */
data class TransportScopeState(
    val active: Boolean,
    val handle: TransportHandle?,
    val membershipIdentity: String = "MEM-FIXED",
    val conferenceGeneration: Long = 1L,
    val anchorEpoch: Long = 1L,
)
