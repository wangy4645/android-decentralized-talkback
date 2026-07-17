package com.talkback.appprod.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeReceivePathLivenessProviderTest {

    @Test
    fun returnsFalseWhenRuntimeMissing() {
        val provider = RuntimeReceivePathLivenessProvider { null }
        assertFalse(provider.receivePathLive("sess-1", "M02"))
    }
}
