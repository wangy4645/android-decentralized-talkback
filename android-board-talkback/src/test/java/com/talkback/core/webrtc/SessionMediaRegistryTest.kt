package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SessionMediaRegistryTest {
    @Test
    fun groupAndUnicastUseSeparatePeerConnections() {
        val context = RuntimeEnvironment.getApplication()
        val registry = SessionMediaRegistry(
            context,
            useStub = true,
            onMeshIce = { _, _ -> },
            onUnicastIce = { _, _ -> }
        )

        val group = registry.groupEngine("M02")
        val unicast = registry.unicastEngine("call-session-1")

        assertNotSame(group, unicast)
        assertSame(group, registry.getGroup("M02"))
        assertSame(unicast, registry.getUnicast("call-session-1"))

        registry.releaseUnicast("call-session-1")
        assertNull(registry.getUnicast("call-session-1"))
        assertNotNull(registry.getGroup("M02"))

        registry.releaseGroup("M02")
        assertNull(registry.getGroup("M02"))
    }

    @Test
    fun groupThenConference_createsFreshPeerConnection() {
        val context = RuntimeEnvironment.getApplication()
        val registry = SessionMediaRegistry(
            context,
            useStub = true,
            onMeshIce = { _, _ -> },
            onUnicastIce = { _, _ -> }
        )

        val group = registry.groupEngine("M02")
        val conference = registry.conferenceEngine("M02")

        assertNotSame(group, conference)
        assertEquals(
            com.talkback.core.webrtc.MediaBearerScope.CONFERENCE,
            registry.meshSessionState("M02")!!.scope
        )
    }

    @Test
    fun barrierResetBeforeConference_avoidsReuseMetric() {
        val context = RuntimeEnvironment.getApplication()
        val registry = SessionMediaRegistry(
            context,
            useStub = true,
            onMeshIce = { _, _ -> },
            onUnicastIce = { _, _ -> }
        )
        registry.groupEngine("M02")
        registry.resetMeshForMeetingBarrier(listOf("M02"), channelId = "CH-01")
        registry.conferenceEngine("M02")
        assertEquals(0, registry.mediaSessionReuseCount())
    }
}
