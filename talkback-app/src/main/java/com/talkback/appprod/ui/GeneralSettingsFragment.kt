package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.talkback.appprod.R
import com.talkback.appprod.data.AppConfigStore

class GeneralSettingsFragment : Fragment() {
    private lateinit var store: AppConfigStore
    private var suppressToggleCallbacks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_general, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = AppConfigStore(requireContext())
        bindSettingsToolbar(R.string.settings_general_title)

        val groupSystem = view.findViewById<LinearLayout>(R.id.groupSystem)
        val groupPtt = view.findViewById<LinearLayout>(R.id.groupPtt)
        val groupOther = view.findViewById<LinearLayout>(R.id.groupOther)

        val config = store.load()
        suppressToggleCallbacks = true

        groupSystem.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_auto_start),
            checked = config.autoStartOnBoot,
            showDivider = true,
            onCheckedChange = { checked -> persist(autoStart = checked) }
        )

        groupSystem.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_auto_redial),
            checked = config.autoRedial,
            showDivider = true,
            onCheckedChange = { checked -> persist(autoRedial = checked) }
        )

        groupSystem.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_auto_accept_calls),
            footer = getString(R.string.settings_auto_accept_calls_footer),
            checked = config.autoAcceptIncoming,
            showDivider = true,
            onCheckedChange = { checked -> persist(autoAcceptIncoming = checked) }
        )

        groupSystem.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_keep_screen_on_title),
            value = getString(R.string.settings_keep_screen_on_value),
            showChevron = true,
            onClick = { showComingSoon() }
        )

        groupPtt.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_ptt_hold_timeout_title),
            value = getString(R.string.settings_ptt_hold_timeout_value),
            showChevron = true,
            showDivider = true,
            onClick = { showComingSoon() }
        )

        groupPtt.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_ptt_lock_mode_title),
            value = getString(R.string.settings_ptt_lock_mode_value),
            showChevron = true,
            showDivider = true,
            onClick = { showComingSoon() }
        )

        groupPtt.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_meeting_auto_join),
            footer = getString(R.string.settings_meeting_auto_join_footer),
            checked = config.meetingAutoJoin,
            showDivider = true,
            onCheckedChange = { checked -> persist(meetingAutoJoin = checked) }
        )

        groupPtt.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_release_to_listen_title),
            footer = getString(R.string.settings_release_to_listen_footer),
            checked = true,
            enabled = false
        )

        groupOther.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_language_title),
            value = getString(R.string.settings_language_value),
            showChevron = true,
            showDivider = true,
            onClick = { showComingSoon() }
        )

        groupOther.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_time_format_title),
            value = getString(R.string.settings_time_format_value),
            showChevron = true,
            showDivider = true,
            onClick = { showComingSoon() }
        )

        groupOther.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_clear_cache_title),
            value = getString(R.string.settings_clear_cache_value),
            showChevron = true,
            onClick = { showComingSoon() }
        )

        suppressToggleCallbacks = false
    }

    private fun persist(
        autoStart: Boolean? = null,
        autoRedial: Boolean? = null,
        autoAcceptIncoming: Boolean? = null,
        meetingAutoJoin: Boolean? = null
    ) {
        if (suppressToggleCallbacks || !isAdded) return
        val current = store.load()
        store.save(
            current.copy(
                autoStartOnBoot = autoStart ?: current.autoStartOnBoot,
                autoRedial = autoRedial ?: current.autoRedial,
                autoAcceptIncoming = autoAcceptIncoming ?: current.autoAcceptIncoming,
                meetingAutoJoin = meetingAutoJoin ?: current.meetingAutoJoin
            )
        )
        if (autoAcceptIncoming != null) {
            Toast.makeText(requireContext(), R.string.settings_restart_service_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showComingSoon() {
        Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
    }
}
