package com.talkback.core.webrtc

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import com.talkback.core.network.SelectedOperationalNetworkRegistry
import java.io.File
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkMonitor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class F8TalkbackSelectedNetworkBridgeTest {

    @Test
    fun f8Eg1_factoryInstallsBeforeMonitoringWithoutAssertionError() {
        WebRtcNetworkBridgeInstall.resetForTest()
        val connectivity = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(ConnectivityManager::class.java)
        val registry = SelectedOperationalNetworkRegistry(connectivity) { _, _ -> null }
        WebRtcNetworkBridgeInstall.register(registry)
        WebRtcNetworkBridgeInstall.installBeforeWebRtcInit()
        WebRtcNetworkBridgeInstall.installBeforeWebRtcInit()
        assertNotNull(NetworkMonitor.getInstance())
    }

    @Test
    fun f8Eg2_projectionMapsHandleInterfaceAndIpFromLinkProperties() {
        val network = testNetwork(100)
        val linkProperties = testLinkProperties("wlan0", "192.168.10.23", 24)
        val factsSource =
            object : WebRtcNetworkInformationProjection.FactsSource {
                override fun linkProperties(network: Network): LinkProperties = linkProperties

                override fun networkState(network: Network) =
                    WebRtcNetworkInformationProjection.NetworkStateFacts(
                        connected = true,
                        type = ConnectivityManager.TYPE_WIFI,
                        subtype = 0,
                    )
            }

        val information = WebRtcNetworkInformationProjection.project(factsSource, network)

        assertNotNull(information)
        assertEquals("wlan0", information!!.name)
        assertEquals(network.networkHandle, information.handle)
        assertEquals(NetworkChangeDetector.ConnectionType.CONNECTION_WIFI, information.type)
        assertEquals(1, information.ipAddresses.size)
        assertEquals("192.168.10.23", InetAddress.getByAddress(information.ipAddresses[0].address).hostAddress)
    }

    @Test
    fun f8Eg2_projectionReturnsNullWhenLinkPropertiesMissing() {
        val network = testNetwork(101)
        val factsSource =
            object : WebRtcNetworkInformationProjection.FactsSource {
                override fun linkProperties(network: Network): LinkProperties? = null

                override fun networkState(network: Network) =
                    WebRtcNetworkInformationProjection.NetworkStateFacts(
                        connected = true,
                        type = ConnectivityManager.TYPE_WIFI,
                        subtype = 0,
                    )
            }

        assertNull(WebRtcNetworkInformationProjection.project(factsSource, network))
    }

    @Test
    fun f8Eg3_networkReplacementDisconnectsN1ThenConnectsN2() {
        val connectivity = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(ConnectivityManager::class.java)
        val n1 = testNetwork(201)
        val n2 = testNetwork(202)
        val info1 = wlanInfo("wlan0", n1, "192.168.10.23")
        val info2 = wlanInfo("wlan0", n2, "192.168.10.24")
        val registry =
            SelectedOperationalNetworkRegistry(connectivity) { _, network ->
                when (network) {
                    n1 -> info1
                    n2 -> info2
                    else -> null
                }
            }
        val observer = RecordingObserver()
        val bridge = TalkbackWebRtcNetworkBridge(registry, observer)

        registry.onNetworkAvailable(n1)
        registry.onNetworkLost(n1)
        registry.onNetworkAvailable(n2)

        assertEquals(listOf(n1.networkHandle), observer.disconnectHandles)
        assertEquals(listOf(info1, info2), observer.connects)
        assertEquals(listOf(info2), bridge.getActiveNetworkList())
        bridge.destroy()
    }

    @Test
    fun f8Eg3_staleLostIgnoredAfterReplacement() {
        val connectivity = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(ConnectivityManager::class.java)
        val n1 = testNetwork(301)
        val n2 = testNetwork(302)
        val info1 = wlanInfo("wlan0", n1, "192.168.10.23")
        val info2 = wlanInfo("wlan0", n2, "192.168.10.24")
        val registry =
            SelectedOperationalNetworkRegistry(connectivity) { _, network ->
                when (network) {
                    n1 -> info1
                    n2 -> info2
                    else -> null
                }
            }
        val observer = RecordingObserver()
        val bridge = TalkbackWebRtcNetworkBridge(registry, observer)

        registry.onNetworkAvailable(n1)
        registry.onNetworkAvailable(n2)
        registry.onNetworkLost(n1)

        assertTrue(observer.disconnectHandles.isEmpty())
        assertEquals(listOf(info1, info2), observer.connects)
        assertEquals(listOf(info2), bridge.getActiveNetworkList())
        bridge.destroy()
    }

    @Test
    fun f8Eg4_noSelectedNetworkReturnsEmptyActiveList() {
        val connectivity = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(ConnectivityManager::class.java)
        val registry = SelectedOperationalNetworkRegistry(connectivity) { _, _ -> null }
        val bridge =
            TalkbackWebRtcNetworkBridge(
                registry,
                RecordingObserver(),
            )

        assertTrue(bridge.getActiveNetworkList()!!.isEmpty())
        bridge.destroy()
    }

    @Test
    fun f8Eg5_f8BridgeSourcesDoNotReferenceSyntheticIceCandidate() {
        val moduleRoot = locateModuleRoot()
        val f8Sources =
            listOf(
                "src/main/java/com/talkback/core/network/SelectedOperationalNetworkRegistry.kt",
                "src/main/java/com/talkback/core/webrtc/WebRtcNetworkInformationProjection.kt",
                "src/main/java/com/talkback/core/webrtc/TalkbackWebRtcNetworkBridge.kt",
                "src/main/java/com/talkback/core/webrtc/WebRtcNetworkBridgeInstall.kt",
                "src/main/java/com/talkback/core/webrtc/F8BridgeDiagnostic.kt",
                "src/main/java/com/talkback/core/webrtc/F8BridgeLoggingObserver.kt",
            )
        f8Sources.forEach { relativePath ->
            val text = File(moduleRoot, relativePath).readText()
            assertFalse("$relativePath must not synthesize IceCandidate", "IceCandidate" in text)
        }
    }

    private fun testLinkProperties(
        interfaceName: String,
        ip: String,
        prefixLength: Int,
    ): LinkProperties {
        val link = LinkProperties()
        link.interfaceName = interfaceName
        val add =
            LinkProperties::class.java.getMethod(
                "addLinkAddress",
                LinkAddress::class.java,
            )
        add.invoke(link, testLinkAddress(ip, prefixLength))
        return link
    }

    private fun testNetwork(netId: Int): Network {
        val ctor = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        return ctor.newInstance(netId)
    }

    private fun testLinkAddress(ip: String, prefixLength: Int): LinkAddress {
        val ctor =
            LinkAddress::class.java.getConstructor(
                InetAddress::class.java,
                Int::class.javaPrimitiveType,
            )
        return ctor.newInstance(InetAddress.getByName(ip), prefixLength)
    }

    private fun wlanInfo(
        interfaceName: String,
        network: Network,
        ip: String,
    ): NetworkChangeDetector.NetworkInformation {
        return NetworkChangeDetector.NetworkInformation(
            interfaceName,
            NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
            NetworkChangeDetector.ConnectionType.CONNECTION_NONE,
            network.networkHandle,
            arrayOf(
                NetworkChangeDetector.IPAddress(
                    InetAddress.getByName(ip).address,
                ),
            ),
        )
    }

    private fun locateModuleRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "src/main/java/com/talkback/core/webrtc/WebRtcSharedFactory.kt").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        error("android-board-talkback module root not found")
    }

    private class RecordingObserver : NetworkChangeDetector.Observer() {
        val connects = mutableListOf<NetworkChangeDetector.NetworkInformation>()
        val disconnectHandles = mutableListOf<Long>()

        override fun onConnectionTypeChanged(newConnectionType: NetworkChangeDetector.ConnectionType) = Unit

        override fun onNetworkConnect(networkInfo: NetworkChangeDetector.NetworkInformation) {
            connects.add(networkInfo)
        }

        override fun onNetworkDisconnect(networkHandle: Long) {
            disconnectHandles.add(networkHandle)
        }

        override fun onNetworkPreference(
            types: MutableList<NetworkChangeDetector.ConnectionType>,
            preference: Int,
        ) = Unit
    }
}
