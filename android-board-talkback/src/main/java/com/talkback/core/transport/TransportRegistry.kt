package com.talkback.core.transport

import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget

enum class TransportSource {
  VERIFIED_HELLO,
  GOSSIP_PRESENCE,
  STATIC_BOOTSTRAP,
  /** RO-4 stub: binding read from legacy signalPeers map. */
  LEGACY_SIGNAL_PEER
}

data class TransportBinding(
  val moduleId: ModuleId,
  val host: String,
  val port: Int,
  val epoch: Long,
  val source: TransportSource
) {
  val peer: PeerTarget get() = PeerTarget(host, port)

  companion object {
    fun fromPeerTarget(
      moduleId: ModuleId,
      peer: PeerTarget,
      epoch: Long = 0L,
      source: TransportSource = TransportSource.LEGACY_SIGNAL_PEER
    ): TransportBinding = TransportBinding(
      moduleId = moduleId,
      host = peer.host,
      port = peer.port,
      epoch = epoch,
      source = source
    )
  }
}

/**
 * Signaling-plane UDP transport bindings per module.
 * Floor and dial read; ICE/media paths do not write here (RO-5).
 */
interface TransportRegistry {
  fun resolve(moduleId: ModuleId): TransportBinding?
  fun bindingEpoch(moduleId: ModuleId): Long
  fun invalidate(moduleId: ModuleId)
}

/**
 * RO-4 stub: [resolve] delegates to legacy signal-peer lookup; behavior unchanged until RO-5.
 */
class DelegatingTransportRegistry(
  private val legacySignalPeerFor: (ModuleId) -> PeerTarget?
) : TransportRegistry {

  override fun resolve(moduleId: ModuleId): TransportBinding? =
    legacySignalPeerFor(moduleId)?.let { peer ->
      TransportBinding.fromPeerTarget(
        moduleId = moduleId,
        peer = peer,
        epoch = bindingEpoch(moduleId),
        source = TransportSource.LEGACY_SIGNAL_PEER
      )
    }

  override fun bindingEpoch(moduleId: ModuleId): Long = 0L

  override fun invalidate(moduleId: ModuleId) {
    // RO-5: drop cached binding for moduleId
  }
}
