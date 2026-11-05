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

    companion object {
        private const val PREFS_NAME = "talkback_app_locale"
        private const val KEY_CHOICE = "choice"
    }
}
