package com.talkback.core.conference.runtime

import android.content.Context
import com.talkback.core.conference.transport.AndroidConferenceMulticastSocketBinder
import com.talkback.core.conference.transport.ConferenceMulticastSocketBinder
import com.talkback.core.conference.transport.MulticastLockPolicy

/**
 * Coordinates Android runtime resources (E2b-05 / C-IG-01).
 *
 * C-E2B05-06: MUST NOT become authority owner; cannot legalize execution.
 * C-E2B05-05: evidence is payload only — not Q12 Health.
 *
 * Default constructor seams are harness fakes (E2b-05 R12/R15/R16). Product
 * assembly is [forAndroidProduct] (Slice 3 / R13/R14).
 */
class AndroidRuntimeResources(
    val selection: ConferenceMediaSelectionRuntime = ConferenceMediaSelectionRuntime(),
    val decoderPool: LiveDecoderPool = LiveDecoderPool(),
    val multicastLock: MulticastLockSeam = FakeMulticastLockSeam(),
    val audioTrack: RecordingAudioTrackSeam = RecordingAudioTrackSeam(),
    val rebindSeam: TransportRebindSeam = FakeTransportRebindSeam(),
) {
    val jitterAllocator = JitterSourceAllocator(selection.registry)

    private var transport = TransportScopeState(active = false, handle = null)
    private val evidence = mutableListOf<RuntimeDegradationEvidence>()

    fun evidenceSnapshot(): List<RuntimeDegradationEvidence> = evidence.toList()

    fun transportState(): TransportScopeState = transport

    fun install(source: AdmittedMediaSource) = selection.install(source)

    fun hardFence(sourceIdentity: String, incarnationId: Long): Boolean {
        val ok = selection.hardFence(sourceIdentity, incarnationId)
        if (ok) decoderPool.hardFenceRelease(sourceIdentity, incarnationId)
        return ok
    }

    fun observeVoice(o: VoiceLevelObservation) = selection.observeVoice(o)

    fun selectTopK(nowMs: Long) = selection.selectTopK(nowMs)

    /** Begin Conference media transport scope — optional MulticastLock, then bind socket. */
    fun beginTransportScope(handle: TransportHandle, nowMs: Long): Boolean {
        when (handle.multicastLockPolicy) {
            MulticastLockPolicy.WIFI_MULTICAST_LOCK -> {
                if (!multicastLock.acquire()) {
                    record(RuntimeDegradationKind.MULTICAST_LOCK_UNAVAILABLE, "acquire failed", nowMs)
                    return false
                }
            }
            MulticastLockPolicy.NONE -> {
                // Non-WiFi mesh netdev — MulticastLock is not a hard dependency.
            }
        }
        val bound = rebindSeam.open(handle)
        transport =
            TransportScopeState(
                active = true,
                handle = bound,
                membershipIdentity = transport.membershipIdentity,
                conferenceGeneration = transport.conferenceGeneration,
                anchorEpoch = transport.anchorEpoch,
            )
        return true
    }

    /**
     * C-E2B05-01: silence / V=0 / empty Top-K / decoder reclaim MUST NOT release lock.
     * Only transport-scope end closes the socket and releases the lock.
     */
    fun endTransportScope() {
        if (!transport.active) return
        rebindSeam.close()
        if (multicastLock.isHeld()) {
            multicastLock.release()
        }
        transport = transport.copy(active = false, handle = null)
    }

    fun onSilenceOrEmptyTopKOrSoftReclaim() {
        // Intentionally no MulticastLock release (R13-A / C-E2B05-01).
    }

    fun allocateJitter(sourceIdentity: String, incarnationId: Long, nowMs: Long): JitterAllocResult {
        val result = jitterAllocator.allocate(sourceIdentity, incarnationId)
        when (result.outcome) {
            JitterAllocOutcome.REJECT_NEW ->
                record(RuntimeDegradationKind.JITTER_CAP_EXHAUSTED, "REJECT_NEW", nowMs)
            JitterAllocOutcome.NOT_ADMITTED ->
                record(RuntimeDegradationKind.RESOURCE_ALLOCATION_REJECTED, "not admitted", nowMs)
            else -> Unit
        }
        return result
    }

    fun allocateDecoder(
        sourceIdentity: String,
        incarnationId: Long,
        nowMs: Long,
        forTransitionOnly: Boolean = false,
    ): LiveDecoderSlot? {
        val inst = selection.registry.get(sourceIdentity)
        if (inst == null || inst.source.incarnationId != incarnationId) {
            record(RuntimeDegradationKind.RESOURCE_ALLOCATION_REJECTED, "decoder not admitted", nowMs)
            return null
        }
        val slot =
            decoderPool.allocate(sourceIdentity, incarnationId, nowMs, forTransitionOnly)
        if (slot == null) {
            record(RuntimeDegradationKind.RESOURCE_ALLOCATION_REJECTED, "decoder cap", nowMs)
        }
        return slot
    }

    /**
     * C-E2B05-02: rebuild transport handle only — no authority mutation.
     */
    fun rebindTransport(newHandle: TransportHandle, nowMs: Long): TransportHandle {
        val rebuilt = rebindSeam.rebind(newHandle)
        transport = transport.copy(handle = rebuilt)
        record(RuntimeDegradationKind.REBIND_DISRUPTION, "rebind handle=${rebuilt.id}", nowMs)
        return rebuilt
    }

    fun playout(block: MixedBlock, nowMs: Long): Boolean {
        val ok = audioTrack.write(block, nowMs)
        if (!ok) {
            val kind = audioTrack.failKind ?: RuntimeDegradationKind.AUDIOTRACK_WRITE_FAILURE
            record(kind, "playout write failed", nowMs)
        }
        return ok
    }

    fun authoritySnapshot(): AuthoritySnapshot {
        val installed = selection.registry.installedSnapshot()
        return AuthoritySnapshot(
            admittedIdentities = installed.keys.toSet(),
            incarnationByIdentity = installed.mapValues { it.value.source.incarnationId },
            fenceByIdentity = installed.mapValues { it.value.fence },
            membershipIdentity = transport.membershipIdentity,
            conferenceGeneration = transport.conferenceGeneration,
            anchorEpoch = transport.anchorEpoch,
        )
    }

    private fun record(kind: RuntimeDegradationKind, detail: String, atMs: Long) {
        evidence += RuntimeDegradationEvidence(kind, detail, atMs)
    }

    companion object {
        /**
         * Product C-IG-01 assembly: real Android WifiManager.MulticastLock +
         * MulticastSocket rebind. Does not wire TalkbackSession / ADR-0056 Meeting.
         */
        fun forAndroidProduct(
            context: Context,
            socketBinder: ConferenceMulticastSocketBinder =
                AndroidConferenceMulticastSocketBinder.from(context),
        ): AndroidRuntimeResources =
            AndroidRuntimeResources(
                multicastLock = AndroidWifiMulticastLockSeam.from(context),
                rebindSeam = MulticastSocketTransportRebindSeam(socketBinder),
            )
    }
}
