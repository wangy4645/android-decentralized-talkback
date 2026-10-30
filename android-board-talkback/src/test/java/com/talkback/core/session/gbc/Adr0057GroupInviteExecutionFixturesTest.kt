package com.talkback.core.session.gbc

import com.talkback.core.media.MediaSessionManager
import com.talkback.core.media.MeshMediaAsyncRelease
import com.talkback.core.media.MeshMediaCoordinatorDeferral
import com.talkback.core.session.GroupInviteExecutionSupport
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0057 GIE-EG1..EG5 — invite execution lifecycle (engine provision queue, not wire burst).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Adr0057GroupInviteExecutionFixturesTest {

    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var coordinatorExecutor: java.util.concurrent.ExecutorService
    private val pendingProvisionKeys = ConcurrentHashMap.newKeySet<String>()
    private val wireSendCount = AtomicInteger(0)
    private val deferredLogCount = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        factory = ModuleMediaEngineFactory(context, useStub = true, onIceConnectionState = null)
        coordinatorExecutor = Executors.newSingleThreadExecutor { Thread(it, "gie-coordinator") }
        pendingProvisionKeys.clear()
        wireSendCount.set(0)
        deferredLogCount.set(0)
    }

    @After
    fun tearDown() {
        coordinatorExecutor.shutdownNow()
    }

    private fun newManager(): MediaSessionManager =
        MediaSessionManager(factory = factory).apply {
            installMeshMediaCoordinatorDeferral(
                MeshMediaCoordinatorDeferral { block -> coordinatorExecutor.execute { block() } },
            )
        }

    private fun installAsyncRelease(manager: MediaSessionManager, holdRelease: CountDownLatch? = null) {
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                Thread {
                    holdRelease?.await(5, TimeUnit.SECONDS)
                    releaseAction()
                    coordinatorExecutor.execute { onReleased(true) }
                }.apply { isDaemon = true }.start()
            },
        )
    }

    /** Mirrors coordinator GIE dispatch gate + requestGroupEngine (without TalkbackCoordinator). */
    private fun gieDispatchInvite(
        manager: MediaSessionManager,
        sessionId: String,
        moduleId: String,
        wireInFlight: Boolean,
    ): GroupInviteExecutionSupport.DispatchOutcome {
        val provisionKey = GroupInviteExecutionSupport.provisionKey(sessionId, moduleId).storageKey()
        when (
            GroupInviteExecutionSupport.evaluateProvisionAttempt(
                wireInFlight = wireInFlight,
                engineProvisionInFlight = provisionKey in pendingProvisionKeys,
            )
        ) {
            GroupInviteExecutionSupport.ProvisionGate.SkipWireInFlight,
            GroupInviteExecutionSupport.ProvisionGate.SkipEngineProvisionInFlight,
            -> return GroupInviteExecutionSupport.DispatchOutcome.SkippedDuplicate
            GroupInviteExecutionSupport.ProvisionGate.Proceed -> Unit
        }
        if (moduleId in manager.releaseInFlightModules()) {
            deferredLogCount.incrementAndGet()
        }
        pendingProvisionKeys.add(provisionKey)
        val provisionImmediate = AtomicBoolean(false)
        val wireCompleted = CountDownLatch(1)
        manager.requestEngine(
            moduleId,
            MediaBearerScope.GROUP,
            sessionId = sessionId,
            onReady = {
                provisionImmediate.set(true)
                coordinatorExecutor.execute {
                    pendingProvisionKeys.remove(provisionKey)
                    wireSendCount.incrementAndGet()
                    wireCompleted.countDown()
                }
            },
            onFailed = {
                coordinatorExecutor.execute {
                    pendingProvisionKeys.remove(provisionKey)
                    wireCompleted.countDown()
                }
            },
        )
        return if (provisionImmediate.get()) {
            assertTrue(wireCompleted.await(2, TimeUnit.SECONDS))
            GroupInviteExecutionSupport.DispatchOutcome.WireSent
        } else {
            GroupInviteExecutionSupport.DispatchOutcome.EngineDeferred
        }
    }

    private fun waitForWireCount(expected: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (wireSendCount.get() >= expected) return true
            Thread.sleep(20L)
        }
        return wireSendCount.get() >= expected
    }

    @Test
    fun gieEg1_engineAvailable_emitsWireOnce() {
        val manager = newManager()
        val outcome = gieDispatchInvite(manager, "grp:CH-01", "M02", wireInFlight = false)
        assertEquals(GroupInviteExecutionSupport.DispatchOutcome.WireSent, outcome)
        assertEquals(1, wireSendCount.get())
        assertTrue(pendingProvisionKeys.isEmpty())
    }

    @Test
    fun gieEg2_releasePending_defersWithoutWire() {
        val manager = newManager()
        val releaseStarted = CountDownLatch(1)
        val releaseHold = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionClose") {
                    releaseStarted.countDown()
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinatorExecutor.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    releaseAction()
                    coordinatorExecutor.execute { onReleased(true) }
                }
            },
        )
        val provisioned = CountDownLatch(1)
        coordinatorExecutor.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, "setup", onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinatorExecutor.execute { manager.close("M02", "setup") }
        assertTrue(releaseStarted.await(2, TimeUnit.SECONDS))

        val outcome = gieDispatchInvite(manager, "grp:CH-01", "M02", wireInFlight = false)
        assertEquals(GroupInviteExecutionSupport.DispatchOutcome.EngineDeferred, outcome)
        assertEquals(0, wireSendCount.get())
        assertEquals(1, deferredLogCount.get())
        assertTrue("M02" in manager.releaseInFlightModules())
    }

    @Test
    fun gieEg3_releaseComplete_grantsExecutionOpportunityExactlyOnce() {
        val manager = newManager()
        val releaseHold = CountDownLatch(1)
        manager.installAsyncMeshMediaRelease(
            MeshMediaAsyncRelease { _, _, origin, releaseAction, onReleased ->
                if (origin == "mediaSessionClose") {
                    Thread {
                        releaseHold.await(5, TimeUnit.SECONDS)
                        releaseAction()
                        coordinatorExecutor.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                } else {
                    Thread {
                        releaseAction()
                        coordinatorExecutor.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            },
        )
        val provisioned = CountDownLatch(1)
        coordinatorExecutor.execute {
            manager.requestEngine("M02", MediaBearerScope.GROUP, "setup", onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinatorExecutor.execute { manager.close("M02", "setup") }
        Thread.sleep(50)

        gieDispatchInvite(manager, "grp:CH-EG3", "M02", wireInFlight = false)
        assertEquals(0, wireSendCount.get())

        releaseHold.countDown()
        assertTrue(waitForWireCount(1, 3_000))
        assertEquals(1, wireSendCount.get())
    }

    @Test
    fun gieEg4_repeatedReconcile_suppressesDuplicateProvision() {
        val manager = newManager()
        val first = gieDispatchInvite(manager, "grp:CH-04", "M02", wireInFlight = false)
        assertEquals(GroupInviteExecutionSupport.DispatchOutcome.WireSent, first)
        pendingProvisionKeys.add("grp:CH-04|M02")
        val second = gieDispatchInvite(manager, "grp:CH-04", "M02", wireInFlight = false)
        assertEquals(GroupInviteExecutionSupport.DispatchOutcome.SkippedDuplicate, second)
        assertEquals(1, wireSendCount.get())
    }

    @Test
    fun gieEg5_wireInFlight_suppressesSecondSend() {
        val manager = newManager()
        val first = gieDispatchInvite(manager, "grp:CH-05", "M02", wireInFlight = false)
        assertEquals(GroupInviteExecutionSupport.DispatchOutcome.WireSent, first)
        val second = gieDispatchInvite(manager, "grp:CH-05", "M02", wireInFlight = true)
        assertEquals(GroupInviteExecutionSupport.DispatchOutcome.SkippedDuplicate, second)
        assertEquals(1, wireSendCount.get())
    }
}
