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
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MediaSessionManagerProvisionContinuationTest {
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

    private fun newManager(): MediaSessionManager {
        return MediaSessionManager(
            factory = factory,
            releaseWatchdogScheduler = ReleaseWatchdogScheduler { _, _ -> }
        ).apply {
            installMeshMediaCoordinatorDeferral(
                MeshMediaCoordinatorDeferral { block -> coordinator.execute { block() } }
            )
        }
    }

    @Test
    fun requestEngine_whenProvisionPending_doesNotFailSyncCaller() {
        val manager = newManager()
        val releaseHold = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionProvision") {
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinator.execute { onReleased(true) }
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
        val provisioned = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.CONFERENCE, sessionId = "sess-1", onReady = {
                provisioned.countDown()
            })
            requestReturned.countDown()
        }

        assertTrue(requestReturned.await(500, TimeUnit.MILLISECONDS))
        assertFalse(provisioned.await(200, TimeUnit.MILLISECONDS))
        releaseHold.countDown()
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun requestEngine_whenProvisionFails_invokesFailureContinuationWithoutEngine() {
        val manager = newManager()
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                coordinator.execute {
                    releaseAction()
                    onReleased(origin != "mediaSessionProvision")
                }
            }
        )

        val provisioned = AtomicBoolean(false)
        val failed = CountDownLatch(1)
        coordinator.execute {
            manager.requestEngine(
                moduleId = "M02",
                scope = MediaBearerScope.CONFERENCE,
                sessionId = "sess-1",
                onReady = { provisioned.set(true) },
                onFailed = { failed.countDown() }
            )
        }

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertFalse(provisioned.get())
    }
}
