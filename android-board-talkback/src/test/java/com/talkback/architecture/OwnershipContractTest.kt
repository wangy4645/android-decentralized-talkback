package com.talkback.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RO-1: Architecture contract tests. Baseline counts must not increase; decrease as RO-2…RO-7 merge.
 *
 * @see OwnershipContractBaseline
 * @see docs/runtime/runtime-public-api.md
 */
class OwnershipContractTest {

    @Test
    fun coordinator_mustNotIncrease_directGroupMembersAssignments() {
        val count = OwnershipContractScanner.countCoordinatorGroupMembersDirectAssign()
        assertAtOrBelow(
            rule = "COORDINATOR_GROUP_MEMBERS_DIRECT_ASSIGN",
            actual = count,
            ceiling = OwnershipContractBaseline.COORDINATOR_GROUP_MEMBERS_DIRECT_ASSIGN,
            fixIssue = "RO-2"
        )
    }

    @Test
    fun coordinator_mustNotIncrease_directFloorAuthorityAssignments() {
        val count = OwnershipContractScanner.countCoordinatorFloorAuthorityDirectAssign()
        assertAtOrBelow(
            rule = "COORDINATOR_FLOOR_AUTHORITY_DIRECT_ASSIGN",
            actual = count,
            ceiling = OwnershipContractBaseline.COORDINATOR_FLOOR_AUTHORITY_DIRECT_ASSIGN,
            fixIssue = "RO-3"
        )
    }

    @Test
    fun resolveFloorAuthorityRoute_mustNotIncrease_signalPeerReads() {
        val count = OwnershipContractScanner.countResolveFloorAuthorityRouteSignalPeerReads()
        assertAtOrBelow(
            rule = "COORDINATOR_RESOLVE_FLOOR_SIGNAL_PEER_READS",
            actual = count,
            ceiling = OwnershipContractBaseline.COORDINATOR_RESOLVE_FLOOR_SIGNAL_PEER_READS,
            fixIssue = "RO-4"
        )
    }

    @Test
    fun participantMedia_mustNotIncrease_writesOutsideWhitelist() {
        val count = OwnershipContractScanner.countParticipantMediaWritesOutsideWhitelist()
        assertAtOrBelow(
            rule = "PARTICIPANT_MEDIA_WRITES_OUTSIDE_WHITELIST",
            actual = count,
            ceiling = OwnershipContractBaseline.PARTICIPANT_MEDIA_WRITES_OUTSIDE_WHITELIST,
            fixIssue = "RO-7"
        )
    }

    @Test
    fun floorAuthorityRoute_mustNotReferenceSignalPeersByModuleSymbol() {
        val root = OwnershipContractScanner.mainJavaRoot()
        val file = java.io.File(root, "com/talkback/core/ptt/FloorAuthorityRoute.kt")
        val source = file.readText()
        assertTrue(
            "FloorAuthorityRoute must not reference signalPeersByModule (use TransportRegistry in RO-4+): ${file.name}",
            !source.contains("signalPeersByModule")
        )
    }

    @Test
    fun scanner_detectsParticipantMediaAssignment() {
        assertTrue(OwnershipContractScanner.isParticipantMediaAssignment("participant.media = MediaState.CONNECTING"))
        assertTrue(OwnershipContractScanner.isParticipantMediaAssignment("meshParticipant(session, id).media = media"))
        assertTrue(OwnershipContractScanner.isParticipantMediaAssignment("media = MediaState.CONNECTED"))
        assertTrue(!OwnershipContractScanner.isParticipantMediaAssignment("if (participant.media == MediaState.NONE)"))
        assertTrue(!OwnershipContractScanner.isParticipantMediaAssignment("// participant.media = x"))
        assertTrue(!OwnershipContractScanner.isParticipantMediaAssignment("val media = when (state)"))
        assertTrue(!OwnershipContractScanner.isParticipantMediaAssignment("media = ps?.media ?: MediaState.NONE"))
    }

    private fun assertAtOrBelow(
        rule: String,
        actual: Int,
        ceiling: Int,
        fixIssue: String
    ) {
        assertEquals(
            "$rule: found $actual, ceiling $ceiling (fix in $fixIssue). " +
                "If intentional debt reduction, lower OwnershipContractBaseline.",
            ceiling,
            actual
        )
    }
}
