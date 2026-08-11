package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.talkback.appprod.R

class FeaturePlaceholderBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_feature_placeholder, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val kind = Kind.entries[requireArguments().getInt(ARG_KIND, Kind.ALL_CALL.ordinal)]
        val spec = specFor(kind)

        view.findViewById<ImageView>(R.id.imgFeatureIcon).apply {
            setImageResource(spec.iconRes)
            setColorFilter(ContextCompat.getColor(requireContext(), spec.iconTintRes))
        }
        view.findViewById<TextView>(R.id.txtFeatureTitle).setText(spec.titleRes)
        view.findViewById<TextView>(R.id.txtFeatureStatus).setText(spec.statusRes)
        view.findViewById<TextView>(R.id.txtFeatureBody).setText(spec.bodyRes)

        val hint = view.findViewById<TextView>(R.id.txtFeatureHint)
        if (spec.hintRes != null) {
            hint.isVisible = true
            hint.setText(spec.hintRes)
        } else {
            hint.isVisible = false
        }

        view.findViewById<TextView>(R.id.btnFeatureDismiss).setOnClickListener { dismiss() }
    }

    private data class Spec(
        val iconRes: Int,
        val iconTintRes: Int,
        val titleRes: Int,
        val statusRes: Int,
        val bodyRes: Int,
        val hintRes: Int? = null
    )

    enum class Kind {
        ALL_CALL,
        MONITOR,
        EMERGENCY_UNAVAILABLE,
        EMERGENCY_PROFILE_READY,
        RECORD;

    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val TAG = "feature_placeholder"

        private fun specFor(kind: Kind): Spec = when (kind) {
            Kind.ALL_CALL -> Spec(
                iconRes = R.drawable.ic_action_broadcast,
                iconTintRes = R.color.tb_primary,
                titleRes = R.string.action_broadcast,
                statusRes = R.string.feature_status_not_enabled,
                bodyRes = R.string.all_call_placeholder_body,
                hintRes = R.string.feature_placeholder_admin_hint
            )
            Kind.MONITOR -> Spec(
                iconRes = R.drawable.ic_action_monitor,
                iconTintRes = R.color.tb_monitor,
                titleRes = R.string.action_monitor,
                statusRes = R.string.feature_status_coming_soon,
                bodyRes = R.string.monitor_placeholder_body
            )
            Kind.EMERGENCY_UNAVAILABLE -> Spec(
                iconRes = R.drawable.ic_action_emergency,
                iconTintRes = R.color.tb_emergency,
                titleRes = R.string.action_emergency,
                statusRes = R.string.feature_status_not_configured,
                bodyRes = R.string.emergency_placeholder_body,
                hintRes = R.string.feature_placeholder_admin_hint
            )
            Kind.EMERGENCY_PROFILE_READY -> Spec(
                iconRes = R.drawable.ic_action_emergency,
                iconTintRes = R.color.tb_emergency,
                titleRes = R.string.action_emergency,
                statusRes = R.string.feature_status_profile_ready,
                bodyRes = R.string.emergency_confirm_body
            )
            Kind.RECORD -> Spec(
                iconRes = R.drawable.ic_action_record,
                iconTintRes = R.color.tb_record,
                titleRes = R.string.action_record,
                statusRes = R.string.feature_status_coming_soon,
                bodyRes = R.string.record_placeholder_body
            )
        }

        fun show(host: androidx.fragment.app.Fragment, kind: Kind) {
            if (host.childFragmentManager.findFragmentByTag(TAG) != null) return
            FeaturePlaceholderBottomSheet().apply {
                arguments = Bundle().apply { putInt(ARG_KIND, kind.ordinal) }
            }.show(host.childFragmentManager, TAG)
        }

        fun showAllCall(host: androidx.fragment.app.Fragment) = show(host, Kind.ALL_CALL)
        fun showMonitor(host: androidx.fragment.app.Fragment) = show(host, Kind.MONITOR)
        fun showRecord(host: androidx.fragment.app.Fragment) = show(host, Kind.RECORD)
        fun showEmergency(host: androidx.fragment.app.Fragment, profileReady: Boolean) =
            show(
                host,
                if (profileReady) Kind.EMERGENCY_PROFILE_READY else Kind.EMERGENCY_UNAVAILABLE
            )
    }
}
