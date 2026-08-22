package com.talkback.core.session

/**
 * B2-1 Commit 2: lease admission before conference SRD native path (IA-001 AUTH-1/2).
 * Non-blocking: BUSY / QUARANTINED return without invoking [block].
 */
object ConferenceSrdNativeDomainAdmission {

    sealed class Outcome<out T> {
        data class Completed<T>(val value: T) : Outcome<T>()
        data class Busy(val holderEdgeKey: String) : Outcome<Nothing>()
        data object Quarantined : Outcome<Nothing>()
    }

    fun <T> runWithLease(
        domain: ConferenceNativeExecutionDomain,
        edgeKey: String,
        block: () -> T,
    ): Outcome<T> =
        when (val request = domain.requestLease(edgeKey)) {
            is ConferenceNativeExecutionDomain.RequestResult.Busy ->
                Outcome.Busy(request.holderEdgeKey)
            ConferenceNativeExecutionDomain.RequestResult.Quarantined ->
                Outcome.Quarantined
            ConferenceNativeExecutionDomain.RequestResult.Granted -> {
                try {
                    Outcome.Completed(block())
                } finally {
                    domain.releaseLease(
                        edgeKey,
                        ConferenceNativeExecutionDomain.ReleaseReason.NORMAL,
                    )
                }
            }
        }
}
