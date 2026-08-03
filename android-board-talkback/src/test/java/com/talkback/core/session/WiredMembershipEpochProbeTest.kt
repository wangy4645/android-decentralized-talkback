package com.talkback.core.session

import com.talkback.core.model.TopologyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0022 E.18.2: wired membership epoch probe three-state semantics. */
class WiredMembershipEpochProbeTest {

    private fun record(): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-wired", "M02")
        return EdgeRecoveryRecord(
            key = key,
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            channelId = "CH-01",
            recoveryAttemptId = 1L,
            recoveryStartedAtMs = 0L,
            obligationGeneration = 1L
        )
    }

    private fun probe(
        authorityByChannel: Map<String, TopologyDigest>,
        local: TopologyDigest,
        isAuthority: Boolean = false,
        authorityId: String = "AUTH-01"
    ): WiredMembershipEpochProbe {
        val context = RecoveryMembershipContext(
            channelId = "CH-01",
            conferenceSessionId = "sess-wired",
            localMembershipView = local,
            isLocalMembershipAuthority = isAuthority
        )
        return WiredMembershipEpochProbe(
            resolver = DefaultMembershipAuthorityResolver { channelId -> authorityByChannel[channelId] },
            resolveContext = { _, _ -> context },
            resolveAuthorityId = { authorityId }
        )
    }

    @Test
    fun checkedTrue_whenLocalMatchesAuthorityDigest() {
        val digest = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = 42)
        val result = probe(mapOf("CH-01" to digest), digest).probe(record(), "CH-01", "sess-wired")
        assertTrue(result is MembershipEpochProbeResult.Checked)
        val checked = result as MembershipEpochProbeResult.Checked
        assertTrue(checked.converged)
        assertEquals(3L, checked.expectedEpoch)
        assertEquals(3L, checked.observedEpoch)
    }

    @Test
    fun checkedFalse_whenEpochMismatch() {
        val authority = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = 42)
        val local = TopologyDigest(rosterEpoch = 2L, anchorEpoch = 1L, memberHash = 99)
        val result = probe(mapOf("CH-01" to authority), local).probe(record(), "CH-01", "sess-wired")
        assertTrue(result is MembershipEpochProbeResult.Checked)
        val checked = result as MembershipEpochProbeResult.Checked
        assertFalse(checked.converged)
        assertEquals(3L, checked.expectedEpoch)
        assertEquals(2L, checked.observedEpoch)
    }

    @Test
    fun unwired_whenAuthorityDigestMissing() {
        val local = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = 42)
        val result = probe(emptyMap(), local).probe(record(), "CH-01", "sess-wired")
        assertTrue(result is MembershipEpochProbeResult.Unwired)
        assertEquals("AUTHORITY_DIGEST_MISSING", (result as MembershipEpochProbeResult.Unwired).reason)
    }

    @Test
    fun checkedTrue_whenLocalIsMembershipAuthority() {
        val local = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = 42)
        val result = probe(emptyMap(), local, isAuthority = true).probe(record(), "CH-01", "sess-wired")
        assertTrue(result is MembershipEpochProbeResult.Checked)
        assertTrue((result as MembershipEpochProbeResult.Checked).converged)
    }

    @Test
    fun evaluator_unwiredDoesNotClaimChecked() {
        val r = record()
        val fact = ControlReconciliationEvaluator.evaluate(
            r,
            MembershipEpochProbeResult.Unwired("AUTHORITY_DIGEST_MISSING")
        )
        assertEquals(MembershipEpochProbeDisposition.UNWIRED, fact.membershipProbeDisposition)
        assertFalse(fact.membershipEpochConverged)
        assertEquals("MEMBERSHIP_AUTHORITY_UNWIRED", fact.mismatchReason())
    }
}