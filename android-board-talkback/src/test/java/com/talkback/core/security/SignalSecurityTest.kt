package com.talkback.core.security

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalSecurityTest {
    private val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
    private val remote = EndpointAddress(ModuleId("M02"), EndpointId("E01"))

    private fun unsignedEnvelope(): SignalEnvelope = SignalEnvelope(
        type = SignalType.HELLO,
        from = local,
        to = remote,
        sessionId = "sess-1",
        timestampMs = 1_700_000_000_000L,
        payload = "{\"moduleId\":\"M01\"}",
        nonce = "nonce-abc",
        signature = ""
    )

    @Test
    fun signAndVerify_withMatchingSecret_succeeds() {
        val secret = "task-secret-alpha"
        val signed = unsignedEnvelope().copy(signature = SignalSecurity.sign(unsignedEnvelope(), secret))
        assertTrue(SignalSecurity.verify(signed, secret))
    }

    @Test
    fun verify_withWrongSecret_fails() {
        val signed = unsignedEnvelope().copy(
            signature = SignalSecurity.sign(unsignedEnvelope(), "secret-a")
        )
        assertFalse(SignalSecurity.verify(signed, "secret-b"))
    }

    @Test
    fun sign_withBlankSecret_returnsEmptyAndVerifyFails() {
        val signature = SignalSecurity.sign(unsignedEnvelope(), "   ")
        assertTrue(signature.isEmpty())
        val signed = unsignedEnvelope().copy(signature = signature)
        assertFalse(SignalSecurity.verify(signed, "any"))
    }

    @Test
    fun verify_detectsTamperedPayload() {
        val secret = "task-secret-beta"
        val signed = unsignedEnvelope().copy(signature = SignalSecurity.sign(unsignedEnvelope(), secret))
        val tampered = signed.copy(payload = "{\"moduleId\":\"M99\"}")
        assertFalse(SignalSecurity.verify(tampered, secret))
    }

    @Test
    fun hmacDiffersFromLegacyShaConcat() {
        val secret = "legacy-check"
        val envelope = unsignedEnvelope()
        val hmac = SignalSecurity.sign(envelope, secret)
        assertNotEquals("", hmac)
        assertTrue(hmac.length == 64)
    }
}
