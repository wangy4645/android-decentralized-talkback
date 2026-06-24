package com.talkback.core.discovery

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.security.SignalSecurity
import com.talkback.core.signaling.DiscoveryTransport
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.util.TalkbackLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class MeshSweepGossipConfig(
    val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    val sweepMaxHosts: Int = 256,
    val sweepPacketDelayMs: Long = 2L,
    val peerTtlMs: Long = 45_000L,
    val announceIntervalMs: Long = 10_000L,
    val ttlCleanupIntervalMs: Long = 5_000L,
    val replayWindowMs: Long = 15_000L,
    val sweepBackoffMs: LongArray = longArrayOf(30_000L, 60_000L, 120_000L),
    /** Fast re-sweep while roster is empty or still converging (cold start). */
    val bootstrapSweepIntervalMs: Long = 3_000L,
    val bootstrapAnnounceIntervalMs: Long = 2_000L,
    val bootstrapDurationMs: Long = 60_000L
) {
    companion object {
        const val DEFAULT_DISCOVERY_PORT = 51999
    }
}

class MeshSweepGossipDiscovery(
    private val sharedSecret: () -> String,
    private val subnetProvider: SubnetHostProvider,
    private val transport: DiscoveryTransport,
    private val config: MeshSweepGossipConfig = MeshSweepGossipConfig(),
    private val localEndpointId: EndpointId = EndpointId("E01")
) : ModuleDiscoveryService, DiscoverySignalHandler, GossipDiscoveryControl {
    private val roster = DiscoveryRoster(config.peerTtlMs)
    private val listeners = CopyOnWriteArrayList<(List<ModulePresence>) -> Unit>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var localModule: ModuleId? = null
    private var localSignalingPort: Int = -1
    private var localHost: String = ""
    private var started = false
    private val sweepBackoffIndex = AtomicInteger(0)
    private var stableRosterSize = 0
    private var stableRosterChecks = 0
    private var bootstrapStartedAtMs: Long = 0L
    private val seenNoncesByModule = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    override fun start(localModule: ModuleId, signalingPort: Int) {
        this.localModule = localModule
        this.localSignalingPort = signalingPort
        this.localHost = subnetProvider.localHostAddress().orEmpty()
        started = true
        sweepBackoffIndex.set(0)
        bootstrapStartedAtMs = System.currentTimeMillis()
        transport.start(config.discoveryPort) listener@{ signal, peer ->
            if (!verifyIncoming(signal)) return@listener
            when (signal.type) {
                SignalType.DISCOVERY_PROBE -> onDiscoveryProbe(signal, peer)
                SignalType.DISCOVERY_ANNOUNCE -> onDiscoveryAnnounce(signal, peer)
                else -> Unit
            }
        }
        scheduler.scheduleAtFixedRate(
            {
                if (roster.removeExpired()) publish()
            },
            config.ttlCleanupIntervalMs,
            config.ttlCleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
        broadcastAnnounceToSubnet()
        scheduleAnnounceLoop()
        scheduleSweep(0L)
        publish()
    }

    override fun stop() {
        started = false
        scheduler.shutdownNow()
        transport.stop()
        listeners.clear()
        roster.clear()
    }

    override fun onPresenceChanged(listener: (List<ModulePresence>) -> Unit) {
        listeners.add(listener)
        publish()
    }

    override fun resetAndSweep() {
        if (!started) return
        roster.clear()
        sweepBackoffIndex.set(0)
        stableRosterSize = 0
        stableRosterChecks = 0
        bootstrapStartedAtMs = System.currentTimeMillis()
        publish()
        scheduleSweep(0L)
    }

    override fun onDiscoveryProbe(signal: SignalEnvelope, fromPeer: PeerTarget) {
        if (!started) return
        val probeSelf = DiscoveryPayload.decodeProbe(signal.payload)
        if (probeSelf != null && probeSelf.moduleId.value != localModule?.value) {
            mergePeer(probeSelf, fromPeer.host)
        }
        sendAnnounce(to = PeerTarget(fromPeer.host, config.discoveryPort))
    }

    override fun onDiscoveryAnnounce(signal: SignalEnvelope, fromPeer: PeerTarget) {
        if (!started) return
        val decoded = DiscoveryPayload.decodeAnnounce(signal.payload) ?: return
        val (self, knownPeers) = decoded
        if (self.moduleId.value == localModule?.value) return
        mergePeer(self, fromPeer.host)
        knownPeers.forEach { mergePeerIndirect(it, it.host) }
    }

    private fun mergePeer(peer: DiscoveryPeerEntry, reachableHost: String) {
        val local = localModule?.value ?: return
        if (peer.moduleId.value == local) return
        val corrected = peer.copy(host = reachableHost.ifBlank { peer.host })
        val wasKnown = roster.knownPeerEntries(localModule)
            .any { it.moduleId.value == corrected.moduleId.value }
        roster.merge(corrected)
        if (!wasKnown) {
            TalkbackLog.i(
                "Discovery peer added: ${corrected.moduleId.value} " +
                    "at ${corrected.host}:${corrected.signalingPort}"
            )
        }
        publish()
    }

    private fun mergePeerIndirect(peer: DiscoveryPeerEntry, reachableHost: String) {
        val local = localModule?.value ?: return
        if (peer.moduleId.value == local) return
        val corrected = peer.copy(host = reachableHost.ifBlank { peer.host })
        val wasKnown = roster.knownPeerEntries(localModule)
            .any { it.moduleId.value == corrected.moduleId.value }
        roster.mergeIndirect(corrected)
        if (!wasKnown) {
            TalkbackLog.i(
                "Discovery peer added (indirect): ${corrected.moduleId.value} " +
                    "at ${corrected.host}:${corrected.signalingPort}"
            )
        }
        publish()
    }

    private fun scheduleSweep(delayMs: Long) {
        scheduler.schedule({
            if (!started) return@schedule
            runSubnetSweep()
            maybeScheduleBackoffSweep()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun runSubnetSweep() {
        val secret = sharedSecret()
        if (secret.isBlank()) {
            TalkbackLog.w("Discovery sweep skipped: shared secret is blank")
            return
        }
        val targets = subnetProvider.sweepTargets(config.sweepMaxHosts)
        val self = localSelfEntry()
        if (self == null) {
            TalkbackLog.w(
                "Discovery sweep skipped: local host unavailable " +
                    "(module=${localModule?.value} port=$localSignalingPort host=$localHost)"
            )
            return
        }
        if (targets.isEmpty()) {
            TalkbackLog.w("Discovery sweep skipped: no subnet targets (check Wi‑Fi/LAN IP)")
            return
        }
        TalkbackLog.i(
            "Discovery sweep: local=${self.host}:${self.signalingPort} " +
                "targets=${targets.size} port=${config.discoveryPort}"
        )
        sweepCountInternal++
        val probePayload = DiscoveryPayload.encodeProbe(self)
        val envelope = buildSignedEnvelope(SignalType.DISCOVERY_PROBE, probePayload)
        targets.forEach { host ->
            transport.send(PeerTarget(host, config.discoveryPort), envelope)
            if (config.sweepPacketDelayMs > 0) {
                Thread.sleep(config.sweepPacketDelayMs)
            }
        }
    }

    private fun isBootstrapPhase(): Boolean =
        System.currentTimeMillis() - bootstrapStartedAtMs < config.bootstrapDurationMs

    private fun maybeScheduleBackoffSweep() {
        val currentSize = roster.size()
        if (currentSize == stableRosterSize) {
            stableRosterChecks++
        } else {
            stableRosterSize = currentSize
            stableRosterChecks = 0
        }
        if (!started) return
        val delay = when {
            currentSize == 0 -> config.bootstrapSweepIntervalMs
            isBootstrapPhase() && stableRosterChecks < 2 -> config.bootstrapSweepIntervalMs
            stableRosterChecks >= 2 && currentSize > 0 -> {
                val backoff = config.sweepBackoffMs
                val index = sweepBackoffIndex.get().coerceAtMost(backoff.lastIndex)
                sweepBackoffIndex.set((index + 1).coerceAtMost(backoff.lastIndex))
                backoff[sweepBackoffIndex.get()]
            }
            else -> {
                val backoff = config.sweepBackoffMs
                backoff[sweepBackoffIndex.get().coerceAtMost(backoff.lastIndex)]
            }
        }
        scheduleSweep(delay)
    }

    private fun scheduleAnnounceLoop() {
        if (!started) return
        val delay = if (isBootstrapPhase()) {
            config.bootstrapAnnounceIntervalMs
        } else {
            config.announceIntervalMs
        }
        scheduler.schedule({
            if (!started) return@schedule
            runCatching {
                announceToKnownPeers()
                if (roster.size() == 0) {
                    broadcastAnnounceToSubnet()
                }
            }
            scheduleAnnounceLoop()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun broadcastAnnounceToSubnet() {
        if (!started) return
        val secret = sharedSecret()
        if (secret.isBlank()) return
        val self = localSelfEntry() ?: return
        val targets = subnetProvider.sweepTargets(config.sweepMaxHosts)
        if (targets.isEmpty()) return
        val known = roster.knownPeerEntries(localModule)
        val payload = DiscoveryPayload.encodeAnnounce(self, known)
        val envelope = buildSignedEnvelope(SignalType.DISCOVERY_ANNOUNCE, payload)
        targets.forEach { host ->
            transport.send(PeerTarget(host, config.discoveryPort), envelope)
        }
    }

    private fun announceToKnownPeers() {
        if (!started) return
        val secret = sharedSecret()
        if (secret.isBlank()) return
        val local = localModule ?: return
        val targets = roster.snapshot(local)
        if (targets.isEmpty()) return
        targets.forEach { presence ->
            sendAnnounce(to = PeerTarget(presence.host, config.discoveryPort))
        }
    }

    private fun sendAnnounce(to: PeerTarget) {
        val self = localSelfEntry() ?: return
        val known = roster.knownPeerEntries(localModule)
        val payload = DiscoveryPayload.encodeAnnounce(self, known)
        val envelope = buildSignedEnvelope(SignalType.DISCOVERY_ANNOUNCE, payload)
        transport.send(to, envelope)
    }

    private fun localSelfEntry(): DiscoveryPeerEntry? {
        val module = localModule ?: return null
        val host = localHost.ifBlank { subnetProvider.localHostAddress().orEmpty() }
        if (host.isBlank() || localSignalingPort <= 0) return null
        return DiscoveryPeerEntry(
            moduleId = module,
            host = host,
            signalingPort = localSignalingPort,
            endpointCount = 1
        )
    }

    private fun buildSignedEnvelope(type: SignalType, payload: String): SignalEnvelope {
        val from = EndpointAddress(
            localModule ?: ModuleId("UNKNOWN"),
            localEndpointId
        )
        val unsigned = SignalEnvelope(
            type = type,
            from = from,
            to = null,
            sessionId = DiscoveryPayload.SESSION_ID,
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = UUID.randomUUID().toString(),
            signature = ""
        )
        return unsigned.copy(signature = SignalSecurity.sign(unsigned, sharedSecret()))
    }

    private fun publish() {
        val local = localModule ?: return
        val list = roster.snapshot(local)
        listeners.forEach { it(list) }
    }

    internal fun rosterSnapshot(): List<ModulePresence> = roster.snapshot(localModule)

    internal val sweepCount: Int get() = sweepCountInternal

    @Volatile
    private var sweepCountInternal = 0

    internal fun ingestVerifiedAnnounce(self: DiscoveryPeerEntry, knownPeers: List<DiscoveryPeerEntry>, fromHost: String) {
        mergePeer(self, fromHost)
        knownPeers.forEach { mergePeer(it, it.host) }
    }

    private fun verifyIncoming(signal: SignalEnvelope): Boolean {
        val secret = sharedSecret()
        if (secret.isBlank() || !SignalSecurity.verify(signal, secret)) return false
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - signal.timestampMs) > config.replayWindowMs) return false
        val moduleKey = signal.from.moduleId.value
        val moduleNonces = seenNoncesByModule.getOrPut(moduleKey) { ConcurrentHashMap() }
        if (moduleNonces.containsKey(signal.nonce)) return false
        moduleNonces[signal.nonce] = signal.timestampMs
        val threshold = now - config.replayWindowMs
        moduleNonces.filterValues { it < threshold }.keys.forEach { moduleNonces.remove(it) }
        return true
    }
}
