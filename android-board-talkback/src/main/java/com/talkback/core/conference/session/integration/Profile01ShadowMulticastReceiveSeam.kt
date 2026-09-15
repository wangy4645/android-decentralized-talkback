package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.transport.ConferenceMulticastRtpSrtpTransport
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PR-PA-SR4-RX — session-scoped multicast receive poll →
 * [ConferenceSessionMediaWiring.admitProtectedDatagram] (wire + jitter ingress pipeline).
 *
 * Does not own socket scope or admission policy. Failures are OBS-only.
 */
class Profile01ShadowMulticastReceiveSeam(
    private val wiringProvider: () -> ConferenceSessionMediaWiring? = { ConferenceSessionMediaBridge.wiring },
    private val hasSession: (String) -> Boolean = { ConferenceSessionMediaBridge.hasSession(it) },
    private val pollOnce: (
        sessionId: String,
        wiring: ConferenceSessionMediaWiring,
        transport: ConferenceMulticastRtpSrtpTransport,
    ) -> ConferenceMulticastRtpSrtpTransport.ReceiveOutcome? =
        { _, _, transport -> transport.receiveOnce() },
    private val admitProtectedDatagram: (
        wiring: ConferenceSessionMediaWiring,
        sessionId: String,
        datagram: ByteArray,
        rxWallMs: Long,
    ) -> WireIngressResult = { wiring, sessionId, datagram, rxWallMs ->
        wiring.admitProtectedDatagram(sessionId, datagram, rxWallMs).ingress
    },
    private val disarmJoinTimeoutMs: Long = DEFAULT_DISARM_JOIN_TIMEOUT_MS,
    private val executorFactory: (String) -> ExecutorService = { sessionId ->
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "shadow-rx-$sessionId").apply { isDaemon = true }
        }
    },
) {
    private data class ArmedSession(
        val sessionId: String,
        val stopRequested: AtomicBoolean,
        val executor: ExecutorService,
        val receivedDatagrams: AtomicLong,
    )

    private val armed = ConcurrentHashMap<String, ArmedSession>()

    fun onShadowSessionStarted(sessionId: String) {
        if (!MeetingProductMediaShadow.enabled) return
        try {
            if (!hasSession(sessionId)) return
            if (armed.containsKey(sessionId)) {
                Profile01ShadowRuntimeObservability.logShadowRxArmed(
                    sessionId = sessionId,
                    outcome = "ALREADY_ARMED",
                )
                return
            }
            val session = createArmedSession(sessionId)
            val prior = armed.putIfAbsent(sessionId, session)
            if (prior != null) {
                session.stopRequested.set(true)
                session.executor.shutdownNow()
                Profile01ShadowRuntimeObservability.logShadowRxArmed(
                    sessionId = sessionId,
                    outcome = "ALREADY_ARMED",
                )
                return
            }
            session.executor.submit { pollLoop(session) }
            Profile01ShadowRuntimeObservability.logShadowRxArmed(
                sessionId = sessionId,
                outcome = "APPLIED",
            )
        } catch (t: Throwable) {
            armed.remove(sessionId)
            Profile01ShadowRuntimeObservability.logShadowRxFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
            )
        }
    }

    /**
     * Disarm RX and wait for poll loop termination before transport teardown (RX-HC1).
     */
    fun onShadowSessionStopping(sessionId: String) {
        disarmSession(sessionId)
    }

    fun onSessionStopped(sessionId: String) {
        disarmSession(sessionId)
    }

    internal fun isArmed(sessionId: String): Boolean = armed.containsKey(sessionId)

    private fun createArmedSession(sessionId: String): ArmedSession {
        val stopRequested = AtomicBoolean(false)
        val executor = executorFactory(sessionId)
        return ArmedSession(
            sessionId = sessionId,
            stopRequested = stopRequested,
            executor = executor,
            receivedDatagrams = AtomicLong(0L),
        )
    }

    private fun disarmSession(sessionId: String) {
        val session = armed.remove(sessionId) ?: return
        session.stopRequested.set(true)
        session.executor.shutdown()
        try {
            if (!session.executor.awaitTermination(disarmJoinTimeoutMs, TimeUnit.MILLISECONDS)) {
                session.executor.shutdownNow()
                session.executor.awaitTermination(disarmJoinTimeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            session.executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun pollLoop(session: ArmedSession) {
        val sessionId = session.sessionId
        while (!session.stopRequested.get()) {
            val wiring = wiringProvider()
            if (wiring == null || !hasSession(sessionId)) break
            val transport = wiring.transport(sessionId)
            if (transport == null) break
            try {
                if (wiring.isIngressBlocked(sessionId)) {
                    continue
                }
                when (val outcome = pollOnce(sessionId, wiring, transport)) {
                    null -> Unit
                    is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw -> {
                        if (wiring.isIngressBlocked(sessionId)) {
                            Profile01ShadowRuntimeObservability.logShadowRxAdmission(
                                sessionId = sessionId,
                                outcome = "REJECTED",
                                reason = "SESSION_INGRESS_BLOCKED",
                            )
                            continue
                        }
                        val received = session.receivedDatagrams.incrementAndGet()
                        Profile01ShadowRuntimeObservability.maybeLogShadowRxActivity(
                            sessionId = sessionId,
                            receivedDatagrams = received,
                        )
                        when (
                            val admit =
                                admitProtectedDatagram(
                                    wiring,
                                    sessionId,
                                    outcome.payload,
                                    outcome.rxWallMs,
                                )
                        ) {
                            is WireIngressResult.Accepted ->
                                Profile01ShadowRuntimeObservability.logShadowRxAdmission(
                                    sessionId = sessionId,
                                    outcome = "ADMITTED",
                                )
                            is WireIngressResult.Rejected ->
                                Profile01ShadowRuntimeObservability.logShadowRxAdmission(
                                    sessionId = sessionId,
                                    outcome = "REJECTED",
                                    reason = admit.reason,
                                )
                        }
                    }
                    is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Ingress -> {
                        if (wiring.isIngressBlocked(sessionId)) {
                            Profile01ShadowRuntimeObservability.logShadowRxAdmission(
                                sessionId = sessionId,
                                outcome = "REJECTED",
                                reason = "SESSION_INGRESS_BLOCKED",
                            )
                            continue
                        }
                        val received = session.receivedDatagrams.incrementAndGet()
                        Profile01ShadowRuntimeObservability.maybeLogShadowRxActivity(
                            sessionId = sessionId,
                            receivedDatagrams = received,
                        )
                        when (outcome.result) {
                            is WireIngressResult.Accepted ->
                                Profile01ShadowRuntimeObservability.logShadowRxAdmission(
                                    sessionId = sessionId,
                                    outcome = "ADMITTED",
                                )
                            is WireIngressResult.Rejected ->
                                Profile01ShadowRuntimeObservability.logShadowRxAdmission(
                                    sessionId = sessionId,
                                    outcome = "REJECTED",
                                    reason = outcome.result.reason,
                                )
                        }
                    }
                }
            } catch (t: Throwable) {
                Profile01ShadowRuntimeObservability.logShadowRxFailed(
                    sessionId = sessionId,
                    reason = t.javaClass.simpleName,
                    detail = t.message,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_DISARM_JOIN_TIMEOUT_MS = 2_000L
    }
}
