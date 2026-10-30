package com.talkback.core.webrtc

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.NetworkChangeDetector

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class F8BridgeDiagnosticTest {

    @Test
    fun formatInformation_includesHandleInterfaceIpsAndType() {
        val information =
            NetworkChangeDetector.NetworkInformation(
                "wlan0",
                NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
                NetworkChangeDetector.ConnectionType.CONNECTION_NONE,
                100L,
                arrayOf(
                    NetworkChangeDetector.IPAddress(
                        InetAddress.getByName("192.168.10.23").address,
                    ),
                ),
            )

        val formatted = F8BridgeDiagnostic.formatInformation(information)

        assertTrue(formatted.contains("handle=100"))
        assertTrue(formatted.contains("interface=wlan0"))
        assertTrue(formatted.contains("192.168.10.23"))
        assertTrue(formatted.contains("type=CONNECTION_WIFI"))
    }

    @Test
    fun loggingObserver_forwardsConnectToDelegate() {
        var connected: NetworkChangeDetector.NetworkInformation? = null
        val delegate =
            object : NetworkChangeDetector.Observer() {
                override fun onConnectionTypeChanged(
                    newConnectionType: NetworkChangeDetector.ConnectionType,
                ) = Unit

                override fun onNetworkConnect(networkInfo: NetworkChangeDetector.NetworkInformation) {
                    connected = networkInfo
                }

                override fun onNetworkDisconnect(networkHandle: Long) = Unit

                override fun onNetworkPreference(
                    types: MutableList<NetworkChangeDetector.ConnectionType>,
                    preference: Int,
                ) = Unit
            }
        val information =
            NetworkChangeDetector.NetworkInformation(
                "wlan0",
                NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
                NetworkChangeDetector.ConnectionType.CONNECTION_NONE,
                42L,
                emptyArray(),
            )

        F8BridgeLoggingObserver(delegate).onNetworkConnect(information)

        assertEquals(information, connected)
    }
}
