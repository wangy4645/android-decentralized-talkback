package com.talkback.core.media

import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import com.talkback.core.webrtc.StubWebRtcAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MediaSessionManagerTest {
    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var manager: MediaSessionManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        factory = ModuleMediaEngineFactory(
            context = context,
            useStub = true,
            onIceConnectionState = null
        )
        manager = MediaSessionManager(
            factory = factory,
            closedWaitTimeoutMs = 200L,
            pollIntervalMs = 5L
        )
    }

    @Test
    fun create_groupThenConference_incrementsGenerationWithoutReuse() {
        val groupEngine = manager.create("M02", MediaBearerScope.GROUP)
        val groupGen = manager.getState("M02")!!.generation

        val conferenceEngine = manager.create("M02", MediaBearerScope.CONFERENCE)
        val conferenceGen = manager.getState("M02")!!.generation

        assertNotSame(groupEngine, conferenceEngine)
        assertTrue(conferenceGen > groupGen)
        assertEquals(MediaBearerScope.CONFERENCE, manager.getState("M02")!!.scope)
    }

    @Test
    fun resetAll_closesSessionsAndReturnsBarrierResult() {
        manager.create("M02", MediaBearerScope.GROUP)
        manager.create("M03", MediaBearerScope.GROUP)

        val result = manager.resetAll(listOf("M02", "M03"), channelId = "CH-01")

        assertEquals(2, result.moduleCount)
        assertEquals(0, result.unresolvedCount)
        assertNull(manager.getState("M02"))
        assertNull(manager.getState("M03"))
    }

    @Test
    fun onIceStateChanged_updatesLifecycleToConnected() {
        manager.create("M02", MediaBearerScope.CONFERENCE)
        manager.onIceStateChanged("M02", "CONNECTED")

        val state = manager.getState("M02")!!
        assertEquals(MediaLifecycle.CONNECTED, state.lifecycle)
        assertEquals("CONNECTED", state.iceState)
    }

    @Test
    fun close_removesState() {
        manager.create("M02", MediaBearerScope.GROUP)
        manager.close("M02")
        assertNull(manager.getState("M02"))
    }

    @Test
    fun create_afterBarrierReset_doesNotReuse() {
        manager.create("M02", MediaBearerScope.GROUP)
        manager.resetAll(listOf("M02"))
        manager.create("M02", MediaBearerScope.CONFERENCE)
        assertEquals(0, manager.mediaSessionReuseCount())
    }
}
