package com.talkback.appprod.ui

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.talkback.appprod.R

fun Fragment.bindSettingsToolbar(@StringRes titleRes: Int) {
    val root = requireView()
    root.findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener {
        requireParentFragment().childFragmentManager.popBackStack()
    }
    root.findViewById<TextView>(R.id.txtSettingsTitle).setText(titleRes)
}

fun View.setupSettingsNavRow(
    @DrawableRes iconRes: Int,
    title: String,
    value: String,
    showDivider: Boolean = false,
    navigable: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    findViewById<ImageView>(R.id.imgNavIcon).setImageResource(iconRes)
    findViewById<TextView>(R.id.txtNavTitle).text = title
    findViewById<TextView>(R.id.txtNavValue).text = value
    findViewById<View>(R.id.dividerNav).isVisible = showDivider
    val chevron = findViewById<ImageView>(R.id.imgNavChevron)
    val row = findViewById<View>(R.id.settingsRowContent)
    if (navigable && onClick != null) {
        chevron.isVisible = true
        row.isClickable = true
        row.setOnClickListener { onClick() }
    } else {
        chevron.isVisible = false
        row.isClickable = false
        row.setOnClickListener(null)
    }
}
