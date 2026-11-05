package com.talkback.appprod.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.talkback.appprod.data.AppLocaleChoice
import com.talkback.appprod.data.AppLocaleStore
import com.talkback.appprod.service.TalkbackForegroundService

object AppLocaleController {

    fun applyStored(context: Context) {
        val store = AppLocaleStore(context)
        val choice = store.load()
        if (!store.hasPersistedChoice()) {
            store.save(choice)
        }
        applyChoice(choice)
    }

    fun applyChoice(choice: AppLocaleChoice) {
        val locales = when (choice) {
            AppLocaleChoice.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLocaleChoice.ENGLISH -> LocaleListCompat.forLanguageTags("en-US")
            AppLocaleChoice.CHINESE_SIMPLIFIED -> LocaleListCompat.forLanguageTags("zh-Hans-CN")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * Persist and apply locales. AppCompat / the system recreates [AppCompatActivity] as needed;
     * do not post fragment work or a second [Activity.recreate] here (races with teardown → crash).
     */
    fun commitUserChoice(
        context: Context,
        choice: AppLocaleChoice,
        refreshVisibleState: (() -> Unit)? = null,
    ) {
        AppLocaleStore(context).save(choice)
        refreshVisibleState?.invoke()
        applyChoice(choice)
        refreshForegroundServiceNotification(context)
    }

    /**
     * [Application] / long-lived contexts do not always honor [AppCompatDelegate.getApplicationLocales]
     * when resolving strings. Use this for ViewModel and other off-UI string formatting.
     */
    fun localizedContext(context: Context): Context {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (appLocales.isEmpty) {
            return context
        }
        val tags = buildList {
            for (i in 0 until appLocales.size()) {
                appLocales[i]?.toLanguageTag()?.let { add(it) }
            }
        }
        if (tags.isEmpty()) {
            return context
        }
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tags.joinToString(",")))
        return context.createConfigurationContext(config)
    }

    fun displayLabel(context: Context, choice: AppLocaleChoice): String =
        context.getString(choice.labelRes())

    fun refreshForegroundServiceNotification(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, TalkbackForegroundService::class.java).apply {
            action = TalkbackForegroundService.ACTION_REFRESH_NOTIFICATION
        }
        ContextCompat.startForegroundService(appContext, intent)
    }
}
