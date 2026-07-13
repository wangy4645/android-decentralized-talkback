package com.talkback.appprod.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.core.session.ConferenceNetworkIndicator

object NetworkStatusHelper {
    data class Status(
        val labelRes: Int,
        val detailRes: Int,
        val transportRes: Int? = null
    )

    fun current(context: Context): Status {
        val app = TalkbackApp.get(context)
        val runtime = app.runtimeManager.getRuntime()
        if (app.serviceRunning && runtime != null) {
            return when (runtime.conferenceNetworkIndicator()) {
                ConferenceNetworkIndicator.EXCELLENT -> Status(
                    R.string.network_quality_excellent,
                    R.string.settings_network_detail_qos_excellent
                )
                ConferenceNetworkIndicator.GOOD -> Status(
                    R.string.network_quality_good,
                    R.string.settings_network_detail_qos_good
                )
                ConferenceNetworkIndicator.DEGRADED -> Status(
                    R.string.network_quality_poor,
                    R.string.settings_network_detail_qos_poor
                )
                ConferenceNetworkIndicator.UNKNOWN -> Status(
                    R.string.network_quality_na,
                    R.string.settings_network_detail_qos_na
                )
            }
        }
        return deviceLinkStatus(context)
    }

    private fun deviceLinkStatus(context: Context): Status {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Status(R.string.network_quality_na, R.string.settings_network_detail_unavailable)

        val network = cm.activeNetwork
        if (network == null) {
            return Status(
                R.string.network_quality_offline,
                R.string.settings_network_detail_offline
            )
        }
        val caps = cm.getNetworkCapabilities(network) ?: return Status(
            R.string.network_quality_na,
            R.string.settings_network_detail_unavailable
        )
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return Status(
                R.string.network_quality_poor,
                R.string.settings_network_detail_limited
            )
        }
        val transportRes = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                R.string.settings_network_transport_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                R.string.settings_network_transport_ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                R.string.settings_network_transport_cellular
            else -> R.string.settings_network_transport_other
        }
        val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            true
        }
        return if (validated) {
            Status(
                R.string.network_quality_excellent,
                R.string.settings_network_detail_link_ready,
                transportRes
            )
        } else {
            Status(
                R.string.network_quality_good,
                R.string.settings_network_detail_link_connected,
                transportRes
            )
        }
    }
}
