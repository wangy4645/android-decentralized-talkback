package com.talkback.appprod.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLocaleStoreTest {

    private lateinit var context: Context
    private lateinit var store: AppLocaleStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("talkback_app_locale", Context.MODE_PRIVATE).edit().clear().apply()
        store = AppLocaleStore(context)
    }

    @Test
    fun defaultsToFollowSystem() {
        assertEquals(AppLocaleChoice.SYSTEM, store.load())
    }

    @Test
    fun persistsChoice() {
        store.save(AppLocaleChoice.CHINESE_SIMPLIFIED)
        assertEquals(AppLocaleChoice.CHINESE_SIMPLIFIED, AppLocaleStore(context).load())
    }

    @Test
    fun explicitUserChoice_tracksSettingsPicker() {
        assertEquals(false, store.hasExplicitUserChoice())
        store.markExplicitUserChoice()
        assertEquals(true, AppLocaleStore(context).hasExplicitUserChoice())
    }
}
