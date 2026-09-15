package com.talkback.core.webrtc

import com.talkback.core.util.TalkbackLog
import java.net.InetAddress
import org.webrtc.NetworkChangeDetector

/**
 * F8 propagation audit diagnostics (behavior-neutral).
 * Grep: F8_BRIDGE_ | F8_REGISTRY_ | F8_DETECTOR_ | F8_OBSERVER_ | F8_NATIVE_
 */
internal object F8BridgeDiagnostic {

    fun registryPublishLost(
        networkId: String,
        handle: Long,
        revision: Long,
    ) {
        TalkbackLog.i(
            "F8_REGISTRY_PUBLISH event=lost networkId=$networkId revision=$revision handle=$handle",
        )
    }

    fun factoryInstalled(
        factoryClass: String,
        installOrdinal: Int,
        beforeMonitoring: Boolean,
    ) {
        TalkbackLog.i(
            "F8_BRIDGE_FACTORY_INSTALLED factoryClass=$factoryClass " +
                "installOrdinal=$installOrdinal beforeMonitoring=$beforeMonitoring",
        )
    }

    fun registryPublish(
        event: String,
        networkId: String,
        information: NetworkChangeDetector.NetworkInformation,
        revision: Long,
    ) {
        TalkbackLog.i(
            "F8_REGISTRY_PUBLISH event=$event networkId=$networkId revision=$revision " +
                formatInformation(information),
        )
    }

    fun registryProjectionMiss(
        event: String,
        networkId: String,
        handle: Long,
    ) {
        TalkbackLog.i(
            "F8_REGISTRY_PUBLISH event=$event projection=miss networkId=$networkId handle=$handle",
        )
    }

    fun detectorCreated(
        initialActiveNetworkCount: Int,
        registryRevision: Long,
        registryHasSnapshot: Boolean,
    ) {
        TalkbackLog.i(
            "F8_DETECTOR_CREATED initialActiveNetworkCount=$initialActiveNetworkCount " +
                "registryRevision=$registryRevision registryHasSnapshot=$registryHasSnapshot",
        )
    }

    fun detectorActiveList(
        count: Int,
        revision: Long,
        information: NetworkChangeDetector.NetworkInformation?,
    ) {
        val facts =
            information?.let { formatInformation(it) }
                ?: "handle=none interface=none ips=none type=none"
        TalkbackLog.i(
            "F8_DETECTOR_ACTIVE_LIST count=$count revision=$revision $facts",
        )
    }

    fun observerNetworkConnect(information: NetworkChangeDetector.NetworkInformation) {
        TalkbackLog.i(
            "F8_OBSERVER_NETWORK_CONNECT ${formatInformation(information)}",
        )
    }

    fun observerNetworkDisconnect(handle: Long) {
        TalkbackLog.i("F8_OBSERVER_NETWORK_DISCONNECT handle=$handle")
    }

    fun formatInformation(information: NetworkChangeDetector.NetworkInformation): String {
        return "handle=${information.handle} interface=${information.name} " +
            "ips=${formatIpAddresses(information.ipAddresses)} type=${information.type}"
    }

    fun formatIpAddresses(addresses: Array<NetworkChangeDetector.IPAddress>): String {
        if (addresses.isEmpty()) return "[]"
        return addresses.joinToString(prefix = "[", postfix = "]") { ip ->
            runCatching { InetAddress.getByAddress(ip.address).hostAddress }
                .getOrElse { "?" }
        }
    }
}
