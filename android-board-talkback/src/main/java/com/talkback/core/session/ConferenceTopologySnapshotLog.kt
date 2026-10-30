package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/**
 * Field grep for Phase 1b-1 topology authority publish boundary.
 *
 * `CONFERENCE_TOPOLOGY_SNAPSHOT conferenceId=... rosterEpoch=... anchorEpoch=... anchorId=... meshGeneration=... topologyMode=ANCHOR host=... members=M01,M02 edges=M01->M02,...`
 */
object ConferenceTopologySnapshotLog {

    fun format(snapshot: ConferenceTopologySnapshot): String {
        val members = snapshot.members.sorted().joinToString(",")
        val edges = snapshot.actualMediaEdges
            .sortedWith(compareBy({ it.anchorModuleId }, { it.remoteModuleId }))
            .joinToString(",") { "${it.anchorModuleId}->${it.remoteModuleId}" }
        return "CONFERENCE_TOPOLOGY_SNAPSHOT" +
            " conferenceId=${snapshot.conferenceId}" +
            " rosterEpoch=${snapshot.rosterEpoch}" +
            " anchorEpoch=${snapshot.anchorEpoch}" +
            " anchorId=${snapshot.anchorId}" +
            " meshGeneration=${snapshot.meshGeneration}" +
            " topologyMode=${snapshot.topologyMode.name}" +
            " host=${snapshot.hostModuleId}" +
            " members=$members" +
            " edges=$edges"
    }

    fun emit(line: String) {
        TalkbackLog.i(line)
    }
}
