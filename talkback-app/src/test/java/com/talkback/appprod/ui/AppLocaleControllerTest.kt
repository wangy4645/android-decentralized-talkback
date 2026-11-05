package com.talkback.appprod.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.talkback.appprod.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLocaleControllerTest {

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun localizedContext_usesApplicationLocales_notDeviceDefault() {
        val app = RuntimeEnvironment.getApplication()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"))

        val localized = AppLocaleController.localizedContext(app)
            .getString(R.string.meeting_participants, 0)

        assertEquals("0 Participants", localized)
        assertEquals("(You)", AppLocaleController.localizedContext(app).getString(R.string.you_suffix))
    }
}
