package com.talkback.appprod.data

import androidx.annotation.StringRes
import com.talkback.appprod.R

enum class AppLocaleChoice {
    SYSTEM,
    ENGLISH,
    CHINESE_SIMPLIFIED;

    fun persistKey(): String = name

    @StringRes
    fun labelRes(): Int = when (this) {
        SYSTEM -> R.string.settings_language_follow_system
        ENGLISH -> R.string.settings_language_english
        CHINESE_SIMPLIFIED -> R.string.settings_language_chinese_simplified
    }

    companion object {
        /** Fresh install / unset preference: follow system locale until the user picks another option. */
        fun fromPersisted(raw: String?): AppLocaleChoice =
            entries.find { it.name == raw } ?: SYSTEM
    }
}
