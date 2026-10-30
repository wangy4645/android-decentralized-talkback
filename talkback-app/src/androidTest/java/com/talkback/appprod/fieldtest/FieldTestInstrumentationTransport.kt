package com.talkback.appprod.fieldtest

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Release-DUT evidence transport (Option A).
 * Emits byte-equivalent payloads on the instrumentation result channel for workstation capture.
 */
object FieldTestInstrumentationTransport {
    const val RESULT_TRANSPORT_KIND = "transportKind"
    const val RESULT_PAYLOAD_B64 = "payloadBase64"

    const val KIND_EP_OPERATOR_EVIDENCE = "EP_OPERATOR_EVIDENCE"
    const val KIND_FTPH_RESULT = "FTPH_RESULT"
    const val KIND_STORE_SANITY = "STORE_SANITY"

    const val STORE_ARTIFACT_ESTABLISHMENT = "accepted-local-establishment-trust-state.bin"

    fun emitPayload(kind: String, payloadUtf8: String) {
        require(kind.isNotBlank()) { "transportKind required" }
        require(payloadUtf8.isNotEmpty()) { "payload required" }
        val bundle =
            Bundle().apply {
                putString(RESULT_TRANSPORT_KIND, kind)
                putString(RESULT_PAYLOAD_B64, encodePayloadBase64(payloadUtf8))
            }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
    }

    fun encodePayloadBase64(payloadUtf8: String): String =
        Base64.encodeToString(payloadUtf8.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
