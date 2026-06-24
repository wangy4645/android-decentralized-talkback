package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import kotlinx.coroutines.launch

class ContactsFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(
            ContactActionBottomSheet.REQUEST_PRIVATE_CALL,
            this
        ) { _, bundle ->
            val remoteKey = bundle.getString("key").orEmpty()
            val remoteLabel = bundle.getString("label").orEmpty()
            val teamName = bundle.getString("team").orEmpty()
            val online = bundle.getBoolean("online", false)
            if (!online) {
                Toast.makeText(requireContext(), R.string.call_peer_offline, Toast.LENGTH_SHORT).show()
                return@setFragmentResultListener
            }
            startPrivateCall(remoteKey, remoteLabel, teamName)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_contacts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.tabGroups).setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }

        val txtOnlineHeader = view.findViewById<TextView>(R.id.txtOnlineHeader)
        val txtOfflineHeader = view.findViewById<TextView>(R.id.txtOfflineHeader)
        val containerOnline = view.findViewById<LinearLayout>(R.id.containerOnline)
        val containerOffline = view.findViewById<LinearLayout>(R.id.containerOffline)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val online = state.endpoints.filter { it.status != EndpointStatus.OFFLINE }
                    val offline = state.endpoints.filter { it.status == EndpointStatus.OFFLINE }
                    txtOnlineHeader.text = getString(R.string.contacts_section_online, online.size)
                    txtOfflineHeader.text = getString(R.string.contacts_section_offline, offline.size)
                    txtOnlineHeader.isVisible = online.isNotEmpty() || offline.isNotEmpty()
                    txtOfflineHeader.isVisible = offline.isNotEmpty()
                    renderSection(containerOnline, online)
                    renderSection(containerOffline, offline)
                }
            }
        }
    }

    private fun renderSection(container: LinearLayout, items: List<EndpointUiItem>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.isVisible = false
            return
        }
        container.isVisible = true
        val inflater = LayoutInflater.from(container.context)
        items.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_endpoint_in_card, container, false)
            ContactEndpointBinder.bind(
                row,
                item,
                showDivider = index < items.lastIndex,
                teamName = viewModel.teamDisplayName()
            )
            row.findViewById<View>(R.id.endpointRowContent).setOnClickListener {
                onEndpointClicked(item)
            }
            container.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncServiceState()
        viewModel.startPolling()
        viewModel.refresh()
    }

    override fun onPause() {
        viewModel.stopPolling()
        super.onPause()
    }

    private fun onEndpointClicked(item: EndpointUiItem) {
        if (item.displayLabel.endsWith(getString(R.string.you_suffix))) return
        viewModel.syncServiceState()
        if (!viewModel.isServiceReady()) {
            Toast.makeText(requireContext(), R.string.service_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        ContactActionBottomSheet
            .newInstance(item, viewModel.teamDisplayName())
            .show(parentFragmentManager, "contact_actions")
    }

    private fun startPrivateCall(remoteKey: String, remoteLabel: String, teamName: String) {
        viewModel.syncServiceState()
        when (val err = viewModel.placeCall(remoteKey)) {
            null -> view?.post {
                (activity as? MainActivity)?.showCallScreen(remoteKey, remoteLabel, teamName)
            }
            "SERVICE_STOPPED" -> Toast.makeText(
                requireContext(),
                R.string.service_not_running,
                Toast.LENGTH_SHORT
            ).show()
            "UNREACHABLE" -> Toast.makeText(
                requireContext(),
                R.string.call_failed_unreachable,
                Toast.LENGTH_SHORT
            ).show()
            "BUSY" -> Toast.makeText(
                requireContext(),
                R.string.call_failed_busy,
                Toast.LENGTH_SHORT
            ).show()
            else -> Toast.makeText(
                requireContext(),
                R.string.call_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
