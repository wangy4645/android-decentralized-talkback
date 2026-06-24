package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.talkback.appprod.R

class MeetingInviteFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_meeting_invite, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val hostLabel = arguments?.getString(ARG_HOST_LABEL).orEmpty()
        val channelTitle = arguments?.getString(ARG_CHANNEL_TITLE).orEmpty()

        view.findViewById<TextView>(R.id.txtInviteTitle).text =
            getString(R.string.meeting_invite_title, hostLabel)
        view.findViewById<TextView>(R.id.txtInviteChannel).text = channelTitle

        view.findViewById<View>(R.id.btnJoinMeeting).setOnClickListener {
            viewModel.acceptIncomingMeeting()
        }
        view.findViewById<View>(R.id.btnDeclineMeeting).setOnClickListener {
            viewModel.rejectIncomingMeeting()
        }
    }

    companion object {
        const val TAG_MEETING_INVITE = "meeting_invite"
        private const val ARG_HOST_LABEL = "host_label"
        private const val ARG_CHANNEL_TITLE = "channel_title"

        fun newInstance(hostLabel: String, channelTitle: String): MeetingInviteFragment =
            MeetingInviteFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_HOST_LABEL, hostLabel)
                    putString(ARG_CHANNEL_TITLE, channelTitle)
                }
            }
    }
}
