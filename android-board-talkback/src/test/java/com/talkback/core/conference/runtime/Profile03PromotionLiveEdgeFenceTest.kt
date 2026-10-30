package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile03PromotionLiveEdgeFenceTest {
    @Test
    fun promotionDiscardLate_removesOnlyPastDeadlineFrames() {
        val buf = PerIncarnationJitterBuffer("S1", 1L)
        val base = 1_000L
        for (slot in 0L..5L) {
            val mediaTime = base + slot * MediaJitterConstants.MEDIA_SLOT_MS
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                buf.admit(
                    AdmittedMediaFrame("S1", 1L, slot, mediaTime, mediaTime),
                    mediaTime,
                ),
            )
        }
        val promotionTime = base + 5_000L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            buf.admit(
                AdmittedMediaFrame(
                    "S1",
                    1L,
                    6,
                    promotionTime - 80,
                    promotionTime,
                ),
                promotionTime,
            ),
        )

        val result = buf.promotionDiscardLateForPlayout(promotionTime)
        assertEquals(6, result.staleDiscarded)
        assertEquals(listOf(6L), buf.bufferedSlots())
        assertEquals(6L, buf.nextExpected())
        assertTrue(result.oldestRetainedAgeMs!! <= MediaJitterConstants.MAX_PLAYOUT_DELAY_MS)
    }

    @Test
    fun selectTopK_appliesFenceOnPromotion() {
        val selection = ConferenceMediaSelectionRuntime()
        val pipe = MediaExecutionPipeline(selection)
        installSource(selection, "loud", 1L)
        installSource(selection, "quiet", 2L)

        selection.observeVoice(VoiceLevelObservation("loud", 1L, voiceActive = true, audioLevel = 90))
        selection.observeVoice(VoiceLevelObservation("quiet", 2L, voiceActive = false, audioLevel = 10))
        val now = 10_000L
        pipe.selectTopK(now)

        val staleTime = now - 2_000L
        for (slot in 0L..3L) {
            val mediaTime = staleTime + slot * MediaJitterConstants.MEDIA_SLOT_MS
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipe.admitFrame(
                    AdmittedMediaFrame("quiet", 2L, slot, mediaTime, mediaTime),
                    mediaTime,
                ),
            )
        }
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipe.admitFrame(
                AdmittedMediaFrame("quiet", 2L, 4, now - 80, now),
                now,
            ),
        )

        selection.observeVoice(VoiceLevelObservation("quiet", 2L, voiceActive = true, audioLevel = 95))
        selection.observeVoice(VoiceLevelObservation("loud", 1L, voiceActive = true, audioLevel = 10))
        pipe.selectTopK(now)

        val metrics = pipe.promotionLiveEdgeFenceMetrics
        assertTrue(metrics.promotionLiveEdgeFenceCount >= 1)
        assertTrue(metrics.promotionStaleFramesDiscarded >= 4)
        assertEquals(listOf(4L), pipe.bufferedSlots("quiet", 2L))
    }

    private fun installSource(
        selection: ConferenceMediaSelectionRuntime,
        identity: String,
        incarnationId: Long,
    ) {
        selection.install(
            AdmittedMediaSource(
                sourceIdentity = identity,
                incarnationId = incarnationId,
            ),
        )
    }
}
