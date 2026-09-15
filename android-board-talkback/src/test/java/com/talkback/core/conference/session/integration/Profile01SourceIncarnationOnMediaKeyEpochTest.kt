package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceMediaMemberControlFact
import com.talkback.core.conference.session.ConferenceMediaSessionControlFact
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0′-SOURCE-INCARNATION-ON-MEDIA-KEY-EPOCH.
 *
 * ADR-0058 requires a `mediaKeyEpoch` change to produce a new source incarnation
 * (new sourceInstanceId + new random SSRC + new Declaration). Field run
 * `p0prime-local-source-registry-r2a-20260915-140434` showed the origin authority holding
 * `sourceGeneration=1` across epoch 1→2, which the registry correctly refused as incoherent.
 */
class Profile01SourceIncarnationOnMediaKeyEpochTest {
    private val conferenceId = "conf-source-incarnation"
    private val moduleId = "M01"
    private val sessionId = "session-source-incarnation"

    // --- Q1/Q3: origin identity authority owns incarnation succession ---

    @Test
    fun mediaKeyEpochAdvance_commitsSuccessorIncarnation() {
        val authority = Profile01LocalConferenceSourceIdentityAuthority()

        val epoch1 = authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 1L)
        assertEquals(1L, epoch1.sourceGeneration)
        assertEquals(1L, epoch1.mediaKeyEpoch)

        val epoch2 = authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 2L)
        assertEquals(2L, epoch2.sourceGeneration)
        assertEquals(2L, epoch2.mediaKeyEpoch)
        assertNotEquals(epoch1.ssrc, epoch2.ssrc)
        assertFalse(epoch1.sourceInstanceId.contentEquals(epoch2.sourceInstanceId))
    }

    @Test
    fun sameMediaKeyEpoch_isIdempotentIncarnation() {
        val authority = Profile01LocalConferenceSourceIdentityAuthority()

        val first = authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 2L)
        val repeat = authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 2L)

        assertEquals(first.sourceGeneration, repeat.sourceGeneration)
        assertEquals(first.ssrc, repeat.ssrc)
        assertTrue(first.sourceInstanceId.contentEquals(repeat.sourceInstanceId))
    }

    @Test
    fun lateLowerMediaKeyEpoch_doesNotCommitNewIncarnation() {
        val authority = Profile01LocalConferenceSourceIdentityAuthority()

        authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 1L)
        val head = authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 2L)
        val late = authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = 1L)

        assertEquals(head.sourceGeneration, late.sourceGeneration)
        assertEquals(head.ssrc, late.ssrc)
        assertEquals(2L, late.mediaKeyEpoch)
    }

    @Test
    fun distinctIncarnations_doNotReuseSsrc() {
        val authority = Profile01LocalConferenceSourceIdentityAuthority()

        val ssrcs =
            (1L..8L).map { epoch ->
                authority.commitLocalSource(sessionId, moduleId, mediaKeyEpoch = epoch).ssrc
            }

        assertEquals(ssrcs.size, ssrcs.toSet().size)
        assertFalse(ssrcs.contains(0))
    }

    // --- Q2: registry coherence predicate, expanded ---

    @Test
    fun successorGenerationAtNewEpoch_isAcceptedAndReplacesPredecessor() {
        val registry = ConferenceSessionMediaControlFactRegistry()
        registry.publishSession(sessionFact(mediaKeyEpoch = 1L, factGeneration = 1L))
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            registry.publishMember(memberFact(sourceGeneration = 1L, mediaKeyEpoch = 1L)),
        )

        registry.publishSession(sessionFact(mediaKeyEpoch = 2L, factGeneration = 2L))
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            registry.publishMember(memberFact(sourceGeneration = 2L, mediaKeyEpoch = 2L)),
        )

        assertTrue(registry.memberModuleIdsAtHead(conferenceId).contains(moduleId))
        assertEquals(2L, registry.member(conferenceId, moduleId)!!.mediaKeyEpoch)
        assertEquals(2L, registry.member(conferenceId, moduleId)!!.membershipIncarnationId)
    }

    /**
     * Field-observed break: the fence is correct and must stay correct. Reproducing the epoch
     * advance without an incarnation successor must remain a rejection, not become an accept.
     */
    @Test
    fun sameGenerationAtNewEpoch_isRejectedIncoherent() {
        val registry = ConferenceSessionMediaControlFactRegistry()
        registry.publishSession(sessionFact(mediaKeyEpoch = 1L, factGeneration = 1L))
        registry.publishMember(memberFact(sourceGeneration = 1L, mediaKeyEpoch = 1L))

        registry.publishSession(sessionFact(mediaKeyEpoch = 2L, factGeneration = 2L))
        assertEquals(
            ControlFactPublishOutcome.REJECTED_INCOHERENT,
            registry.publishMember(memberFact(sourceGeneration = 1L, mediaKeyEpoch = 2L)),
        )
        assertFalse(registry.memberModuleIdsAtHead(conferenceId).contains(moduleId))
    }

    @Test
    fun lateIncarnationAtOldEpoch_cannotReplaceCurrentSource() {
        val registry = ConferenceSessionMediaControlFactRegistry()
        registry.publishSession(sessionFact(mediaKeyEpoch = 1L, factGeneration = 1L))
        registry.publishMember(memberFact(sourceGeneration = 1L, mediaKeyEpoch = 1L))
        registry.publishSession(sessionFact(mediaKeyEpoch = 2L, factGeneration = 2L))
        registry.publishMember(memberFact(sourceGeneration = 2L, mediaKeyEpoch = 2L))

        val outcome = registry.publishMember(memberFact(sourceGeneration = 1L, mediaKeyEpoch = 1L))

        assertEquals(ControlFactPublishOutcome.REJECTED_STALE, outcome)
        assertEquals(2L, registry.member(conferenceId, moduleId)!!.membershipIncarnationId)
        assertEquals(2L, registry.member(conferenceId, moduleId)!!.mediaKeyEpoch)
    }

    private fun sessionFact(
        mediaKeyEpoch: Long,
        factGeneration: Long,
    ) = ConferenceMediaSessionControlFact(
        conferenceId = conferenceId,
        channelId = "ch-source-incarnation",
        conferenceEpoch = 1L,
        membershipVersion = 1L,
        mediaKeyEpoch = mediaKeyEpoch,
        endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = "239.9.9.9",
                mediaPort = 40000,
            ),
        networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        masterKey = Phase1MediaHarness.masterKey.copyOf(),
        masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
        keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
        factGeneration = factGeneration,
    )

    private fun memberFact(
        sourceGeneration: Long,
        mediaKeyEpoch: Long,
    ) = ConferenceMediaMemberControlFact(
        conferenceId = conferenceId,
        moduleId = moduleId,
        membershipIncarnationId = sourceGeneration,
        ssrc = (0x30000000 + sourceGeneration).toInt(),
        sourceAdmissionKey48 = ByteArray(6) { sourceGeneration.toByte() },
        mediaKeyEpoch = mediaKeyEpoch,
        membershipVersion = 1L,
        factGeneration = sourceGeneration,
    )
}
