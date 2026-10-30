package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConcentusOpusDecoderSeam
import com.talkback.core.conference.runtime.OpusPayloadStore
import com.talkback.core.conference.runtime.OpusTestVectors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01A3Q2DecoderProofDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01A3Q2DecoderProof.resetForTest()
        Profile01A3Q2DecoderProof.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01A3Q2DecoderProof.resetForTest()
    }

    @Test
    fun maybeLog_emitsOncePerLiveCountChange_withSession() {
        val store = OpusPayloadStore()
        val seam = ConcentusOpusDecoderSeam(store)
        val opus = OpusTestVectors.encodeTone(440.0)
        store.put("M01", 1L, 0L, opus)
        seam.decode("M01", 1L, 0L, nowMs = 0L)

        Profile01A3Q2DecoderProof.maybeLog("sess-a", seam)
        Profile01A3Q2DecoderProof.maybeLog("sess-a", seam)

        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith(Profile01A3Q2DecoderProof.PHASE))
        assertTrue(lines[0].contains("session=sess-a"))
        assertTrue(lines[0].contains("liveCount=1"))
        assertTrue(lines[0].contains("sources=M01"))
    }
}
