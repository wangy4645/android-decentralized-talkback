package com.talkback.core.webrtc

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.telephony.TelephonyManager
import org.webrtc.NetworkChangeDetector
import java.net.InetAddress

/**
 * Projects Android [Network] facts into WebRTC [NetworkChangeDetector.NetworkInformation].
 * Semantics align with shipped WebRTC NetworkMonitorAutoDetect.networkToInfo().
 */
internal object WebRtcNetworkInformationProjection {

    internal data class NetworkStateFacts(
        val connected: Boolean,
        val type: Int,
        val subtype: Int,
        val underlyingTypeForVpn: Int = -1,
        val underlyingSubtypeForVpn: Int = -1,
    )

    internal interface FactsSource {
        fun linkProperties(network: Network): LinkProperties?

        fun networkState(network: Network): NetworkStateFacts
    }

    fun project(
        connectivityManager: ConnectivityManager,
        network: Network,
    ): NetworkChangeDetector.NetworkInformation? {
        return project(
            object : FactsSource {
                override fun linkProperties(network: Network): LinkProperties? {
                    return connectivityManager.getLinkProperties(network)
                }

                override fun networkState(network: Network): NetworkStateFacts {
                    return readNetworkState(connectivityManager, network)
                }
            },
            network,
        )
    }

    internal fun project(
        source: FactsSource,
        network: Network,
    ): NetworkChangeDetector.NetworkInformation? {
        val linkProperties = source.linkProperties(network) ?: return null
        val interfaceName = linkProperties.interfaceName ?: return null

        val networkState = source.networkState(network)
        val connectionType = connectionType(networkState)
        if (connectionType == NetworkChangeDetector.ConnectionType.CONNECTION_NONE) {
            return null
        }

        val underlyingTypeForVpn = underlyingConnectionTypeForVpn(networkState)
        return NetworkChangeDetector.NetworkInformation(
            interfaceName,
            connectionType,
            underlyingTypeForVpn,
            networkToNetId(network),
            ipAddresses(linkProperties),
        )
    }

    private fun ipAddresses(linkProperties: LinkProperties): Array<NetworkChangeDetector.IPAddress> {
        val linkAddresses: List<LinkAddress> = linkProperties.linkAddresses
        if (linkAddresses.isEmpty()) {
            return emptyArray()
        }
        return linkAddresses.map { NetworkChangeDetector.IPAddress(it.address.address) }.toTypedArray()
    }

    private fun networkToNetId(network: Network): Long = network.networkHandle

    @SuppressLint("MissingPermission")
    private fun readNetworkState(
        connectivityManager: ConnectivityManager,
        network: Network,
    ): NetworkStateFacts {
        val networkInfo = connectivityManager.getNetworkInfo(network)
        if (networkInfo != null && networkInfo.isConnected) {
            return readConnectedNetworkState(connectivityManager, network, networkInfo)
        }

        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        if (capabilities != null &&
            linkProperties != null &&
            linkProperties.linkAddresses.isNotEmpty()
        ) {
            val type =
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                        ConnectivityManager.TYPE_WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        ConnectivityManager.TYPE_MOBILE
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        ConnectivityManager.TYPE_ETHERNET
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                        ConnectivityManager.TYPE_VPN
                    else -> -1
                }
            return NetworkStateFacts(connected = true, type = type, subtype = 0)
        }

        if (networkInfo == null) {
            return NetworkStateFacts(connected = false, type = -1, subtype = -1)
        }
        return readConnectedNetworkState(connectivityManager, network, networkInfo)
    }

    private fun readConnectedNetworkState(
        connectivityManager: ConnectivityManager,
        network: Network,
        networkInfo: NetworkInfo,
    ): NetworkStateFacts {
        if (networkInfo.type != ConnectivityManager.TYPE_VPN) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return networkStateFromInfo(networkInfo)
            }
            return NetworkStateFacts(
                connected = networkInfo.isConnected,
                type = ConnectivityManager.TYPE_VPN,
                subtype = -1,
                underlyingTypeForVpn = networkInfo.type,
                underlyingSubtypeForVpn = networkInfo.subtype,
            )
        }

        if (networkInfo.type == ConnectivityManager.TYPE_VPN) {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null && network == activeNetwork) {
                val underlyingActiveNetworkInfo = connectivityManager.activeNetworkInfo
                if (underlyingActiveNetworkInfo != null &&
                    underlyingActiveNetworkInfo.type != ConnectivityManager.TYPE_VPN
                ) {
                    return NetworkStateFacts(
                        connected = networkInfo.isConnected,
                        type = ConnectivityManager.TYPE_VPN,
                        subtype = -1,
                        underlyingTypeForVpn = underlyingActiveNetworkInfo.type,
                        underlyingSubtypeForVpn = underlyingActiveNetworkInfo.subtype,
                    )
                }
            }
            return NetworkStateFacts(
                connected = networkInfo.isConnected,
                type = ConnectivityManager.TYPE_VPN,
                subtype = -1,
            )
        }

        return networkStateFromInfo(networkInfo)
    }

    private fun networkStateFromInfo(networkInfo: NetworkInfo): NetworkStateFacts {
        if (!networkInfo.isConnected) {
            return NetworkStateFacts(connected = false, type = -1, subtype = -1)
        }
        return NetworkStateFacts(
            connected = true,
            type = networkInfo.type,
            subtype = networkInfo.subtype,
        )
    }

    internal fun connectionType(networkState: NetworkStateFacts): NetworkChangeDetector.ConnectionType {
        if (!networkState.connected) {
            return NetworkChangeDetector.ConnectionType.CONNECTION_NONE
        }
        return when (networkState.type) {
            ConnectivityManager.TYPE_ETHERNET ->
                NetworkChangeDetector.ConnectionType.CONNECTION_ETHERNET
            ConnectivityManager.TYPE_WIFI ->
                NetworkChangeDetector.ConnectionType.CONNECTION_WIFI
            ConnectivityManager.TYPE_WIMAX ->
                NetworkChangeDetector.ConnectionType.CONNECTION_4G
            ConnectivityManager.TYPE_BLUETOOTH ->
                NetworkChangeDetector.ConnectionType.CONNECTION_BLUETOOTH
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_MOBILE_DUN,
            ConnectivityManager.TYPE_MOBILE_HIPRI ->
                cellularConnectionType(networkState.subtype)
            ConnectivityManager.TYPE_VPN ->
                NetworkChangeDetector.ConnectionType.CONNECTION_VPN
            else -> NetworkChangeDetector.ConnectionType.CONNECTION_UNKNOWN
        }
    }

    private fun cellularConnectionType(subtype: Int): NetworkChangeDetector.ConnectionType {
        return when (subtype) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM ->
                NetworkChangeDetector.ConnectionType.CONNECTION_2G
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA ->
                NetworkChangeDetector.ConnectionType.CONNECTION_3G
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN ->
                NetworkChangeDetector.ConnectionType.CONNECTION_4G
            TelephonyManager.NETWORK_TYPE_NR ->
                NetworkChangeDetector.ConnectionType.CONNECTION_5G
            else -> NetworkChangeDetector.ConnectionType.CONNECTION_UNKNOWN_CELLULAR
        }
    }

    internal fun underlyingConnectionTypeForVpn(
        networkState: NetworkStateFacts,
    ): NetworkChangeDetector.ConnectionType {
        if (networkState.type != ConnectivityManager.TYPE_VPN) {
            return NetworkChangeDetector.ConnectionType.CONNECTION_NONE
        }
        return connectionType(
            NetworkStateFacts(
                connected = networkState.connected,
                type = networkState.underlyingTypeForVpn,
                subtype = networkState.underlyingSubtypeForVpn,
            ),
        )
    }
}
