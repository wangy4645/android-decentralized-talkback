package com.talkback.core.session.gbc

import com.talkback.core.media.MediaSessionManager
import com.talkback.core.media.MeshMediaAsyncRelease
import com.talkback.core.media.MeshMediaCoordinatorDeferral
import com.talkback.core.session.GroupInviteInboundExecutionSupport
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.ModuleMediaEngineFactory
import org.junit.After
import org.junit.Assert.assertEquals
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
 * ADR-0057 IGIE-EG1..EG5 — inbound accept lifecycle (engine provision queue, not accept burst).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Adr0057GroupInviteInboundExecutionFixturesTest {

    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var coordinatorExecutor: java.util.concurrent.ExecutorService
    private val pendingProvisionKeys = ConcurrentHashMap.newKeySet<String>()
    private val acceptSendCount = AtomicInteger(0)
    private val deferredLogCount = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        factory = ModuleMediaEngineFactory(context, useStub = true, onIceConnectionState = null)
        coordinatorExecutor = Executors.newSingleThreadExecutor { Thread(it, "igie-coordinator") }
        pendingProvisionKeys.clear()
        acceptSendCount.set(0)
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

    /** Mirrors coordinator IGIE accept gate + requestGroupEngine (without TalkbackCoordinator). */
    private fun igieDispatchAccept(
        manager: MediaSessionManager,
        sessionId: String,
        moduleId: String,
        acceptComplete: Boolean,
    ): GroupInviteInboundExecutionSupport.DispatchOutcome {
        val provisionKey =
            GroupInviteInboundExecutionSupport.provisionKey(sessionId, moduleId).storageKey()
        when (
            GroupInviteInboundExecutionSupport.evaluateAcceptAttempt(
                acceptComplete = acceptComplete,
                engineProvisionInFlight = provisionKey in pendingProvisionKeys,
            )
        ) {
            GroupInviteInboundExecutionSupport.AcceptGate.SkipAcceptComplete,
            GroupInviteInboundExecutionSupport.AcceptGate.SkipEngineProvisionInFlight,
            -> return GroupInviteInboundExecutionSupport.DispatchOutcome.SkippedDuplicate
            GroupInviteInboundExecutionSupport.AcceptGate.Proceed -> Unit
        }
        if (moduleId in manager.releaseInFlightModules()) {
            deferredLogCount.incrementAndGet()
        }
        pendingProvisionKeys.add(provisionKey)
        val provisionImmediate = AtomicBoolean(false)
        val acceptCompleted = CountDownLatch(1)
        manager.requestEngine(
            moduleId,
            MediaBearerScope.GROUP,
            sessionId = sessionId,
            onReady = {
                provisionImmediate.set(true)
                coordinatorExecutor.execute {
                    pendingProvisionKeys.remove(provisionKey)
                    acceptSendCount.incrementAndGet()
                    acceptCompleted.countDown()
                }
            },
            onFailed = {
                coordinatorExecutor.execute {
                    pendingProvisionKeys.remove(provisionKey)
                    acceptCompleted.countDown()
                }
            },
        )
        return if (provisionImmediate.get()) {
            assertTrue(acceptCompleted.await(2, TimeUnit.SECONDS))
            GroupInviteInboundExecutionSupport.DispatchOutcome.AcceptSent
        } else {
            GroupInviteInboundExecutionSupport.DispatchOutcome.EngineDeferred
        }
    }

    private fun waitForAcceptCount(expected: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (acceptSendCount.get() >= expected) return true
            Thread.sleep(20L)
        }
        return acceptSendCount.get() >= expected
    }

    @Test
    fun igieEg1_engineAvailable_emitsAcceptOnce() {
        val manager = newManager()
        val outcome = igieDispatchAccept(manager, "grp:CH-01", "M01", acceptComplete = false)
        assertEquals(GroupInviteInboundExecutionSupport.DispatchOutcome.AcceptSent, outcome)
        assertEquals(1, acceptSendCount.get())
        assertTrue(pendingProvisionKeys.isEmpty())
    }

    @Test
    fun igieEg2_releasePending_defersWithoutAccept() {
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
                    Thread {
                        releaseAction()
                        coordinatorExecutor.execute { onReleased(true) }
                    }.apply { isDaemon = true }.start()
                }
            },
        )
        val provisioned = CountDownLatch(1)
        coordinatorExecutor.execute {
            manager.requestEngine("M01", MediaBearerScope.GROUP, "setup", onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinatorExecutor.execute { manager.close("M01", "setup") }
        assertTrue(releaseStarted.await(2, TimeUnit.SECONDS))

        val outcome = igieDispatchAccept(manager, "grp:CH-01", "M01", acceptComplete = false)
        assertEquals(GroupInviteInboundExecutionSupport.DispatchOutcome.EngineDeferred, outcome)
        assertEquals(0, acceptSendCount.get())
        assertEquals(1, deferredLogCount.get())
        assertTrue("M01" in manager.releaseInFlightModules())
    }

    @Test
    fun igieEg3_releaseComplete_grantsExecutionOpportunityExactlyOnce() {
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
            manager.requestEngine("M01", MediaBearerScope.GROUP, "setup", onReady = { provisioned.countDown() })
        }
        assertTrue(provisioned.await(2, TimeUnit.SECONDS))
        coordinatorExecutor.execute { manager.close("M01", "setup") }
        Thread.sleep(50)

        igieDispatchAccept(manager, "grp:CH-EG3", "M01", acceptComplete = false)
        assertEquals(0, acceptSendCount.get())

        releaseHold.countDown()
        assertTrue(waitForAcceptCount(1, 3_000))
        assertEquals(1, acceptSendCount.get())
    }

    @Test
    fun igieEg4_repeatedInvite_suppressesDuplicateProvision() {
        val manager = newManager()
        val first = igieDispatchAccept(manager, "grp:CH-04", "M01", acceptComplete = false)
        assertEquals(GroupInviteInboundExecutionSupport.DispatchOutcome.AcceptSent, first)
        pendingProvisionKeys.add("grp:CH-04|M01")
        val second = igieDispatchAccept(manager, "grp:CH-04", "M01", acceptComplete = false)
        assertEquals(GroupInviteInboundExecutionSupport.DispatchOutcome.SkippedDuplicate, second)
        assertEquals(1, acceptSendCount.get())
    }

    @Test
    fun igieEg5_acceptComplete_suppressesSecondAccept() {
        val manager = newManager()
        val first = igieDispatchAccept(manager, "grp:CH-05", "M01", acceptComplete = false)
        assertEquals(GroupInviteInboundExecutionSupport.DispatchOutcome.AcceptSent, first)
        val second = igieDispatchAccept(manager, "grp:CH-05", "M01", acceptComplete = true)
        assertEquals(GroupInviteInboundExecutionSupport.DispatchOutcome.SkippedDuplicate, second)
        assertEquals(1, acceptSendCount.get())
    }
}
