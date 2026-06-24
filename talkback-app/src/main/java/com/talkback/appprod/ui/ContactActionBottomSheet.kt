package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.talkback.appprod.R

class ContactActionBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_contact_actions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val label = requireArguments().getString(ARG_LABEL).orEmpty()
        val team = requireArguments().getString(ARG_TEAM).orEmpty()
        val key = requireArguments().getString(ARG_KEY).orEmpty()
        val online = requireArguments().getBoolean(ARG_ONLINE)

        view.findViewById<TextView>(R.id.txtSheetName).text = label
        view.findViewById<TextView>(R.id.txtSheetTeam).text = team
        view.findViewById<TextView>(R.id.txtSheetOnline).isVisible = online
        if (!online) {
            view.findViewById<TextView>(R.id.txtSheetOnline).text = getString(R.string.status_offline)
        }

        bindAction(
            view.findViewById(R.id.actionPrivateCall),
            R.drawable.ic_mic_ptt,
            getString(R.string.call_action_private),
            getString(R.string.call_action_private_sub)
        ) {
            setFragmentResult(
                REQUEST_PRIVATE_CALL,
                bundleOf(
                    ARG_KEY to key,
                    ARG_LABEL to label,
                    ARG_TEAM to team,
                    ARG_ONLINE to online
                )
            )
            dismiss()
        }

        bindAction(
            view.findViewById(R.id.actionMonitor),
            R.drawable.ic_action_monitor,
            getString(R.string.call_action_monitor),
            getString(R.string.call_action_monitor_sub)
        ) {
            showSoon()
        }

        bindAction(
            view.findViewById(R.id.actionMessage),
            R.drawable.ic_toolbar_menu,
            getString(R.string.call_action_message),
            subtitle = null
        ) {
            showSoon()
        }

        bindAction(
            view.findViewById(R.id.actionFavorite),
            R.drawable.ic_nav_contacts,
            getString(R.string.call_action_favorite),
            subtitle = null
        ) {
            showSoon()
        }

        view.findViewById<View>(R.id.btnSheetCancel).setOnClickListener { dismiss() }
    }

    private fun bindAction(
        row: View,
        iconRes: Int,
        title: String,
        subtitle: String?,
        onClick: () -> Unit
    ) {
        row.findViewById<ImageView>(R.id.imgActionIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.txtActionTitle).text = title
        val sub = row.findViewById<TextView>(R.id.txtActionSubtitle)
        if (subtitle.isNullOrBlank()) {
            sub.visibility = View.GONE
        } else {
            sub.visibility = View.VISIBLE
            sub.text = subtitle
        }
        row.setOnClickListener { onClick() }
    }

    private fun showSoon() {
        Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val REQUEST_PRIVATE_CALL = "contact_private_call"
        private const val ARG_KEY = "key"
        private const val ARG_LABEL = "label"
        private const val ARG_TEAM = "team"
        private const val ARG_ONLINE = "online"

        fun newInstance(item: EndpointUiItem, teamName: String): ContactActionBottomSheet {
            return ContactActionBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, item.key)
                    putString(ARG_LABEL, item.displayLabel)
                    putString(ARG_TEAM, teamName)
                    putBoolean(ARG_ONLINE, item.status != EndpointStatus.OFFLINE)
                }
            }
        }
    }
}
