package com.talkback.core.conference.session.integration

import com.talkback.core.util.CoordinatorTaskAttribution
import com.talkback.core.util.TalkbackLog

/**
 * Read-only ACCEPT_BIND_DRAIN internal attribution. Does not change scheduling or drain order.
 *
 * Grep: ACCEPT_BIND_DRAIN_SPAN, ACCEPT_BIND_DRAIN_SUMMARY, ACCEPT_BIND_DRAIN_DUP
 */
object AcceptBindDrainAttribution {
    enum class Obligation {
        STATE_COMMIT,
        PROJECTION,
        MEDIA_WIRING,
        RETENTION,
        OTHER,
    }

    data class Scope(
        val sessionId: String,
        val traceToken: String,
        var topDrainStarted: Boolean = false,
        var nestedDrainPasses: Int = 0,
        val seenItemKeys: MutableSet<String> = linkedSetOf(),
        var duplicateItemCount: Long = 0L,
    )

    private val tls = ThreadLocal<Scope>()
    @Volatile
    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        tls.remove()
        logSink = sink
    }

    fun current(): Scope? = tls.get()

    fun inAcceptBindDrain(): Boolean = tls.get() != null

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) sink(message) else TalkbackLog.i(message)
    }

    fun <T> scoped(sessionId: String, traceToken: String, block: () -> T): T {
        val prev = tls.get()
        val scope = Scope(sessionId = sessionId, traceToken = traceToken)
        tls.set(scope)
        val onCoordinator = CoordinatorTaskAttribution.isCoordinatorThread()
        log(
            "ACCEPT_BIND_DRAIN_SPAN stage=SCOPE_BEGIN session=$sessionId trace=$traceToken " +
                "onCoordinator=$onCoordinator thread=${Thread.currentThread().name}",
        )
        try {
            return block()
        } finally {
            logSummary(scope, onCoordinator)
            log(
                "ACCEPT_BIND_DRAIN_SPAN stage=SCOPE_END session=$sessionId trace=$traceToken " +
                    "onCoordinator=$onCoordinator nestedDrainPasses=${scope.nestedDrainPasses} " +
                    "duplicateItems=${scope.duplicateItemCount} uniqueItems=${scope.seenItemKeys.size}",
            )
            if (prev != null) tls.set(prev) else tls.remove()
        }
    }

    private fun logSummary(scope: Scope, onCoordinator: Boolean) {
        log(
            "ACCEPT_BIND_DRAIN_SUMMARY session=${scope.sessionId} trace=${scope.traceToken} " +
                "nestedDrainPasses=${scope.nestedDrainPasses} duplicateItems=${scope.duplicateItemCount} " +
                "uniqueItems=${scope.seenItemKeys.size} onCoordinator=$onCoordinator",
        )
    }

    fun noteItemKey(key: String): Boolean {
        val scope = tls.get() ?: return false
        if (!scope.seenItemKeys.add(key)) {
            scope.duplicateItemCount += 1
            log(
                "ACCEPT_BIND_DRAIN_DUP session=${scope.sessionId} trace=${scope.traceToken} " +
                    "itemKey=$key count=${scope.duplicateItemCount}",
            )
            return true
        }
        return false
    }

    fun markTopDrainStarting() {
        val scope = tls.get() ?: return
        if (scope.topDrainStarted) {
            onNestedDrainEnter(null)
        } else {
            scope.topDrainStarted = true
        }
    }

    fun onNestedDrainEnter(factTypes: Set<Int>?) {
        val scope = tls.get() ?: return
        scope.nestedDrainPasses += 1
        log(
            "ACCEPT_BIND_DRAIN_SPAN stage=NESTED_DRAIN_ENTER session=${scope.sessionId} " +
                "trace=${scope.traceToken} pass=${scope.nestedDrainPasses} factTypes=${formatFactTypes(factTypes)} " +
                "onCoordinator=${CoordinatorTaskAttribution.isCoordinatorThread()}",
        )
    }

    fun <T> span(
        stage: String,
        detail: String = "-",
        obligation: Obligation = Obligation.OTHER,
        block: () -> T,
    ): T {
        val scope = tls.get() ?: return block()
        val t0 = System.currentTimeMillis()
        val onCoordinator = CoordinatorTaskAttribution.isCoordinatorThread()
        try {
            return block()
        } finally {
            val execMs = System.currentTimeMillis() - t0
            log(
                "ACCEPT_BIND_DRAIN_SPAN stage=$stage session=${scope.sessionId} trace=${scope.traceToken} " +
                    "obligation=${obligation.name} detail=$detail execMs=$execMs " +
                    "onCoordinator=$onCoordinator thread=${Thread.currentThread().name}",
            )
        }
    }

    fun <T> ifActive(
        stage: String,
        detail: String = "-",
        obligation: Obligation = Obligation.OTHER,
        block: () -> T,
    ): T {
        if (tls.get() == null) return block()
        return span(stage, detail, obligation, block)
    }

    fun formatFactTypes(factTypes: Set<Int>?): String =
        factTypes?.sorted()?.joinToString(",") ?: "default"

    fun itemKey(
        factType: Int,
        factDigestHex: String,
        peerModuleId: String,
        membershipVersion: Long?,
        mediaKeyEpoch: Long?,
    ): String =
        "ft=$factType digest=$factDigestHex peer=$peerModuleId mv=${membershipVersion ?: "-"} mk=${mediaKeyEpoch ?: "-"}"
}
