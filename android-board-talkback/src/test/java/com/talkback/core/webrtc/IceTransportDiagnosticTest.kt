package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IceTransportDiagnosticTest {

  @Test
  fun parseCandidateSdp_hostUdp_extractsFields() {
    val sdp =
        "candidate:842163049 1 udp 2130706431 192.168.10.101 54321 typ host generation 0"
    val parsed = IceTransportDiagnostic.parseCandidateSdp(sdp, "0", 0)
    assertEquals("host", parsed.candidateType)
    assertEquals("udp", parsed.protocol)
    assertEquals("192.168.10.101", parsed.address)
    assertEquals(54321, parsed.port)
    assertEquals("842163049", parsed.foundation)
    assertEquals("0", parsed.sdpMid)
    assertEquals(0, parsed.sdpMLineIndex)
    assertEquals("0", parsed.generation)
  }

  @Test
  fun parseCandidateWire_midIndexPipeFormat() {
    val wire =
        "0|0|candidate:842163049 1 udp 2130706431 192.168.10.101 54321 typ host"
    val parsed = IceTransportDiagnostic.parseCandidateWire(wire)
    assertEquals("0", parsed.sdpMid)
    assertEquals(0, parsed.sdpMLineIndex)
    assertEquals("host", parsed.candidateType)
    assertEquals("192.168.10.101", parsed.address)
  }

  @Test
  fun parseCandidateSdp_srflx_includesRelatedAddress() {
    val sdp =
        "candidate:1 1 udp 1694498815 192.168.10.50 40000 typ srflx raddr 10.0.0.5 rport 40000"
    val parsed = IceTransportDiagnostic.parseCandidateSdp(sdp)
    assertEquals("srflx", parsed.candidateType)
    assertEquals("10.0.0.5", parsed.relatedAddress)
    assertEquals("40000", parsed.relatedPort)
  }

  @Test
  fun extractIceCredentials_returnsUfragAndPwdFingerprint_notFullPwd() {
    val sdp =
        """
        v=0
        a=ice-ufrag:AbCdEf
        a=ice-pwd:superSecretPasswordMaterial1234567890
        """
            .trimIndent()
    val creds = IceTransportDiagnostic.extractIceCredentials(sdp)
    assertEquals("AbCdEf", creds.iceUfrag)
    assertNotEquals("superSecretPasswordMaterial1234567890", creds.icePwdFingerprint)
    assertEquals(12, creds.icePwdFingerprint?.length)
  }

  @Test
  fun extractIceCredentials_blankSdp_returnsNulls() {
    val creds = IceTransportDiagnostic.extractIceCredentials(null)
    assertNull(creds.iceUfrag)
    assertNull(creds.icePwdFingerprint)
  }
}
