package com.talkback.governance.gate

sealed interface BlockReason {
    val category: BlockCategory
    val code: String
}

sealed interface ReadinessReason : BlockReason {
    override val category: BlockCategory get() = BlockCategory.READINESS

    data object MembershipNotReady : ReadinessReason {
        override val code: String = "MEMBERSHIP_NOT_READY"
    }

    data object RoutingNotReady : ReadinessReason {
        override val code: String = "ROUTING_NOT_READY"
    }

    data object AuthorityNotReady : ReadinessReason {
        override val code: String = "AUTHORITY_NOT_READY"
    }

    data object ConferenceNotReady : ReadinessReason {
        override val code: String = "CONFERENCE_NOT_READY"
    }

    data object MediaNotReady : ReadinessReason {
        override val code: String = "MEDIA_NOT_READY"
    }

    data object DirectoryNotReady : ReadinessReason {
        override val code: String = "DIRECTORY_NOT_READY"
    }
}

sealed interface PolicyReason : BlockReason {
    override val category: BlockCategory get() = BlockCategory.POLICY

    data object TransitionInProgress : PolicyReason {
        override val code: String = "TRANSITION_IN_PROGRESS"
    }

    data object CooldownActive : PolicyReason {
        override val code: String = "COOLDOWN_ACTIVE"
    }

    data object ChannelBusy : PolicyReason {
        override val code: String = "CHANNEL_BUSY"
    }

    data object RateLimited : PolicyReason {
        override val code: String = "RATE_LIMITED"
    }

    data object RoleRestricted : PolicyReason {
        override val code: String = "ROLE_RESTRICTED"
    }

    data object OperationNotAllowed : PolicyReason {
        override val code: String = "OPERATION_NOT_ALLOWED"
    }
}
