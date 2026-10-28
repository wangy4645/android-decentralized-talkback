package com.talkback.appprod.fieldtest

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Adr0057FieldTestProvisioningInstrumentedTest {
    @Test
    fun provisionTrustPreconditions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = FieldTestProvisioningSupport.provision(context)
        assertTrue(result.message, result.success)
    }
}
