package com.talkback.core.transport

import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportManagerTest {

  private val m01 = ModuleId("M01")
  private val m02 = ModuleId("M02")
  private val peerM01 = PeerTarget("10.0.0.1", 50_701)
  private val peerM02 = PeerTarget("10.0.0.2", 50_702)

  @Test
  fun onVerifiedHello_resolveReturnsBinding() {
    val manager = TransportManager()
    manager.onVerifiedHello(m01, peerM01)
    val binding = manager.resolve(m01)
    assertEquals(m01, binding?.moduleId)
    assertEquals(peerM01, binding?.peer)
    assertEquals(TransportSource.VERIFIED_HELLO, binding?.source)
    assertEquals(1L, binding?.epoch)
  }

  @Test
  fun gossipDoesNotDowngradeVerifiedHello() {
    val manager = TransportManager()
    manager.onVerifiedHello(m01, peerM01)
    manager.onGossipPresence(m01, peerM02)
    assertEquals(peerM01, manager.resolve(m01)?.peer)
  }

  @Test
  fun verifiedHelloOverwritesGossip() {
    val manager = TransportManager()
    manager.onGossipPresence(m01, peerM02)
    manager.onVerifiedHello(m01, peerM01)
    assertEquals(peerM01, manager.resolve(m01)?.peer)
    assertEquals(TransportSource.VERIFIED_HELLO, manager.resolve(m01)?.source)
  }

  @Test
  fun invalidate_clearsBinding() {
    val manager = TransportManager()
    manager.onVerifiedHello(m01, peerM01)
    manager.invalidate(m01)
    assertNull(manager.resolve(m01))
    assertEquals(0L, manager.bindingEpoch(m01))
  }

  @Test
  fun invalidate_doesNotAffectOtherModules() {
    val manager = TransportManager()
    manager.onVerifiedHello(m01, peerM01)
    manager.onVerifiedHello(m02, peerM02)
    manager.invalidate(m01)
    assertEquals(peerM02, manager.resolve(m02)?.peer)
  }
}
