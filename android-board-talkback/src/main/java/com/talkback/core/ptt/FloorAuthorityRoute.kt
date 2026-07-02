package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.transport.TransportBinding

/**
 * ADR-0013: Floor authority UDP target is a pure projection of canonical roster endpoint
 * plus [TransportRegistry] binding — never [TalkbackSession.remotePeersByModule] or discovery caches.
 */
data class FloorRouteDecision(
    val authorityModuleId: ModuleId,
    val authorityEndpoint: EndpointAddress,
    val signalPeer: PeerTarget,
    val authorityEpoch: Long,
    val peerSetHash: Int,
    val resolvedFrom: String = RESOLVED_FROM
) {
    companion object {
        const val RESOLVED_FROM = "ROSTER_ENDPOINT+TRANSPORT_REGISTRY"
    }
}

enum class FloorRouteInvalidReason {
    AUTHORITY_MISSING,
    ROSTER_MISS,
    SIGNAL_PEER_MISSING
}

sealed class FloorAuthorityRouteResult {
    data class Ok(val decision: FloorRouteDecision) : FloorAuthorityRouteResult()
    data class Invalid(
        val reason: FloorRouteInvalidReason,
        val authorityModuleId: ModuleId?
    ) : FloorAuthorityRouteResult()
}

object FloorAuthorityRoute {
    fun resolve(
        authorityModuleId: ModuleId?,
        authorityEndpoint: EndpointAddress?,
        authorityEpoch: Long,
        transport: TransportBinding?
    ): FloorAuthorityRouteResult {
        val authorityId = authorityModuleId
            ?: return FloorAuthorityRouteResult.Invalid(FloorRouteInvalidReason.AUTHORITY_MISSING, null)
        val endpoint = authorityEndpoint
            ?: return FloorAuthorityRouteResult.Invalid(FloorRouteInvalidReason.ROSTER_MISS, authorityId)
        val binding = transport
            ?: return FloorAuthorityRouteResult.Invalid(FloorRouteInvalidReason.SIGNAL_PEER_MISSING, authorityId)
        if (binding.epoch < authorityEpoch) {
            return FloorAuthorityRouteResult.Invalid(FloorRouteInvalidReason.SIGNAL_PEER_MISSING, authorityId)
        }
        val signalPeer = binding.peer
        val peerSetHash = peerSetFingerprint(authorityId, endpoint, signalPeer, authorityEpoch)
        return FloorAuthorityRouteResult.Ok(
            FloorRouteDecision(
                authorityModuleId = authorityId,
                authorityEndpoint = endpoint,
                signalPeer = signalPeer,
                authorityEpoch = authorityEpoch,
                peerSetHash = peerSetHash
            )
        )
    }

    internal fun peerSetFingerprint(
        authorityId: ModuleId,
        authorityEndpoint: EndpointAddress,
        signalPeer: PeerTarget,
        authorityEpoch: Long
    ): Int {
        var hash = 17
        hash = 31 * hash + authorityId.value.hashCode()
        hash = 31 * hash + authorityEndpoint.key.hashCode()
        hash = 31 * hash + signalPeer.host.hashCode()
        hash = 31 * hash + signalPeer.port
        hash = 31 * hash + authorityEpoch.hashCode()
        return hash
    }
}
