package com.talkback.core.session.edge

/** One mesh media edge from the local node to [peerModuleId] inside [channelId]. */
data class EdgeKey(val channelId: String, val peerModuleId: String)

/** Perfect-negotiation role for the edge; fixed by the caller (lexicographic rule). */
enum class EdgeRole { OFFERER, ANSWERER }

/** How far the controller's decisions are allowed to reach into the live signaling path. */
enum class EdgeLinkMode {
    /** Phase 1: log decisions only. */
    SHADOW,

    /**
     * Phase 2a/2b: execute offerer SendOffer / Rebuild, answerer RequestReoffer / Rebuild,
     * and log MarkFailed. [EdgeAction.Release] stays a local bookkeeping fact.
     */
    ACTIVE_OFFERER,
}

/** Which actions a mode may execute; everything else is logged with `mode=SHADOW`. */
fun EdgeLinkMode.mayExecute(action: EdgeAction): Boolean = when (this) {
    EdgeLinkMode.SHADOW -> false
    EdgeLinkMode.ACTIVE_OFFERER -> when (action) {
        is EdgeAction.SendOffer,
        is EdgeAction.RebuildPeerConnection,
        is EdgeAction.RequestReoffer,
        is EdgeAction.MarkFailed -> true
        is EdgeAction.Release -> false
    }
}

/**
 * Authoritative per-edge lifecycle. Every other gate (GIDR, SIF, L0 admission, reconciler
 * pacing) is a projection of this state, never a peer of it.
 */
enum class EdgeLinkState {
    /** Desired but no negotiation in flight (may be queued by the concurrency cap). */
    IDLE,

    /** Offerer: offer handed off, waiting for the remote answer. */
    OFFER_SENT,

    /** Answerer: waiting for the remote offer. */
    AWAITING_OFFER,

    /** SDP exchanged on the current generation; ICE not yet connected. */
    ICE_CHECKING,

    CONNECTED,

    /** Was CONNECTED; ICE reported DISCONNECTED and the grace window is running. */
    DEGRADED,

    /** ICE-restart offer issued (offerer) or re-offer requested (answerer). */
    RESTARTING,

    /** PeerConnection recycle in flight; waiting for [EdgeFact.RebuildCompleted]. */
    REBUILDING,

    /** Escalation budget exhausted; backoff running; UI must show "peer unreachable". */
    FAILED,
}

/** Observed facts. Generation-bearing facts older than the edge's current generation are dropped. */
sealed interface EdgeFact {
    data class Desired(val role: EdgeRole) : EdgeFact
    data object Undesired : EdgeFact
    data class OfferSent(val generation: Long, val lineageId: String, val iceRestart: Boolean) : EdgeFact
    data class RemoteOfferApplied(val generation: Long) : EdgeFact
    data class AnswerSent(val generation: Long) : EdgeFact
    data class AnswerApplied(val generation: Long) : EdgeFact
    data class IceState(val generation: Long, val state: String) : EdgeFact
    /** Whether the engine can legally build an ICE-restart offer on this generation right now. */
    data class RestartCapability(val generation: Long, val legal: Boolean) : EdgeFact
    /** Remote answerer asked us (offerer) to re-offer. */
    data class ReofferRequested(val generation: Long) : EdgeFact
    data class RebuildCompleted(val newGeneration: Long) : EdgeFact
    data class PeerReachability(val reachable: Boolean) : EdgeFact
}

/** Decisions. The executor (coordinator) performs them; the controller never touches I/O. */
sealed interface EdgeAction {
    val key: EdgeKey

    data class SendOffer(
        override val key: EdgeKey,
        val generation: Long,
        val iceRestart: Boolean,
        /** Non-null when this is a retransmit of a still-pending offer lineage. */
        val retransmitOfLineage: String?,
    ) : EdgeAction

    data class RequestReoffer(override val key: EdgeKey, val generation: Long) : EdgeAction

    data class RebuildPeerConnection(override val key: EdgeKey, val fromGeneration: Long) : EdgeAction

    data class MarkFailed(override val key: EdgeKey, val reason: String, val retryAtMs: Long) : EdgeAction

    data class Release(override val key: EdgeKey) : EdgeAction
}

enum class EdgeDeadline { NONE, ANSWER, OFFER, ICE, DEGRADED_GRACE, REBUILD, BACKOFF }

/** Emitted on every state change — the single log line a field RCA needs per edge. */
data class EdgeTransition(
    val key: EdgeKey,
    val generation: Long,
    val role: EdgeRole?,
    val from: EdgeLinkState,
    val to: EdgeLinkState,
    val cause: String,
    val iceState: String?,
    val deadline: EdgeDeadline,
    val deadlineAtMs: Long,
    val offerRetransmits: Int,
    val restarts: Int,
    val rebuilds: Int,
    val reofferRequests: Int,
)

/**
 * Timing and budgets. Defaults target a 8–10 node LAN mesh (up to 9 edges per node):
 * every edge escalates independently but the node-level cap keeps negotiation storms bounded.
 */
data class EdgeLinkConfig(
    /** OFFER_SENT without answer → retransmit same lineage. */
    val answerTimeoutMs: Long = 3_000L,
    val answerRetransmitMax: Int = 2,
    /** Answerer waiting for an offer before asking for one. */
    val offerTimeoutMs: Long = 5_000L,
    val reofferRequestMax: Int = 3,
    /** ICE_CHECKING / RESTARTING without CONNECTED → escalate. */
    val iceCheckingTimeoutMs: Long = 8_000L,
    /** At most this many ICE restarts per generation before a rebuild. */
    val restartMax: Int = 1,
    /** DISCONNECTED grace before acting; WebRTC often self-heals. */
    val degradedGraceMs: Long = 5_000L,
    /** Waiting for [EdgeFact.RebuildCompleted]. */
    val rebuildTimeoutMs: Long = 3_000L,
    val rebuildMax: Int = 3,
    val backoffInitialMs: Long = 5_000L,
    val backoffMaxMs: Long = 40_000L,
    /** Node-level cap on edges simultaneously in OFFER_SENT / RESTARTING / REBUILDING. */
    val maxConcurrentNegotiations: Int = 3,
)
