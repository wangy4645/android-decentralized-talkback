package com.talkback.core.session

import com.talkback.core.model.TopologyDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0022 Q7: membership convergence reads channel GROUP view vs authority observation. */
class MembershipAuthorityResolverTest {

    private fun resolver(authorityByChannel: Map<String, TopologyDigest> = emptyMap()) =
        DefaultMembershipAuthorityResolver { channelId -> authorityByChannel[channelId] }

    @Test
    fun convergedWhenLocalMatchesAuthorityDigest() {
        val authority = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val local = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val resolver = resolver(mapOf("CH-01" to authority))
        val context = RecoveryMembershipContext(
            channelId = "CH-01",
            conferenceSessionId = "conf-epoch-1",
            localMembershipView = local
        )
        assertTrue(resolver.isMembershipEpochConverged(context))
    }

    @Test
    fun notConvergedWhenConferenceEpochWouldLagButLocalGroupEpochMatchesAuthority() {
        val authority = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val groupLocal = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val resolver = resolver(mapOf("CH-01" to authority))
        val context = RecoveryMembershipContext(
            channelId = "CH-01",
            conferenceSessionId = "1cb3a3e4-conference-roster-epoch-1",
            localMembershipView = groupLocal
        )
        assertTrue(resolver.isMembershipEpochConverged(context))
    }

    @Test
    fun notConvergedWhenLocalEpochBehindAuthority() {
        val authority = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val local = TopologyDigest(rosterEpoch = 2L, anchorEpoch = 1L, memberHash = 12345)
        val resolver = resolver(mapOf("CH-01" to authority))
        val context = RecoveryMembershipContext(
            channelId = "CH-01",
            conferenceSessionId = "conf",
            localMembershipView = local
        )
        assertFalse(resolver.isMembershipEpochConverged(context))
    }

    @Test
    fun convergedWhenLocalIsMembershipAuthority() {
        val local = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val resolver = resolver(emptyMap())
        val context = RecoveryMembershipContext(
            channelId = "CH-01",
            conferenceSessionId = "conf",
            localMembershipView = local,
            isLocalMembershipAuthority = true
        )
        assertTrue(resolver.isMembershipEpochConverged(context))
    }

    @Test
    fun notConvergedWhenAuthorityDigestMissing() {
        val local = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val resolver = resolver(emptyMap())
        val context = RecoveryMembershipContext(
            channelId = "CH-01",
            conferenceSessionId = "conf",
            localMembershipView = local
        )
        assertFalse(resolver.isMembershipEpochConverged(context))
    }
}