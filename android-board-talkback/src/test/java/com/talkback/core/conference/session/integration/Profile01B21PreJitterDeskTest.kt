package com.talkback.core.conference.session.integration

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01B21PreJitterDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01B21PreJitterObservation.resetForTest()
        Profile01B21PreJitterObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01B21PreJitterObservation.resetForTest()
    }

    @Test
    fun txWindow_countsSent() {
        repeat(Profile01B21PreJitterObservation.WINDOW) { i ->
            Profile01B21PreJitterObservation.noteTxSent("M01", 0x5000 + i)
        }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("B21_TX_EGRESS local=M01"))
        assertTrue(lines.single().contains("sent=25"))
        assertTrue(lines.single().contains("skip=0"))
    }

    @Test
    fun rxPreJitter_wireFailNotQueued() {
        repeat(Profile01B21PreJitterObservation.WINDOW) {
            Profile01B21PreJitterObservation.noteRxResolved(
                sourceIdentity = "M02",
                wireOk = false,
                queued = false,
                failReason = "AEAD|fail",
            )
        }
        val line = lines.single()
        assertTrue(line.contains("B21_RX_PRE_JITTER source=M02"))
        assertTrue(line.contains("wireFail=25"))
        assertTrue(line.contains("queued=0"))
        assertTrue(line.contains("lastFail=AEAD|fail"))
    }

    @Test
    fun rxSock_includesUnknownSsrcInSameWindow() {
        repeat(24) { Profile01B21PreJitterObservation.noteRxSock() }
        Profile01B21PreJitterObservation.noteRxUnknownSsrc()
        Profile01B21PreJitterObservation.noteRxSock()
        val sock = lines.single { it.startsWith("B21_RX_SOCK") }
        assertTrue(sock.contains("sock=25"))
        assertTrue(sock.contains("unknownSsrc=1"))
    }
}
