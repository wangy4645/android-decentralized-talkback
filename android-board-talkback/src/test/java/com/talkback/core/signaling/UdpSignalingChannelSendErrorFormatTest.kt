package com.talkback.core.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UdpSignalingChannelSendErrorFormatTest {
    @Test
    fun formatDatagramSendErr_usesClassNameWhenMessageNull() {
        val error = RuntimeException()
        assertEquals("RuntimeException:null", UdpSignalingChannel.formatDatagramSendErr(error))
    }

    @Test
    fun formatDatagramSendErr_keepsMessageWhenPresent() {
        val error = IllegalStateException("signaling socket unavailable")
        assertEquals(
            "IllegalStateException:signaling socket unavailable",
            UdpSignalingChannel.formatDatagramSendErr(error),
        )
    }

    @Test
    fun isMainLooperThread_isFalseOnJvmUnitTestThread() {
        assertFalse(UdpSignalingChannel.isMainLooperThread())
    }
}
