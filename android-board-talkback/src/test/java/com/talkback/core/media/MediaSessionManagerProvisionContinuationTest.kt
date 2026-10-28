package com.talkback.core.media

import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import com.talkback.core.webrtc.WebRtcAudioEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
import java.util.concurrent.atomic.AtomicLong

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

    @Test
    fun requestEngine_groupDuplicatePendingDrain_keepsOneGenerationAndSamePc() {
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

        val engines = mutableListOf<WebRtcAudioEngine>()
        val generations = mutableListOf<Long>()
        val ready = CountDownLatch(2)
        coordinator.execute {
            val onReady: (WebRtcAudioEngine) -> Unit = { engine ->
                engines.add(engine)
                generations.add(manager.getState("M04")!!.generation)
                ready.countDown()
            }
            manager.requestEngine(
                "M04",
                MediaBearerScope.GROUP,
                sessionId = "grp:CH-01",
                onReady = onReady,
            )
            manager.requestEngine(
                "M04",
                MediaBearerScope.GROUP,
                sessionId = "grp:CH-01",
                onReady = onReady,
            )
        }

        assertFalse(ready.await(200, TimeUnit.MILLISECONDS))
        releaseHold.countDown()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertEquals(2, engines.size)
        assertSame(engines[0], engines[1])
        assertEquals(listOf(1L, 1L), generations)
        assertEquals(0, manager.mediaSessionReuseCount())
        assertEquals(1L, manager.getState("M04")!!.generation)
    }

    @Test
    fun requestEngine_groupLiveChecking_doesNotEnterReuseRelease() {
        val manager = newManager()
        val reuseReleaseAttempted = AtomicBoolean(false)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionReuse") {
                    reuseReleaseAttempted.set(true)
                }
                coordinator.execute {
                    releaseAction()
                    onReleased(true)
                }
            }
        )

        val firstReady = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val firstGen = AtomicLong()
        val secondGen = AtomicLong(-1L)
        coordinator.execute {
            manager.requestEngine("M04", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
                firstReady.countDown()
            })
        }
        assertTrue(firstReady.await(2, TimeUnit.SECONDS))
        coordinator.execute {
            manager.onIceStateChanged("M04", "CHECKING")
            firstGen.set(manager.getState("M04")!!.generation)
            manager.requestEngine("M04", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
                secondGen.set(manager.getState("M04")!!.generation)
                secondReady.countDown()
            })
        }
        assertTrue(secondReady.await(2, TimeUnit.SECONDS))
        assertFalse(reuseReleaseAttempted.get())
        assertEquals(firstGen.get(), secondGen.get())
        assertEquals(0, manager.mediaSessionReuseCount())
    }
}
