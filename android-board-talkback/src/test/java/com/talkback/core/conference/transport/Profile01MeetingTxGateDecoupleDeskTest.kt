package com.talkback.core.conference.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-5: Gate muter still exists; Meeting TX seam no longer applies it to encode PCM. */
class Profile01MeetingTxGateDecoupleDeskTest {
    @Test
    fun frameMuter_stillZerosWhenAsked() {
        val muter = LocalPlayoutActivityGate.FrameMuter()
        val pcm = ShortArray(960) { 1234 }
        muter.apply(pcm, muteNow = true)
        assertEquals(0, pcm.last().toInt())
        assertTrue(pcm.takeLast(100).all { it.toInt() == 0 })
    }
}
