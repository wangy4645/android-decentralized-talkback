package com.talkback.core.transport

import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget
import java.util.concurrent.ConcurrentHashMap

/**
 * RO-5: sole writer for signaling transport bindings consumed by Floor / dial via [TransportRegistry].
 * Writes only from verified HELLO, gossip presence, and static bootstrap.
 */
class TransportManager : TransportRegistry {

  private data class StoredBinding(
    val host: String,
    val port: Int,
    val source: TransportSource,
    val epoch: Long
  )

  private val bindings = ConcurrentHashMap<String, StoredBinding>()

  fun onVerifiedHello(moduleId: ModuleId, peer: PeerTarget) {
    upsert(moduleId, peer, TransportSource.VERIFIED_HELLO)
  }

  fun onGossipPresence(moduleId: ModuleId, peer: PeerTarget) {
    upsertIfNotDowngraded(moduleId, peer, TransportSource.GOSSIP_PRESENCE)
  }

  fun onStaticBootstrap(moduleId: ModuleId, peer: PeerTarget) {
    upsertIfNotDowngraded(moduleId, peer, TransportSource.STATIC_BOOTSTRAP)
  }

  override fun resolve(moduleId: ModuleId): TransportBinding? {
    val stored = bindings[moduleId.value] ?: return null
    return TransportBinding(
      moduleId = moduleId,
      host = stored.host,
      port = stored.port,
      epoch = stored.epoch,
      source = stored.source
    )
  }

  override fun bindingEpoch(moduleId: ModuleId): Long =
    bindings[moduleId.value]?.epoch ?: 0L

  override fun invalidate(moduleId: ModuleId) {
    bindings.remove(moduleId.value)
  }

  private fun upsert(moduleId: ModuleId, peer: PeerTarget, source: TransportSource) {
    val key = moduleId.value
    val nextEpoch = (bindings[key]?.epoch ?: 0L) + 1L
    bindings[key] = StoredBinding(peer.host, peer.port, source, nextEpoch)
  }

  private fun upsertIfNotDowngraded(moduleId: ModuleId, peer: PeerTarget, source: TransportSource) {
    val key = moduleId.value
    val existing = bindings[key]
    if (existing != null && sourceRank(source) < sourceRank(existing.source)) return
    upsert(moduleId, peer, source)
  }

  private fun sourceRank(source: TransportSource): Int = when (source) {
    TransportSource.VERIFIED_HELLO -> 3
    TransportSource.GOSSIP_PRESENCE -> 2
    TransportSource.STATIC_BOOTSTRAP -> 1
    TransportSource.LEGACY_SIGNAL_PEER -> 0
  }
}
