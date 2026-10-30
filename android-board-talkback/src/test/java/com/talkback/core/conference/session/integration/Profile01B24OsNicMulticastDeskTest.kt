package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.Profile01B20PlcReasonObservation
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.transport.PerIncarnationIngressTimeline
import com.talkback.core.conference.wire.ConferenceWireConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.MulticastSocket

/**
 * B-24 desk — OS/NIC/multicast ingress vs B20=A (read-only).
 *
 * App rxWallMs is after MulticastSocket.receive, not a NIC timestamp.
 * Kernel silent drops never reach admitFrame (B20 class A).
 * A packet that does reach admit as LATE is B20 class B, not A.
 */
class Profile01B24OsNicMulticastDeskTest {
    @Test
    fun socketSeam_defaults() {
        assertEquals(120, ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES)
        val transport = ConferenceMulticastRtpSrtpTransport(AndroidRuntimeResources())
        assertEquals(250, transport.socketReceiveTimeoutMs)
    }

    @Test
    fun jvmMulticastSocket_hasDefaultRcvbufLargerThanOnePacket() {
        val sock = MulticastSocket(null)
        try {
            sock.reuseAddress = true
            assertTrue(sock.receiveBufferSize > ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES)
        } finally {
            sock.close()
        }
    }

    @Test
    fun osDelayOnLaterPacket_doesNotRewriteSeqSlotMediaTime() {
        val timeline = PerIncarnationIngressTimeline("M01", 1L)
        val t0 = timeline.mediaTimeMs(sequence = 20_480, arrivalMs = 1_000L)
        val t1 = timeline.mediaTimeMs(sequence = 20_481, arrivalMs = 5_000L)
        assertEquals(1_000L, t0)
        assertEquals(1_000L + MediaJitterConstants.MEDIA_SLOT_MS, t1)
    }

    @Test
    fun lateAdmit_isNotPlcClassA() {
        assertEquals(
            'B',
            Profile01B20PlcReasonObservation.classify(ingressSeen = true, peekPresent = false),
        )
        assertEquals(
            'A',
            Profile01B20PlcReasonObservation.classify(ingressSeen = false, peekPresent = false),
        )
    }
}
