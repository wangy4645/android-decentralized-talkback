package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.data.TaskProfile
import com.talkback.appprod.data.TaskProfileManager

class TaskProfilesListFragment : Fragment() {
    private lateinit var manager: TaskProfileManager
    private lateinit var adapter: TaskProfileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_task_profiles, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        manager = TaskProfileManager(requireContext())
        manager.ensureInitialized()
        bindSettingsToolbar(R.string.task_profiles_title)

        adapter = TaskProfileAdapter(
            activeId = manager.activeProfile()?.id.orEmpty(),
            onEdit = { profile ->
                (parentFragment as? SettingsFragment)?.openDetail(
                    TaskProfileEditFragment.newInstance(profile.id)
                )
            },
            onSwitch = { profile -> confirmSwitch(profile) },
            onDelete = { profile -> deleteProfile(profile) }
        )

        view.findViewById<RecyclerView>(R.id.listTaskProfiles).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@TaskProfilesListFragment.adapter
        }

        view.findViewById<Button>(R.id.btnAddTaskProfile).setOnClickListener {
            (parentFragment as? SettingsFragment)?.openDetail(TaskProfileEditFragment.newInstance(null))
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val activeId = manager.activeProfile()?.id.orEmpty()
        adapter.submit(manager.listProfiles(), activeId)
    }

    private fun confirmSwitch(profile: TaskProfile) {
        if (profile.id == manager.activeProfile()?.id) {
            Toast.makeText(requireContext(), R.string.task_profile_already_active, Toast.LENGTH_SHORT).show()
            return
        }
        TaskProfileSwitchDialog.show(requireContext(), profile) {
            val restart = TalkbackApp.get(requireContext()).serviceRunning
            manager.applyProfile(profile.id, restartService = restart)
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.task_profile_switched, Toast.LENGTH_SHORT).show()
                    refreshList()
                }
                .onFailure {
                    Toast.makeText(requireContext(), it.message ?: "switch failed", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun deleteProfile(profile: TaskProfile) {
        if (!manager.deleteProfile(profile.id)) {
            Toast.makeText(requireContext(), R.string.task_profile_delete_last, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), R.string.task_profile_deleted, Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private class TaskProfileAdapter(
        private var activeId: String,
        private val onEdit: (TaskProfile) -> Unit,
        private val onSwitch: (TaskProfile) -> Unit,
        private val onDelete: (TaskProfile) -> Unit
    ) : RecyclerView.Adapter<TaskProfileAdapter.Holder>() {
        private val items = mutableListOf<TaskProfile>()

        fun submit(profiles: List<TaskProfile>, activeProfileId: String) {
            items.clear()
            items.addAll(profiles)
            activeId = activeProfileId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_profile_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtName = itemView.findViewById<TextView>(R.id.txtProfileName)
            private val txtActive = itemView.findViewById<TextView>(R.id.txtProfileActive)
            private val txtMeta = itemView.findViewById<TextView>(R.id.txtProfileMeta)
            private val btnEdit = itemView.findViewById<TextView>(R.id.btnEditProfile)
            private val btnSwitch = itemView.findViewById<TextView>(R.id.btnSwitchProfile)
            private val btnDelete = itemView.findViewById<TextView>(R.id.btnDeleteProfile)

            fun bind(profile: TaskProfile) {
                val isActive = profile.id == activeId
                txtName.text = profile.name
                txtActive.isVisible = isActive
                val rfHint = profile.rfKeyLabel.takeIf { it.isNotBlank() }
                    ?.let { itemView.context.getString(R.string.task_profile_rf_meta, it) }
                    ?: ""
                txtMeta.text = itemView.context.getString(
                    R.string.task_profile_meta,
                    profile.channelId,
                    profile.channelDisplayName,
                    rfHint
                ).trim()
                val ctx = itemView.context
                if (isActive) {
                    btnSwitch.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_muted))
                    btnSwitch.setBackgroundResource(R.drawable.bg_task_action_switch_disabled)
                    btnSwitch.isClickable = false
                    btnSwitch.isFocusable = false
                } else {
                    btnSwitch.setTextColor(ContextCompat.getColor(ctx, R.color.tb_success))
                    btnSwitch.setBackgroundResource(R.drawable.bg_task_action_switch)
                    btnSwitch.isClickable = true
                    btnSwitch.isFocusable = true
                }
                btnSwitch.alpha = 1f
                btnEdit.setTextColor(ContextCompat.getColor(ctx, R.color.tb_monitor))
                btnDelete.setTextColor(ContextCompat.getColor(ctx, R.color.tb_danger))
                btnEdit.setOnClickListener { onEdit(profile) }
                btnSwitch.setOnClickListener { if (!isActive) onSwitch(profile) }
                btnDelete.setOnClickListener { onDelete(profile) }
            }
        }
    }
}
