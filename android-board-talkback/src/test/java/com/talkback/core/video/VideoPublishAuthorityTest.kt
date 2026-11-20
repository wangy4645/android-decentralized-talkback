package com.talkback.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPublishAuthorityTest {

    @Test
    fun emptySlot_grantsGenerationOne() {
        val decision = VideoPublishAuthority.decide(null, "M02", "s1")
        val grant = decision as VideoPublishDecision.Grant
        assertEquals("M02", grant.canonical.publisherModuleId)
        assertEquals(1L, grant.canonical.videoGeneration)
        assertEquals("s1", grant.canonical.streamId)
    }

    @Test
    fun occupiedByOther_denies() {
        val current = CanonicalVideoPublish("M02", "s1", 3L)
        val decision = VideoPublishAuthority.decide(current, "M03", "s2")
        val deny = decision as VideoPublishDecision.Deny
        assertEquals("M02", deny.current.publisherModuleId)
        assertEquals(3L, deny.current.videoGeneration)
    }

    @Test
    fun samePublisher_refreshesWithoutBump() {
        val current = CanonicalVideoPublish("M02", "s1", 3L)
        val decision = VideoPublishAuthority.decide(current, "M02", "s1")
        val refresh = decision as VideoPublishDecision.Refresh
        assertEquals(3L, refresh.canonical.videoGeneration)
    }

    @Test
    fun sameTick_lexicographicWinner() {
        val result = VideoPublishAuthority.decideSameTick(
            listOf("M04", "M02", "M03"),
            mapOf("M04" to "s4", "M02" to "s2", "M03" to "s3"),
        )
        assertEquals("M02", result!!.first.canonical.publisherModuleId)
        assertEquals("s2", result.first.canonical.streamId)
        assertEquals(listOf("M04", "M03"), result.second)
        assertEquals("M02", VideoPublishAuthority.winnerModuleId(listOf("M10", "M02", "M03")))
    }
}
