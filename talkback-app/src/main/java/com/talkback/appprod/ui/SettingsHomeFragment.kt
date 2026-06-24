package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.talkback.appprod.BuildConfig
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.data.TaskProfileManager

class SettingsHomeFragment : Fragment() {
    private lateinit var store: AppConfigStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = AppConfigStore(requireContext())
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    fun refreshSummary() {
        val view = view ?: return
        val config = store.load()
        val host = parentFragment as? SettingsFragment
        val networkStatus = NetworkStatusHelper.current(requireContext())

        view.findViewById<View>(R.id.rowIdentity).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_identity,
            title = getString(R.string.settings_identity_title),
            value = "${config.moduleId} / ${config.endpointId}"
        ) { host?.openDetail(IdentitySettingsFragment()) }

        view.findViewById<View>(R.id.rowNetwork).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_network_status,
            title = getString(R.string.settings_network_status_title),
            value = getString(networkStatus.labelRes),
            showDivider = true
        ) { host?.openDetail(NetworkSettingsFragment()) }

        val serviceRunning = TalkbackApp.get(requireContext()).serviceRunning
        view.findViewById<View>(R.id.rowService).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_service,
            title = getString(R.string.settings_service_title),
            value = getString(
                if (serviceRunning) R.string.settings_service_running else R.string.settings_service_stopped
            )
        ) { host?.openDetail(ServiceSettingsFragment()) }

        val activeTask = TaskProfileManager(requireContext()).activeProfile()
        view.findViewById<View>(R.id.rowTaskProfiles).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_shield,
            title = getString(R.string.settings_task_profiles_title),
            value = activeTask?.name ?: getString(R.string.settings_task_profiles_subtitle),
            showDivider = true
        ) { host?.openDetail(TaskProfilesListFragment()) }

        val securityValue = when {
            !config.whitelistEnabled -> getString(R.string.settings_security_subtitle_open)
            config.allowedModuleIds.isEmpty() -> getString(R.string.settings_allowed_devices_count, 0)
            else -> getString(R.string.settings_security_subtitle_whitelist, config.allowedModuleIds.size)
        }
        view.findViewById<View>(R.id.rowSecurity).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_shield,
            title = getString(R.string.settings_security_title),
            value = securityValue
        ) { host?.openDetail(SecuritySettingsFragment()) }

        view.findViewById<View>(R.id.rowGeneral).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_general,
            title = getString(R.string.settings_general_title),
            value = getString(R.string.settings_general_subtitle),
            showDivider = true
        ) { host?.openDetail(GeneralSettingsFragment()) }

        view.findViewById<View>(R.id.rowAbout).setupSettingsNavRow(
            iconRes = R.drawable.ic_settings_info,
            title = getString(R.string.settings_about),
            value = getString(R.string.settings_about_version, BuildConfig.VERSION_NAME),
            navigable = false,
            onClick = null
        )

        val modeLabel = getString(
            if (config.isConferenceMode()) R.string.settings_channel_mode_conference
            else R.string.settings_channel_mode_group
        )
        view.findViewById<TextView>(R.id.txtSettingsSummary).text = getString(
            R.string.settings_home_channel_summary_with_mode,
            config.defaultChannelId,
            config.channelDisplayName,
            modeLabel
        )
    }
}
