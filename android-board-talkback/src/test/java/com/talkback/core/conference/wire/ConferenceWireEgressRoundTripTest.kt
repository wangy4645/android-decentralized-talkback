package com.talkback.core.conference.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke: egress protect + round-trip through real ingress (not a corpus bypass).
 * PUBLIC TEST KEYS ONLY.
 */
class ConferenceWireEgressRoundTripTest {
    @Test
    fun protectExact120_thenIngressAccepts() {
        val masterKey = hex("000102030405060708090a0b0c0d0e0f")
        val masterSalt = hex("101112131415161718191a1b")
        val ssrc = 0x11223344
        val seq = 0x1001
        val roc = 0
        val headerHe =
            hex(
                "906f10012000000011223344bede00041ebb49f2142c4c194a3a6cb214fe3e94",
            )
        val opus = ByteArray(72) { (it and 0xff).toByte() }

        val protected =
            ConferenceWireEgress.protect(
                headerAndHe = headerHe,
                plaintextPayload = opus,
                masterKey = masterKey,
                masterSalt = masterSalt,
                ssrc = ssrc,
                roc = roc,
                seq = seq,
            )
        assertTrue(protected is ConferenceWireEgress.EgressResult.Protected)
        val udp = (protected as ConferenceWireEgress.EgressResult.Protected).udpPayload
        assertEquals(120, udp.size)

        val result =
            ConferenceWireIngress.admit(
                udp,
                WireIngressContext(
                    key =
                        WireKeyContext(
                            masterKey = masterKey,
                            masterSalt = masterSalt,
                            keyContextHint64 = hex("bb49f2142c4c194a"),
                        ),
                    installedBinding =
                        WireSourceBinding(
                            ssrc = ssrc,
                            sourceAdmissionKey48 = hex("fe3e3a6cb214"),
                        ),
                    replay = null,
                    roc = roc,
                ),
            )
        assertTrue(result is WireIngressResult.Accepted)
        val accepted = result as WireIngressResult.Accepted
        assertTrue(accepted.plaintextPayload.contentEquals(opus))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
