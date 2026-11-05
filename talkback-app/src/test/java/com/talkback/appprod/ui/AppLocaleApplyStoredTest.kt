package com.talkback.appprod.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.talkback.appprod.data.AppLocaleChoice
import com.talkback.appprod.data.AppLocaleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLocaleApplyStoredTest {

    private lateinit var store: AppLocaleStore

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("talkback_app_locale", 0).edit().clear().apply()
        store = AppLocaleStore(app)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun applyStored_withoutPrefs_defaultsToFollowSystem() {
        AppLocaleController.applyStored(RuntimeEnvironment.getApplication())
        assertEquals(AppLocaleChoice.SYSTEM, store.load())
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun applyStored_legacyEnglishWithoutExplicitUser_migratesToFollowSystem() {
        store.save(AppLocaleChoice.ENGLISH)
        AppLocaleController.applyStored(RuntimeEnvironment.getApplication())
        assertEquals(AppLocaleChoice.SYSTEM, store.load())
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun applyStored_explicitEnglish_keepsEnglish() {
        store.save(AppLocaleChoice.ENGLISH)
        store.markExplicitUserChoice()
        AppLocaleController.applyStored(RuntimeEnvironment.getApplication())
        assertEquals(AppLocaleChoice.ENGLISH, store.load())
    }
}
