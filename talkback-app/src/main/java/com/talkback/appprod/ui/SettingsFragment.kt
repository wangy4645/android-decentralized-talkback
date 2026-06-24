package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.talkback.appprod.R

class SettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (childFragmentManager.findFragmentById(R.id.settingsContainer) == null) {
            childFragmentManager.commit {
                replace(R.id.settingsContainer, SettingsHomeFragment())
            }
        }
    }

    fun openDetail(fragment: Fragment) {
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.settingsContainer, fragment)
            addToBackStack(null)
        }
    }

    fun updateServiceState(state: String, detail: String) {
        childFragmentManager.fragments.forEach { child ->
            when (child) {
                is ServiceSettingsFragment -> child.updateServiceState(state, detail)
                is SettingsHomeFragment -> child.refreshSummary()
            }
        }
    }
}
