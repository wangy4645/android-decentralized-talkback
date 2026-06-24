package com.talkback.appprod.ui



import android.os.Bundle

import android.view.LayoutInflater

import android.view.View

import android.view.ViewGroup

import android.widget.Button

import android.widget.EditText

import android.widget.RadioButton

import android.widget.Toast

import androidx.fragment.app.Fragment

import com.google.android.material.textfield.TextInputEditText

import com.talkback.appprod.R

import com.talkback.appprod.data.AppConfigStore

import com.talkback.appprod.data.TaskProfile

import com.talkback.appprod.data.TaskProfileManager

import com.talkback.core.discovery.StaticPeerConfig

import com.talkback.core.model.EndpointPriority



class TaskProfileEditFragment : Fragment() {

    private lateinit var manager: TaskProfileManager

    private lateinit var configStore: AppConfigStore

    private lateinit var peerEditor: ManualPeerEditor

    private var editingId: String? = null



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        editingId = arguments?.getString(ARG_PROFILE_ID)

    }



    override fun onCreateView(

        inflater: LayoutInflater,

        container: ViewGroup?,

        savedInstanceState: Bundle?

    ): View = inflater.inflate(R.layout.fragment_task_profile_edit, container, false)



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        manager = TaskProfileManager(requireContext())

        configStore = AppConfigStore(requireContext())

        manager.ensureInitialized()

        bindSettingsToolbar(

            if (editingId == null) R.string.task_profile_add else R.string.task_profile_edit

        )



        peerEditor = ManualPeerEditor(

            root = view.findViewById(R.id.manualPeerEditor),

            localModuleId = { configStore.load().moduleId }

        )

        peerEditor.setHintText(R.string.task_profile_manual_peers_hint)



        val inputName = view.findViewById<EditText>(R.id.inputProfileName)

        val inputSecret = view.findViewById<TextInputEditText>(R.id.inputProfileSecret)

        val inputChannelId = view.findViewById<EditText>(R.id.inputProfileChannelId)

        val inputChannelName = view.findViewById<EditText>(R.id.inputProfileChannelName)

        val inputRfKey = view.findViewById<EditText>(R.id.inputProfileRfKeyLabel)
        val radioNormal = view.findViewById<RadioButton>(R.id.radioPriorityNormal)
        val radioDispatch = view.findViewById<RadioButton>(R.id.radioPriorityDispatch)
        val radioEmergency = view.findViewById<RadioButton>(R.id.radioPriorityEmergency)

        fun bindPriority(priority: EndpointPriority) {
            when (priority) {
                EndpointPriority.NORMAL -> radioNormal.isChecked = true
                EndpointPriority.DISPATCH -> radioDispatch.isChecked = true
                EndpointPriority.EMERGENCY -> radioEmergency.isChecked = true
            }
        }

        fun selectedPriority(): EndpointPriority = when {
            radioEmergency.isChecked -> EndpointPriority.EMERGENCY
            radioDispatch.isChecked -> EndpointPriority.DISPATCH
            else -> EndpointPriority.NORMAL
        }

        editingId?.let { id ->

            manager.getProfile(id)?.let { profile ->

                inputName.setText(profile.name)

                inputSecret.setText(profile.sharedSecret)

                inputChannelId.setText(profile.channelId)

                inputChannelName.setText(profile.channelDisplayName)

                inputRfKey.setText(profile.rfKeyLabel)

                bindPriority(profile.localPriority)

                peerEditor.loadFromJson(profile.staticPeersJson)

            }

        } ?: run {

            val active = manager.activeProfile()

            inputChannelId.setText(active?.channelId ?: "CH-01")

            inputChannelName.setText(active?.channelDisplayName ?: "")

            bindPriority(active?.localPriority ?: EndpointPriority.NORMAL)

            peerEditor.loadFromJson(active?.staticPeersJson ?: "")

        }



        view.findViewById<Button>(R.id.btnSaveProfile).setOnClickListener {

            val name = inputName.text?.toString()?.trim().orEmpty()

            val secret = inputSecret.text?.toString()?.trim().orEmpty()

            val channelId = inputChannelId.text?.toString()?.trim().orEmpty()

            val channelName = inputChannelName.text?.toString()?.trim().orEmpty()

            val rfKey = inputRfKey.text?.toString()?.trim().orEmpty()

            val peers = peerEditor.peers()



            SettingsActions.validateSecret(secret)?.let {

                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()

                return@setOnClickListener

            }



            val profile = editingId?.let { id ->

                val existing = manager.getProfile(id)

                if (existing == null) {

                    Toast.makeText(requireContext(), R.string.task_profile_not_found, Toast.LENGTH_SHORT).show()

                    return@setOnClickListener

                }

                existing.copy(

                    name = name.ifEmpty { "Task" },

                    sharedSecret = secret,

                    channelId = channelId.ifEmpty { "CH-01" },

                    channelDisplayName = channelName.ifEmpty { name },

                    staticPeersJson = StaticPeerConfig.encode(peers),

                    rfKeyLabel = rfKey,

                    localPriority = selectedPriority()

                )

            } ?: TaskProfile.createNew(

                name = name.ifEmpty { "Task" },

                sharedSecret = secret,

                channelId = channelId,

                channelDisplayName = channelName,

                staticPeersJson = StaticPeerConfig.encode(peers),

                rfKeyLabel = rfKey,

                localPriority = selectedPriority()

            )



            manager.upsertProfile(profile)

            if (editingId == null || profile.id == manager.activeProfile()?.id) {

                manager.applyProfile(profile.id, restartService = false)

            }

            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()

            parentFragmentManager.popBackStack()

        }

    }



    companion object {

        private const val ARG_PROFILE_ID = "profileId"



        fun newInstance(profileId: String?): TaskProfileEditFragment =

            TaskProfileEditFragment().apply {

                arguments = Bundle().apply {

                    profileId?.let { putString(ARG_PROFILE_ID, it) }

                }

            }

    }

}


