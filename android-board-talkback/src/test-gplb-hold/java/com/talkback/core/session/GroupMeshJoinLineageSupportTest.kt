package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GroupMeshJoinLineageSupportTest {
    private fun offerer(
        group: Boolean = true,
        live: Boolean = true,
        generation: Long? = 1L,
        hash: Int? = 11,
    ) = GroupMeshJoinLineageSupport.OffererFacts(
        groupSession = group,
        producerIsLiveEngine = live,
        pcGeneration = generation,
        pcHash = hash,
    )

    private fun answerer(
        group: Boolean = true,
        live: Boolean = true,
        generation: Long? = 1L,
        hash: Int? = 11,
        lineage: String? = "GM1",
        existing: GroupOfferBinding.BindingState? = null,
    ) = GroupMeshJoinLineageSupport.AnswererFacts(
        groupSession = group,
        producerIsLiveEngine = live,
        pcGeneration = generation,
        pcHash = hash,
        offerLineageId = lineage,
        existingState = existing,
    )

    @Test
    fun joinOfferer_bindsOnlyWhenLivePcIdentityIsComplete() {
        assertEquals(
            GroupMeshJoinLineageSupport.OffererDecision.BIND,
            GroupMeshJoinLineageSupport.decideOffererBind(offerer()),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.OffererDecision.SKIP_NO_IDENTITY,
            GroupMeshJoinLineageSupport.decideOffererBind(offerer(generation = null)),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.OffererDecision.SKIP_NO_IDENTITY,
            GroupMeshJoinLineageSupport.decideOffererBind(offerer(hash = null)),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.OffererDecision.SKIP_STALE_ENGINE,
            GroupMeshJoinLineageSupport.decideOffererBind(offerer(live = false)),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.OffererDecision.SKIP_NOT_GROUP,
            GroupMeshJoinLineageSupport.decideOffererBind(offerer(group = false)),
        )
    }

    @Test
    fun joinAnswerer_rejectsMissingLineageAndStaleEngine() {
        assertEquals(
            GroupMeshJoinLineageSupport.AnswererDecision.BIND,
            GroupMeshJoinLineageSupport.decideAnswererBind(answerer()),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.AnswererDecision.SKIP_NO_LINEAGE,
            GroupMeshJoinLineageSupport.decideAnswererBind(answerer(lineage = null)),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.AnswererDecision.SKIP_NO_LINEAGE,
            GroupMeshJoinLineageSupport.decideAnswererBind(
                answerer(lineage = GroupPcLineageWire.UNKNOWN_LINEAGE),
            ),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.AnswererDecision.SKIP_STALE_ENGINE,
            GroupMeshJoinLineageSupport.decideAnswererBind(answerer(live = false)),
        )
        assertEquals(
            GroupMeshJoinLineageSupport.AnswererDecision.SKIP_RESURRECT,
            GroupMeshJoinLineageSupport.decideAnswererBind(
                answerer(existing = GroupOfferBinding.BindingState.FENCED),
            ),
        )
    }

    @Test
    fun samePcReconnect_newJoinLineageFencesOldWithoutGenerationBump() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M04", "GM1", pcLineage = 1L)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M04", "GM2", pcLineage = 1L)
        val old = GroupPcLineageBindingSupport.binding(session, "M04", "GM1")!!
        val live = GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M04", 1L)!!
        assertEquals(GroupOfferBinding.BindingState.FENCED, old.state)
        assertEquals("GM2", live.offerLineageId)
        assertEquals(1L, live.pcLineage)
        assertEquals(1L, old.pcLineage)
    }

    @Test
    fun legalPcReplacement_liveBindingMovesWithGeneration() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M04", "GM1", pcLineage = 1L)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M04", "GM2", pcLineage = 2L)
        assertEquals(
            "GM2",
            GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M04", 2L)?.offerLineageId,
        )
        assertEquals(
            null,
            GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M04", 1L),
        )
        assertEquals(
            GroupOfferBinding.BindingState.FENCED,
            GroupPcLineageBindingSupport.binding(session, "M04", "GM1")?.state,
        )
    }

    @Test
    fun lateJoinDoesNotResurrectFencedBinding() {
        val session = session()
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M04", "GM1", pcLineage = 1L)
        GroupPcLineageBindingSupport.recordOffererBinding(session, "M04", "GM2", pcLineage = 1L)
        val revived =
            GroupPcLineageBindingSupport.recordAnswererBinding(
                session,
                "M04",
                "GM1",
                pcLineage = 1L,
            )
        assertEquals(GroupOfferBinding.BindingState.FENCED, revived.state)
        assertEquals(
            "GM2",
            GroupPcLineageBindingSupport.liveBindingForPcLineage(session, "M04", 1L)?.offerLineageId,
        )
        assertNotEquals(GroupOfferBinding.BindingState.LIVE, revived.state)
        assertNotNull(GroupPcLineageBindingSupport.binding(session, "M04", "GM2"))
    }

    private fun session(): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        return TalkbackSession(id = "grp:CH-01", type = SessionType.GROUP, local = local, channelId = "CH-01")
    }
}
