package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.ModuleId
import com.talkback.core.ptt.FloorState
import com.talkback.core.ptt.PttStateMachine
import com.talkback.core.signaling.PeerTarget
import java.util.UUID

/**
 * Per-session state: PTT machine, floor ownership, media link key, trace id.
 */
class TalkbackSession(
    val id: String,
    val type: SessionType,
    val local: EndpointAddress,
    val channelId: String?,
    val traceId: String = UUID.randomUUID().toString().take(8)
) {
    val ptt = PttStateMachine()
    val floor = FloorState()

    var remote: EndpointAddress? = null
    var remotePeer: PeerTarget? = null
    val memberModules: MutableSet<ModuleId> = linkedSetOf()
    val remotePeersByModule: MutableMap<String, PeerTarget> = linkedMapOf()
    var initiatorModuleId: ModuleId? = null
    var floorAuthorityModuleId: ModuleId? = null
    var groupMembers: List<EndpointAddress> = emptyList()
    /** GROUP invitees not yet in [groupMembers]; excluded from digest / HELLO. */
    val pendingInviteeEndpoints: MutableMap<String, EndpointAddress> = linkedMapOf()
    /** Soft-leave roster: prior members who may rejoin without a first-time invite. */
    val leftMemberEndpoints: MutableMap<String, EndpointAddress> = linkedMapOf()
    val meshCompletedModules: MutableSet<String> = linkedSetOf()
    var mediaTopology: GroupMediaTopology = GroupMediaTopology.MESH
    var anchorModuleId: ModuleId? = null
    var backupAnchorModuleId: ModuleId? = null
    var anchorEpoch: Long = 0L
    var anchorTenureStartMs: Long = 0L
    /** Conference half-hot: ICE/DTLS pre-established to backup, audio not forwarded yet. */
    val backupStandbyPeers: MutableSet<String> = linkedSetOf()
    var rosterEpochMs: Long = System.currentTimeMillis()
    /** Monotonic membership generation; bumped only on EVICT / authority roster apply. */
    var rosterEpoch: Long = GroupMembershipSupport.INITIAL_ROSTER_EPOCH
    /** Reserved for Phase 3 topology rebuilds; P0 stays at 0. */
    var meshGeneration: Long = 0L
    val membershipStateByModule: MutableMap<String, GroupMemberReachability> = linkedMapOf()
    val suspectSinceMsByModule: MutableMap<String, Long> = linkedMapOf()
    /** Per-member invite + media state for mesh sessions (conference roster accuracy). */
    val participants: MutableMap<String, ParticipantState> = linkedMapOf()

    var lastActiveMs: Long = System.currentTimeMillis()
    var accepted: Boolean = false
    var unicastPhase: UnicastCallPhase? = null
    var localInitiated: Boolean = false
    var muted: Boolean = false
    /** SDP from CALL_INVITE, kept until manual accept. */
    var pendingRemoteOfferSdp: String? = null
    /** Wait for ICE CONNECTED before opening the mic. */
    var pendingTransmit: Boolean = false
    var lastFloorRequestMs: Long = 0L
    @Volatile
    var localFloorPreempted: Boolean = false
    /** Set when acquire-release timeout fires; consumed once by UI for capture-failure toast. */
    var localAcquireTimedOut: Boolean = false
    /** Frozen Channel.members at session create (ADR-0002 R8). */
    var channelMemberSnapshot: Set<String> = emptySet()
    /** Unicast: optional origin channel label only; does not gate runtime or mutate Channel. */
    var sessionOriginChannelId: String? = null
    var disposition: SessionDisposition = SessionDisposition.ACTIVE

    fun touch() {
        lastActiveMs = System.currentTimeMillis()
    }

    fun primaryRemotePeer(): PeerTarget? = remotePeer ?: remotePeersByModule.values.firstOrNull()

    fun mediaLinkKey(moduleId: String): String = "${id}_$moduleId"

    fun participant(moduleId: String): ParticipantState =
        participants.getOrPut(moduleId) {
            ParticipantState(ModuleId(moduleId))
        }

    fun syncParticipantsFromMembers(localModuleId: ModuleId) {
        groupMembers.forEach { member ->
            val id = member.moduleId.value
            if (id == localModuleId.value) return@forEach
            participant(id)
        }
    }
}
