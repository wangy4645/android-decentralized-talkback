package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #180-A — pairwise obligation model and required-edge calculation. */
class PairwiseMeshAdmissionSupportTest {

    private fun endpoint(moduleId: String) =
        EndpointAddress(ModuleId(moduleId), EndpointId("E01"))

    private fun groupSession(
        localId: String,
        memberIds: List<String>,
        accepted: Boolean = true
    ): TalkbackSession {
        val session = TalkbackSession(
            id = "grp:CH-01",
            type = SessionType.GROUP,
            local = endpoint(localId),
            channelId = "CH-01"
        )
        session.accepted = accepted
        GroupMembershipSupport.applyGroupMembersList(
            session,
            memberIds.map { endpoint(it) }
        )
        return session
    }

    private fun members(vararg ids: String): Set<ModuleId> =
        ids.map { ModuleId(it) }.toSet()

    @Test
    fun requiredPairwiseEdges_meshThreeMembers_returnsThreeUnorderedPairs() {
        val canonical = members("M01", "M02", "M03")
        val edges = PairwiseMeshAdmissionSupport.requiredPairwiseEdges(
            topology = GroupMediaTopology.MESH,
            anchorModuleId = null,
            canonicalMembers = canonical
        )
        assertEquals(3, edges.size)
        assertTrue(edges.contains(PairwiseMeshEdgeKey("M01", "M02")))
        assertTrue(edges.contains(PairwiseMeshEdgeKey("M01", "M03")))
        assertTrue(edges.contains(PairwiseMeshEdgeKey("M02", "M03")))
    }

    @Test
    fun perfectNegotiationRoles_m01M03_offererIsLexicographicallySmaller() {
        val edge = PairwiseMeshEdgeKey.of(ModuleId("M01"), ModuleId("M03"))
        val (offerer, answerer) = PairwiseMeshAdmissionSupport.perfectNegotiationRoles(edge)
        assertEquals("M01", offerer.value)
        assertEquals("M03", answerer.value)
    }

    @Test
    fun reconcile_m03Session_tracksOnlyLocalInvolvedEdges() {
        val session = groupSession(localId = "M03", memberIds = listOf("M01", "M02", "M03"))
        val counts = PairwiseMeshAdmissionSupport.reconcile(session)
        assertEquals(2, counts.requiredEdges)
        assertEquals(0, counts.signalingSatisfiedEdges)
        assertNull(PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M01", "M02")))
        val m01m03 = PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M01", "M03"))!!
        assertEquals(ModuleId("M01"), m01m03.offerer)
        assertEquals(ModuleId("M03"), m01m03.answerer)
        assertFalse(m01m03.signalingSatisfied)
        val m02m03 = PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M02", "M03"))!!
        assertEquals(ModuleId("M02"), m02m03.offerer)
        assertEquals(ModuleId("M03"), m02m03.answerer)
        assertFalse(m02m03.signalingSatisfied)
    }

    @Test
    fun reconcile_m01Session_includesM01M03WithCorrectRoles() {
        val session = groupSession(localId = "M01", memberIds = listOf("M01", "M02", "M03"))
        PairwiseMeshAdmissionSupport.reconcile(session)
        val obligation = PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M01", "M03"))!!
        assertEquals(ModuleId("M01"), obligation.offerer)
        assertEquals(ModuleId("M03"), obligation.answerer)
        assertFalse(obligation.signalingSatisfied)
        assertTrue(PairwiseMeshAdmissionSupport.hasUnsatisfiedObligation(session))
    }

    @Test
    fun reconcile_notAccepted_clearsObligations() {
        val session = groupSession(
            localId = "M03",
            memberIds = listOf("M01", "M02", "M03"),
            accepted = false
        )
        val counts = PairwiseMeshAdmissionSupport.reconcile(session)
        assertEquals(0, counts.requiredEdges)
        assertTrue(session.pairwiseMeshAdmissionObligations.isEmpty())
    }

    @Test
    fun reconcile_signalingSatisfied_whenPeerInMeshCompletedModules() {
        val session = groupSession(localId = "M03", memberIds = listOf("M01", "M02", "M03"))
        session.meshCompletedModules.add("M01")
        val counts = PairwiseMeshAdmissionSupport.reconcile(session)
        val m01m03 = PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M01", "M03"))!!
        assertTrue(m01m03.signalingSatisfied)
        assertFalse(
            PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M02", "M03"))!!
                .signalingSatisfied
        )
        assertEquals(1, counts.signalingSatisfiedEdges)
        assertTrue(PairwiseMeshAdmissionSupport.hasUnsatisfiedObligation(session))
    }

    @Test
    fun reconcile_memberRemoved_dropsStaleObligation() {
        val session = groupSession(localId = "M03", memberIds = listOf("M01", "M02", "M03"))
        GroupMembershipSupport.applyGroupMembersList(
            session,
            listOf("M01", "M03").map { endpoint(it) }
        )
        assertEquals(1, session.pairwiseMeshAdmissionObligations.size)
        assertNull(PairwiseMeshAdmissionSupport.obligation(session, PairwiseMeshEdgeKey("M02", "M03")))
    }

    @Test
    fun requiredPairwiseEdges_anchorTopology_returnsStarFromAnchor() {
        val canonical = members("M01", "M02", "M03")
        val edges = PairwiseMeshAdmissionSupport.requiredPairwiseEdges(
            topology = GroupMediaTopology.ANCHOR,
            anchorModuleId = ModuleId("M01"),
            canonicalMembers = canonical
        )
        assertEquals(2, edges.size)
        assertTrue(edges.contains(PairwiseMeshEdgeKey("M01", "M02")))
        assertTrue(edges.contains(PairwiseMeshEdgeKey("M01", "M03")))
        assertFalse(edges.contains(PairwiseMeshEdgeKey("M02", "M03")))
    }

    @Test
    fun reconcile_sameCanonicalRoster_preservesStableEdgeKeys() {
        val session = groupSession(localId = "M03", memberIds = listOf("M01", "M02", "M03"))
        PairwiseMeshAdmissionSupport.reconcile(session)
        val keysBefore = session.pairwiseMeshAdmissionObligations.keys.toSet()
        val m01m03Before = session.pairwiseMeshAdmissionObligations["M01|M03"]

        GroupMembershipSupport.applyGroupMembersList(
            session,
            listOf("M01", "M02", "M03").map { endpoint(it) }
        )

        assertEquals(keysBefore, session.pairwiseMeshAdmissionObligations.keys.toSet())
        val m01m03After = session.pairwiseMeshAdmissionObligations["M01|M03"]
        assertEquals(m01m03Before?.offerer, m01m03After?.offerer)
        assertEquals(m01m03Before?.answerer, m01m03After?.answerer)
        assertEquals(m01m03Before?.edge, m01m03After?.edge)
    }

    @Test
    fun shouldDeferMeshCompletionAfterSnapshot_trueWhenSignalingUnsatisfied() {
        val session = groupSession(localId = "M03", memberIds = listOf("M01", "M02", "M03"))
        assertTrue(
            PairwiseMeshAdmissionSupport.shouldDeferMeshCompletionAfterSnapshot(
                session,
                ModuleId("M01")
            )
        )
    }

    @Test
    fun shouldDeferMeshCompletionAfterSnapshot_falseWhenSignalingSatisfied() {
        val session = groupSession(localId = "M03", memberIds = listOf("M01", "M02", "M03"))
        session.meshCompletedModules.add("M01")
        PairwiseMeshAdmissionSupport.reconcile(session)
        assertFalse(
            PairwiseMeshAdmissionSupport.shouldDeferMeshCompletionAfterSnapshot(
                session,
                ModuleId("M01")
            )
        )
    }
}
