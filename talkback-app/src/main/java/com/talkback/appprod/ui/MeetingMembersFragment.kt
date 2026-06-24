package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import kotlinx.coroutines.launch

class MeetingMembersFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }
    private var showMutedOnly = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_meeting_members, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtSettingsTitle).setText(R.string.meeting_members_title)
        view.findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            (parentFragment as? MeetingFragment)?.hideSubPage()
        }

        val tabAll = view.findViewById<TextView>(R.id.tabMembersAll)
        val tabMuted = view.findViewById<TextView>(R.id.tabMembersMuted)
        tabAll.setOnClickListener {
            showMutedOnly = false
            updateTabs(tabAll, tabMuted)
            viewModel.refresh()
        }
        tabMuted.setOnClickListener {
            showMutedOnly = true
            updateTabs(tabMuted, tabAll)
            viewModel.refresh()
        }

        view.findViewById<View>(R.id.btnInviteMembers).setOnClickListener {
            (parentFragment as? MeetingFragment)?.showSubPage(InviteMembersFragment())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> bindMembers(view, state) }
            }
        }
    }

    private fun updateTabs(selected: TextView, other: TextView) {
        selected.setBackgroundResource(R.drawable.bg_mode_segment_selected)
        selected.setTextColor(ContextCompat.getColor(requireContext(), R.color.tb_primary))
        other.background = null
        other.setTextColor(ContextCompat.getColor(requireContext(), R.color.tb_text_secondary))
    }

    private fun bindMembers(view: View, state: TalkUiState) {
        val container = view.findViewById<LinearLayout>(R.id.containerMeetingMembers)
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val items = if (!showMutedOnly) {
            state.endpoints
        } else {
            state.endpoints.filter { it.isLocal && state.conferenceMuted }
        }
        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_endpoint, container, false)
            EndpointAdapter.bindHolder(EndpointAdapter.VH(row), item)
            container.addView(row)
        }
    }
}
