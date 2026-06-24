package com.talkback.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class SubnetResolverTest {
    @Test
    fun hostsInSubnet_slash24_excludesSelf() {
        val address = InetAddress.getByName("192.168.1.10") as Inet4Address
        val mask = InetAddress.getByName("255.255.255.0") as Inet4Address
        val hosts = NetworkInterfaceSubnetProvider.hostsInSubnet(address, mask, 256)
        assertTrue(hosts.isNotEmpty())
        assertTrue(hosts.none { it == "192.168.1.10" })
        assertTrue(hosts.contains("192.168.1.1"))
        assertTrue(hosts.contains("192.168.1.254"))
    }

    @Test
    fun hostsInSubnet_respectsMaxHosts() {
        val address = InetAddress.getByName("10.0.0.5") as Inet4Address
        val mask = InetAddress.getByName("255.255.255.0") as Inet4Address
        val hosts = NetworkInterfaceSubnetProvider.hostsInSubnet(address, mask, 10)
        assertEquals(10, hosts.size)
    }

    @Test
    fun fixedProvider_returnsConfiguredTargets() {
        val provider = FixedSubnetHostProvider("127.0.0.1", listOf("127.0.0.2", "127.0.0.3"))
        assertEquals("127.0.0.1", provider.localHostAddress())
        assertEquals(listOf("127.0.0.2"), provider.sweepTargets(1))
    }
}
