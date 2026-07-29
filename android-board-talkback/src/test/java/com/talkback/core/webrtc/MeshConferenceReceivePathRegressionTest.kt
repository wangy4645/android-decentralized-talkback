package com.talkback.core.webrtc

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.signaling.PeerTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 regression: mesh conference audio can be audible (WebRTC default playback)
 * while [receivePathLive] stays false if mesh PCM taps were never installed.
 */
class MeshConferenceReceivePathRegressionTest {

    @Test
    fun meshFirstJoin_withoutSyncMeshSession_receivePathNotLive() {
        val observer = ReceivePathLivenessObserver(clock = { 0L })
        val session = meshConferenceSession(
            sessionId = "conf-mesh",
            localId = "M01",
            remoteModuleIds = listOf("M02", "M03")
        )

        // ICE may be connected, but coordinator never called syncMeshSession on mesh first join.
        assertFalse(observer.receivePathLive(session.id, "M02"))
        assertFalse(observer.receivePathLive(session.id, "M03"))
    }

    @Test
    fun g_r30_j_regression_meshObserverAttached_firstPcm_notReconnecting() {
        var now = 0L
        val observer = ReceivePathLivenessObserver(
            debounceMs = 500L,
            clock = { now }
        )
        val m02Engine = StubWebRtcAudioEngine()
        val m03Engine = StubWebRtcAudioEngine()
        val session = meshConferenceSession(
            sessionId = "conf-mesh",
            localId = "M01",
            remoteModuleIds = listOf("M02", "M03")
        )

        observer.syncMeshSession(session, ModuleId("M01")) { moduleId ->
            when (moduleId) {
                "M02" -> m02Engine
                "M03" -> m03Engine
                else -> null
            }
        }

        repeat(6) { step ->
            now = step * 100L
            m02Engine.simulateInboundPcm()
            m03Engine.simulateInboundPcm()
        }
        assertTrue(observer.receivePathLive(session.id, "M02"))
        assertTrue(observer.receivePathLive(session.id, "M03"))
    }

    private fun meshConferenceSession(
        sessionId: String,
        localId: String,
        remoteModuleIds: List<String>
    ): TalkbackSession {
        val local = EndpointAddress(
            moduleId = ModuleId(localId),
            endpointId = EndpointId("E01")
        )
        return TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-01").apply {
            mediaTopology = GroupMediaTopology.MESH
            remoteModuleIds.forEach { remoteId ->
                remotePeersByModule[remoteId] = PeerTarget(
                    host = "127.0.0.1",
                    port = 9_002
                )
            }
        }
    }
}
