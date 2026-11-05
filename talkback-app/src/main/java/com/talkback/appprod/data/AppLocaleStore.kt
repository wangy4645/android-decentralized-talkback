package com.talkback.appprod.data

import android.content.Context

class AppLocaleStore(context: Context) {
    private val storageContext = context.applicationContext ?: context
    private val prefs =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppLocaleChoice =
        AppLocaleChoice.fromPersisted(prefs.getString(KEY_CHOICE, null))

    fun hasPersistedChoice(): Boolean = prefs.contains(KEY_CHOICE)

    fun save(choice: AppLocaleChoice) {
        prefs.edit().putString(KEY_CHOICE, choice.persistKey()).apply()
    }

    /** Set when the user picks a language in Settings (not first-run default). */
    fun hasExplicitUserChoice(): Boolean = prefs.getBoolean(KEY_EXPLICIT_USER, false)

    fun markExplicitUserChoice() {
        prefs.edit().putBoolean(KEY_EXPLICIT_USER, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "talkback_app_locale"
        private const val KEY_CHOICE = "choice"
        private const val KEY_EXPLICIT_USER = "explicit_user"
    }
}
