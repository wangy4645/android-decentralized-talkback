package com.talkback.core.session

import com.talkback.core.model.AuthorityDigestFreshness
import com.talkback.core.model.TopologyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0036 Phase 2.4: stale digest cache must be replaceable by live authority snapshot
 * before convergence compare — without changing the convergence predicate itself.
 */
class AuthorityDigestFreshnessConvergenceTest {

    @Test
    fun snapshotApply_invalidatesStaleHelloCache_thenEpochConverges() {
        val cache = ConcurrentHashMap<String, TopologyDigest>()
        cache["CH-01"] = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)

        val liveSnapshot = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = -528664596)
        val localAfterApply = liveSnapshot.copy()

        assertTrue(AuthorityDigestFreshness.isStaleReplacement(cache["CH-01"], liveSnapshot))
        cache["CH-01"] = liveSnapshot

        val resolver = DefaultMembershipAuthorityResolver { channelId -> cache[channelId] }
        val outcome = resolver.evaluateMembershipConvergence(
            RecoveryMembershipContext(
                channelId = "CH-01",
                conferenceSessionId = "cnf-1",
                localMembershipView = localAfterApply,
                isLocalMembershipAuthority = false
            ),
            localGroupSessionId = null
        )
        assertTrue(outcome.converged)
        assertEquals("ALIGNED", outcome.reason)
        assertEquals(1L, outcome.authorityDigest?.rosterEpoch)
        assertFalse(outcome.reason.contains("EPOCH_MISMATCH"))
    }
}