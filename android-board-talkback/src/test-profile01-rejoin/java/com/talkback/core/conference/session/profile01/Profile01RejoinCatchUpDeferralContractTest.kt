package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents wire fact types referenced in M03 rejoin field RCA (no coordinator harness).
 */
class Profile01RejoinCatchUpDeferralContractTest {
    @Test
    fun preBindRetentionFactTypes_matchFieldLogInterpretation() {
        assertEquals(1, Profile01WireConstants.FACT_TYPE_CREATION)
        assertEquals(2, Profile01WireConstants.FACT_TYPE_MEMBERSHIP)
        assertEquals(9, Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION)
        assertEquals(11, Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE)
    }
}
