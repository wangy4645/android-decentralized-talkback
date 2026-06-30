package com.talkback.core.grouphealth

import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.GroupMediaTopology

/**
 * Immutable Group PTT convergence projection (ADR-0008 schemaVersion 1).
 */
data class GroupRuntimeHealth(
    val schemaVersion: Int,
    val sessionId: String?,
    val localModuleId: String,
    val topologyKind: GroupMediaTopology?,
    val members: List<String>,
    val rosterEpoch: Long,
    val memberHash: Int,
    val sessionAccepted: Boolean,
    val membershipDigestAligned: Boolean,
    val suspectPeers: Set<String>,
    val membershipReconciled: Boolean,
    val meshDesiredLinks: Set<MeshLink>,
    val meshSignaledPeers: Set<String>,
    val meshIceConnectedPeers: Set<String>,
    val meshMissingSignal: Set<String>,
    val meshMissingIce: Set<String>,
    val transmitRequiredPeers: Set<String>,
    val transmitReadyPeers: Set<String>,
    val transmitMissingPeers: Set<String>,
    val groupTopologyReadiness: GroupTopologyReadiness,
    val mappedChannelReadiness: ChannelReadiness,
    val convergenceAgeMs: Long,
    val floorOwnerKey: String?,
    val floorEpoch: Long,
    val floorVersion: Long,
    val channelGated: Boolean
)
