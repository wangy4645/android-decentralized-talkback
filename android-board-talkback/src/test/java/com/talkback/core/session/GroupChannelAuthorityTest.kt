package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0053 — GroupChannelAuthority unit tests (phase 1). */
class GroupChannelAuthorityTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val channelId = "CH-176"
    private val sessionId = "sess-stale-001"

    @Test
    fun e1_authorityHolderLostSession_becomesStale() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        val snap = auth.evaluate(
            channelId,
            obs(m01, sessionId = null)
        )
        assertEquals(GroupAuthorityState.STALE_PRIMARY, snap.state)
        assertEquals(GroupAuthorityEvidenceKind.E1_LOCAL_AUTHORITY_MISSING, snap.evidence?.kind)
    }

    @Test
    fun followerWithoutSession_isBootstrapRequired_notStale() {
        val auth = GroupChannelAuthority()
        val snap = auth.evaluate(
            channelId,
            obs(m02, dialable = listOf("M01"), sessionId = null)
        )
        assertEquals(GroupAuthorityState.BOOTSTRAP_REQUIRED, snap.state)
        assertFalse(snap.isStale)
        assertFalse(auth.mayEmitBootstrap(snap, m02))
        assertEquals(m01, snap.candidate)
    }

    @Test
    fun e2_sessionIdentityInvalid_becomesStale() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        val snap = auth.evaluate(
            channelId,
            obs(m01, sessionIdentityValid = false)
        )
        assertEquals(GroupAuthorityState.STALE_PRIMARY, snap.state)
        assertEquals(GroupAuthorityEvidenceKind.E2_SESSION_IDENTITY_INVALID, snap.evidence?.kind)
    }

    @Test
    fun e3_joinQueuedWithoutBootstrapEmission_becomesStale() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        val snap = auth.evaluate(
            channelId,
            obs(
                m01,
                claimedRoster = setOf("M02"),
                peerPosture = mapOf("M02" to PeerBootstrapPostureEvidence(joinIngressQueuedNoSession = true)),
                joinOrientedMaintenance = true
            )
        )
        assertEquals(GroupAuthorityState.STALE_PRIMARY, snap.state)
        assertEquals(GroupAuthorityEvidenceKind.E3_MEMBERSHIP_BOOTSTRAP_INCOMPATIBILITY, snap.evidence?.kind)
    }

    @Test
    fun e3_queuedAloneDuringActiveBootstrap_doesNotBecomeStale() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        val snap = auth.evaluate(
            channelId,
            obs(
                m01,
                claimedRoster = setOf("M02"),
                peerPosture = mapOf("M02" to PeerBootstrapPostureEvidence(joinIngressQueuedNoSession = true)),
                joinOrientedMaintenance = true,
                activeBootstrapEmission = true
            )
        )
        assertEquals(GroupAuthorityState.VALID_PRIMARY, snap.state)
    }

    @Test
    fun coldStartCandidateWithoutSession_isBootstrapRequired_notStale() {
        val auth = GroupChannelAuthority()
        val snap = auth.evaluate(
            channelId,
            obs(m01, sessionId = null)
        )
        assertEquals(GroupAuthorityState.BOOTSTRAP_REQUIRED, snap.state)
        assertFalse(snap.isStale)
        assertTrue(auth.mayEmitBootstrap(snap, m01))
    }

    @Test
    fun stale_invalidation_clearsSessionHandle() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        val result = auth.commitAuthorityInvalidation(channelId)
        assertEquals(sessionId, result.oldSessionId)
        assertNull(auth.localSessionId(channelId))
        assertEquals(GroupAuthorityState.AUTHORITY_INVALIDATED, auth.snapshot(channelId).state)
    }

    @Test
    fun stale_invalidation_clearsPendingJoins() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.onJoinIngress(
            channelId,
            joinCtx(auth, queuedNoSession = false),
            "M02",
            queuedNoSession = false
        )
        assertEquals(1, auth.pendingJoinCount(channelId, sessionId))

        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        val result = auth.commitAuthorityInvalidation(channelId)
        assertEquals(1, result.clearedPendingJoinCount)
        assertEquals(0, auth.pendingJoinCount(channelId, sessionId))
    }

    @Test
    fun t3_stalePeriodJoin_doesNotEnterPending() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        val stale = auth.evaluate(
            channelId,
            obs(
                m01,
                claimedRoster = setOf("M02"),
                peerPosture = mapOf("M02" to PeerBootstrapPostureEvidence(joinIngressQueuedNoSession = true)),
                joinOrientedMaintenance = true
            )
        )
        val decision = auth.onJoinIngress(
            channelId,
            joinCtx(auth, snapshot = stale, queuedNoSession = true),
            "M02",
            queuedNoSession = true
        )
        assertEquals(GroupJoinIngressDecision.STALE_AUTHORITY_EVIDENCE_ONLY, decision)
        assertEquals(0, auth.pendingJoinCount(channelId, sessionId))
    }

    @Test
    fun invalidated_joinIsRejected_notQueued() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        auth.commitAuthorityInvalidation(channelId)
        val invalidated = auth.snapshot(channelId)
        val decision = auth.onJoinIngress(
            channelId,
            joinCtx(auth, snapshot = invalidated, queuedNoSession = true),
            "M02",
            queuedNoSession = true
        )
        assertEquals(GroupJoinIngressDecision.STALE_AUTHORITY_REJECTED, decision)
        assertEquals(0, auth.pendingJoinCount(channelId, sessionId))
    }

    @Test
    fun postInvalidationBootstrap_rejectsJoinWithSameSessionId() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        auth.commitAuthorityInvalidation(channelId)
        val bootstrap = auth.advanceToBootstrapRequired(channelId, obs(m01, sessionId = null))
        assertTrue(bootstrap.evidence?.invalidationCommitted == true)

        val decision = auth.decideJoinIngress(
            JoinIngressContext(
                sessionId = sessionId,
                authoritySnapshot = bootstrap,
                isKnownCurrentSession = false,
                hasActiveBootstrapEmission = false
            )
        )
        assertEquals(GroupJoinIngressDecision.STALE_AUTHORITY_REJECTED, decision)
    }

    @Test
    fun coldStartBootstrap_allowsJoinQueueWhenSessionAbsent() {
        val auth = GroupChannelAuthority()
        val bootstrap = auth.evaluate(channelId, obs(m01, sessionId = null))
        val decision = auth.decideJoinIngress(
            JoinIngressContext(
                sessionId = sessionId,
                authoritySnapshot = bootstrap,
                isKnownCurrentSession = false,
                hasActiveBootstrapEmission = false
            )
        )
        assertEquals(GroupJoinIngressDecision.ACCEPT_OR_QUEUE_NORMAL, decision)
    }

    @Test
    fun maintenanceForbiddenUnlessValidPrimary() {
        val auth = GroupChannelAuthority()
        val stale = auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        assertFalse(auth.mayPerformMaintenance(stale))

        val valid = auth.markRecovered(channelId, sessionId, m01)
        assertTrue(auth.mayPerformMaintenance(valid))
    }

    @Test
    fun peerBootstrapEmission_blocksLocalEmission() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        auth.commitAuthorityInvalidation(channelId)
        val snap = auth.advanceToBootstrapRequired(
            channelId,
            obs(
                m02,
                dialable = listOf("M01"),
                sessionId = null,
                peerCandidateBootstrapEmission = m01
            )
        )
        assertFalse(auth.mayEmitBootstrap(snap, m02))
    }

    @Test
    fun advanceAfterInvalidation_doesNotReenterStaleOnNullSession() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        auth.commitAuthorityInvalidation(channelId)
        auth.advanceToBootstrapRequired(channelId, obs(m01, sessionId = null))
        val snap = auth.evaluate(channelId, obs(m01, sessionId = null))
        assertEquals(GroupAuthorityState.BOOTSTRAP_REQUIRED, snap.state)
        assertFalse(snap.isStale)
    }

    @Test
    fun advanceToBootstrapRequired_allowsCandidateEmission() {
        val auth = GroupChannelAuthority()
        auth.markRecovered(channelId, sessionId, m01)
        auth.evaluate(channelId, obs(m01, sessionIdentityValid = false))
        auth.commitAuthorityInvalidation(channelId)
        val snap = auth.advanceToBootstrapRequired(
            channelId,
            obs(m01, sessionId = null)
        )
        assertEquals(GroupAuthorityState.BOOTSTRAP_REQUIRED, snap.state)
        assertTrue(auth.mayEmitBootstrap(snap, m01))
    }

    private fun joinCtx(
        auth: GroupChannelAuthority,
        sessionId: String = this.sessionId,
        snapshot: GroupAuthoritySnapshot? = null,
        isKnownCurrentSession: Boolean = false,
        hasActiveBootstrapEmission: Boolean = false,
        @Suppress("UNUSED_PARAMETER") queuedNoSession: Boolean = true
    ): JoinIngressContext = JoinIngressContext(
        sessionId = sessionId,
        authoritySnapshot = snapshot ?: auth.snapshot(channelId),
        isKnownCurrentSession = isKnownCurrentSession,
        hasActiveBootstrapEmission = hasActiveBootstrapEmission
    )

    private fun obs(
        local: ModuleId,
        dialable: List<String> = listOf("M02", "M03"),
        sessionId: String? = this.sessionId,
        sessionIdentityValid: Boolean = true,
        sessionTerminal: Boolean = false,
        claimedRoster: Set<String> = emptySet(),
        peerPosture: Map<String, PeerBootstrapPostureEvidence> = emptyMap(),
        joinOrientedMaintenance: Boolean = false,
        activeBootstrapEmission: Boolean = false,
        peerCandidateBootstrapEmission: ModuleId? = null
    ): GroupAuthorityObservation = GroupAuthorityObservation.withCandidate(local, dialable) {
        localSessionId = sessionId
        this.sessionIdentityValid = sessionIdentityValid
        this.sessionTerminal = sessionTerminal
        this.claimedRoster = claimedRoster
        this.peerBootstrapPosture = peerPosture
        this.joinOrientedMaintenanceActive = joinOrientedMaintenance
        this.activeBootstrapEmission = activeBootstrapEmission
        this.peerCandidateBootstrapEmission = peerCandidateBootstrapEmission
    }
}
