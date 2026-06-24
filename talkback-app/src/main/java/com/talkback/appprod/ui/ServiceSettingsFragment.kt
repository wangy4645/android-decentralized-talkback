package com.talkback.appprod.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.service.TalkbackForegroundService
import com.talkback.core.discovery.StaticPeerConfig

class ServiceSettingsFragment : Fragment() {
    private lateinit var store: AppConfigStore
    private lateinit var txtState: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var inputLocalIp: EditText
    private lateinit var inputPort: EditText
    private lateinit var peerEditor: ManualPeerEditor

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_service, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = AppConfigStore(requireContext())
        bindSettingsToolbar(R.string.settings_service_title)
        txtState = view.findViewById(R.id.txtState)
        btnStart = view.findViewById(R.id.btnStartService)
        btnStop = view.findViewById(R.id.btnStopService)
        inputLocalIp = view.findViewById(R.id.inputLocalIp)
        inputPort = view.findViewById(R.id.inputSignalingPort)
        peerEditor = ManualPeerEditor(
            root = view.findViewById(R.id.manualPeerEditor),
            localModuleId = { store.load().moduleId },
            onPeersChanged = { persistPeers() }
        )

        val config = store.load()
        inputPort.setText(config.signalingPort.toString())
        peerEditor.loadFromJson(config.staticPeersJson)

        view.findViewById<Button>(R.id.btnCopyLocalIp).setOnClickListener { copyLocalIp() }

        btnStart.setOnClickListener { startService() }
        btnStop.setOnClickListener {
            SettingsActions.stopService(requireContext().applicationContext)
            txtState.text = getString(R.string.settings_state_stopping)
            refreshButtons(running = false)
        }

        refreshButtons(TalkbackApp.get(requireContext()).serviceRunning)
    }

    override fun onResume() {
        super.onResume()
        bindLocalIp()
        refreshButtons(TalkbackApp.get(requireContext()).serviceRunning)
    }

    fun updateServiceState(state: String, detail: String) {
        if (!::txtState.isInitialized) return
        txtState.text = getString(R.string.settings_state_running, state, detail)
        val running = state == TalkbackForegroundService.STATE_RUNNING
        refreshButtons(running)
    }

    private fun bindLocalIp() {
        val ip = LocalNetworkHelper.localIpv4(requireContext())
        inputLocalIp.setText(ip ?: getString(R.string.settings_local_ip_unavailable))
    }

    private fun copyLocalIp() {
        val ip = LocalNetworkHelper.localIpv4(requireContext())
        if (ip.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.settings_local_ip_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("local_ip", ip))
        Toast.makeText(requireContext(), R.string.settings_local_ip_copied, Toast.LENGTH_SHORT).show()
    }

    private fun persistPeers() {
        val current = store.load()
        store.save(current.copy(staticPeersJson = peerEditor.encodePeersJson()))
        TaskProfileManager(requireContext()).syncActiveProfileFromAppConfig()
    }

    private fun startService() {
        val stored = store.load()
        val port = inputPort.text.toString().trim().toIntOrNull()
        val next = stored.copy(
            signalingPort = requireNotNull(port),
            staticPeersJson = peerEditor.encodePeersJson()
        )
        val validationError = SettingsActions.validateServiceConfig(next)
        if (validationError != null) {
            txtState.text = getString(R.string.settings_state_invalid, validationError)
            return
        }
        store.save(next)
        TaskProfileManager(requireContext()).syncActiveProfileFromAppConfig()
        SettingsActions.startService(requireContext().applicationContext, next)
        txtState.text = getString(R.string.settings_state_starting, next.moduleId, next.endpointId)
        refreshButtons(running = true)
    }

    private fun refreshButtons(running: Boolean) {
        if (!::btnStart.isInitialized) return
        btnStart.isVisible = !running
        btnStop.isVisible = running
    }
}
