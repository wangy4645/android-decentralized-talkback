package com.talkback.appprod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.talkback.appprod.R

fun ViewGroup.inflateSettingsRow(@LayoutRes layoutRes: Int): View {
    val row = LayoutInflater.from(context).inflate(layoutRes, this, false)
    addView(row)
    return row
}

private fun TextView.applySettingsTitleStyle() {
    isEnabled = true
    setTextColor(ContextCompat.getColor(context, R.color.tb_text_primary))
}

private fun TextView.applySettingsValueStyle() {
    isEnabled = true
    setTextColor(ContextCompat.getColor(context, R.color.tb_text_muted))
}

private fun TextView.applySettingsHintStyle() {
    isEnabled = true
    setTextColor(ContextCompat.getColor(context, R.color.tb_text_muted))
}

private fun TextView.applySettingsStatusEnabledStyle() {
    isEnabled = true
    setTextColor(ContextCompat.getColor(context, R.color.tb_success))
}

fun View.setupSubpageNavRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    badge: String? = null,
    showChevron: Boolean = true,
    footer: String? = null,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    findViewById<TextView>(R.id.txtSubpageTitle).apply {
        text = title
        applySettingsTitleStyle()
    }
    val txtSubtitle = findViewById<TextView>(R.id.txtSubpageSubtitle)
    if (subtitle.isNullOrBlank()) {
        txtSubtitle.isVisible = false
    } else {
        txtSubtitle.isVisible = true
        txtSubtitle.text = subtitle
        txtSubtitle.applySettingsValueStyle()
    }
    val txtValue = findViewById<TextView>(R.id.txtSubpageValue)
    if (value.isNullOrBlank()) {
        txtValue.isVisible = false
    } else {
        txtValue.isVisible = true
        txtValue.text = value
        txtValue.applySettingsValueStyle()
    }
    val txtBadge = findViewById<TextView>(R.id.txtSubpageBadge)
    if (badge.isNullOrBlank()) {
        txtBadge.isVisible = false
    } else {
        txtBadge.isVisible = true
        txtBadge.text = badge
        txtBadge.applySettingsValueStyle()
    }
    findViewById<ImageView>(R.id.imgSubpageChevron).isVisible = showChevron
    val txtFooter = findViewById<TextView>(R.id.txtSubpageFooter)
    if (footer.isNullOrBlank()) {
        txtFooter.isVisible = false
    } else {
        txtFooter.isVisible = true
        txtFooter.text = footer
        txtFooter.applySettingsHintStyle()
    }
    findViewById<View>(R.id.dividerSubpageRow).isVisible = showDivider
    val content = findViewById<View>(R.id.subpageRowContent)
    if (onClick != null) {
        content.isClickable = true
        content.setOnClickListener { onClick() }
    } else {
        content.isClickable = false
        content.setOnClickListener(null)
    }
}

fun View.setupSubpageToggleRow(
    title: String,
    subtitle: String? = null,
    footer: String? = null,
    checked: Boolean,
    showDivider: Boolean = false,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    findViewById<TextView>(R.id.txtToggleTitle).apply {
        text = title
        applySettingsTitleStyle()
    }
    val txtSubtitle = findViewById<TextView>(R.id.txtToggleSubtitle)
    if (subtitle.isNullOrBlank()) {
        txtSubtitle.isVisible = false
    } else {
        txtSubtitle.isVisible = true
        txtSubtitle.text = subtitle
        txtSubtitle.applySettingsStatusEnabledStyle()
    }
    val txtFooter = findViewById<TextView>(R.id.txtToggleFooter)
    if (footer.isNullOrBlank()) {
        txtFooter.isVisible = false
    } else {
        txtFooter.isVisible = true
        txtFooter.text = footer
        txtFooter.applySettingsHintStyle()
    }
    findViewById<View>(R.id.dividerToggleRow).isVisible = showDivider
    val switch = findViewById<SwitchCompat>(R.id.switchRow)
    switch.isEnabled = enabled
    switch.setShowText(false)
    switch.jumpDrawablesToCurrentState()
    switch.setOnCheckedChangeListener(null)
    switch.isChecked = checked
    if (onCheckedChange != null) {
        switch.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            onCheckedChange(isChecked)
        }
    }
}
