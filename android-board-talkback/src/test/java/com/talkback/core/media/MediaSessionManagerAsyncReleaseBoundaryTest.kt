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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MediaSessionManagerAsyncReleaseBoundaryTest {
    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var coordinator: java.util.concurrent.ExecutorService
    private val pendingWatchdogActions = ConcurrentLinkedQueue<() -> Unit>()
    private val asyncReleaseStarts = AtomicInteger(0)
    private val watchdogScheduler = ReleaseWatchdogScheduler { _, action ->
        pendingWatchdogActions.add(action)
    }

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

    private fun newManager(watchdogMs: Long = 50L): MediaSessionManager {
        return MediaSessionManager(
            factory = factory,
            releaseWatchdogCompleteMs = watchdogMs,
            releaseWatchdogScheduler = watchdogScheduler
        ).apply {
            installMeshMediaCoordinatorDeferral(
                MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
            )
        }
    }

    private fun installImmediateAsyncRelease(
        manager: MediaSessionManager,
        delegate: MeshMediaAsyncRelease = MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
            val complete = {
                releaseAction()
                onReleased(true)
            }
            if (origin == "mediaSessionProvision") {
                Thread {
                    coordinator.execute { complete() }
                }.apply { isDaemon = true }.start()
            } else {
                coordinator.execute { complete() }
            }
        }
    ) {
        manager.installAsyncMeshMediaRelease(delegate)
    }

    private fun provisionConference(manager: MediaSessionManager, moduleId: String) {
        val ready = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine(moduleId, MediaBearerScope.CONFERENCE, sessionId = "setup", onReady = {
                ready.countDown()
            })
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
    }

    private fun runWatchdogOnCoordinator() {
        val watchdogAction = pendingWatchdogActions.poll() ?: error("no watchdog action")
        val watchdogFinished = CountDownLatch(1)
        coordinator.execute {
            watchdogAction()
            watchdogFinished.countDown()
        }
        assertTrue(watchdogFinished.await(1, TimeUnit.SECONDS))
        val preemptFinished = CountDownLatch(1)
        coordinator.execute { preemptFinished.countDown() }
        assertTrue(preemptFinished.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun requestEngine_afterReleaseInFlightCleared_doesNotSyncReleaseOnCallerThread() {
        val manager = newManager()
        val firstReleaseStarted = CountDownLatch(1)
        val provisionReleaseHold = CountDownLatch(1)
        val provisionReleaseStarted = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                asyncReleaseStarts.incrementAndGet()
                when (origin) {
                    "mediaSessionClose" -> {
                        firstReleaseStarted.countDown()
                        // hung hangup release; watchdog will clear releaseInFlight
                    }
                    "mediaSessionProvision" -> {
                        provisionReleaseStarted.countDown()
                        Thread {
                            provisionReleaseHold.await(5, TimeUnit.SECONDS)
                            releaseAction()
                            coordinator.execute { onReleased(true) }
                        }.apply { isDaemon = true }.start()
                    }
                    else -> {
                        Thread {
                            releaseAction()
                            coordinator.execute { onReleased(true) }
                        }.apply { isDaemon = true }.start()
                    }
                }
            }
        )
        manager.admitConferenceRelease("M02", sessionId = "sess-1", origin = "releaseSessionMedia")
        assertTrue(firstReleaseStarted.await(1, TimeUnit.SECONDS))
        runWatchdogOnCoordinator()
        assertFalse(manager.releaseInFlightModules().contains("M02"))

        val requestReturned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.CONFERENCE, sessionId = "sess-2", onReady = { })
            requestReturned.countDown()
        }
        assertTrue(provisionReleaseStarted.await(1, TimeUnit.SECONDS))
        assertTrue(requestReturned.await(200, TimeUnit.MILLISECONDS))
        assertTrue(provisionReleaseHold.await(200, TimeUnit.MILLISECONDS).not())
        provisionReleaseHold.countDown()
    }

    @Test
    fun requestEngine_withAsyncRelease_usesAsyncPathForProvisionFallthrough() {
        val manager = newManager()
        installImmediateAsyncRelease(manager)
        val releaseScheduled = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionProvision") {
                    releaseScheduled.countDown()
                    Thread {
                        coordinator.execute {
                            releaseAction()
                            onReleased(true)
                        }
                    }.apply { isDaemon = true }.start()
                } else {
                    coordinator.execute {
                        releaseAction()
                        onReleased(true)
                    }
                }
            }
        )

        val requestReturned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.CONFERENCE, sessionId = "sess-2", onReady = { })
            requestReturned.countDown()
        }

        assertTrue(requestReturned.await(500, TimeUnit.MILLISECONDS))
        assertTrue(releaseScheduled.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun deferGroupAction_withAsyncRelease_usesAsyncProvisionOnFlush() {
        val manager = newManager()
        manager.engineOwnershipGateForTest().beginConferenceRelease("M02")
        val releaseScheduled = CountDownLatch(1)
        val groupReady = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionProvision") {
                    releaseScheduled.countDown()
                    Thread {
                        coordinator.execute {
                            releaseAction()
                            onReleased(true)
                        }
                    }.apply { isDaemon = true }.start()
                } else {
                    coordinator.execute {
                        releaseAction()
                        onReleased(true)
                    }
                }
            }
        )
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
                groupReady.countDown()
            })
        }
        manager.engineOwnershipGateForTest().completeConferenceRelease("M02", success = true)
        assertTrue(releaseScheduled.await(1, TimeUnit.SECONDS))
        assertTrue(groupReady.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun watchdogFailed_thenSecondConferenceRequest_keepsCoordinatorRunnable() {
        val manager = newManager()
        val releaseHold = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, _, _, _ ->
                asyncReleaseStarts.incrementAndGet()
                // hung: never completes first hangup release
            }
        )
        manager.admitConferenceRelease("M02", sessionId = "sess-1", origin = "releaseSessionMedia")
        runWatchdogOnCoordinator()

        val secondRequestReturned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.CONFERENCE, sessionId = "sess-2", onReady = { })
            secondRequestReturned.countDown()
        }
        assertTrue(secondRequestReturned.await(500, TimeUnit.MILLISECONDS))

        val followUpTask = CountDownLatch(1)
        coordinator.execute { followUpTask.countDown() }
        assertTrue(followUpTask.await(500, TimeUnit.MILLISECONDS))
        releaseHold.countDown()
    }
}
