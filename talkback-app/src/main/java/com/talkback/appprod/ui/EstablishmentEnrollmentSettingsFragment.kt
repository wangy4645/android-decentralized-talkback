package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.enrollment.EstablishmentEnrollmentCoordinator

class EstablishmentEnrollmentSettingsFragment : Fragment() {
    private lateinit var txtStatus: TextView
    private lateinit var inputDelivery: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings_enrollment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindSettingsToolbar(R.string.settings_enrollment_title)
        txtStatus = view.findViewById(R.id.txtEnrollmentStatus)
        inputDelivery = view.findViewById(R.id.inputEnrollmentDelivery)
        view.findViewById<MaterialButton>(R.id.btnSubmitEnrollment).setOnClickListener {
            submitEnrollment()
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val app = TalkbackApp.get(requireContext())
        val config = AppConfigStore(requireContext()).load()
        val phase = app.runtimeManager.enrollmentCoordinatorPhase()
        val localReady = app.runtimeManager.isLocalEstablishmentReady(config)
        txtStatus.text =
            buildString {
                append(getString(R.string.settings_enrollment_phase_label))
                append(' ')
                append(phase?.name ?: getString(R.string.settings_enrollment_phase_not_started))
                append('\n')
                append(getString(R.string.settings_enrollment_local_ready_label))
                append(' ')
                append(
                    if (localReady) {
                        getString(R.string.settings_enrollment_ready_yes)
                    } else {
                        getString(R.string.settings_enrollment_ready_no)
                    },
                )
            }
    }

    private fun submitEnrollment() {
        val raw = inputDelivery.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(requireContext(), R.string.settings_enrollment_empty, Toast.LENGTH_SHORT).show()
            return
        }
        when (val result = TalkbackApp.get(requireContext()).runtimeManager.submitEstablishmentEnrollmentBase64(raw)) {
            is EstablishmentEnrollmentCoordinator.SubmitResult.Accepted -> {
                Toast.makeText(
                    requireContext(),
                    if (result.idempotent) {
                        R.string.settings_enrollment_accept_idempotent
                    } else {
                        R.string.settings_enrollment_accept_ok
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                inputDelivery.text?.clear()
                refreshStatus()
            }
            is EstablishmentEnrollmentCoordinator.SubmitResult.Failed -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_enrollment_accept_failed, result.code, result.reason),
                    Toast.LENGTH_LONG,
                ).show()
                refreshStatus()
            }
            EstablishmentEnrollmentCoordinator.SubmitResult.Busy ->
                Toast.makeText(requireContext(), R.string.settings_enrollment_busy, Toast.LENGTH_SHORT).show()
        }
    }
}
