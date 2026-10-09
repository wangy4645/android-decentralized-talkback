package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConcentusOpusDecoderSeam
import com.talkback.core.conference.runtime.DecodeMixRuntime
import com.talkback.core.conference.runtime.LiveDecoderPool
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.OpusPayloadStore
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.VoiceLevelObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 — stale incarnation must not resurrect or replace active physical decoder.
 */
class Profile01P2StaleIncarnationDecoderIsolationDeskTest {
    @Test
    fun staleIncarnation_decodeRejected_activeIncarnationUnchanged() {
        val store = OpusPayloadStore()
        val seam = ConcentusOpusDecoderSeam(store)
        val decodeMix = DecodeMixRuntime()
        decodeMix.setDecodeSeam(seam)

        installExecutable(decodeMix, "M04", 1L)
        selectTopK(decodeMix, "M04", 1L)

        val opus = OpusTestVectors.encodeTone(440.0)
        store.put("M04", 1L, 0L, opus)
        assertTrue(decodeMix.produceMixablePcm("M04", 1L, 0L, nowMs = 100L) != null)
        assertEquals(1, seam.physicalDecoderCount)

        installExecutable(decodeMix, "M04", 2L)
        selectTopK(decodeMix, "M04", 2L)
        store.put("M04", 2L, 1L, opus)
        assertTrue(decodeMix.produceMixablePcm("M04", 2L, 1L, nowMs = 200L) != null)
        assertEquals(1, seam.physicalDecoderCount)

        store.put("M04", 1L, 2L, opus)
        assertNull(decodeMix.produceMixablePcm("M04", 1L, 2L, nowMs = 300L))
        assertNull(seam.decode("M04", 1L, 2L, nowMs = 300L))

        seam.evict("M04", 1L)
        assertEquals(1, seam.physicalDecoderCount)
        store.put("M04", 2L, 2L, opus)
        assertTrue(decodeMix.produceMixablePcm("M04", 2L, 2L, nowMs = 400L) != null)
    }

    @Test
    fun sixthSource_allocateFails_noSixthPhysicalDecoder() {
        val seam = ConcentusOpusDecoderSeam(OpusPayloadStore())
        val pool = LiveDecoderPool()
        val ids = listOf("M01", "M02", "M03", "M04", "M05")
        var now = 1_000L
        for (id in ids) {
            assertTrue(pool.allocate(id, 1L, now, forTransitionOnly = false) != null)
            seam.onPoolAllocated(id, 1L)
            now += 10
        }
        assertEquals(MediaMixConstants.MAX_LIVE_DECODERS, pool.liveCount())
        assertEquals(MediaMixConstants.MAX_LIVE_DECODERS, seam.physicalDecoderCount)

        assertNull(pool.allocate("M06", 1L, now, forTransitionOnly = false))
        seam.onPoolAllocated("M06", 1L)
        assertEquals(MediaMixConstants.MAX_LIVE_DECODERS, seam.physicalDecoderCount)
    }

    @Test
    fun hardFenceRelease_matchesPoolAndPhysicalDecoderCount() {
        val seam = ConcentusOpusDecoderSeam(OpusPayloadStore())
        val pool = LiveDecoderPool()
        seam.onPoolAllocated("M01", 1L)
        seam.onPoolAllocated("M02", 2L)
        pool.allocate("M01", 1L, 0L, false)
        pool.allocate("M02", 2L, 0L, false)
        assertEquals(2, seam.physicalDecoderCount)

        pool.hardFenceRelease("M01", 1L)
        seam.evict("M01", 1L)
        assertEquals(1, pool.liveCount())
        assertEquals(1, seam.physicalDecoderCount)

        pool.hardFenceRelease("M02", 99L)
        assertEquals(1, pool.liveCount())
        assertEquals(1, seam.physicalDecoderCount)
    }

    private fun installExecutable(
        decodeMix: DecodeMixRuntime,
        source: String,
        incarnationId: Long,
    ) {
        decodeMix.install(AdmittedMediaSource(sourceIdentity = source, incarnationId = incarnationId))
    }

    private fun selectTopK(
        decodeMix: DecodeMixRuntime,
        focusSource: String,
        incarnationId: Long,
        retain: List<String> = listOf(focusSource),
    ) {
        for (id in retain) {
            decodeMix.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = id,
                    incarnationId = if (id == focusSource) incarnationId else 1L,
                    voiceActive = true,
                    audioLevel = 90,
                ),
            )
        }
        decodeMix.selectTopK(1_000L)
    }
}
