package com.talkback.core.media

import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MediaSessionManagerCreateContinuationTest {
    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var coordinator: java.util.concurrent.ExecutorService

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        factory = ModuleMediaEngineFactory(
            context = context,
            useStub = true,
            onIceConnectionState = null
        )
        coordinator = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "talkback-coordinator")
        }
    }

    @After
    fun tearDown() {
        coordinator.shutdownNow()
    }

    private fun newManager(
        eligible: (String?, String) -> Boolean = { _, _ -> true }
    ): MediaSessionManager {
        return MediaSessionManager(
            factory = factory,
            releaseWatchdogScheduler = ReleaseWatchdogScheduler { _, _ -> }
        ).apply {
            installMeshMediaCoordinatorDeferral(
                MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
            )
            installCreateContinuationEligibility(CreateContinuationEligibility(eligible))
            installAsyncMeshMediaRelease(
                MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                    Thread {
                        if (origin == "mediaSessionClose") {
                            Thread.sleep(20)
                        }
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            )
        }
    }

    @Test
    fun closeInFlight_drainsCreateContinuation_invokesOnReadyOnce() {
        val releaseHold = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val manager = newManager()
        val sessionId = "sess-create-1"
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionClose") {
                    closeStarted.countDown()
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    Thread {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            }
        )
        val provisioned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId, onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinator.execute { manager.close("M02", sessionId) }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))

        val readyCount = AtomicInteger(0)
        val requestReturned = CountDownLatch(1)
        val ready = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine(
                moduleId = "M02",
                scope = MediaBearerScope.CONFERENCE,
                sessionId = sessionId,
                intent = EngineRequestIntent.CREATE_CONTINUATION,
                onReady = {
                    readyCount.incrementAndGet()
                    ready.countDown()
                }
            )
            requestReturned.countDown()
        }
        assertTrue(requestReturned.await(2, TimeUnit.SECONDS))
        assertTrue(manager.releaseInFlightModules().contains("M02"))
        releaseHold.countDown()
        assertTrue(ready.await(3, TimeUnit.SECONDS))
        assertEquals(1, readyCount.get())
    }

    @Test
    fun provisionPath_singleDrain_noDuplicate() {
        val manager = newManager()
        val sessionId = "sess-provision-1"
        val readyCount = AtomicInteger(0)
        val ready = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine(
                moduleId = "M02",
                scope = MediaBearerScope.CONFERENCE,
                sessionId = sessionId,
                intent = EngineRequestIntent.DEFAULT,
                onReady = {
                    readyCount.incrementAndGet()
                    ready.countDown()
                }
            )
        }
        assertTrue(ready.await(3, TimeUnit.SECONDS))
        assertEquals(1, readyCount.get())
    }

    @Test
    fun closeComplete_doesNotDrainHangupStackingConference() {
        val releaseHold = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val manager = newManager()
        val sessionId = "sess-stack-1"
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionClose") {
                    closeStarted.countDown()
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    Thread {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            }
        )
        val provisioned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId, onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinator.execute { manager.close("M02", sessionId) }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        assertTrue(manager.releaseInFlightModules().contains("M02"))

        val readyCount = AtomicInteger(0)
        coordinator.execute {
            manager.requestEngine(
                moduleId = "M02",
                scope = MediaBearerScope.CONFERENCE,
                sessionId = sessionId,
                intent = EngineRequestIntent.DEFAULT,
                onReady = { readyCount.incrementAndGet() }
            )
        }
        assertEquals(0, readyCount.get())
        releaseHold.countDown()
        Thread.sleep(200)
        assertEquals(0, readyCount.get())
    }

    @Test
    fun createContinuation_discardedWhenFenceRejects() {
        val releaseHold = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val manager = newManager(eligible = { _, _ -> false })
        val sessionId = "sess-discard-1"
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionClose") {
                    closeStarted.countDown()
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    Thread {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            }
        )
        val provisioned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId, onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinator.execute { manager.close("M02", sessionId) }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))

        val failed = CountDownLatch(1)
        val readyCount = AtomicInteger(0)
        coordinator.execute {
            manager.requestEngine(
                moduleId = "M02",
                scope = MediaBearerScope.CONFERENCE,
                sessionId = sessionId,
                intent = EngineRequestIntent.CREATE_CONTINUATION,
                onReady = { readyCount.incrementAndGet() },
                onFailed = { failed.countDown() }
            )
        }
        releaseHold.countDown()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(0, readyCount.get())
    }

    @Test
    fun createContinuation_exactlyOnceOnRepeatedCloseCompletion() {
        val manager = newManager()
        val sessionId = "sess-once-1"
        val releaseHold = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionClose") {
                    closeStarted.countDown()
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    Thread {
                        releaseAction()
                        coordinator.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            }
        )
        val provisioned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId, onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinator.execute { manager.close("M02", sessionId) }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))

        val readyCount = AtomicInteger(0)
        coordinator.execute {
            manager.requestEngine(
                moduleId = "M02",
                scope = MediaBearerScope.CONFERENCE,
                sessionId = sessionId,
                intent = EngineRequestIntent.CREATE_CONTINUATION,
                onReady = { readyCount.incrementAndGet() }
            )
        }
        releaseHold.countDown()
        Thread.sleep(500)
        assertEquals(1, readyCount.get())
        assertFalse(manager.releaseInFlightModules().contains("M02"))
    }
}
