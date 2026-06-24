package com.talkback.appprod.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.talkback.appprod.R
import com.talkback.core.discovery.StaticPeerConfig
import com.talkback.core.discovery.StaticPeerEntry
import com.talkback.core.model.ModuleId

/**
 * Form-based manual peer editor. JSON import is hidden under Advanced for field engineers only.
 */
class ManualPeerEditor(
    root: View,
    private val localModuleId: () -> String,
    private val onPeersChanged: ((List<StaticPeerEntry>) -> Unit)? = null
) {
    private val inputPeerModuleId = root.findViewById<EditText>(R.id.inputPeerModuleId)
    private val inputPeerHost = root.findViewById<EditText>(R.id.inputPeerHost)
    private val inputPeerPort = root.findViewById<EditText>(R.id.inputPeerPort)
    private val containerPeerList = root.findViewById<LinearLayout>(R.id.containerPeerList)
    private val txtPeersEmpty = root.findViewById<TextView>(R.id.txtPeersEmpty)
    private val inputStaticPeersJson = root.findViewById<EditText>(R.id.inputStaticPeersJson)
    private val groupAdvanced = root.findViewById<View>(R.id.groupAdvanced)
    private val btnToggleAdvanced = root.findViewById<TextView>(R.id.btnToggleAdvanced)
    private val hintText = root.findViewById<TextView>(R.id.txtManualPeersHint)

    private val peers = mutableListOf<StaticPeerEntry>()
    private var advancedVisible = false

    init {
        root.findViewById<Button>(R.id.btnAddPeer).setOnClickListener { addPeer() }
        root.findViewById<Button>(R.id.btnImportPeersJson).setOnClickListener { importPeersFromJson() }
        btnToggleAdvanced.setOnClickListener {
            advancedVisible = !advancedVisible
            groupAdvanced.isVisible = advancedVisible
            btnToggleAdvanced.setText(
                if (advancedVisible) R.string.settings_hide_advanced else R.string.settings_show_advanced
            )
            if (advancedVisible) syncAdvancedJson()
        }
    }

    fun setHintText(resId: Int) {
        hintText.setText(resId)
    }

    fun setPeers(entries: List<StaticPeerEntry>) {
        peers.clear()
        peers.addAll(entries)
        renderPeerList()
        syncAdvancedJson()
    }

    fun loadFromJson(json: String) {
        setPeers(StaticPeerConfig.parse(json))
    }

    fun peers(): List<StaticPeerEntry> = peers.toList()

    fun encodePeersJson(): String = StaticPeerConfig.encode(peers)

    private fun addPeer() {
        val moduleId = inputPeerModuleId.text.toString().trim().uppercase()
        val host = inputPeerHost.text.toString().trim()
        val port = inputPeerPort.text.toString().trim().toIntOrNull()
        val local = localModuleId()

        SettingsActions.validatePeerModuleId(moduleId, local)?.let {
            toast(it)
            return
        }
        SettingsActions.validatePeerHost(host)?.let {
            toast(it)
            return
        }
        SettingsActions.validatePeerPort(port)?.let {
            toast(it)
            return
        }
        if (peers.any { it.moduleId.value == moduleId }) {
            toast(hintText.context.getString(R.string.settings_peer_duplicate, moduleId))
            return
        }

        peers += StaticPeerEntry(ModuleId(moduleId), host, requireNotNull(port))
        inputPeerModuleId.text?.clear()
        inputPeerHost.text?.clear()
        inputPeerPort.text?.clear()
        renderPeerList()
        syncAdvancedJson()
        notifyChanged()
        toast(hintText.context.getString(R.string.settings_peer_added))
    }

    private fun removePeer(moduleId: String) {
        peers.removeAll { it.moduleId.value == moduleId }
        renderPeerList()
        syncAdvancedJson()
        notifyChanged()
        toast(hintText.context.getString(R.string.settings_peer_removed))
    }

    private fun importPeersFromJson() {
        val imported = StaticPeerConfig.parse(inputStaticPeersJson.text.toString().trim())
        if (imported.isEmpty() && inputStaticPeersJson.text.toString().trim().isNotEmpty()) {
            toast(hintText.context.getString(R.string.settings_import_peers_json_failed))
            return
        }
        val local = localModuleId().trim().uppercase()
        peers.clear()
        peers.addAll(imported.filter { it.moduleId.value != local })
        renderPeerList()
        syncAdvancedJson()
        notifyChanged()
        toast(hintText.context.getString(R.string.settings_import_peers_json_ok, peers.size))
    }

    private fun renderPeerList() {
        containerPeerList.removeAllViews()
        val inflater = LayoutInflater.from(hintText.context)
        peers.forEach { peer ->
            val row = inflater.inflate(R.layout.item_static_peer_row, containerPeerList, false)
            row.findViewById<TextView>(R.id.txtPeerSummary).text =
                hintText.context.getString(
                    R.string.settings_peer_summary,
                    peer.moduleId.value,
                    peer.host,
                    peer.port
                )
            row.findViewById<Button>(R.id.btnRemovePeer).setOnClickListener {
                removePeer(peer.moduleId.value)
            }
            containerPeerList.addView(row)
        }
        txtPeersEmpty.isVisible = peers.isEmpty()
        containerPeerList.isVisible = peers.isNotEmpty()
    }

    private fun syncAdvancedJson() {
        inputStaticPeersJson.setText(StaticPeerConfig.encode(peers))
    }

    private fun notifyChanged() {
        onPeersChanged?.invoke(peers.toList())
    }

    private fun toast(message: String) {
        Toast.makeText(hintText.context, message, Toast.LENGTH_SHORT).show()
    }
}
