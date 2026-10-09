package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1b-6 contract fixtures S1–S8 (downstream consistency).
 * Exit: S1–S8 PASS → 1b-6 contract PASS → 1b-6 implementation NOT AUTHORIZED until signed.
 */
class ConferenceTopologyConsistencyPhase1b6FixturesTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val members3 = listOf("M01", "M02", "M03")

    private fun anchorSnapshot(
        host: String = "M01",
        anchor: String = "M01",
        anchorEpoch: Long = 100L,
        meshGeneration: Long = 1L,
        rosterEpoch: Long = 1L,
        members: List<String> = members4
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = host,
            anchorId = anchor,
            members = members,
            rosterEpoch = rosterEpoch,
            anchorEpoch = anchorEpoch,
            meshGeneration = meshGeneration
        )
    )

    private fun meshSnapshot(
        meshGeneration: Long = 2L,
        members: List<String> = members3
    ) = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
        conferenceId = "c1",
        hostModuleId = "M01",
        members = members,
        rosterEpoch = 1L,
        meshGeneration = meshGeneration
    )

    @Test
    fun s1_presenceBind_matchesSnapshotLineage_hostNotAnchor() {
        val snapshot = anchorSnapshot(
            host = "M02",
            anchor = "M01",
            anchorEpoch = 100L,
            meshGeneration = 3L,
            rosterEpoch = 7L
        )
        val bind = ConferenceTopologyConsistencyContract.bindDownstreamView(snapshot)
        assertTrue(bind is ConferenceTopologyConsistencyContract.BindResult.Ok)
        val view = (bind as ConferenceTopologyConsistencyContract.BindResult.Ok).view
        val read = ConferenceTopologyReadBind.presenceReadInput(snapshot)

        assertEquals("M01", view.anchorId)
        assertEquals(100L, view.anchorEpoch)
        assertEquals(3L, view.meshGeneration)
        assertEquals(7L, view.rosterEpoch)
        assertEquals(members4, view.members)

        assertEquals("M01", read.anchorId)
        assertEquals(100L, read.anchorEpoch)
        assertEquals(3L, read.meshGeneration)
        assertEquals("M02", read.hostModuleId)
        assertEquals(members4, read.canonicalRoster)
        assertEquals("M01", read.producerModuleId)
    }

    @Test
    fun s2_uiAndCpp_shareTopologyGeneration() {
        val t1 = anchorSnapshot(meshGeneration = 5L, anchorEpoch = 10L)
        val t2 = t1.copy(
            meshGeneration = 6L,
            actualMediaEdges = ConferenceTopologyContract.anchorStarEdges("M01", members4)
        )
        assertTrue(
            ConferenceTopologyConsistencyContract.validateAtomicConsistency(t2) is SnapshotValidity.Valid
        )

        val view1 = (ConferenceTopologyConsistencyContract.bindDownstreamView(t1)
            as ConferenceTopologyConsistencyContract.BindResult.Ok).view
        val view2 = (ConferenceTopologyConsistencyContract.bindDownstreamView(t2)
            as ConferenceTopologyConsistencyContract.BindResult.Ok).view

        val snap1 = ConferenceTopologyConsistencyContract.presenceSnapshotLineageFromTopology(t1)!!
        val snap2 = ConferenceTopologyConsistencyContract.presenceSnapshotLineageFromTopology(t2)!!

        assertEquals(5L, view1.meshGeneration)
        assertEquals(6L, view2.meshGeneration)
        assertEquals(5L, snap1.meshGeneration)
        assertEquals(6L, snap2.meshGeneration)
        assertEquals(5L, ConferenceTopologyConsistencyContract.uiLineageGeneration(t1))
        assertEquals(6L, ConferenceTopologyConsistencyContract.uiLineageGeneration(t2))

        val out1 = ConferencePresenceProjector.compose(
            conferenceId = t1.conferenceId,
            canonicalRoster = t1.members,
            currentAnchorEpoch = t1.anchorEpoch,
            snapshot = snap1,
            nowMs = 100_000L
        )
        val out2 = ConferencePresenceProjector.compose(
            conferenceId = t2.conferenceId,
            canonicalRoster = t2.members,
            currentAnchorEpoch = t2.anchorEpoch,
            snapshot = snap2,
            nowMs = 100_000L
        )
        assertEquals(4, out1.joinedCount)
        assertEquals(4, out2.joinedCount)
    }

    @Test
    fun s3_diagnostic_snapshotOnly_meshAndAnchor() {
        val mesh = meshSnapshot()
        val anchor = anchorSnapshot(meshGeneration = 5L, anchorEpoch = 100L)

        val meshLine = ConferenceTopologyConsistencyContract.diagnosticFromSnapshot(mesh)
        val anchorLine = ConferenceTopologyConsistencyContract.diagnosticFromSnapshot(anchor)

        listOf(meshLine, anchorLine).forEach { line ->
            assertTrue(line.contains("topologyMode="))
            assertTrue(line.contains("anchorId="))
            assertTrue(line.contains("anchorEpoch="))
            assertTrue(line.contains("meshGeneration="))
            assertTrue(line.contains("members="))
            assertTrue(line.contains("edges="))
            assertTrue(ConferenceTopologyConsistencyContract.diagnosticIsSnapshotOnly(line))
        }
        assertTrue(meshLine.contains("topologyMode=MESH"))
        assertTrue(meshLine.contains("anchorId=null"))
        assertTrue(meshLine.contains("edges="))
        assertFalse(meshLine.contains("M01->"))
        assertTrue(anchorLine.contains("topologyMode=ANCHOR"))
        assertTrue(anchorLine.contains("M01->M02"))
    }

    @Test
    fun s4_atomicDownstream_rejectsCrossGenerationMix_andCrossSnapshotMerge() {
        val invalid = anchorSnapshot(anchor = "M02", anchorEpoch = 11L, meshGeneration = 31L).copy(
            actualMediaEdges = ConferenceTopologyContract.anchorStarEdges("M01", members4)
        )
        assertTrue(
            ConferenceTopologyConsistencyContract.validateAtomicConsistency(invalid)
                is SnapshotValidity.Invalid
        )
        assertTrue(
            ConferenceTopologyConsistencyContract.bindDownstreamView(invalid)
                is ConferenceTopologyConsistencyContract.BindResult.Rejected
        )

        val s0 = anchorSnapshot(anchor = "M01", anchorEpoch = 10L, meshGeneration = 31L)
        val s1 = ConferenceTopologyContract.failoverSnapshot(s0, "M02")
        assertTrue(
            ConferenceTopologyConsistencyContract.attemptCrossSnapshotBind(s0, s1)
                is SnapshotValidity.Invalid
        )
    }

    @Test
    fun s5_failover_downstreamUsesNewAnchor_notStale() {
        val previous = anchorSnapshot(anchor = "M01", anchorEpoch = 10L, meshGeneration = 31L)
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")

        assertTrue(
            ConferenceTopologyConsistencyContract.failoverDownstreamReflectsCurrent(previous, current)
        )
        val view = (ConferenceTopologyConsistencyContract.bindDownstreamView(current)
            as ConferenceTopologyConsistencyContract.BindResult.Ok).view
        assertEquals("M02", view.anchorId)
        assertEquals(11L, view.anchorEpoch)
        assertEquals("M02", ConferenceTopologyReadBind.presenceReadInput(current).anchorId)

        val oldPresence = ConferenceTopologyConsistencyContract.presenceSnapshotLineageFromTopology(previous)!!
        val newPresence = ConferenceTopologyConsistencyContract.presenceSnapshotLineageFromTopology(current)!!
        val chosen = ConferencePresenceProjector.selectPresenceSnapshot(
            conferenceId = current.conferenceId,
            currentAnchorEpoch = current.anchorEpoch,
            candidates = listOf(oldPresence, newPresence)
        )
        assertEquals(11L, chosen?.anchorEpoch)
        assertEquals(32L, chosen?.meshGeneration)
    }

    @Test
    fun s6_meshDownstream_noFabricatedAnchorOrEdges() {
        val mesh = meshSnapshot(meshGeneration = 2L)
        assertTrue(
            ConferenceTopologyConsistencyContract.validateAtomicConsistency(mesh) is SnapshotValidity.Valid
        )
        val view = (ConferenceTopologyConsistencyContract.bindDownstreamView(mesh)
            as ConferenceTopologyConsistencyContract.BindResult.Ok).view

        assertEquals(ConferenceTopologyMode.MESH, view.topologyMode)
        assertNull(view.anchorId)
        assertEquals(0L, view.anchorEpoch)
        assertEquals(0, view.actualMediaEdgeCount)
        assertEquals(
            ConferencePresenceReadPath.MESH_SESSION_OBSERVATION,
            view.presenceReadPath
        )
        assertTrue(
            ConferenceTopologyConsistencyContract.validateMeshDownstreamInvariants(view)
                is SnapshotValidity.Valid
        )
        assertNull(ConferenceTopologyConsistencyContract.presenceSnapshotLineageFromTopology(mesh))
    }

    @Test
    fun s7_downstreamRead_doesNotMutateSnapshot() {
        val snapshot = anchorSnapshot(host = "M02", meshGeneration = 4L)
        val (unchanged, _) = ConferenceTopologyConsistencyContract.readWithoutMutatingSnapshot(snapshot) {
            ConferenceTopologyConsistencyContract.bindDownstreamView(it)
            ConferenceTopologyReadBind.presenceReadInput(it)
            ConferenceTopologyConsistencyContract.diagnosticFromSnapshot(it)
            ConferenceTopologyConsistencyContract.presenceSnapshotLineageFromTopology(it)?.let { presence ->
                ConferencePresenceProjector.compose(
                    conferenceId = it.conferenceId,
                    canonicalRoster = it.members,
                    currentAnchorEpoch = it.anchorEpoch,
                    snapshot = presence,
                    nowMs = 100_000L
                )
            }
        }
        assertEquals(snapshot, unchanged)
    }

    @Test
    fun s8_repeatedBind_stableResults() {
        val anchor = anchorSnapshot(meshGeneration = 9L)
        val mesh = meshSnapshot(meshGeneration = 3L)
        assertTrue(ConferenceTopologyConsistencyContract.repeatedBindStable(anchor))
        assertTrue(ConferenceTopologyConsistencyContract.repeatedBindStable(mesh))
    }
}
