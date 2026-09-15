package com.talkback.core.media

import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import com.talkback.core.webrtc.StubWebRtcAudioEngine
import com.talkback.core.media.MeshMediaAsyncRelease
import com.talkback.core.media.MeshMediaCoordinatorDeferral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun create_conferenceConnected_reusesWithoutViolation() {
        val first = manager.create("M02", MediaBearerScope.CONFERENCE)
        manager.onIceStateChanged("M02", "CONNECTED")
        val firstGen = manager.getState("M02")!!.generation

        val second = manager.create("M02", MediaBearerScope.CONFERENCE)

        assertEquals(first, second)
        assertEquals(firstGen, manager.getState("M02")!!.generation)
        assertEquals(0, manager.mediaSessionReuseCount())
    }

    @Test
    fun create_conferenceDisconnected_reusesWithoutViolation() {
        manager.create("M02", MediaBearerScope.CONFERENCE)
        manager.onIceStateChanged("M02", "CONNECTED")
        manager.onIceStateChanged("M02", "DISCONNECTED")

        manager.create("M02", MediaBearerScope.CONFERENCE)

        assertEquals(0, manager.mediaSessionReuseCount())
        assertEquals("DISCONNECTED", manager.getState("M02")!!.iceState)
        assertEquals(MediaLifecycle.DEGRADED, manager.getState("M02")!!.lifecycle)
    }

    @Test
    fun create_conferenceChecking_reusesWithoutViolation() {
        manager.create("M02", MediaBearerScope.CONFERENCE)
        manager.onIceStateChanged("M02", "CHECKING")

        manager.create("M02", MediaBearerScope.CONFERENCE)

        assertEquals(0, manager.mediaSessionReuseCount())
        assertEquals("CHECKING", manager.getState("M02")!!.iceState)
    }

    private fun provisionConferenceOnCoordinator(
        manager: MediaSessionManager,
        coordinator: java.util.concurrent.ExecutorService,
        moduleId: String
    ) {
        val ready = java.util.concurrent.CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine(moduleId, MediaBearerScope.CONFERENCE, sessionId = "setup", onReady = {
                ready.countDown()
            })
        }
        org.junit.Assert.assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun create_conferenceToGroup_usesAsyncReleaseWithoutBlockingCaller() {
        val coordinator = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
        val releaseStarted = java.util.concurrent.CountDownLatch(1)
        val releaseHold = java.util.concurrent.CountDownLatch(1)
        val groupReady = java.util.concurrent.CountDownLatch(1)
        manager.installMeshMediaCoordinatorDeferral(
            MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
        )
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                Thread {
                    if (origin == "mediaSessionProvision") {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    } else {
                        releaseStarted.countDown()
                        releaseHold.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }
                }.apply { isDaemon = true }.start()
            }
        )
        provisionConferenceOnCoordinator(manager, coordinator, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")

        val caller = Thread {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
                groupReady.countDown()
            })
        }
        caller.start()
        Thread.sleep(100)
        assertFalse(releaseStarted.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        manager.close("M02", sessionId = "302fb48d")
        assertTrue(releaseStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(groupReady.await(200, java.util.concurrent.TimeUnit.MILLISECONDS).not())
        releaseHold.countDown()
        caller.join(2_000)
        assertTrue(groupReady.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(MediaBearerScope.GROUP, manager.getState("M02")!!.scope)
        coordinator.shutdownNow()
    }

    @Test
    fun create_conferenceToGroup_provisionsOnNextCoordinatorTurn() {
        val coordinator = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
        val releaseTurn = java.util.concurrent.atomic.AtomicInteger(0)
        val provisionTurn = java.util.concurrent.atomic.AtomicInteger(0)
        val turnCounter = java.util.concurrent.atomic.AtomicInteger(0)
        manager.installMeshMediaCoordinatorDeferral(
            MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
        )
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                Thread {
                    if (origin == "mediaSessionProvision") {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    } else {
                        releaseAction()
                        coordinator.execute {
                            val turn = turnCounter.incrementAndGet()
                            releaseTurn.set(turn)
                            onReleased(true)
                            assertTrue(
                                "provision must not run on release coordinator turn",
                                provisionTurn.get() == 0 || provisionTurn.get() > turn
                            )
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        )
        provisionConferenceOnCoordinator(manager, coordinator, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")

        val groupReady = java.util.concurrent.CountDownLatch(1)
        manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
            provisionTurn.set(turnCounter.incrementAndGet())
            groupReady.countDown()
        })
        manager.close("M02", sessionId = "302fb48d")
        assertTrue(groupReady.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(releaseTurn.get() > 0)
        assertTrue(provisionTurn.get() > releaseTurn.get())
        coordinator.shutdownNow()
    }

    @Test
    fun create_conferenceToGroup_releaseFailed_doesNotProvision() {
        val coordinator = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
        val groupReady = java.util.concurrent.CountDownLatch(1)
        manager.installMeshMediaCoordinatorDeferral(
            MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
        )
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionProvision") {
                    Thread {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    coordinator.execute { onReleased(false) }
                }
            }
        )
        provisionConferenceOnCoordinator(manager, coordinator, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")
        manager.close("M02", sessionId = "302fb48d")

        manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
            groupReady.countDown()
        })
        assertTrue(groupReady.await(500, java.util.concurrent.TimeUnit.MILLISECONDS).not())
        assertNull(manager.getState("M02"))
        val snapshot = manager.engineOwnershipGateForTest().stateSnapshot("M02")
        assertEquals(EngineOwnershipGate.Owner.CONFERENCE_EDGE, snapshot?.first)
        assertEquals(EngineOwnershipGate.State.FAILED, snapshot?.second)
        coordinator.shutdownNow()
    }

    @Test
    fun create_conferenceToGroup_defersReuseUntilConferenceReleased() {
        val coordinator = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
        val groupReady = java.util.concurrent.CountDownLatch(1)
        val reuseReleaseAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
        manager.installMeshMediaCoordinatorDeferral(
            MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
        )
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                Thread {
                    if (origin == "mediaSessionProvision") {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    } else {
                        if (origin == "mediaSessionReuse") {
                            reuseReleaseAttempted.set(true)
                        }
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }
                }.apply { isDaemon = true }.start()
            }
        )
        provisionConferenceOnCoordinator(manager, coordinator, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")

        manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
            groupReady.countDown()
        })
        Thread.sleep(100)
        assertFalse(reuseReleaseAttempted.get())

        manager.close("M02", sessionId = "302fb48d")
        assertTrue(groupReady.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(reuseReleaseAttempted.get())
        coordinator.shutdownNow()
    }

    @Test
    fun hangupBarrier_waitsForConferenceReleaseBeforeCallback() {
        val coordinator = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
        val barrierComplete = java.util.concurrent.CountDownLatch(1)
        var barrierResult = false
        val releaseHold = java.util.concurrent.CountDownLatch(1)
        manager.installMeshMediaCoordinatorDeferral(
            MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
        )
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                Thread {
                    if (origin == "mediaSessionProvision") {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    } else if (origin == "mediaSessionClose") {
                        releaseHold.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    } else {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }
                }.apply { isDaemon = true }.start()
            }
        )
        provisionConferenceOnCoordinator(manager, coordinator, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")
        manager.registerHangupMediaBarrier(listOf("M02")) { success ->
            barrierResult = success
            barrierComplete.countDown()
        }
        manager.close("M02", sessionId = "302fb48d")
        assertTrue(barrierComplete.await(200, java.util.concurrent.TimeUnit.MILLISECONDS).not())
        releaseHold.countDown()
        assertTrue(barrierComplete.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(barrierResult)
        coordinator.shutdownNow()
    }

    @Test
    fun admitConferenceRelease_schedulesImmediatelyWithoutEdgeDispatch() {
        val coordinator = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
        val releaseScheduled = java.util.concurrent.CountDownLatch(1)
        manager.installMeshMediaCoordinatorDeferral(
            MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
        )
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionProvision") {
                    Thread {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    releaseScheduled.countDown()
                }
            }
        )
        provisionConferenceOnCoordinator(manager, coordinator, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")

        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")

        assertTrue(releaseScheduled.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        coordinator.shutdownNow()
    }

    @Test
    fun create_conferenceClosed_allowsNewGeneration() {
        val first = manager.create("M02", MediaBearerScope.CONFERENCE)
        manager.onIceStateChanged("M02", "CONNECTED")
        val firstGen = manager.getState("M02")!!.generation
        manager.close("M02")

        val second = manager.create("M02", MediaBearerScope.CONFERENCE)

        assertNotSame(first, second)
        assertTrue(manager.getState("M02")!!.generation > firstGen)
        assertEquals(0, manager.mediaSessionReuseCount())
    }
}
