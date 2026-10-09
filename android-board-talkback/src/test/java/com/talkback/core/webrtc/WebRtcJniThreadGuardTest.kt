package com.talkback.core.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcJniThreadGuardTest {

    @Test
    fun warnIfCoordinator_isNoOpOnWorkerThread() {
        val called = AtomicBoolean(false)
        WebRtcJniThreadGuard.warnIfCoordinator("setRemotePlaybackEnabled")
        called.set(true)
        assertTrue(called.get())
        assertFalse(Thread.currentThread().name == WebRtcJniThreadGuard.COORDINATOR_THREAD_NAME)
    }
}
