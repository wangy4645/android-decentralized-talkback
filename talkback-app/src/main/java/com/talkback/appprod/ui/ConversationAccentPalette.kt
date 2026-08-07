package com.talkback.appprod.ui

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.talkback.appprod.R

/**
 * Stable accent colors for conversation / endpoint avatars (mock channel palette).
 */
object ConversationAccentPalette {
    private val accentRes = intArrayOf(
        R.color.tb_success,
        R.color.tb_primary,
        R.color.tb_warning,
        R.color.tb_record
    )

    fun accentResId(endpointKey: String): Int {
        val index = (endpointKey.hashCode().ushr(1)) % accentRes.size
        return accentRes[index]
    }

    @ColorInt
    fun fillColor(@ColorInt accent: Int): Int =
        ColorUtils.setAlphaComponent(accent, 0x26)

    @ColorInt
    fun offlineAccent(@ColorInt accent: Int): Int =
        ColorUtils.blendARGB(accent, Color.GRAY, 0.45f)
}
