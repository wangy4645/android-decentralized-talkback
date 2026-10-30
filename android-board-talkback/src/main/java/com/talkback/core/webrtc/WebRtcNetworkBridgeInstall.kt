package com.talkback.core.webrtc

import com.talkback.core.network.SelectedOperationalNetworkRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.webrtc.NetworkMonitor

/**
 * F8-T0: installs TalkbackWebRtcNetworkBridge before WebRTC monitoring starts.
 */
internal object WebRtcNetworkBridgeInstall {
    @Volatile
    private var registry: SelectedOperationalNetworkRegistry? = null

    @Volatile
    private var installed = false

    private val installOrdinal = AtomicInteger(0)

    fun register(registry: SelectedOperationalNetworkRegistry) {
        synchronized(this) {
            val existing = this.registry
            if (existing != null && existing !== registry) {
                error("F8: SelectedOperationalNetworkRegistry already registered")
            }
            this.registry = registry
        }
    }

    fun installBeforeWebRtcInit() {
        val selectedRegistry =
            registry
                ?: error(
                    "F8-T0: register SelectedOperationalNetworkRegistry before WebRTC init",
                )
        synchronized(this) {
            if (installed) return
            try {
                NetworkMonitor.getInstance().setNetworkChangeDetectorFactory(
                    TalkbackWebRtcNetworkBridgeFactory(selectedRegistry),
                )
            } catch (error: AssertionError) {
                throw IllegalStateException(
                    "F8-T0: WebRTC NetworkMonitor already observing before factory injection",
                    error,
                )
            }
            installed = true
            F8BridgeDiagnostic.factoryInstalled(
                factoryClass = TalkbackWebRtcNetworkBridgeFactory::class.java.name,
                installOrdinal = installOrdinal.incrementAndGet(),
                beforeMonitoring = true,
            )
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            registry = null
            installed = false
            installOrdinal.set(0)
        }
    }
}
