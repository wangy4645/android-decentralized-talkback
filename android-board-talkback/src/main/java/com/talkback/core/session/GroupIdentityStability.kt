package com.talkback.core.session

/**
 * R37: Group session endpoint identity stability for PTT / floor-request gating (ADR-0009).
 */
object GroupIdentityStability {

    enum class UnstableReason {
        MEMBERSHIP_DIGEST_MISMATCH,
        ENDPOINT_DRIFT
    }

    data class Result(
        val stable: Boolean,
        val reason: UnstableReason? = null,
        val detail: String? = null
    )

    fun evaluate(
        session: TalkbackSession,
        localModuleId: String,
        authorityDigestSeen: Boolean,
        membershipDigestAlignedWithAuthority: Boolean,
        verifiedHelloEndpointId: (String) -> String?
    ): Result {
        if (session.type != SessionType.GROUP) return Result(stable = true)
        if (authorityDigestSeen && !membershipDigestAlignedWithAuthority) {
            return Result(
                stable = false,
                reason = UnstableReason.MEMBERSHIP_DIGEST_MISMATCH
            )
        }
        for (member in GroupMembershipSupport.canonicalRosterEndpoints(session)) {
            if (member.moduleId.value == localModuleId) continue
            val helloEndpoint = verifiedHelloEndpointId(member.moduleId.value) ?: continue
            if (member.endpointId.value != helloEndpoint) {
                return Result(
                    stable = false,
                    reason = UnstableReason.ENDPOINT_DRIFT,
                    detail = "${member.key}!=$helloEndpoint"
                )
            }
        }
        return Result(stable = true)
    }
}
