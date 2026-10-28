package com.talkback.core.session

/**
 * Phase 4-3 fixtures — meeting runtime observations (Coordinator-like, test only).
 */
object MeetingRuntimeObservationFixture {
    const val CONFERENCE_ID = "conf-meeting-shadow"
    val EIGHT_MEMBERS: List<String> = EightNodeControlledLifecycleContract.EIGHT_NODE_ROSTER

    fun membershipObservation(
        members: List<String> = EIGHT_MEMBERS,
        conferenceId: String = CONFERENCE_ID
    ) = MeetingRuntimeObservation(
        conferenceId = conferenceId,
        kind = MeetingRuntimeObservationKind.MEMBERSHIP,
        members = members
    )

    fun meshConnectionObservation(
        meshCompleted: Set<String>,
        members: List<String> = EIGHT_MEMBERS,
        conferenceId: String = CONFERENCE_ID
    ) = MeetingRuntimeObservation(
        conferenceId = conferenceId,
        kind = MeetingRuntimeObservationKind.MESH_CONNECTION,
        members = members,
        meshCompletedModules = meshCompleted
    )

    fun iceDisconnected(
        remote: String,
        snapshot: ConferenceTopologySnapshot,
        conferenceId: String = CONFERENCE_ID
    ): MeetingRuntimeObservation {
        val anchor = snapshot.anchorId!!
        return MeetingRuntimeObservation(
            conferenceId = conferenceId,
            kind = MeetingRuntimeObservationKind.ICE_STATE,
            iceObservation = IceEdgeObservation(
                localModuleId = anchor,
                remoteModuleId = remote,
                iceConnected = false
            )
        )
    }

    fun recoverySignalForRemote(
        snapshot: ConferenceTopologySnapshot,
        remote: String
    ): RecoverySignal {
        val anchor = snapshot.anchorId!!
        return RecoverySignal(
            kind = RecoverySignalKind.ICE_STATE_DISCONNECTED,
            source = RecoveryTriggerSource.ICE_MONITOR,
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(snapshot, MediaEdge(anchor, remote)),
            negotiation = NegotiationEdgeRef(
                sessionId = snapshot.conferenceId,
                localModuleId = anchor,
                remoteModuleId = remote
            )
        )
    }

    fun cleanJoinObservations(
        conferenceId: String = CONFERENCE_ID
    ): List<MeetingRuntimeObservation> {
        val meshCompleted = EIGHT_MEMBERS.filter { it != "M01" }.toSet()
        return listOf(
            membershipObservation(EIGHT_MEMBERS, conferenceId),
            meshConnectionObservation(meshCompleted, EIGHT_MEMBERS, conferenceId)
        ) + meshCompleted.map { remote ->
            MeetingRuntimeObservation(
                conferenceId = conferenceId,
                kind = MeetingRuntimeObservationKind.ICE_STATE,
                iceObservation = IceEdgeObservation("M01", remote, iceConnected = true)
            )
        }
    }

    fun leaveObservations(
        remainingMembers: List<String>,
        conferenceId: String = CONFERENCE_ID
    ): List<MeetingRuntimeObservation> {
        val meshCompleted = remainingMembers.filter { it != "M01" }.toSet()
        return listOf(
            membershipObservation(remainingMembers, conferenceId),
            meshConnectionObservation(meshCompleted, remainingMembers, conferenceId)
        )
    }

    fun mismatchedLegacyMesh(
        authorityMembers: List<String>,
        conferenceId: String = CONFERENCE_ID
    ): List<MeetingRuntimeObservation> =
        listOf(
            membershipObservation(authorityMembers, conferenceId),
            meshConnectionObservation(setOf("M02", "M03", "M99"), authorityMembers, conferenceId)
        )
}
