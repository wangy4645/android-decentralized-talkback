package com.talkback.core.session

import com.talkback.core.model.TopologyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipAuthorityResolveTraceTest {

    private fun resolver(authorityByChannel: Map<String, TopologyDigest> = emptyMap()) =
        DefaultMembershipAuthorityResolver { channelId -> authorityByChannel[channelId] }

    @Test
    fun formatIncludesDiagFields() {
        val outcome = MembershipAuthorityResolveOutcome(
            channelId = "CH-01",
            conferenceSessionId = "conf-1",
            resolverImpl = "DefaultMembershipAuthorityResolver",
            authorityDigestSource = MembershipAuthorityResolveTrace.SOURCE_LAST_SEEN_AUTHORITY_DIGEST,
            localGroupSessionId = "grp:CH-01",
            localDigest = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082),
            authorityDigest = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082),
            converged = true,
            reason = "ALIGNED"
        )
        val line = MembershipAuthorityResolveTrace.format(outcome)
        assertTrue(line.startsWith("MEMBERSHIP_AUTHORITY_RESOLVE_TRACE"))
        assertTrue(line.contains("channelId=CH-01"))
        assertTrue(line.contains("conferenceSessionId=conf-1"))
        assertTrue(line.contains("resolverImpl=DefaultMembershipAuthorityResolver"))
        assertTrue(line.contains("authorityDigestSource=lastSeenAuthorityDigestByChannel"))
        assertTrue(line.contains("localGroupSessionId=grp:CH-01"))
        assertTrue(line.contains("localEpoch=3"))
        assertTrue(line.contains("authorityEpoch=3"))
        assertTrue(line.contains("localHash=-925203082"))
        assertTrue(line.contains("authorityHash=-925203082"))
        assertTrue(line.contains("result=true"))
        assertTrue(line.contains("reason=ALIGNED"))
    }

    @Test
    fun evaluateEpochMismatch() {
        val authority = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082)
        val local = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = -925203082)
        val outcome = resolver(mapOf("CH-01" to authority)).evaluateMembershipConvergence(
            RecoveryMembershipContext(
                channelId = "CH-01",
                conferenceSessionId = "conf",
                localMembershipView = local
            ),
            localGroupSessionId = "grp:CH-01"
        )
        assertFalse(outcome.converged)
        assertEquals("EPOCH_MISMATCH", outcome.reason)
    }

    @Test
    fun evaluateAuthorityDigestMissing() {
        val local = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = -925203082)
        val outcome = resolver(emptyMap()).evaluateMembershipConvergence(
            RecoveryMembershipContext(
                channelId = "CH-01",
                conferenceSessionId = "conf",
                localMembershipView = local
            ),
            localGroupSessionId = null
        )
        assertFalse(outcome.converged)
        assertEquals("AUTHORITY_DIGEST_MISSING", outcome.reason)
    }
}
