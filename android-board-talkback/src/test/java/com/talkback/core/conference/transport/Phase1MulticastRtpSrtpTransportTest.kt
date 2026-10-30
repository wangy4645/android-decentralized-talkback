package com.talkback.core.conference.transport

import com.talkback.core.conference.authority.AuthorityFactStore
import com.talkback.core.conference.authority.AuthorityWiringRuntime
import com.talkback.core.conference.authority.MediaKeyContextFact
import com.talkback.core.conference.authority.SourceAuthorizationFact
import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.FakeMulticastLockSeam
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.TransportHandle
import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 — multicast RTP/SRTP transport seam (explicit binding + source-scoped SRTP).
 */
class Phase1MulticastRtpSrtpTransportTest {
    @Test
    fun sourceScopedSrtpEgress_advancesSeqPerSource_notPerDestination() {
        val egress = harnessEgress(initialSeq = 0x1001)
        val opus = ByteArray(72) { 0 }

        val first = egress.protectNext(opus)
        val second = egress.protectNext(opus)
        assertTrue(first is ConferenceWireEgress.EgressResult.Protected)
        assertTrue(second is ConferenceWireEgress.EgressResult.Protected)
        assertEquals(0x1003, egress.currentSeq)
    }

    @Test
    fun multicastLockPolicyNone_doesNotRequireWifiLock() {
        val rt =
            AndroidRuntimeResources(
                multicastLock = FakeMulticastLockSeam(),
                rebindSeam = MulticastSocketTransportRebindSeam(),
            )
        val handle =
            TransportHandle(
                id = "mesh-1",
                endpoint = endpoint(),
                multicastLockPolicy = MulticastLockPolicy.NONE,
            )
        assertTrue(rt.beginTransportScope(handle, 0L))
        assertFalse(rt.multicastLock.isHeld())
        assertEquals(0, rt.multicastLock.acquireCount())
        rt.endTransportScope()
    }

    @Test
    fun transportHandle_resolvedNetworkBinding_prefersExplicitBinding() {
        val handle =
            TransportHandle(
                id = "h1",
                networkInterfaceName = "wlan0",
                networkBinding = ConferenceMulticastNetworkBinding.fromInterfaceName("mesh0"),
            )
        assertEquals("mesh0", handle.resolvedNetworkBinding()?.networkInterfaceName)
    }

    @Test
    fun protectedArtifact_roundTripThroughIngressWiring() {
        val store = AuthorityFactStore()
        installHarnessAuthority(store, sourceIdentity = "S1")
        val wiring = AuthorityWiringRuntime(store = store)
        wiring.syncAllAdmittedToRuntime()

        val egress = harnessEgress(initialSeq = 0x1001)
        val opus = ByteArray(72) { (it and 0xff).toByte() }
        val protected =
            egress.protectNext(opus) as ConferenceWireEgress.EgressResult.Protected

        val result = wiring.admitWire("S1", protected.udpPayload)
        assertTrue(result is WireIngressResult.Accepted)
    }

    @Test
    fun observability_recordsIngressAndPlayoutBudgetFields() {
        val obs = MulticastTransportObservability()
        obs.recordIngressAccepted("S1", rxWallMs = 100L, durationUs = 50L, jitterDepth = 3)
        obs.recordDecodeMixPlayout(
            decodeUs = 400,
            mixUs = 200,
            playoutBudgetUsedUs = 1_500,
            underrun = true,
        )
        val snap = obs.snapshot()
        assertEquals(1L, snap.ingressAccepted)
        assertEquals(1L, snap.packetsReceived)
        assertEquals(3, snap.perSourceJitterDepth["S1"])
        assertEquals(400L, snap.decodeDurationUs)
        assertEquals(200L, snap.mixDurationUs)
        assertEquals(1_500L, snap.playoutBudgetUsedUs)
        assertEquals(1L, snap.audioTrackUnderrunCount)
    }

    private fun harnessEgress(initialSeq: Int): SourceScopedSrtpEgress {
        val masterKey = hex("000102030405060708090a0b0c0d0e0f")
        val masterSalt = hex("101112131415161718191a1b")
        return SourceScopedSrtpEgress(
            sourceIdentity = "S1",
            masterKey = masterKey,
            masterSalt = masterSalt,
            ssrc = 0x11223344,
            roc = 0,
            initialSeq = initialSeq,
            headerHeTemplate = SourceScopedSrtpEgress.buildHeaderHeTemplate(0x11223344),
        )
    }

    private fun installHarnessAuthority(
        store: AuthorityFactStore,
        sourceIdentity: String,
    ) {
        store.acceptVerifiedKey(
            MediaKeyContextFact(
                mediaKeyEpoch = 1L,
                masterKey = hex("000102030405060708090a0b0c0d0e0f"),
                masterSalt = hex("101112131415161718191a1b"),
                keyContextHint64 = hex("bb49f2142c4c194a"),
            ),
        )
        store.acceptVerifiedSource(
            SourceAuthorizationFact(
                sourceIdentity = sourceIdentity,
                incarnationId = 1L,
                ssrc = 0x11223344,
                sourceAdmissionKey48 = hex("fe3e3a6cb214"),
                mediaKeyEpoch = 1L,
            ),
        )
    }

    private fun endpoint(): MediaGroupEndpointBinding =
        MediaGroupEndpointBinding(
            multicastAddress = "239.255.42.99",
            mediaPort = 47099,
        )

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
