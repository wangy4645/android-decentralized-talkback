package com.talkback.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryNegotiationAuthorityTest {

    private fun key(episode: Long = 2L) = RecoveryNegotiationAuthority.RecoveryNegotiationKey(
        sessionId = "sess-1",
        edgeModuleId = "M01",
        recoveryEpisodeId = episode
    )

  private fun input(
        local: String,
        remote: String,
        existing: String? = null,
        coordinator: String? = null,
        episode: Long = 2L
    ): RecoveryNegotiationAuthority.OwnerElectionInput {
        return RecoveryNegotiationAuthority.OwnerElectionInput(
            key = key(episode),
            localModuleId = local,
            remoteModuleId = remote,
            existingTransactionOwnerModuleId = existing,
            recoveryCoordinatorOwnerModuleId = coordinator
        )
    }

    @Test
    fun resolveOwner_sameInputs_M01_and_M03_agree() {
        val m01 = RecoveryNegotiationAuthority.resolveOwner(
            input(local = "M01", remote = "M03", coordinator = "M01")
        )
        val m03 = RecoveryNegotiationAuthority.resolveOwner(
            input(local = "M03", remote = "M01", coordinator = "M01")
        )
        assertEquals("M01", m01.negotiationOwnerModuleId)
        assertEquals("M01", m03.negotiationOwnerModuleId)
    }

    @Test
    fun bootstrapCoordinator_participant_vs_authority() {
        assertEquals("M03", RecoveryNegotiationAuthority.bootstrapCoordinatorOwner("M03", "M01", true))
        assertEquals("M01", RecoveryNegotiationAuthority.bootstrapCoordinatorOwner("M03", "M01", false))
    }

    @Test
    fun validateWireOwner_conflict_isObservable() {
        val result = RecoveryNegotiationAuthority.validateWireOwner(
            input(local = "M03", remote = "M01", coordinator = "M01"),
            wireOwnerModuleId = "M03"
        )
        assertEquals(RecoveryNegotiationAuthority.WireOwnerValidation.CONFLICT, result.validation)
        assertEquals("M01", result.canonicalOwner)
        assertEquals("M03", result.wireOwner)
    }

    @Test
    fun validateWireOwner_ok_when_wire_matches_canonical() {
        val result = RecoveryNegotiationAuthority.validateWireOwner(
            input(local = "M03", remote = "M01", existing = "M01"),
            wireOwnerModuleId = "M01"
        )
        assertEquals(RecoveryNegotiationAuthority.WireOwnerValidation.OK, result.validation)
    }

    @Test
    fun glareResolver_keepLocal_when_local_is_owner() {
        val resolution = RecoveryNegotiationAuthority.resolveGlare(
            localModuleId = "M03",
            localOwner = "M03",
            remoteOwner = "M01",
            localSignalingState = "HAVE_LOCAL_OFFER",
            localDescType = "OFFER",
            remoteDescType = "OFFER",
            isPoliteNegotiator = false
        )
        assertEquals(RecoveryNegotiationAuthority.GlareResolution.KEEP_LOCAL, resolution)
    }

    @Test
    fun glareResolver_acceptRemote_when_remote_is_owner() {
        val resolution = RecoveryNegotiationAuthority.resolveGlare(
            localModuleId = "M03",
            localOwner = "M01",
            remoteOwner = "M01",
            localSignalingState = "HAVE_LOCAL_OFFER",
            localDescType = "OFFER",
            remoteDescType = "OFFER",
            isPoliteNegotiator = true
        )
        assertEquals(RecoveryNegotiationAuthority.GlareResolution.ACCEPT_REMOTE, resolution)
    }

    @Test
    fun bootstrapCoordinator_inboundReattach_local_is_coordinator() {
        assertEquals(
            "LOCAL",
            RecoveryNegotiationAuthority.bootstrapCoordinatorOwner(
                localModuleId = "LOCAL",
                remoteModuleId = "M20",
                initiatesReattach = false,
                recoveryViaInboundReattach = true
            )
        )
    }

    @Test
    fun glareResolver_noGlare_when_stable() {
        val resolution = RecoveryNegotiationAuthority.resolveGlare(
            localModuleId = "M03",
            localOwner = "M01",
            remoteOwner = "M01",
            localSignalingState = "STABLE",
            localDescType = "ANSWER",
            remoteDescType = "OFFER",
            isPoliteNegotiator = false
        )
        assertEquals(RecoveryNegotiationAuthority.GlareResolution.NO_GLARE, resolution)
    }
}