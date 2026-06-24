package com.talkback.app

import android.content.Context
import com.talkback.core.discovery.FixedDiscoveryService
import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingChannel
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.signaling.PeerTarget

internal class TestTalkbackNode(
    context: Context,
    val moduleId: ModuleId,
    val port: Int,
    hub: InMemorySignalingHub,
    allPeers: List<ModulePresence>,
    sharedSecret: String = "test-secret",
    sessionIdleTimeoutMs: Long = 30_000L,
    cleanupIntervalMs: Long = 5_000L,
    heartbeatIntervalMs: Long = 2_000L,
    autoReDialOnModuleRecovery: Boolean = true,
    conferenceHostIceReconnectGraceMs: Long = 5_000L,
    conferenceInviteRingTimeoutMs: Long = 20_000L
) {
    val logs = mutableListOf<String>()
    val channel = InMemorySignalingChannel(hub, TEST_HOST, port)
    private val discovery = FixedDiscoveryService(moduleId, allPeers)
    val runtime: TalkbackRuntime = TalkbackRuntimeFactory.create(
        context = context,
        config = TalkbackRuntimeConfig(
            localModuleId = moduleId,
            signalingPort = port,
            autoAcceptIncoming = true,
            sessionIdleTimeoutMs = sessionIdleTimeoutMs,
            cleanupIntervalMs = cleanupIntervalMs,
            heartbeatIntervalMs = heartbeatIntervalMs,
            autoReDialOnModuleRecovery = autoReDialOnModuleRecovery,
            sharedSecret = sharedSecret,
            maxGroupModules = 8,
            conferenceHostIceReconnectGraceMs = conferenceHostIceReconnectGraceMs,
            conferenceInviteRingTimeoutMs = conferenceInviteRingTimeoutMs
        ),
        mode = AudioEngineMode.STUB,
        discoveryService = discovery,
        signalingChannel = channel,
        onLog = { msg -> synchronized(logs) { logs.add(msg) } }
    )

    val localEndpoint = EndpointAddress(moduleId, EndpointId("E01"))

    fun start() {
        runtime.start()
        runtime.upsertLocalEndpoint(localEndpoint.endpointId, "TestHandset", online = true)
        discovery.publishNow()
    }

    fun stop() {
        runtime.stop()
    }

    fun refreshDiscovery() {
        discovery.publishNow()
    }

    fun waitForLog(timeoutMs: Long = 5_000L, predicate: (String) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(logs) {
                if (logs.any(predicate)) return true
            }
            Thread.sleep(50)
        }
        return false
    }

    fun hasLog(predicate: (String) -> Boolean): Boolean = synchronized(logs) { logs.any(predicate) }

    companion object {
        const val TEST_HOST = "127.0.0.1"

        fun allPeers(vararg modules: Pair<ModuleId, Int>): List<ModulePresence> {
            val now = System.currentTimeMillis()
            return modules.map { (id, port) ->
                ModulePresence(
                    moduleId = id,
                    host = TEST_HOST,
                    port = port,
                    endpointCount = 1,
                    lastSeenMs = now
                )
            }
        }

        fun peerTarget(port: Int): PeerTarget = PeerTarget(TEST_HOST, port)
    }
}

internal fun TestTalkbackNode.pressPtt(sessionId: String) {
    runtime.pressPtt(sessionId, EndpointPriority.NORMAL)
}

internal fun TestTalkbackNode.releasePtt(sessionId: String) {
    runtime.releasePtt(sessionId)
}
