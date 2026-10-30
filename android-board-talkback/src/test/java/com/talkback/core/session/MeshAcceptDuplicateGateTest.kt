package com.talkback.core.session

import com.talkback.core.media.MediaLifecycle
import com.talkback.core.media.MediaSessionState
import com.talkback.core.webrtc.MediaBearerScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAcceptDuplicateGateTest {
    private val liveMedia = MediaSessionState(
        moduleId = "M02",
        scope = MediaBearerScope.CONFERENCE,
        lifecycle = MediaLifecycle.CONNECTED,
        iceState = "CONNECTED",
        generation = 2L,
    )

    @Test
    fun staleHistoricalIce_doesNotQualifyAfterRelease() {
        assertFalse(
            MeshAcceptDuplicateGate.qualifiesForMeshAlreadyConnected(
                qosIceState = "CONNECTED",
                moduleId = "M02",
                meshCompletedModules = emptySet(),
                liveMediaState = null,
            )
        )
    }

    @Test
    fun staleQosWithMissingMeshCompletion_doesNotQualify() {
        assertFalse(
            MeshAcceptDuplicateGate.qualifiesForMeshAlreadyConnected(
                qosIceState = "CONNECTED",
                moduleId = "M02",
                meshCompletedModules = emptySet(),
                liveMediaState = liveMedia,
            )
        )
    }

    @Test
    fun currentGenerationDuplicateAccept_stillQualifies() {
        assertTrue(
            MeshAcceptDuplicateGate.qualifiesForMeshAlreadyConnected(
                qosIceState = "CONNECTED",
                moduleId = "M02",
                meshCompletedModules = setOf("M02"),
                liveMediaState = liveMedia,
            )
        )
    }

    @Test
    fun disconnectedQos_doesNotQualifyEvenWhenMeshComplete() {
        assertFalse(
            MeshAcceptDuplicateGate.qualifiesForMeshAlreadyConnected(
                qosIceState = "DISCONNECTED",
                moduleId = "M02",
                meshCompletedModules = setOf("M02"),
                liveMediaState = liveMedia,
            )
        )
    }
}
