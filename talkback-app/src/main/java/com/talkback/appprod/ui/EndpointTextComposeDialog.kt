package com.talkback.appprod.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.talkback.appprod.R
import com.talkback.core.endpointtext.EndpointTextController

/**
 * Compose dialog for Endpoint Text (ADR-0039). Max [EndpointTextController.MAX_TEXT_CHARS] chars.
 */
class EndpointTextComposeDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val remoteKey = requireArguments().getString(ARG_KEY).orEmpty()
        val remoteLabel = requireArguments().getString(ARG_LABEL).orEmpty()
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_endpoint_text_compose, null, false)
        view.findViewById<TextView>(R.id.txtEndpointTextTitle).text =
            getString(R.string.endpoint_text_compose_title, remoteLabel)
        val edit = view.findViewById<EditText>(R.id.editEndpointText)
        val count = view.findViewById<TextView>(R.id.txtEndpointTextCount)
        val max = EndpointTextController.MAX_TEXT_CHARS

        fun refreshCount() {
            count.text = getString(R.string.endpoint_text_char_count, edit.text?.length ?: 0, max)
        }
        refreshCount()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshCount()
        })

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setNegativeButton(R.string.call_action_cancel, null)
            .setPositiveButton(R.string.endpoint_text_send, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = edit.text?.toString().orEmpty().trim()
                        if (text.isEmpty()) return@setOnClickListener
                        setFragmentResult(
                            REQUEST_SEND,
                            bundleOf(
                                ARG_KEY to remoteKey,
                                ARG_LABEL to remoteLabel,
                                ARG_TEXT to text
                            )
                        )
                        dialog.dismiss()
                    }
                }
            }
    }

    companion object {
        const val REQUEST_SEND = "endpoint_text_compose_send"
        const val ARG_KEY = "key"
        const val ARG_LABEL = "label"
        const val ARG_TEXT = "text"

        fun newInstance(remoteKey: String, remoteLabel: String): EndpointTextComposeDialog {
            return EndpointTextComposeDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, remoteKey)
                    putString(ARG_LABEL, remoteLabel)
                }
            }
        }
    }
}
