package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.talkback.appprod.R
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.data.ChannelMode

class IdentitySettingsFragment : Fragment() {
    private lateinit var store: AppConfigStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_identity, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = AppConfigStore(requireContext())
        bindSettingsToolbar(R.string.settings_identity_title)

        val inputModule = view.findViewById<EditText>(R.id.inputModuleId)
        val inputEndpoint = view.findViewById<EditText>(R.id.inputEndpointId)
        val inputChannelId = view.findViewById<EditText>(R.id.inputChannelId)
        val inputChannelName = view.findViewById<EditText>(R.id.inputChannelName)
        val config = store.load()
        inputModule.setText(config.moduleId)
        inputEndpoint.setText(config.endpointId)
        inputChannelId.setText(config.defaultChannelId)
        inputChannelName.setText(config.channelDisplayName)

        view.findViewById<Button>(R.id.btnSaveIdentity).setOnClickListener {
            val moduleId = inputModule.text.toString().trim().uppercase()
            val endpointId = inputEndpoint.text.toString().trim().uppercase()
            val port = store.load().signalingPort
            val validationError = SettingsActions.validateInput(moduleId, endpointId, port)
            if (validationError != null) {
                Toast.makeText(requireContext(), validationError, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = store.load()
            store.save(
                current.copy(
                    moduleId = moduleId,
                    endpointId = endpointId,
                    defaultChannelId = inputChannelId.text.toString().trim().ifEmpty { "CH-01" },
                    channelDisplayName = inputChannelName.text.toString().trim().ifEmpty { "Maintenance Team" },
                    channelMode = ChannelMode.GROUP_PTT
                )
            )
            TaskProfileManager(requireContext()).syncActiveProfileFromAppConfig()
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            requireParentFragment().childFragmentManager.popBackStack()
        }
    }
}
