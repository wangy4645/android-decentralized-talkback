package com.talkback.core.qos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IceConnectivityTest {

    @Test
    fun isConnected_acceptsConnectedAndCompleted() {
        assertTrue(IceConnectivity.isConnected("CONNECTED"))
        assertTrue(IceConnectivity.isConnected("COMPLETED"))
        assertFalse(IceConnectivity.isConnected("CHECKING"))
        assertFalse(IceConnectivity.isConnected("FAILED"))
        assertFalse(IceConnectivity.isConnected(null))
    }

    @Test
    fun isNegotiating_coversNewCheckingAndNull() {
        assertTrue(IceConnectivity.isNegotiating(null))
        assertTrue(IceConnectivity.isNegotiating("NEW"))
        assertTrue(IceConnectivity.isNegotiating("CHECKING"))
        assertFalse(IceConnectivity.isNegotiating("CONNECTED"))
        assertFalse(IceConnectivity.isNegotiating("COMPLETED"))
    }

    @Test
    fun isLost_coversFailedAndDisconnected() {
        assertTrue(IceConnectivity.isLost("FAILED"))
        assertTrue(IceConnectivity.isLost("DISCONNECTED"))
        assertFalse(IceConnectivity.isLost("CONNECTED"))
    }

    @Test
    fun networkQualityMonitor_isGroupConnected_followsIceConnectivity() {
        val monitor = NetworkQualityMonitor()
        monitor.updateGroupIceState("M02", "COMPLETED")
        assertTrue(monitor.isGroupConnected("M02"))
        monitor.updateUnicastIceState("sess-1", "M03", "COMPLETED")
        assertTrue(monitor.isUnicastConnected("sess-1"))
    }
}
