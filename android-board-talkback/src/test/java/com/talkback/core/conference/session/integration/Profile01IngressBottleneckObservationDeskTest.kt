package com.talkback.core.conference.session.integration

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Desk: ingress bottleneck observation emits window lines; behavior-neutral. */
class Profile01IngressBottleneckObservationDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01IngressBottleneckObservation.resetForTest()
        Profile01IngressBottleneckObservation.testLogSink = { lines += it }
        Profile01IngressBottleneckObservation.enabled = true
    }

    @After
    fun tearDown() {
        Profile01IngressBottleneckObservation.resetForTest()
        Profile01IngressBottleneckObservation.enabled = false
    }

    @Test
    fun windowEmitsAfterFiftyAdmits() {
        Profile01IngressBottleneckObservation.noteSocketBufferBytes(212992)
        repeat(Profile01IngressBottleneckObservation.WINDOW) {
            Profile01IngressBottleneckObservation.noteSockReceived()
            Profile01IngressBottleneckObservation.noteAdmitComplete(
                admitUs = 500L + it,
                acceptedWire = true,
                sourceIdentity = "M02",
                sequence = 1000 + it,
            )
        }
        assertTrue(lines.any { it.contains("INGRESS_BOTTLENECK") && it.contains("admitMaxUs=") })
        assertTrue(lines.any { it.contains("soRcvBuf=212992") })
    }
}