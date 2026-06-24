package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.talkback.appprod.R

class NetworkSettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_network, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindSettingsToolbar(R.string.settings_network_status_title)
    }

    override fun onResume() {
        super.onResume()
        bindStatus()
    }

    private fun bindStatus() {
        val root = view ?: return
        val status = NetworkStatusHelper.current(requireContext())
        root.findViewById<TextView>(R.id.txtNetworkQuality).setText(status.labelRes)
        root.findViewById<TextView>(R.id.txtNetworkDetail).setText(status.detailRes)
        val transport = root.findViewById<TextView>(R.id.txtNetworkTransport)
        val transportRes = status.transportRes
        if (transportRes == null) {
            transport.isVisible = false
        } else {
            transport.isVisible = true
            transport.text = getString(R.string.settings_network_transport_label, getString(transportRes))
        }
    }
}
