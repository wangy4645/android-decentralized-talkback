package com.talkback.core.conference.authority

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaSelectionRuntime
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.wire.ConferenceWireIngress
import com.talkback.core.conference.wire.WireIngressContext
import com.talkback.core.conference.wire.WireIngressResult
import com.talkback.core.conference.wire.WireReplayState

/**
 * P01 → P02/P03 wiring facade (E2b-07).
 * Authority never flows upward from packet/P02/P03.
 */
class AuthorityWiringRuntime(
    val store: AuthorityFactStore = AuthorityFactStore(),
    val selection: ConferenceMediaSelectionRuntime = ConferenceMediaSelectionRuntime(),
    val pipeline: MediaExecutionPipeline = MediaExecutionPipeline(selection),
) {
    /**
     * Propagate current derived AdmittedMediaSource into P03 registry.
     * Does not invent authority — only installs store-derived capability.
     */
    fun syncAdmittedToRuntime(sourceIdentity: String) {
        val admitted = store.derivedAdmitted(sourceIdentity) ?: return
        selection.install(admitted)
    }

    fun syncAllAdmittedToRuntime() {
        for (id in store.currentAdmitted().keys) {
            syncAdmittedToRuntime(id)
        }
    }

    /**
     * Build WireIngressContext from derived capability only.
     * Returns null if capability is non-current / absent.
     */
    fun wireIngressContext(
        sourceIdentity: String,
        roc: Int = 0,
        replay: WireReplayState? = null,
    ): WireIngressContext? {
        val cap = store.derivedWire(sourceIdentity) ?: return null
        return WireIngressContext(
            key = cap.key,
            installedBinding = cap.binding,
            replay = replay,
            roc = roc,
        )
    }

    fun admitWire(
        sourceIdentity: String,
        datagram: ByteArray,
        roc: Int = 0,
        replay: WireReplayState? = null,
    ): WireIngressResult {
        val ctx =
            wireIngressContext(sourceIdentity, roc, replay)
                ?: return WireIngressResult.Rejected(
                    com.talkback.core.conference.wire.WireOwningSeam.Q3,
                    "SOURCE_PACKET_IDENTITY_MISMATCH",
                    "no current derived wire capability",
                )
        return ConferenceWireIngress.admit(datagram, ctx)
    }

    /**
     * C-E2B07-02 fail-closed revocation:
     * 1) mark non-current / remove derived caps
     * 2) immediate HARD FENCE old P03 incarnation
     * 3) P02 capability already removed in step 1
     */
    fun revokeSource(sourceIdentity: String, incarnationId: Long): Boolean {
        val commit = store.revokeSource(sourceIdentity, incarnationId) ?: return false
        // Immediate logical fence — MUST NOT wait for teardown.
        pipeline.hardFence(commit.sourceIdentity, commit.incarnationId)
        selection.hardFence(commit.sourceIdentity, commit.incarnationId)
        return true
    }

    fun retireMediaKeyEpoch(mediaKeyEpoch: Long): Int {
        val commits = store.retireMediaKeyEpoch(mediaKeyEpoch)
        for (c in commits) {
            pipeline.hardFence(c.sourceIdentity, c.incarnationId)
            selection.hardFence(c.sourceIdentity, c.incarnationId)
        }
        return commits.size
    }

    /**
     * C-E2B07-03: install new epoch/source incarnation as fresh capability.
     * Caller supplies verified facts; old epoch must already be retired/fenced separately.
     */
    fun installNewEpochSource(
        source: SourceAuthorizationFact,
        key: MediaKeyContextFact,
    ) {
        require(source.mediaKeyEpoch == key.mediaKeyEpoch)
        require(source.current && key.current)
        store.acceptVerifiedKey(key)
        store.acceptVerifiedSource(source)
        syncAdmittedToRuntime(source.sourceIdentity)
    }

    fun currentAdmitted(sourceIdentity: String): AdmittedMediaSource? =
        store.derivedAdmitted(sourceIdentity)
}
