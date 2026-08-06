package com.talkback.appprod.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.talkback.appprod.R
import com.talkback.appprod.endpointtext.EndpointTextDirection
import com.talkback.appprod.endpointtext.EndpointTextRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only Recent Messages for one EndpointKey (process-local presentation cache).
 * No compose, unread, or persistence.
 */
class EndpointTextRecentDialog : DialogFragment() {

    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val remoteKey = requireArguments().getString(ARG_KEY).orEmpty()
        val remoteLabel = requireArguments().getString(ARG_LABEL).orEmpty()
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_endpoint_text_recent, null, false)
        view.findViewById<TextView>(R.id.txtRecentTitle).text =
            getString(R.string.endpoint_text_recent_title, remoteLabel)

        val empty = view.findViewById<TextView>(R.id.txtRecentEmpty)
        val container = view.findViewById<LinearLayout>(R.id.containerRecentMessages)
        val records = viewModel.recentEndpointText(remoteKey)
        empty.isVisible = records.isEmpty()
        container.isVisible = records.isNotEmpty()
        val inflater = LayoutInflater.from(requireContext())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        records.forEach { record ->
            container.addView(bindRow(inflater, container, record, timeFormat))
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun bindRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        record: EndpointTextRecord,
        timeFormat: SimpleDateFormat
    ): View {
        val row = inflater.inflate(R.layout.item_endpoint_text_recent_row, parent, false)
        row.findViewById<TextView>(R.id.txtRecentTime).text =
            timeFormat.format(Date(record.timestampMs))
        val textView = row.findViewById<TextView>(R.id.txtRecentText)
        textView.text = when (record.direction) {
            EndpointTextDirection.INBOUND -> record.text
            EndpointTextDirection.OUTBOUND ->
                getString(R.string.endpoint_text_recent_outbound_prefix, record.text)
        }
        return row
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_LABEL = "label"

        fun newInstance(remoteKey: String, remoteLabel: String): EndpointTextRecentDialog {
            return EndpointTextRecentDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, remoteKey)
                    putString(ARG_LABEL, remoteLabel)
                }
            }
        }
    }
}
