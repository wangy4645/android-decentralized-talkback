package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupIdentityStabilityTest {

    private fun session(vararg memberIds: String, local: String = "M02"): TalkbackSession {
        val s = TalkbackSession(
            id = "s1",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId(local), EndpointId("E01")),
            channelId = "CH-01"
        )
        s.groupMembers = memberIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
        s.memberModules.addAll(memberIds.map { ModuleId(it) })
        GroupMembershipSupport.syncMembershipFromGroupMembers(s)
        s.accepted = true
        return s
    }

    @Test
    fun stable_whenDigestAlignedAndEndpointsMatchHello() {
        val s = session("M01", "M02", "M03")
        val result = GroupIdentityStability.evaluate(
            session = s,
            localModuleId = "M02",
            authorityDigestSeen = true,
            membershipDigestAlignedWithAuthority = true,
            verifiedHelloEndpointId = { "E01" }
        )
        assertTrue(result.stable)
    }

    @Test
    fun unstable_whenMembershipDigestNotAligned() {
        val s = session("M01", "M02", "M03")
        val result = GroupIdentityStability.evaluate(
            session = s,
            localModuleId = "M02",
            authorityDigestSeen = true,
            membershipDigestAlignedWithAuthority = false,
            verifiedHelloEndpointId = { "E01" }
        )
        assertEquals(GroupIdentityStability.UnstableReason.MEMBERSHIP_DIGEST_MISMATCH, result.reason)
    }

    @Test
    fun unstable_whenRosterEndpointDiffersFromVerifiedHello() {
        val s = session("M01", "M02", "M03")
        val result = GroupIdentityStability.evaluate(
            session = s,
            localModuleId = "M02",
            authorityDigestSeen = true,
            membershipDigestAlignedWithAuthority = true,
            verifiedHelloEndpointId = { moduleId -> if (moduleId == "M03") "E03" else "E01" }
        )
        assertEquals(GroupIdentityStability.UnstableReason.ENDPOINT_DRIFT, result.reason)
        assertEquals("M03-E01!=E03", result.detail)
    }

    @Test
    fun stable_afterR35ReconcileAlignsRosterWithHello() {
        val s = session("M01", "M02", "M03")
        GroupMembershipSupport.replaceGroupMemberEndpoint(s, "M03", EndpointId("E03"))
        val result = GroupIdentityStability.evaluate(
            session = s,
            localModuleId = "M02",
            authorityDigestSeen = true,
            membershipDigestAlignedWithAuthority = true,
            verifiedHelloEndpointId = { moduleId -> if (moduleId == "M03") "E03" else "E01" }
        )
        assertTrue(result.stable)
    }

    @Test
    fun stable_whenAuthorityDigestNotYetSeen_skipsDigestGate() {
        val s = session("M01", "M02", "M03")
        val result = GroupIdentityStability.evaluate(
            session = s,
            localModuleId = "M02",
            authorityDigestSeen = false,
            membershipDigestAlignedWithAuthority = false,
            verifiedHelloEndpointId = { "E01" }
        )
        assertTrue(result.stable)
    }

    @Test
    fun stable_whenHelloUnknownForRemote_skipsDriftCheck() {
        val s = session("M01", "M02", "M03")
        val result = GroupIdentityStability.evaluate(
            session = s,
            localModuleId = "M02",
            authorityDigestSeen = true,
            membershipDigestAlignedWithAuthority = true,
            verifiedHelloEndpointId = { moduleId -> if (moduleId == "M01") "E01" else null }
        )
        assertTrue(result.stable)
    }
}
