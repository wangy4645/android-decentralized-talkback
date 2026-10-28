package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-3 — Media Bus contract (Q1–Q4). */
class MediaBusSessionContractTest {

    private fun starSpec(): MediaBusSessionSpec {
        val ids = listOf("M02", "M03", "M04").map { MediaEdgeId(it) }
        return MediaBusSessionSpec(
            sessionId = "conf-1",
            targetEdgeIds = ids,
            sourceEdgeIds = ids
        )
    }

    @Test
    fun q1_sessionCreatedOnlyViaFactory() {
        val events = mutableListOf<MediaBusEvent>()
        val session = ConferenceMediaBusSession.create(
            spec = starSpec(),
            onEvent = { events.add(it) }
        ).getOrThrow()
        assertEquals("conf-1", session.sessionId)
        assertEquals(3, session.targetEdgeIds.size)
        assertTrue(events.any { it is MediaBusEvent.SessionCreated })
    }

    @Test
    fun q2_usesMediaEdgeId_notImplicitPeerGraph() {
        val edge = MediaEdgeId("anchor-edge-M02-uplink-gen3")
        val session = ConferenceMediaBusSession.create(
            spec = MediaBusSessionSpec(
                sessionId = "s1",
                targetEdgeIds = listOf(MediaEdgeId("M03")),
                sourceEdgeIds = listOf(edge)
            )
        ).getOrThrow()
        assertTrue(session.pushFromSource(edge, PcmFrame.constantLevel(1_000)).isSuccess)
        assertTrue(session.renderTarget(MediaEdgeId("M03")).isSuccess)
    }

    @Test
    fun q3_drainRamp_revokeDoesNotKillRenderClock() {
        val session = ConferenceMediaBusSession.create(
            spec = MediaBusSessionSpec(
                sessionId = "s1",
                targetEdgeIds = listOf(MediaEdgeId("M02"), MediaEdgeId("M03")),
                sourceEdgeIds = listOf(MediaEdgeId("M02"), MediaEdgeId("M03"))
            )
        ).getOrThrow()
        session.pushFromSource(MediaEdgeId("M02"), PcmFrame.constantLevel(2_000))
        session.renderTarget(MediaEdgeId("M03"))
        session.revokeSource(MediaEdgeId("M02"), MediaBusRevokeSemantics.DRAIN_RAMP)
        session.pushFromSource(MediaEdgeId("M03"), PcmFrame.constantLevel(3_000))
        val rendered = session.renderTarget(MediaEdgeId("M02"))
        assertTrue(rendered.isSuccess)
        assertEquals(ConferencePcmFormat.CANONICAL.samplesPerFrame, rendered.getOrThrow().frame.samples.size)
    }

    @Test
    fun q3_hardStop_revokeSession_closesPorts() {
        val session = ConferenceMediaBusSession.create(spec = starSpec()).getOrThrow()
        session.revokeSession(MediaBusRevokeSemantics.HARD_STOP)
        assertTrue(session.pushFromSource(MediaEdgeId("M02"), PcmFrame.constantLevel(500)).isFailure)
        session.targetPort(MediaEdgeId("M02"))?.let { assertTrue(!it.isOpen) }
    }

    @Test
    fun q4_injectionFailure_emitsMediaEdgeFact_notHealthMutation() {
        val failures = mutableListOf<MediaBusFailureFact>()
        val port = RecordingPcmInjectionPort()
        val session = ConferenceMediaBusSession.create(
            spec = MediaBusSessionSpec(
                sessionId = "s1",
                targetEdgeIds = listOf(MediaEdgeId("M02")),
                sourceEdgeIds = listOf(MediaEdgeId("M03"))
            ),
            onFailure = { failures.add(it) },
            portFactory = { port }
        ).getOrThrow()
        port.injectNextFailure(PcmInjectionFailure.INJECT_FAILED)
        session.pushFromSource(MediaEdgeId("M03"), PcmFrame.constantLevel(1_000))
        val result = session.renderTarget(MediaEdgeId("M02"))
        assertTrue(result.isFailure)
        assertEquals(1, failures.filterIsInstance<MediaBusFailureFact.InjectionFailed>().size)
        assertEquals(MediaBusFailureDomain.MEDIA_EDGE, failures.first().domain)
    }

    @Test
    fun excludeSelf_sourceNotMixedIntoOwnTarget() {
        val session = ConferenceMediaBusSession.create(
            spec = MediaBusSessionSpec(
                sessionId = "s1",
                targetEdgeIds = listOf(MediaEdgeId("M02")),
                sourceEdgeIds = listOf(MediaEdgeId("M02"))
            )
        ).getOrThrow()
        val push = session.pushFromSource(MediaEdgeId("M02"), PcmFrame.constantLevel(5_000))
        assertTrue(push.isFailure)
    }
}
