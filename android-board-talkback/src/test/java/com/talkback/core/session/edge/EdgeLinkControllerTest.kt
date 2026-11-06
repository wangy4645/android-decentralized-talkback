package com.talkback.core.session.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLinkControllerTest {
    private var now = 1_000_000L
    private val transitions = mutableListOf<EdgeTransition>()
    private val config = EdgeLinkConfig()
    private val controller = EdgeLinkController(config, { now }, { transitions += it })
    private val m04 = EdgeKey("ch1", "M04")

    private fun advance(ms: Long): List<EdgeAction> {
        now += ms
        return controller.tick(now)
    }

    private fun offererEstablish(key: EdgeKey = m04, gen: Long = 1L): List<EdgeAction> {
        val actions = controller.onFact(key, EdgeFact.Desired(EdgeRole.OFFERER))
        controller.onFact(key, EdgeFact.OfferSent(gen, "L1", iceRestart = false))
        return actions
    }

    // ---- Phase 2a mode gating ----

    @Test
    fun activeOffererMode_executesOnlyOffererSideActions() {
        val send = EdgeAction.SendOffer(m04, 1L, iceRestart = false, retransmitOfLineage = null)
        val rebuild = EdgeAction.RebuildPeerConnection(m04, 1L)
        val reoffer = EdgeAction.RequestReoffer(m04, 1L)
        val failed = EdgeAction.MarkFailed(m04, "X", 0L)
        assertTrue(EdgeLinkMode.ACTIVE_OFFERER.mayExecute(send))
        assertTrue(EdgeLinkMode.ACTIVE_OFFERER.mayExecute(rebuild))
        assertTrue(EdgeLinkMode.ACTIVE_OFFERER.mayExecute(reoffer))
        assertTrue(EdgeLinkMode.ACTIVE_OFFERER.mayExecute(failed))
        listOf(send, rebuild, reoffer, failed).forEach { assertFalse(EdgeLinkMode.SHADOW.mayExecute(it)) }
    }

    // ---- 11-06 M04 kill test: peer gone silent must park, and HELLO must end the backoff early ----

    @Test
    fun unreachablePeer_parksEdgeWithoutBurningBudget_thenResumesOnHello() {
        offererEstablish()
        // Peer dies while we wait for the answer.
        val parked = controller.onFact(m04, EdgeFact.PeerReachability(reachable = false))
        assertTrue(parked.isEmpty())
        assertEquals(EdgeLinkState.IDLE, controller.state(m04))
        // 60 s of ticks: no retransmit, no rebuild, no FAILED.
        repeat(60) { assertTrue(advance(1_000L).isEmpty()) }
        assertEquals(EdgeLinkState.IDLE, controller.state(m04))
        assertTrue(transitions.none { it.to == EdgeLinkState.FAILED || it.to == EdgeLinkState.REBUILDING })
        // HELLO arrives: fresh offer immediately, not after a backoff.
        val resumed = controller.onFact(m04, EdgeFact.PeerReachability(reachable = true))
        assertEquals(1, resumed.filterIsInstance<EdgeAction.SendOffer>().size)
        assertEquals(EdgeLinkState.OFFER_SENT, controller.state(m04))
    }

    @Test
    fun reachabilityBeforeDesired_isHonouredByFirstSlotGrant() {
        controller.onFact(m04, EdgeFact.PeerReachability(reachable = false))
        val actions = controller.onFact(m04, EdgeFact.Desired(EdgeRole.OFFERER))
        assertTrue(actions.isEmpty())
        assertEquals(EdgeLinkState.IDLE, controller.state(m04))
        val resumed = controller.onFact(m04, EdgeFact.PeerReachability(reachable = true))
        assertEquals(1, resumed.filterIsInstance<EdgeAction.SendOffer>().size)
    }

    @Test
    fun connectedEdge_ignoresTransientUnreachable() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        controller.onFact(m04, EdgeFact.PeerReachability(reachable = false))
        assertEquals(EdgeLinkState.CONNECTED, controller.state(m04))
    }

    // ---- 11-06 shape: offer handed off, answer never comes, old gates kept SIF/GIDR ACTIVE for 10 s ----

    @Test
    fun offerer_answerLost_retransmitsThenRestartsThenRebuildsThenFails() {
        offererEstablish()
        assertEquals(EdgeLinkState.OFFER_SENT, controller.state(m04))

        val retx1 = advance(config.answerTimeoutMs)
        assertEquals(listOf(EdgeAction.SendOffer(m04, 1L, iceRestart = false, retransmitOfLineage = "L1")), retx1)
        val retx2 = advance(config.answerTimeoutMs)
        assertEquals("L1", (retx2.single() as EdgeAction.SendOffer).retransmitOfLineage)

        val restart = advance(config.answerTimeoutMs)
        assertEquals(EdgeAction.SendOffer(m04, 1L, iceRestart = true, retransmitOfLineage = null), restart.single())
        assertEquals(EdgeLinkState.RESTARTING, controller.state(m04))

        val rebuild = advance(config.iceCheckingTimeoutMs)
        assertEquals(EdgeAction.RebuildPeerConnection(m04, 1L), rebuild.single())
        assertEquals(EdgeLinkState.REBUILDING, controller.state(m04))

        // Rebuild never reports back → counts against the rebuild budget, then FAILED with backoff.
        repeat(config.rebuildMax - 1) { advance(config.rebuildTimeoutMs) }
        val failed = advance(config.rebuildTimeoutMs)
        val mark = failed.single() as EdgeAction.MarkFailed
        assertEquals(EdgeLinkState.FAILED, controller.state(m04))
        assertEquals(now + config.backoffInitialMs, mark.retryAtMs)
    }

    @Test
    fun offerer_answerArrives_cancelsRetransmitAndMovesToIceChecking() {
        offererEstablish()
        advance(config.answerTimeoutMs) // one retransmit
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        assertEquals(EdgeLinkState.ICE_CHECKING, controller.state(m04))
        assertTrue(advance(config.answerTimeoutMs).isEmpty())
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        assertEquals(EdgeLinkState.CONNECTED, controller.state(m04))
        assertTrue(advance(60_000L).isEmpty())
    }

    // ---- 11-05 shape: ICE stuck CHECKING, restart illegal (STABLE+remote offer) → must not spin, must rebuild ----

    @Test
    fun iceCheckingForever_restartIllegal_skipsRestartAndRebuilds() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CHECKING"))
        controller.onFact(m04, EdgeFact.RestartCapability(1L, legal = false))

        val actions = advance(config.iceCheckingTimeoutMs)
        assertEquals(EdgeAction.RebuildPeerConnection(m04, 1L), actions.single())
        assertFalse(actions.any { it is EdgeAction.SendOffer })
    }

    @Test
    fun rebuildCompleted_adoptsNewGenerationAndReoffersImmediately() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.RestartCapability(1L, legal = false))
        advance(config.iceCheckingTimeoutMs)
        assertEquals(EdgeLinkState.REBUILDING, controller.state(m04))

        val actions = controller.onFact(m04, EdgeFact.RebuildCompleted(2L))
        assertEquals(EdgeAction.SendOffer(m04, 2L, iceRestart = false, retransmitOfLineage = null), actions.single())
        assertEquals(2L, controller.generation(m04))
        assertEquals(EdgeLinkState.OFFER_SENT, controller.state(m04))
    }

    @Test
    fun staleGenerationFacts_areIgnored() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.RestartCapability(1L, legal = false))
        advance(config.iceCheckingTimeoutMs)
        controller.onFact(m04, EdgeFact.RebuildCompleted(2L))

        // Late CONNECTED from the torn-down PC must not flip the fresh edge to CONNECTED.
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        assertEquals(EdgeLinkState.OFFER_SENT, controller.state(m04))
        controller.onFact(m04, EdgeFact.IceState(1L, "FAILED"))
        assertEquals(EdgeLinkState.OFFER_SENT, controller.state(m04))
    }

    // ---- answerer side: the old code had no exit at all (TOPOLOGY_MESH_NOT_OFFERER) ----

    @Test
    fun answerer_noOffer_requestsReofferBoundedThenRebuilds() {
        controller.onFact(m04, EdgeFact.Desired(EdgeRole.ANSWERER))
        assertEquals(EdgeLinkState.AWAITING_OFFER, controller.state(m04))
        repeat(config.reofferRequestMax) {
            val actions = advance(config.offerTimeoutMs)
            assertTrue(actions.single() is EdgeAction.RequestReoffer)
        }
        val actions = advance(config.offerTimeoutMs)
        assertTrue(actions.single() is EdgeAction.RebuildPeerConnection)
    }

    @Test
    fun answerer_offerApplied_thenIceStuck_requestsReoffer() {
        controller.onFact(m04, EdgeFact.Desired(EdgeRole.ANSWERER))
        controller.onFact(m04, EdgeFact.RemoteOfferApplied(1L))
        controller.onFact(m04, EdgeFact.AnswerSent(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CHECKING"))
        assertEquals(EdgeLinkState.ICE_CHECKING, controller.state(m04))
        val actions = advance(config.iceCheckingTimeoutMs)
        assertEquals(EdgeAction.RequestReoffer(m04, 1L), actions.single())
        assertEquals(EdgeLinkState.RESTARTING, controller.state(m04))
    }

    @Test
    fun offerer_receivesReofferRequest_whileOfferSent_retransmitsSameLineage() {
        offererEstablish()
        val actions = controller.onFact(m04, EdgeFact.ReofferRequested(1L))
        assertEquals(
            EdgeAction.SendOffer(m04, 1L, iceRestart = false, retransmitOfLineage = "L1"),
            actions.single(),
        )
        assertEquals(EdgeLinkState.OFFER_SENT, controller.state(m04))
    }

    @Test
    fun offerer_receivesReofferRequest_whileChecking_issuesIceRestartOffer() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CHECKING"))
        val actions = controller.onFact(m04, EdgeFact.ReofferRequested(1L))
        assertEquals(EdgeAction.SendOffer(m04, 1L, iceRestart = true, retransmitOfLineage = null), actions.single())
        assertEquals(EdgeLinkState.RESTARTING, controller.state(m04))
    }

    @Test
    fun offerer_receivesReofferRequest_whileConnected_isIgnored() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        val actions = controller.onFact(m04, EdgeFact.ReofferRequested(1L))
        assertTrue(actions.isEmpty())
        assertEquals(EdgeLinkState.CONNECTED, controller.state(m04))
    }

    @Test
    fun offerer_receivesReofferRequest_whileRestarting_isIgnored() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CHECKING"))
        controller.onFact(m04, EdgeFact.ReofferRequested(1L))
        val dup = controller.onFact(m04, EdgeFact.ReofferRequested(1L))
        assertTrue(dup.isEmpty())
        assertEquals(EdgeLinkState.RESTARTING, controller.state(m04))
    }

    // ---- connected edge must never be disturbed; DISCONNECTED gets a grace window ----

    @Test
    fun connectedEdge_disconnectGrace_thenRestart_thenSelfHealCancels() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        controller.onFact(m04, EdgeFact.IceState(1L, "DISCONNECTED"))
        assertEquals(EdgeLinkState.DEGRADED, controller.state(m04))
        assertTrue(advance(config.degradedGraceMs - 1).isEmpty())
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        assertEquals(EdgeLinkState.CONNECTED, controller.state(m04))
        assertTrue(advance(60_000L).isEmpty())
    }

    @Test
    fun failedEdge_backoffDoubles_andPeerReachabilityShortCircuits() {
        val cfg = EdgeLinkConfig(rebuildMax = 0, restartMax = 0, answerRetransmitMax = 0)
        val c = EdgeLinkController(cfg, { now })
        c.onFact(m04, EdgeFact.Desired(EdgeRole.OFFERER))
        c.onFact(m04, EdgeFact.OfferSent(1L, "L1", iceRestart = false))

        now += cfg.answerTimeoutMs
        val first = c.tick(now).single() as EdgeAction.MarkFailed
        assertEquals(now + cfg.backoffInitialMs, first.retryAtMs)

        now += cfg.backoffInitialMs
        assertTrue(c.tick(now).single() is EdgeAction.SendOffer)
        c.onFact(m04, EdgeFact.OfferSent(1L, "L2", iceRestart = false))
        now += cfg.answerTimeoutMs
        val second = c.tick(now).single() as EdgeAction.MarkFailed
        assertEquals(now + cfg.backoffInitialMs * 2, second.retryAtMs)

        // HELLO from the peer ends backoff early.
        val resumed = c.onFact(m04, EdgeFact.PeerReachability(reachable = true))
        assertTrue(resumed.single() is EdgeAction.SendOffer)
    }

    // ---- 8–10 node mesh: per-node concurrency cap, independent edges, FAILED is visible not contagious ----

    @Test
    fun nineOffererEdges_respectConcurrencyCap_andDrainInOrder() {
        val peers = (10..18).map { EdgeKey("ch1", "M$it") }
        val actions = peers.flatMap { controller.onFact(it, EdgeFact.Desired(EdgeRole.OFFERER)) }
        assertEquals(config.maxConcurrentNegotiations, actions.size)
        assertEquals(peers.take(3), actions.map { it.key })
        assertEquals(6, controller.snapshot().values.count { it == EdgeLinkState.IDLE })

        // First edge connects → exactly one slot frees.
        controller.onFact(peers[0], EdgeFact.OfferSent(1L, "L", iceRestart = false))
        controller.onFact(peers[0], EdgeFact.AnswerApplied(1L))
        controller.onFact(peers[0], EdgeFact.IceState(1L, "CONNECTED"))
        val next = controller.tick(now)
        assertEquals(listOf(peers[3]), next.map { it.key })
    }

    @Test
    fun oneFailedEdge_doesNotBlockOtherEdgesOrSlots() {
        val cfg = EdgeLinkConfig(rebuildMax = 0, restartMax = 0, answerRetransmitMax = 0, maxConcurrentNegotiations = 2)
        val c = EdgeLinkController(cfg, { now })
        val a = EdgeKey("ch1", "M10")
        val b = EdgeKey("ch1", "M11")
        val d = EdgeKey("ch1", "M12")
        c.onFact(a, EdgeFact.Desired(EdgeRole.OFFERER))
        c.onFact(b, EdgeFact.Desired(EdgeRole.OFFERER))
        c.onFact(d, EdgeFact.Desired(EdgeRole.OFFERER))
        c.onFact(a, EdgeFact.OfferSent(1L, "L", iceRestart = false))
        c.onFact(b, EdgeFact.OfferSent(1L, "L", iceRestart = false))
        now += cfg.answerTimeoutMs
        val actions = c.tick(now)
        assertEquals(2, actions.count { it is EdgeAction.MarkFailed })
        // FAILED edges no longer hold slots → the third edge gets one in the same tick.
        assertEquals(listOf(d), actions.filterIsInstance<EdgeAction.SendOffer>().map { it.key })
        assertEquals(EdgeLinkState.FAILED, c.state(a))
        assertEquals(EdgeLinkState.OFFER_SENT, c.state(d))
    }

    @Test
    fun repeatedConnectedIceFacts_doNotEmitTransitions() {
        offererEstablish()
        controller.onFact(m04, EdgeFact.AnswerApplied(1L))
        controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED"))
        val before = transitions.size
        repeat(5) { controller.onFact(m04, EdgeFact.IceState(1L, "CONNECTED")) }
        controller.onFact(m04, EdgeFact.IceState(1L, "COMPLETED"))
        assertEquals(before, transitions.size)
    }

    @Test
    fun undesired_releasesEdge() {
        offererEstablish()
        assertEquals(listOf(EdgeAction.Release(m04)), controller.onFact(m04, EdgeFact.Undesired))
        assertEquals(null, controller.state(m04))
    }

    @Test
    fun transitionLog_isSingleLineWithAllCounters() {
        offererEstablish()
        val line = EdgeLinkController.formatTransition(transitions.last())
        assertTrue(line.startsWith("EDGE_LINK ch=ch1 peer=M04 gen=1 role=OFFERER OFFER_SENT->OFFER_SENT"))
        assertTrue(line.contains("deadline=ANSWER@"))
    }
}
