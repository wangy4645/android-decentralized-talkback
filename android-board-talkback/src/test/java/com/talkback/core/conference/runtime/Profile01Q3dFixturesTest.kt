package com.talkback.core.conference.runtime

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01Q3dFixturesTest {
    @Test
    fun toneAIsFiveSecondsAtAboutMinus20Rms() {
        val tone = Profile01Q3dFixtures.toneA()
        val level = Profile01Q3dFixtures.measure(tone)
        assertEquals(48_000 * 5, level.samples)
        assertTrue(level.rmsDbfs, abs(level.rmsDbfs.toDouble() - (-20.0)) < 0.2)
        assertTrue(level.peakDbfs.toDouble() > -18.0)
        assertTrue(level.peakDbfs.toDouble() < -16.0)
    }

    @Test
    fun speechBIsSixSecondsAtAboutMinus20Rms() {
        val speech = Profile01Q3dFixtures.speechB()
        val level = Profile01Q3dFixtures.measure(speech)
        assertEquals(48_000 * 6, level.samples)
        assertTrue(level.rmsDbfs, abs(level.rmsDbfs.toDouble() - (-20.0)) < 0.2)
    }
}
