package com.talkback.core.webrtc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcSharedSignalingFenceTest {

    @Test
    fun lockAcquiresOnCallingThread() {
        var ran = false
        WebRtcSharedFactory.withSrdApplyLock { ran = true }
        assertTrue(ran)
    }

    @Test
    fun srdApplyLockPreventsOverlappingUnsafeSections() {
        val firstInside = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val secondDone = CountDownLatch(1)
        val firstThread = Thread {
            WebRtcSharedFactory.withSrdApplyLock {
                firstInside.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }
        firstThread.start()
        assertTrue(firstInside.await(3, TimeUnit.SECONDS))
        val secondThread = Thread {
            WebRtcSharedFactory.withSrdApplyLock {
                secondEntered.set(true)
            }
            secondDone.countDown()
        }
        secondThread.start()
        Thread.sleep(80)
        assertTrue("second thread must block on fence until first completes", !secondEntered.get())
        releaseFirst.countDown()
        assertTrue(secondDone.await(2, TimeUnit.SECONDS))
        assertTrue(secondEntered.get())
        firstThread.join(2_000)
        secondThread.join(2_000)
    }
}
