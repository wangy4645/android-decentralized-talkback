package com.talkback.core.util

import com.talkback.core.model.ConferenceJoinIntent
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * D1 Option A: M03 ingress-miss debug injection (ADR-0022 E.15).
 *
 * When armed, suppresses RECOVERY_REATTACH ingress before REMOTE_RECEIVE /
 * handler dispatch so sender window can expire ABSENT without peer OBSERVED.
 * Does not alter RecoveryDeliveryPolicy / CompletionPolicy / PR5-2c-C.
 */
object D1IngressMissDebugInjection {

    private val dropRecoveryOfferIngress = AtomicBoolean(false)
    private val dropCount = AtomicInteger(0)
    private var logSink: ((String) -> Unit)? = null

    fun armDropRecoveryOfferIngress() {
        dropRecoveryOfferIngress.set(true)
    }

    fun clear() {
        dropRecoveryOfferIngress.set(false)
        dropCount.set(0)
        logSink = null
    }

    /** JVM UT: avoid android.util.Log (not mocked). */
    internal fun resetForTest(log: ((String) -> Unit)? = null) {
        clear()
        logSink = log
    }

    fun isArmed(): Boolean = dropRecoveryOfferIngress.get()

    fun dropCount(): Int = dropCount.get()

    /**
     * @return true when this envelope must be discarded before REMOTE_RECEIVE / listener.
     */
    fun consumeDropIfArmed(envelope: SignalEnvelope): Boolean {
        if (!dropRecoveryOfferIngress.get()) return false
        if (envelope.type != SignalType.GROUP_JOIN) return false
        if (!isRecoveryReattachPayload(envelope.payload)) return false
        dropCount.incrementAndGet()
        val lineage = extractField(envelope.payload, "offerLineageId") ?: "NONE"
        val attempt = extractField(envelope.payload, "deliveryAttemptId") ?: "1"
        val msg =
            "D1_DEBUG_DROP_RECOVERY_INGRESS from=${envelope.from.moduleId.value} " +
                "offerLineageId=$lineage deliveryAttemptId=$attempt " +
                "session=${envelope.sessionId}"
        val sink = logSink
        if (sink != null) sink(msg) else TalkbackLog.i(msg)
        return true
    }

    private fun isRecoveryReattachPayload(payload: String): Boolean {
        val decoded = GroupSessionPayload.decode(payload)
        if (decoded != null) {
            return decoded.joinIntent == ConferenceJoinIntent.RECOVERY_REATTACH
        }
        return payload.contains("RECOVERY_REATTACH")
    }

    private fun extractField(payload: String, name: String): String? {
        val decoded = GroupSessionPayload.decode(payload)
        if (decoded != null) {
            return when (name) {
                "offerLineageId" -> decoded.offerLineageId
                "deliveryAttemptId" -> decoded.deliveryAttemptId.toString()
                else -> null
            }
        }
        val m = Regex(""""$name"\s*:\s*"?([^,"}\s]+)""").find(payload)
        return m?.groupValues?.getOrNull(1)
    }
}