package com.talkback.appprod.fieldtest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EP1–EP5 establishment-only field gate probe emitter.
 *
 * Run via adb instrumentation with:
 *   -e establishmentKeyVersion <V>
 * optional:
 *   -e peerModuleId M02
 *   -e ep3Mode required|skip
 */
@RunWith(AndroidJUnit4::class)
class EpEstablishmentFieldGateInstrumentedTest {
    @Test
    fun emitEpEstablishmentFieldProbes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = EpEstablishmentFieldGateSupport.run(context)
        // EP1 is N/A on non-host devices (null); host must emit a lookup outcome.
        assertTrue(
            "EP1 must be N/A or produce a lookup outcome",
            result.ep1 == null || result.ep1!!.wireName.isNotBlank(),
        )
        assertTrue("EP2 must produce local possession outcome", result.ep2.wireName.isNotBlank())
    }
}
