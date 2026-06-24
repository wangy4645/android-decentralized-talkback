package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.talkback.appprod.R
import com.talkback.appprod.data.AppConfig
import com.talkback.appprod.data.AppConfigStore

class SecuritySettingsFragment : Fragment() {
    private lateinit var store: AppConfigStore
    private lateinit var inputSecret: TextInputEditText
    private lateinit var txtSecretWarning: TextView
    private lateinit var groupAllowed: LinearLayout
    private var suppressToggleCallbacks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_security, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = AppConfigStore(requireContext())
        bindSettingsToolbar(R.string.settings_security_title)

        val groupEncryption = view.findViewById<LinearLayout>(R.id.groupEncryption)
        val groupSecret = view.findViewById<LinearLayout>(R.id.groupSecret)
        groupAllowed = view.findViewById(R.id.groupAllowed)
        val groupAdvanced = view.findViewById<LinearLayout>(R.id.groupAdvanced)

        groupEncryption.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_encryption_mode_title),
            subtitle = getString(R.string.settings_encryption_mode_subtitle),
            badge = getString(R.string.settings_badge_planned),
            showChevron = true,
            footer = getString(R.string.settings_encryption_mode_footer),
            showDivider = true,
            onClick = { showComingSoon() }
        )

        groupEncryption.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_signaling_protection_title),
            subtitle = getString(R.string.settings_status_enabled),
            footer = getString(R.string.settings_signaling_protection_footer),
            checked = true,
            enabled = false
        )

        groupSecret.inflateSettingsRow(R.layout.item_settings_secret_inline)
        inputSecret = groupSecret.findViewById(R.id.inputSharedSecret)
        txtSecretWarning = groupSecret.findViewById(R.id.txtSecretWarning)
        groupSecret.findViewById<View>(R.id.dividerSecretRow).isVisible = true
        inputSecret.addTextChangedListener(SimpleTextWatcher { updateSecretWarning() })

        groupSecret.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_change_shared_secret),
            showChevron = true,
            onClick = {
                inputSecret.requestFocus()
                inputSecret.setSelection(inputSecret.text?.length ?: 0)
            }
        )

        bindAllowedGroup(store.load())
        loadConfig()

        groupAdvanced.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_certificate_title),
            value = getString(R.string.settings_certificate_not_configured),
            showChevron = true,
            onClick = { showComingSoon() }
        )
    }

    override fun onResume() {
        super.onResume()
        bindAllowedGroup(store.load())
    }

    override fun onPause() {
        persistSecret()
        super.onPause()
    }

    private fun loadConfig() {
        val config = store.load()
        inputSecret.setText(config.sharedSecret)
        updateSecretWarning()
        bindAllowedGroup(config)
    }

    private fun updateSecretWarning() {
        if (!::txtSecretWarning.isInitialized) return
        val empty = inputSecret.text?.toString()?.trim().isNullOrEmpty()
        txtSecretWarning.isVisible = empty
    }

    private fun bindAllowedGroup(config: AppConfig) {
        groupAllowed.removeAllViews()
        suppressToggleCallbacks = true

        groupAllowed.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.settings_whitelist_mode_title),
            subtitle = if (config.whitelistEnabled) getString(R.string.settings_status_enabled) else null,
            checked = config.whitelistEnabled,
            showDivider = true,
            onCheckedChange = { enabled ->
                if (suppressToggleCallbacks) return@setupSubpageToggleRow
                store.save(store.load().copy(whitelistEnabled = enabled))
                bindAllowedGroup(store.load())
            }
        )

        val count = config.allowedModuleIds.size
        val value = when {
            !config.whitelistEnabled -> getString(R.string.settings_security_subtitle_open)
            count == 0 -> getString(R.string.settings_allowed_devices_count, 0)
            else -> getString(R.string.settings_allowed_devices_count, count)
        }
        groupAllowed.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.settings_manage_allowed_devices),
            value = value,
            showChevron = true,
            onClick = {
                (parentFragment as? SettingsFragment)?.openDetail(AllowedListSettingsFragment())
            }
        )

        suppressToggleCallbacks = false
    }

    private fun persistSecret() {
        if (!::inputSecret.isInitialized) return
        val current = store.load()
        val secret = inputSecret.text?.toString()?.trim().orEmpty()
        if (secret != current.sharedSecret) {
            store.save(current.copy(sharedSecret = secret))
            com.talkback.appprod.data.TaskProfileManager(requireContext()).syncActiveProfileFromAppConfig()
        }
        updateSecretWarning()
    }

    private fun showComingSoon() {
        Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
    }
}
