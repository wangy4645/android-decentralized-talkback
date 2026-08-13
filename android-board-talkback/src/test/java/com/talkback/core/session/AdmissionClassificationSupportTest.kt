package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class AdmissionClassificationSupportTest {

    private fun classify(
        activeSession: Boolean,
        canonical: Boolean,
        requiredEdge: Boolean,
        edgeSatisfied: Boolean,
        coldRequired: Boolean
    ): AdmissionClassificationSupport.AdmissionClassificationResult =
        AdmissionClassificationSupport.classify(
            AdmissionClassificationSupport.AdmissionClassificationContext(
                hasActiveGroupSession = activeSession,
                peerInCanonicalRoster = canonical,
                requiredEdgeExists = requiredEdge,
                edgeSignalingSatisfied = edgeSatisfied,
                coldAdmissionRequired = coldRequired
            )
        )

    @Test
    fun coldStart_selectsBootstrap() {
        val result = classify(
            activeSession = false,
            canonical = false,
            requiredEdge = false,
            edgeSatisfied = false,
            coldRequired = true
        )
        assertEquals(GroupAdmissionDomain.BOOTSTRAP, result.domain)
        assertEquals("COLD_ADMISSION_REQUIRED", result.reason)
    }

    @Test
    fun existingSessionNewPeer_notCanonical_selectsNone() {
        val result = classify(
            activeSession = true,
            canonical = false,
            requiredEdge = false,
            edgeSatisfied = false,
            coldRequired = false
        )
        assertEquals(GroupAdmissionDomain.NONE, result.domain)
        assertEquals("NO_ADMISSION_DOMAIN", result.reason)
    }

    @Test
    fun snapshotLatePeer_unsatisfiedEdge_selectsPairwiseMesh() {
        val result = classify(
            activeSession = true,
            canonical = true,
            requiredEdge = true,
            edgeSatisfied = false,
            coldRequired = false
        )
        assertEquals(GroupAdmissionDomain.PAIRWISE_MESH, result.domain)
        assertEquals("CANONICAL_PEER_UNSATISFIED_EDGE", result.reason)
    }

    @Test
    fun alreadyConnected_selectsNone() {
        val result = classify(
            activeSession = true,
            canonical = true,
            requiredEdge = true,
            edgeSatisfied = true,
            coldRequired = false
        )
        assertEquals(GroupAdmissionDomain.NONE, result.domain)
    }

    @Test
    fun formerMemberRejoin_notCanonical_doesNotSelectPairwiseMesh() {
        val result = classify(
            activeSession = true,
            canonical = false,
            requiredEdge = true,
            edgeSatisfied = false,
            coldRequired = false
        )
        assertEquals(GroupAdmissionDomain.NONE, result.domain)
    }

    @Test
    fun coldAdmissionRequired_takesPrecedenceOverPairwiseFacts() {
        val result = classify(
            activeSession = true,
            canonical = true,
            requiredEdge = true,
            edgeSatisfied = false,
            coldRequired = true
        )
        assertEquals(GroupAdmissionDomain.BOOTSTRAP, result.domain)
    }

    @Test
    fun canonicalPeer_noRequiredEdge_selectsNone() {
        val result = classify(
            activeSession = true,
            canonical = true,
            requiredEdge = false,
            edgeSatisfied = false,
            coldRequired = false
        )
        assertEquals(GroupAdmissionDomain.NONE, result.domain)
    }

    @Test
    fun canonicalPeer_unsatisfiedEdge_contextSelectsPairwiseMesh() {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val session = TalkbackSession("grp:CH-01", SessionType.GROUP, local, "CH-01")
        session.accepted = true
        GroupMembershipSupport.applyGroupMembersList(
            session,
            listOf(
                local,
                EndpointAddress(ModuleId("M02"), EndpointId("E02")),
                EndpointAddress(ModuleId("M03"), EndpointId("E03"))
            )
        )
        session.meshCompletedModules.add("M02")
        val result = AdmissionClassificationSupport.classify(
            AdmissionClassificationSupport.contextForPeer(
                session = session,
                peerModuleId = ModuleId("M03"),
                coldAdmissionRequired = false
            )
        )
        assertEquals(GroupAdmissionDomain.PAIRWISE_MESH, result.domain)
    }
}
