package com.talkback.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.talkback.core.model.ModuleId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NsdModuleDiscoveryService(
    context: Context,
    private val serviceType: String = "_talkback._udp.",
    private val peerTtlMs: Long = 15_000L,
    private val cleanupIntervalMs: Long = 3_000L,
    private val discoveryRetryMs: Long = 2_000L
) : ModuleDiscoveryService {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainExecutor = appContext.mainExecutor
    private val peers = ConcurrentHashMap<String, ModulePresence>()
    private var listener: ((List<ModulePresence>) -> Unit)? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val serviceInfoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var started = false
    private var serviceRegistered = false
    private var discoveryActive = false
    private var localModule: ModuleId? = null
    private var localSignalingPort: Int = -1

    override fun start(localModule: ModuleId, signalingPort: Int) {
        this.localModule = localModule
        this.localSignalingPort = signalingPort
        started = true
        registerLocalService(localModule, signalingPort)
        startDiscovery()
        scheduler.scheduleAtFixedRate(
            { cleanupExpiredPeers() },
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    override fun stop() {
        started = false
        listener = null
        scheduler.shutdownNow()
        safeUnregisterService()
        safeStopDiscovery()
        serviceInfoCallbacks.keys.toList().forEach { serviceName ->
            safeUnregisterServiceInfoCallback(serviceName)
        }
        serviceInfoCallbacks.clear()
        peers.clear()
    }

    override fun onPresenceChanged(listener: (List<ModulePresence>) -> Unit) {
        this.listener = listener
    }

    private fun registerLocalService(localModule: ModuleId, signalingPort: Int) {
        safeUnregisterService()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "TB-${localModule.value}"
            serviceType = this@NsdModuleDiscoveryService.serviceType
            port = signalingPort
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                serviceRegistered = true
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                serviceRegistered = false
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                serviceRegistered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                serviceRegistered = false
            }
        }
        registrationListener = regListener
        serviceRegistered = false
        runCatching {
            @Suppress("DEPRECATION")
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        }.onFailure {
            registrationListener = null
            serviceRegistered = false
        }
    }

    private fun startDiscovery() {
        safeStopDiscovery()
        val discListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryActive = false
                restartDiscoveryLater()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryActive = false
            }

            override fun onDiscoveryStarted(serviceType: String) {
                discoveryActive = true
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryActive = false
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.remove(serviceInfo.serviceName)
                safeUnregisterServiceInfoCallback(serviceInfo.serviceName)
                publish()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != this@NsdModuleDiscoveryService.serviceType) return
                resolveDiscoveredService(serviceInfo)
            }
        }
        discoveryListener = discListener
        discoveryActive = false
        runCatching {
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discListener)
        }.onFailure {
            discoveryListener = null
            discoveryActive = false
        }
    }

    private fun resolveDiscoveredService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (serviceInfoCallbacks.containsKey(serviceInfo.serviceName)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                override fun onServiceInfoCallbackUnregistered() {
                    serviceInfoCallbacks.remove(serviceInfo.serviceName)
                }

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    applyResolvedService(info)
                }

                override fun onServiceLost() {
                    peers.remove(serviceInfo.serviceName)
                    safeUnregisterServiceInfoCallback(serviceInfo.serviceName)
                    publish()
                }
            }
            serviceInfoCallbacks[serviceInfo.serviceName] = callback
            runCatching {
                nsdManager.registerServiceInfoCallback(serviceInfo, mainExecutor, callback)
            }.onFailure {
                serviceInfoCallbacks.remove(serviceInfo.serviceName)
            }
            return
        }

        runCatching {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    applyResolvedService(resolved)
                }
            })
        }
    }

    private fun applyResolvedService(resolved: NsdServiceInfo) {
        val moduleName = resolved.serviceName.removePrefix("TB-")
        val moduleId = runCatching { ModuleId(moduleName) }.getOrNull() ?: return
        val host = resolved.host?.hostAddress ?: return
        val now = System.currentTimeMillis()
        val existing = peers[resolved.serviceName]
        peers[resolved.serviceName] = ModulePresence(
            moduleId = moduleId,
            host = host,
            port = resolved.port,
            endpointCount = existing?.endpointCount ?: 0,
            lastSeenMs = now
        )
        publish()
    }

    private fun safeUnregisterService() {
        val regListener = registrationListener ?: return
        if (!serviceRegistered) {
            registrationListener = null
            return
        }
        runCatching {
            nsdManager.unregisterService(regListener)
        }
        registrationListener = null
        serviceRegistered = false
    }

    private fun safeStopDiscovery() {
        val discListener = discoveryListener ?: return
        if (!discoveryActive) {
            discoveryListener = null
            return
        }
        runCatching {
            nsdManager.stopServiceDiscovery(discListener)
        }
        discoveryListener = null
        discoveryActive = false
    }

    private fun safeUnregisterServiceInfoCallback(serviceName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = serviceInfoCallbacks.remove(serviceName) ?: return
        runCatching {
            nsdManager.unregisterServiceInfoCallback(callback)
        }
    }

    private fun publish() {
        listener?.invoke(peers.values.sortedBy { it.moduleId.value })
    }

    private fun cleanupExpiredPeers() {
        val now = System.currentTimeMillis()
        val expiredKeys = peers.entries
            .filter { now - it.value.lastSeenMs > peerTtlMs }
            .map { it.key }
        if (expiredKeys.isEmpty()) return
        expiredKeys.forEach { peers.remove(it) }
        publish()
    }

    private fun restartDiscoveryLater() {
        if (!started) return
        scheduler.schedule(
            {
                if (!started) return@schedule
                safeStopDiscovery()
                startDiscovery()
            },
            discoveryRetryMs,
            TimeUnit.MILLISECONDS
        )
    }
}
