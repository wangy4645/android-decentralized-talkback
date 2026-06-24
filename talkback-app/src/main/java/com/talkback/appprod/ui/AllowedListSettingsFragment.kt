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

class AllowedListSettingsFragment : Fragment() {
    private lateinit var store: AppConfigStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_allowed_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = AppConfigStore(requireContext())
        bindSettingsToolbar(R.string.settings_allowed_list_title)

        val inputAllowed = view.findViewById<EditText>(R.id.inputAllowedModules)
        inputAllowed.setText(store.load().allowedModuleIds.joinToString(","))

        view.findViewById<Button>(R.id.btnSaveAllowed).setOnClickListener {
            val allowed = inputAllowed.text.toString()
                .split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val current = store.load()
            store.save(
                current.copy(
                    allowedModuleIds = allowed,
                    whitelistEnabled = true
                )
            )
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            requireParentFragment().childFragmentManager.popBackStack()
        }
    }
}
