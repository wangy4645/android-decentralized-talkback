package com.talkback.core.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Resolves local IPv4 host and sweep targets from active network interfaces.
 */
interface SubnetHostProvider {
    fun localHostAddress(): String?
    fun sweepTargets(maxHosts: Int): List<String>
}

class NetworkInterfaceSubnetProvider : SubnetHostProvider {
    override fun localHostAddress(): String? = selectInterface()?.let { (addr, _) -> addr.hostAddress }

    override fun sweepTargets(maxHosts: Int): List<String> {
        val selected = selectInterface() ?: return emptyList()
        val (address, mask) = selected
        return hostsInSubnet(address, mask, maxHosts)
    }

    private fun selectInterface(): Pair<Inet4Address, Inet4Address>? {
        val candidates = mutableListOf<Triple<Inet4Address, Inet4Address, Int>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val netIf = interfaces.nextElement()
            if (!netIf.isUp || netIf.isLoopback) continue
            val addresses = netIf.interfaceAddresses
            for (ifaceAddr in addresses) {
                val addr = ifaceAddr.address
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val mask = prefixToMask(ifaceAddr.networkPrefixLength) ?: continue
                candidates += Triple(addr, mask, scoreInterface(netIf.name, addr))
            }
        }
        return candidates.maxByOrNull { it.third }?.let { it.first to it.second }
    }

    /** Prefer Wi‑Fi/Ethernet LAN over cellular when both are up (fixes phone gossip sweep). */
    private fun scoreInterface(interfaceName: String, address: Inet4Address): Int {
        val host = address.hostAddress.orEmpty()
        var score = 0
        val lower = interfaceName.lowercase()
        when {
            lower.contains("wlan") || lower.contains("wifi") || lower.contains("eth") -> score += 100
            lower.contains("rmnet") || lower.contains("ccmni") || lower.contains("pdp") -> score -= 80
        }
        when {
            host.startsWith("192.168.") -> score += 50
            host.startsWith("10.") -> score += 40
            host.startsWith("172.") -> score += 30
            host.startsWith("100.") -> score -= 60
        }
        return score
    }

    private fun prefixToMask(prefixLength: Short): Inet4Address? {
        if (prefixLength <= 0 || prefixLength > 32) return null
        val mask = if (prefixLength.toInt() == 32) {
            0xFFFFFFFF.toInt()
        } else {
            (-1 shl (32 - prefixLength))
        }
        return runCatching {
            InetAddress.getByAddress(
                byteArrayOf(
                    ((mask ushr 24) and 0xFF).toByte(),
                    ((mask ushr 16) and 0xFF).toByte(),
                    ((mask ushr 8) and 0xFF).toByte(),
                    (mask and 0xFF).toByte()
                )
            ) as Inet4Address
        }.getOrNull()
    }

    companion object {
        fun hostsInSubnet(
            address: Inet4Address,
            mask: Inet4Address,
            maxHosts: Int
        ): List<String> {
            val ip = address.address.toIntBigEndian()
            val netMask = mask.address.toIntBigEndian()
            val network = ip and netMask
            val broadcast = network or netMask.inv()
            val hosts = mutableListOf<String>()
            var host = network + 1
            while (host < broadcast && hosts.size < maxHosts) {
                val hostIp = intToInet4(host)
                if (hostIp != address.hostAddress) {
                    hosts += hostIp
                }
                host++
            }
            return hosts
        }

        private fun ByteArray.toIntBigEndian(): Int =
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)

        private fun intToInet4(value: Int): String =
            "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }
}

/** Test / deterministic subnet provider. */
class FixedSubnetHostProvider(
    private val localHost: String,
    private val targets: List<String>
) : SubnetHostProvider {
    override fun localHostAddress(): String? = localHost
    override fun sweepTargets(maxHosts: Int): List<String> = targets.take(maxHosts)
}
