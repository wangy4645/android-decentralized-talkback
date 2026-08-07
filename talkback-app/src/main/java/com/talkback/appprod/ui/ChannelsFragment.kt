package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import kotlinx.coroutines.launch

class ChannelsFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    private enum class Tab { CHANNELS, MESSAGES }

    private var selectedTab = Tab.CHANNELS
    private var tabChannels: TextView? = null
    private var tabMessages: TextView? = null
    private var indicatorChannels: View? = null
    private var indicatorMessages: View? = null
    private var unreadBadge: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_channels_host, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            selectedTab = runCatching {
                Tab.valueOf(savedInstanceState.getString(STATE_TAB, Tab.CHANNELS.name))
            }.getOrDefault(Tab.CHANNELS)
        }

        tabChannels = view.findViewById(R.id.tabChannels)
        tabMessages = view.findViewById(R.id.tabMessages)
        indicatorChannels = view.findViewById(R.id.tabChannelsIndicator)
        indicatorMessages = view.findViewById(R.id.tabMessagesIndicator)
        unreadBadge = view.findViewById(R.id.txtMessagesUnreadBadge)

        tabChannels?.setOnClickListener { selectTab(Tab.CHANNELS) }
        tabMessages?.setOnClickListener { selectTab(Tab.MESSAGES) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conversationUi.collect { state ->
                    if (state.totalUnread > 0) {
                        unreadBadge?.isVisible = true
                        unreadBadge?.text = state.totalUnread.toString()
                    } else {
                        unreadBadge?.isVisible = false
                    }
                }
            }
        }

        applyTabUi()
    }

    fun showMessagesTab() {
        if (!isAdded) return
        selectedTab = Tab.MESSAGES
        view?.post { applyTabUi() } ?: applyTabUi()
    }

    private fun selectTab(tab: Tab) {
        selectedTab = tab
        applyTabUi()
    }

    private fun applyTabUi() {
        val channelsSelected = selectedTab == Tab.CHANNELS
        tabChannels?.setTextColor(color(if (channelsSelected) R.color.tb_primary else R.color.tb_text_secondary))
        tabChannels?.alpha = if (channelsSelected) 1f else 0.45f
        indicatorChannels?.setBackgroundColor(
            color(if (channelsSelected) R.color.tb_primary else android.R.color.transparent)
        )

        val messagesSelected = selectedTab == Tab.MESSAGES
        tabMessages?.setTextColor(color(if (messagesSelected) R.color.tb_primary else R.color.tb_text_secondary))
        tabMessages?.alpha = if (messagesSelected) 1f else 0.45f
        indicatorMessages?.setBackgroundColor(
            color(if (messagesSelected) R.color.tb_primary else android.R.color.transparent)
        )

        val tag = if (channelsSelected) TAG_CHANNEL_STATUS else TAG_MESSAGES_LIST
        if (childFragmentManager.findFragmentByTag(tag)?.isAdded == true) {
            return
        }
        val fragment: Fragment = if (channelsSelected) {
            ChannelStatusFragment()
        } else {
            MessagesListFragment()
        }
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.channelsChildContainer, fragment, tag)
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, selectedTab.name)
    }

    companion object {
        private const val STATE_TAB = "channels_tab"
        private const val TAG_CHANNEL_STATUS = "channel_status"
        private const val TAG_MESSAGES_LIST = "messages_list"
    }
}
