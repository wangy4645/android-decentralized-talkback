package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.transport.TransportBinding
import com.talkback.core.transport.TransportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorAuthorityRouteTest {
  private val m01 = ModuleId("M01")
  private val m01Endpoint = EndpointAddress(m01, EndpointId("E01"))
  private val m01Peer = PeerTarget("127.0.0.1", 50_901)
  private val m01Transport = TransportBinding(
    moduleId = m01,
    host = m01Peer.host,
    port = m01Peer.port,
    epoch = 2L,
    source = TransportSource.VERIFIED_HELLO
  )

  @Test
  fun resolve_usesTransportBinding_notSessionCaches() {
    val result = FloorAuthorityRoute.resolve(
      authorityModuleId = m01,
      authorityEndpoint = m01Endpoint,
      authorityEpoch = 2L,
      transport = m01Transport
    )

    val decision = (result as FloorAuthorityRouteResult.Ok).decision
    assertEquals(m01, decision.authorityModuleId)
    assertEquals(m01Peer, decision.signalPeer)
    assertEquals(2L, decision.authorityEpoch)
    assertEquals(FloorRouteDecision.RESOLVED_FROM, decision.resolvedFrom)
  }

  @Test
  fun resolve_missingTransport_isInvalid_noFallback() {
    val result = FloorAuthorityRoute.resolve(
      authorityModuleId = m01,
      authorityEndpoint = m01Endpoint,
      authorityEpoch = 0L,
      transport = null
    )

    assertTrue(result is FloorAuthorityRouteResult.Invalid)
    assertEquals(
      FloorRouteInvalidReason.SIGNAL_PEER_MISSING,
      (result as FloorAuthorityRouteResult.Invalid).reason
    )
  }

  @Test
  fun resolve_missingRosterEntry_isInvalid() {
    val result = FloorAuthorityRoute.resolve(
      authorityModuleId = m01,
      authorityEndpoint = null,
      authorityEpoch = 0L,
      transport = m01Transport
    )

    assertTrue(result is FloorAuthorityRouteResult.Invalid)
    assertEquals(
      FloorRouteInvalidReason.ROSTER_MISS,
      (result as FloorAuthorityRouteResult.Invalid).reason
    )
  }

  @Test
  fun resolve_missingAuthority_isInvalid() {
    val result = FloorAuthorityRoute.resolve(
      authorityModuleId = null,
      authorityEndpoint = m01Endpoint,
      authorityEpoch = 0L,
      transport = m01Transport
    )

    assertTrue(result is FloorAuthorityRouteResult.Invalid)
    assertEquals(
      FloorRouteInvalidReason.AUTHORITY_MISSING,
      (result as FloorAuthorityRouteResult.Invalid).reason
    )
  }

  @Test
  fun resolve_staleTransportEpoch_isInvalid() {
    val stale = m01Transport.copy(epoch = 1L)
    val result = FloorAuthorityRoute.resolve(
      authorityModuleId = m01,
      authorityEndpoint = m01Endpoint,
      authorityEpoch = 2L,
      transport = stale
    )

    assertTrue(result is FloorAuthorityRouteResult.Invalid)
    assertEquals(
      FloorRouteInvalidReason.SIGNAL_PEER_MISSING,
      (result as FloorAuthorityRouteResult.Invalid).reason
    )
  }

  @Test
  fun peerSetFingerprint_changesWhenEpochChanges() {
    val hash0 = FloorAuthorityRoute.peerSetFingerprint(m01, m01Endpoint, m01Peer, 0L)
    val hash1 = FloorAuthorityRoute.peerSetFingerprint(m01, m01Endpoint, m01Peer, 1L)
    assertTrue(hash0 != hash1)
  }
}
