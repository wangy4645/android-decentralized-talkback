package com.talkback.core.session.gbc

import com.talkback.core.media.MediaSessionManager
import com.talkback.core.media.MeshMediaCoordinatorDeferral
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupInviteDeliveryRecoverySupport
import com.talkback.core.session.GroupInviteExecutionSupport
import com.talkback.core.session.GroupInvitePayloadSemantic
import com.talkback.core.session.OutboundGroupInviteAttemptSupport
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0057 GIDR-EG1..EG6 — delivery obligation vs bounded stale retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Adr0057GroupInviteDeliveryRecoveryFixturesTest {

    private lateinit var factory: ModuleMediaEngineFactory
    private lateinit var coordinatorExecutor: java.util.concurrent.ExecutorService
    private val pendingProvisionKeys = ConcurrentHashMap.newKeySet<String>()
    private val wireSendCount = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        factory = ModuleMediaEngineFactory(context, useStub = true, onIceConnectionState = null)
        coordinatorExecutor = Executors.newSingleThreadExecutor { Thread(it, "gidr-coordinator") }
        pendingProvisionKeys.clear()
        wireSendCount.set(0)
    }

    @After
    fun tearDown() {
        coordinatorExecutor.shutdownNow()
    }

    private fun session(): TalkbackSession =
        TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            channelId = "CH-01",
        ).apply {
            pendingInviteeEndpoints["M03"] =
                EndpointAddress(ModuleId("M03"), EndpointId("E03"))
        }

    private fun newManager(): MediaSessionManager =
        MediaSessionManager(factory = factory).apply {
            installMeshMediaCoordinatorDeferral(
                MeshMediaCoordinatorDeferral { block -> coordinatorExecutor.execute { block() } },
            )
        }

    private fun recordHandoff(session: TalkbackSession, issuedAtMs: Long = 1000L) {
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = "M03",
            sessionId = session.id,
            semantic = GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE,
            offerLineageId = "GM1",
            deliveryAttemptId = 1L,
            issuedAtMs = issuedAtMs,
        )
    }

    private fun reconcileWouldDispatchWire(session: TalkbackSession, nowMs: Long): Boolean {
        if (!GroupInviteDeliveryRecoverySupport.isDeliveryRetryEligible(session, "M03", nowMs)) {
            return false
        }
        if (
            GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(session, "M03", nowMs) !=
            GroupInviteDeliveryRecoverySupport.ReconciliationGate.PermitRetry
        ) {
            return false
        }
        return !OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03")
    }

    private fun gieWireOnce(
        manager: MediaSessionManager,
        sessionId: String,
        moduleId: String,
        wireInFlight: Boolean,
    ): Boolean {
        val provisionKey = GroupInviteExecutionSupport.provisionKey(sessionId, moduleId).storageKey()
        when (
            GroupInviteExecutionSupport.evaluateProvisionAttempt(
                wireInFlight = wireInFlight,
                engineProvisionInFlight = provisionKey in pendingProvisionKeys,
            )
        ) {
            GroupInviteExecutionSupport.ProvisionGate.Proceed -> Unit
            else -> return false
        }
        pendingProvisionKeys.add(provisionKey)
        val done = CountDownLatch(1)
        manager.requestEngine(
            moduleId,
            MediaBearerScope.GROUP,
            sessionId = sessionId,
            onReady = {
                coordinatorExecutor.execute {
                    pendingProvisionKeys.remove(provisionKey)
                    wireSendCount.incrementAndGet()
                    done.countDown()
                }
            },
            onFailed = {
                coordinatorExecutor.execute {
                    pendingProvisionKeys.remove(provisionKey)
                    done.countDown()
                }
            },
        )
        assertTrue(done.await(2, TimeUnit.SECONDS))
        return true
    }

    @Test
    fun gidrEg1_handoffAndGroupAccept_satisfiedNoRetry() {
        val session = session()
        recordHandoff(session)
        assertTrue(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
        OutboundGroupInviteAttemptSupport.markDeliverySatisfiedFromGroupAccept(session, "M03")
        assertFalse(reconcileWouldDispatchWire(session, 2000L))
        assertEquals(0, wireSendCount.get())
    }

    @Test
    fun gidrEg2_handoffWithoutAccept_staleLeavesObligationOpen() {
        val session = session()
        recordHandoff(session, issuedAtMs = 1000L)
        val beforeStale = 5000L
        assertFalse(OutboundGroupInviteAttemptSupport.isDeliverySatisfied(session, "M03"))
        assertFalse(reconcileWouldDispatchWire(session, beforeStale))
        val staleAt = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS + 1L
        assertFalse(OutboundGroupInviteAttemptSupport.isDeliverySatisfied(session, "M03"))
        assertTrue(reconcileWouldDispatchWire(session, staleAt))
    }

    @Test
    fun gidrEg3_staleReconcile_grantsExactlyOneWireOpportunity() {
        val session = session()
        val manager = newManager()
        recordHandoff(session, issuedAtMs = 1000L)
        val staleAt = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS + 1L
        assertTrue(reconcileWouldDispatchWire(session, staleAt))
        assertTrue(gieWireOnce(manager, session.id, "M03", wireInFlight = false))
        recordHandoff(session, issuedAtMs = staleAt + 1L)
        assertEquals(1, wireSendCount.get())
        assertTrue(OutboundGroupInviteAttemptSupport.isRemoteSignalingInFlight(session, "M03"))
        assertFalse(reconcileWouldDispatchWire(session, staleAt + 100L))
        assertEquals(1, wireSendCount.get())
    }

    @Test
    fun gidrEg4_secondAcceptClosed_noFurtherWireOnReconcile() {
        val session = session()
        val manager = newManager()
        recordHandoff(session, issuedAtMs = 1000L)
        val staleAt = 1000L + OutboundGroupInviteAttemptSupport.ATTEMPT_STALE_TIMEOUT_MS + 1L
        assertTrue(gieWireOnce(manager, session.id, "M03", wireInFlight = false))
        recordHandoff(session, issuedAtMs = staleAt)
        OutboundGroupInviteAttemptSupport.markDeliverySatisfiedFromGroupAccept(session, "M03")
        repeat(100) {
            assertFalse(reconcileWouldDispatchWire(session, staleAt + it))
        }
        assertEquals(1, wireSendCount.get())
    }

    @Test
    fun gidrEg5_activeWindow_highFrequencyReconcile_zeroAdditionalWire() {
        val session = session()
        recordHandoff(session, issuedAtMs = 10_000L)
        repeat(150) { i ->
            assertFalse(reconcileWouldDispatchWire(session, 10_000L + i))
        }
        assertEquals(0, wireSendCount.get())
    }

    @Test
    fun gidrEg6_gieProvisionGate_unchangedByGidr() {
        val gate =
            GroupInviteExecutionSupport.evaluateProvisionAttempt(
                wireInFlight = true,
                engineProvisionInFlight = false,
            )
        assertEquals(GroupInviteExecutionSupport.ProvisionGate.SkipWireInFlight, gate)
        assertTrue(
            GroupInviteExecutionSupport.countsAsDispatched(
                GroupInviteExecutionSupport.DispatchOutcome.EngineDeferred,
            ),
        )
    }
}