package com.talkback.core.media

import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class ReleaseTransactionWatchdogTest {
    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var coordinator: java.util.concurrent.ExecutorService
    private val pendingWatchdogActions = ConcurrentLinkedQueue<() -> Unit>()
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

    private fun newManager(watchdogMs: Long = 100L): MediaSessionManager {
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

    private fun fireWatchdogOnCoordinator() {
        val action = pendingWatchdogActions.poll()
            ?: error("no pending watchdog action")
        coordinator.execute { action() }
        val done = CountDownLatch(1)
        coordinator.execute { done.countDown() }
        assertTrue(done.await(1, TimeUnit.SECONDS))
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

    @Test
    fun conferenceRelease_normalCompletion_reachesReleased() {
        val manager = newManager()
        val barrierComplete = CountDownLatch(1)
        var barrierResult = false
        installImmediateAsyncRelease(manager)
        provisionConference(manager, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")
        manager.registerHangupMediaBarrier(listOf("M02")) { success ->
            barrierResult = success
            barrierComplete.countDown()
        }
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        assertTrue(barrierComplete.await(2, TimeUnit.SECONDS))
        assertTrue(barrierResult)
        val snapshot = manager.engineOwnershipGateForTest().stateSnapshot("M02")
        assertEquals(EngineOwnershipGate.Owner.NONE, snapshot?.first)
        assertEquals(EngineOwnershipGate.State.RELEASED, snapshot?.second)
    }

    @Test
    fun conferenceRelease_hungWatchdog_failsAndRetainsOwnership() {
        val manager = newManager()
        val barrierComplete = CountDownLatch(1)
        var barrierResult = true
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, _, _, _ ->
                // hung: never calls onReleased
            }
        )
        manager.registerHangupMediaBarrier(listOf("M02")) { success ->
            barrierResult = success
            barrierComplete.countDown()
        }
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        assertTrue(pendingWatchdogActions.isNotEmpty())
        fireWatchdogOnCoordinator()
        assertTrue(barrierComplete.await(2, TimeUnit.SECONDS))
        assertFalse(barrierResult)
        val snapshot = manager.engineOwnershipGateForTest().stateSnapshot("M02")
        assertEquals(EngineOwnershipGate.Owner.CONFERENCE_EDGE, snapshot?.first)
        assertEquals(EngineOwnershipGate.State.FAILED, snapshot?.second)
    }

    @Test
    fun conferenceRelease_lateCompletionAfterFailed_doesNotReleaseOwnership() {
        val manager = newManager()
        val releaseHold = CountDownLatch(1)
        val lateReleased = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, _, releaseAction, onReleased ->
                Thread {
                    releaseHold.await(5, TimeUnit.SECONDS)
                    releaseAction()
                    coordinator.execute {
                        onReleased(true)
                        lateReleased.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }
        )
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        fireWatchdogOnCoordinator()
        coordinator.execute { releaseHold.countDown() }
        assertTrue(lateReleased.await(2, TimeUnit.SECONDS))
        val snapshot = manager.engineOwnershipGateForTest().stateSnapshot("M02")
        assertEquals(EngineOwnershipGate.State.FAILED, snapshot?.second)
    }

    @Test
    fun hangupBarrier_admittedModulesOnly_allFailed_closesOnceWithoutPhantomParticipant() {
        val manager = newManager()
        val barrierTerminals = AtomicInteger(0)
        var barrierResult = true
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, _, _, _ -> }
        )
        manager.registerHangupMediaBarrier(listOf("M02", "M03", "M04")) { success ->
            barrierResult = success
            barrierTerminals.incrementAndGet()
        }
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        manager.admitConferenceRelease("M03", sessionId = "302fb48d", origin = "releaseSessionMedia")
        manager.admitConferenceRelease("M04", sessionId = "302fb48d", origin = "releaseSessionMedia")
        assertEquals(3, pendingWatchdogActions.size)
        repeat(3) {
            val watchdogAction = pendingWatchdogActions.poll()!!
            coordinator.execute { watchdogAction() }
        }
        Thread.sleep(200)
        assertEquals(1, barrierTerminals.get())
        assertFalse(barrierResult)
    }

    @Test
    fun conferenceRelease_doubleTerminalization_emitsSingleBarrierTerminal() {
        val manager = newManager()
        val barrierTerminals = AtomicInteger(0)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, _, _, _ -> }
        )
        manager.registerHangupMediaBarrier(listOf("M02")) { _ ->
            barrierTerminals.incrementAndGet()
        }
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        val watchdogAction = pendingWatchdogActions.poll()!!
        coordinator.execute { watchdogAction() }
        coordinator.execute { watchdogAction() }
        Thread.sleep(200)
        assertEquals(1, barrierTerminals.get())
    }

    @Test
    fun conferenceRelease_failed_doesNotProvisionGroupEngine() {
        val manager = newManager()
        val groupReady = CountDownLatch(1)
        installImmediateAsyncRelease(
            manager,
            delegate = MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionProvision") {
                    Thread {
                        coordinator.execute {
                            releaseAction()
                            onReleased(true)
                        }
                    }.apply { isDaemon = true }.start()
                } else {
                    coordinator.execute { onReleased(false) }
                }
            }
        )
        provisionConference(manager, "M02")
        manager.onIceStateChanged("M02", "CONNECTED")
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        coordinator.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, sessionId = "grp:CH-01", onReady = {
                groupReady.countDown()
            })
        }
        assertFalse(groupReady.await(500, TimeUnit.MILLISECONDS))
        assertNull(manager.getState("M02"))
    }

    @Test
    fun conferenceRelease_watchdogDoesNotBlockCoordinatorTurn() {
        val manager = newManager()
        val coordinatorTurn = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, _, _, _ -> }
        )
        manager.admitConferenceRelease("M02", sessionId = "302fb48d", origin = "releaseSessionMedia")
        coordinator.execute { coordinatorTurn.countDown() }
        assertTrue(coordinatorTurn.await(500, TimeUnit.MILLISECONDS))
        val otherWork = CountDownLatch(1)
        coordinator.execute { otherWork.countDown() }
        assertTrue(otherWork.await(500, TimeUnit.MILLISECONDS))
        fireWatchdogOnCoordinator()
        assertTrue(coordinatorTurn.await(500, TimeUnit.MILLISECONDS))
    }

    @org.junit.After
    fun tearDown() {
        coordinator.shutdownNow()
    }
}
