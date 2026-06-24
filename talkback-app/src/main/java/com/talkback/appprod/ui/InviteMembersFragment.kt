package com.talkback.appprod.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.talkback.appprod.R

class InviteMembersFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }
    private val selectedModules = linkedSetOf<String>()
    private var candidates: List<EndpointUiItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_invite_members, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtSettingsTitle).setText(R.string.meeting_invite_members)
        view.findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            (parentFragment as? MeetingFragment)?.hideSubPage()
        }

        val inputSearch = view.findViewById<EditText>(R.id.inputInviteSearch)
        val btnInvite = view.findViewById<View>(R.id.btnInviteSelected)

        inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderCandidates(view, s?.toString().orEmpty())
            }
        })

        btnInvite.setOnClickListener {
            if (selectedModules.isEmpty()) {
                Toast.makeText(requireContext(), R.string.meeting_invite_none, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = viewModel.inviteMeetingMembers(selectedModules.toList())
            result.onSuccess { count ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.meeting_invite_success, count),
                    Toast.LENGTH_SHORT
                ).show()
                (parentFragment as? MeetingFragment)?.hideSubPage()
            }.onFailure {
                Toast.makeText(requireContext(), R.string.meeting_invite_failed, Toast.LENGTH_SHORT).show()
            }
        }

        candidates = viewModel.inviteCandidates()
        renderCandidates(view, "")
    }

    private fun renderCandidates(view: View, query: String) {
        val container = view.findViewById<LinearLayout>(R.id.containerInviteCandidates)
        val btnInvite = view.findViewById<MaterialButton>(R.id.btnInviteSelected)
        container.removeAllViews()
        val filtered = candidates.filter {
            query.isBlank() || it.displayLabel.contains(query, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                setText(R.string.meeting_invite_none)
                setTextColor(resources.getColor(R.color.tb_text_muted, null))
                textSize = 12f
            }
            container.addView(empty)
        }
        val inflater = LayoutInflater.from(requireContext())
        filtered.forEach { item ->
            val moduleId = moduleIdFromKey(item.key)
            val row = inflater.inflate(R.layout.item_meeting_invite_row, container, false)
            row.findViewById<TextView>(R.id.txtInviteLabel).text = item.displayLabel
            row.findViewById<TextView>(R.id.txtInviteStatus).text =
                getString(R.string.status_online)
            val check = row.findViewById<CheckBox>(R.id.checkInvite)
            check.isChecked = moduleId in selectedModules
            row.setOnClickListener {
                check.isChecked = !check.isChecked
            }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedModules.add(moduleId) else selectedModules.remove(moduleId)
                btnInvite.text = getString(R.string.meeting_invite_selected, selectedModules.size)
            }
            container.addView(row)
        }
        btnInvite.text = getString(R.string.meeting_invite_selected, selectedModules.size)
    }

    private fun moduleIdFromKey(key: String): String {
        val dash = key.indexOf('-')
        return if (dash <= 0) key.uppercase() else key.substring(0, dash).uppercase()
    }
}
