package com.talkback.core.conference.session.integration



import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerSource

import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants

import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder

import com.talkback.core.session.ConferenceTopologyMode

import com.talkback.core.session.ConferenceTopologySnapshot

import org.junit.Assert.assertArrayEquals

import org.junit.Assert.assertEquals

import org.junit.Assert.assertNotNull

import org.junit.Assert.assertTrue

import org.junit.Test

import java.security.KeyPairGenerator

import java.security.spec.ECGenParameterSpec

import java.util.concurrent.atomic.AtomicReference



class MeetingProfile01CreationOriginBridgeTest {

    @Test

    fun topologyPublished_emitsSignedCreationOnce() {

        val sessionIndex = MeetingProfile01ConferenceSessionIndex()

        val sessionId = "conf-session-1"

        sessionIndex.registerSession(sessionId, "CH-01", rosterEpoch = 1L)

        val mediaKeyAuthority =

            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority()

        val keyPair =

            KeyPairGenerator.getInstance("EC").apply {

                initialize(ECGenParameterSpec("secp256r1"))

            }.generateKeyPair()

        val signer =

            com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner.fromPkcs8(

                pkcs8PrivateKey = keyPair.private.encoded,

                signerModuleId = "M01",

                signerKeyVersion = 1L,

            )!!

        val emitted = AtomicReference<ByteArray>()

        val bridge =

            MeetingProfile01CreationOriginBridge(

                sessionIndex = sessionIndex,

                mediaKeyAuthority = mediaKeyAuthority,

                signerSource = Profile01SignedFactSignerSource.fixed(signer),

            )

        val snapshot =

            ConferenceTopologySnapshot(

                conferenceId = sessionId,

                rosterEpoch = 1L,

                anchorEpoch = 100L,

                anchorId = "M01",

                meshGeneration = 7L,

                topologyMode = ConferenceTopologyMode.ANCHOR,

                hostModuleId = "M01",

                members = listOf("M01", "M02"),

                actualMediaEdges = emptySet(),

            )

        val first =

            bridge.onTopologyPublished(

                sessionId = sessionId,

                snapshot = snapshot,

                localModuleId = "M01",

                isMembershipAuthority = true,

                eligiblePeerModuleIds = setOf("M02"),

                publishToPeer = { _, bytes ->

                    emitted.set(bytes)

                    true

                },

            )

        assertEquals(OriginEmitOutcome.EMITTED, first)

        val signed = emitted.get()

        assertNotNull(signed)

        assertTrue(Profile01SignedFactEnvelope.parse(signed!!) != null)

        assertEquals(

            Profile01WireConstants.FACT_TYPE_CREATION,

            Profile01WireCborDecoder.readFactType(signed),

        )

        val conferenceId = sessionIndex.conferenceIdForSession(sessionId)

        assertNotNull(conferenceId)

        assertEquals(

            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceIdAuthority.deriveId128Hex(

                sessionId,

            ),

            conferenceId,

        )

        val second =

            bridge.onTopologyPublished(

                sessionId = sessionId,

                snapshot = snapshot,

                localModuleId = "M01",

                isMembershipAuthority = true,

                eligiblePeerModuleIds = setOf("M02"),

                publishToPeer = { _, bytes ->

                    emitted.set(bytes)

                    true

                },

            )

        assertEquals(OriginEmitOutcome.SKIPPED_NO_PENDING_PUBLICATION, second)

    }



    @Test

    fun soloFirst_thenReplaySameEnvelopeWhenPeerBecomesEligible() {

        val sessionIndex = MeetingProfile01ConferenceSessionIndex()

        val sessionId = "conf-session-solo-replay"

        sessionIndex.registerSession(sessionId, "CH-02", rosterEpoch = 1L)

        val mediaKeyAuthority =

            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority()

        val keyPair =

            KeyPairGenerator.getInstance("EC").apply {

                initialize(ECGenParameterSpec("secp256r1"))

            }.generateKeyPair()

        val signer =

            com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner.fromPkcs8(

                pkcs8PrivateKey = keyPair.private.encoded,

                signerModuleId = "M01",

                signerKeyVersion = 1L,

            )!!

        val bridge =

            MeetingProfile01CreationOriginBridge(

                sessionIndex = sessionIndex,

                mediaKeyAuthority = mediaKeyAuthority,

                signerSource = Profile01SignedFactSignerSource.fixed(signer),

            )

        val soloSnapshot =

            ConferenceTopologySnapshot(

                conferenceId = sessionId,

                rosterEpoch = 1L,

                anchorEpoch = 100L,

                anchorId = "M01",

                meshGeneration = 1L,

                topologyMode = ConferenceTopologyMode.ANCHOR,

                hostModuleId = "M01",

                members = listOf("M01"),

                actualMediaEdges = emptySet(),

            )

        val duoSnapshot =

            soloSnapshot.copy(members = listOf("M01", "M02"), meshGeneration = 2L)

        val first =

            bridge.onTopologyPublished(

                sessionId = sessionId,

                snapshot = soloSnapshot,

                localModuleId = "M01",

                isMembershipAuthority = true,

                eligiblePeerModuleIds = emptySet(),

                publishToPeer = { _, _ -> true },

            )

        assertEquals(OriginEmitOutcome.EMITTED, first)

        val replayed = AtomicReference<ByteArray>()

        val replay =

            bridge.onTopologyPublished(

                sessionId = sessionId,

                snapshot = duoSnapshot,

                localModuleId = "M01",

                isMembershipAuthority = true,

                eligiblePeerModuleIds = setOf("M02"),

                publishToPeer = { moduleId, bytes ->

                    assertEquals("M02", moduleId)

                    replayed.set(bytes)

                    true

                },

            )

        assertEquals(OriginEmitOutcome.REPLAYED, replay)
        assertNotNull(replayed.get())
        val noPending =
            bridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = duoSnapshot,
                localModuleId = "M01",
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { _, _ -> error("unexpected publish") },
            )
        assertEquals(OriginEmitOutcome.SKIPPED_NO_PENDING_PUBLICATION, noPending)
    }



    @Test

    fun replayRetriesPeerWhenFirstPublishAttemptFails() {

        val sessionIndex = MeetingProfile01ConferenceSessionIndex()

        val sessionId = "conf-session-retry-peer"

        sessionIndex.registerSession(sessionId, "CH-03", rosterEpoch = 1L)

        val mediaKeyAuthority =

            com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority()

        val keyPair =

            KeyPairGenerator.getInstance("EC").apply {

                initialize(ECGenParameterSpec("secp256r1"))

            }.generateKeyPair()

        val signer =

            com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner.fromPkcs8(

                pkcs8PrivateKey = keyPair.private.encoded,

                signerModuleId = "M01",

                signerKeyVersion = 1L,

            )!!

        val bridge =

            MeetingProfile01CreationOriginBridge(

                sessionIndex = sessionIndex,

                mediaKeyAuthority = mediaKeyAuthority,

                signerSource = Profile01SignedFactSignerSource.fixed(signer),

            )

        val snapshot =

            ConferenceTopologySnapshot(

                conferenceId = sessionId,

                rosterEpoch = 1L,

                anchorEpoch = 100L,

                anchorId = "M01",

                meshGeneration = 1L,

                topologyMode = ConferenceTopologyMode.ANCHOR,

                hostModuleId = "M01",

                members = listOf("M01"),

                actualMediaEdges = emptySet(),

            )

        bridge.onTopologyPublished(

            sessionId = sessionId,

            snapshot = snapshot,

            localModuleId = "M01",

            isMembershipAuthority = true,

            eligiblePeerModuleIds = emptySet(),

            publishToPeer = { _, _ -> true },

        )

        val firstAttempt =

            bridge.onTopologyPublished(

                sessionId = sessionId,

                snapshot = snapshot,

                localModuleId = "M01",

                isMembershipAuthority = true,

                eligiblePeerModuleIds = setOf("M02"),

                publishToPeer = { _, _ -> false },

            )

        assertEquals(OriginEmitOutcome.SKIPPED_NO_PENDING_PUBLICATION, firstAttempt)

        val captured = AtomicReference<ByteArray>()

        val secondAttempt =

            bridge.onTopologyPublished(

                sessionId = sessionId,

                snapshot = snapshot,

                localModuleId = "M01",

                isMembershipAuthority = true,

                eligiblePeerModuleIds = setOf("M02"),

                publishToPeer = { _, bytes ->

                    captured.set(bytes)

                    true

                },

            )

        assertEquals(OriginEmitOutcome.REPLAYED, secondAttempt)

        assertNotNull(captured.get())

    }

}


