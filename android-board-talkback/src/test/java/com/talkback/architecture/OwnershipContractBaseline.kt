package com.talkback.architecture

/**
 * Known Ownership debt ceiling (RO-1 v1). Counts must **only decrease** as RO-2…RO-7 land.
 *
 * @see docs/runtime/runtime-public-api.md
 * @see docs/prd-runtime-ownership-refactor.md
 */
object OwnershipContractBaseline {

    /** [TalkbackCoordinator] direct ``session.groupMembers =`` — target 0 in RO-2. */
    const val COORDINATOR_GROUP_MEMBERS_DIRECT_ASSIGN: Int = 6

    /** [TalkbackCoordinator] direct ``session.floorAuthorityModuleId =`` — target 0 in RO-3. */
    const val COORDINATOR_FLOOR_AUTHORITY_DIRECT_ASSIGN: Int = 4

    /**
     * ``signalPeersByModule`` reads inside [resolveFloorAuthorityRoute] (Floor send decision).
     * Target 0 in RO-4/RO-5.
     */
    const val COORDINATOR_RESOLVE_FLOOR_SIGNAL_PEER_READS: Int = 0

    /**
     * ``participants.media`` / ``meshParticipant().media`` assignments outside whitelist.
     * Whitelist: [ConferenceParticipantManager], [MediaRuntime]. Target 0 in RO-7.
     */
    const val PARTICIPANT_MEDIA_WRITES_OUTSIDE_WHITELIST: Int = 10
}
