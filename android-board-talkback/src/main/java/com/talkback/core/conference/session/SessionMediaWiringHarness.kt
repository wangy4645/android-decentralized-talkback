package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.conference.wire.WireIngressResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2 Slice 2 — lifecycle harness (no audio capacity testing).
 */
object SessionMediaWiringHarness {
    private val nextSsrc = AtomicInteger(0x22000000)
    private val nextIncarnation = AtomicInteger(1)

    fun sessionFact(
        sessionId: String,
        generation: Long = 1L,
        mediaKeyEpoch: Long = 1L,
    ): ConferenceSessionMediaFact =
        ConferenceSessionMediaFact(
            sessionId = sessionId,
            generation = generation,
            mediaKeyEpoch = mediaKeyEpoch,
            endpoint =
                MediaGroupEndpointBinding(
                    multicastAddress = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
                    mediaPort = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
                    underlayScopeId = "session-wiring-$sessionId",
                ),
            networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            masterKey = Phase1MediaHarness.masterKey.copyOf(),
            masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
            keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
        )

    fun memberBinding(
        moduleId: String,
        mediaKeyEpoch: Long = 1L,
        incarnationId: Long = nextIncarnation.getAndIncrement().toLong(),
        ssrc: Int = nextSsrc.getAndIncrement(),
        admissionKeySuffix: Int = moduleId.hashCode() and 0xFF,
    ): MemberBindingFact {
        val key = Phase1MediaHarness.threeSourceFixtures.first().sourceAdmissionKey48.copyOf()
        key[5] = admissionKeySuffix.toByte()
        return MemberBindingFact(
            moduleId = moduleId,
            incarnationId = incarnationId,
            ssrc = ssrc,
            sourceAdmissionKey48 = key,
            mediaKeyEpoch = mediaKeyEpoch,
        )
    }

    fun protectedPacket(
        binding: MemberBindingFact,
        mediaSlot: Int = 100,
        audioLevel: Int = 10,
    ): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = audioLevel,
                frequencyHz = 440.0,
            )
        val opus = ByteArray(72) { (it and 0xff).toByte() }
        return Phase1MediaHarness.buildProtectedPacket(
            fixture = fixture,
            mediaSlot = mediaSlot,
            opusPayload = opus,
            audioLevel = audioLevel,
        )
    }

    fun assertRuntimeEmpty(
        wiring: ConferenceSessionMediaWiring,
        sessionId: String,
    ) {
        val snap = wiring.runtimeSnapshot(sessionId)
        require(snap != null) { "session missing" }
        require(!snap.transportScopeActive) { "transport scope still active" }
        require(snap.catalogEntries == 0) { "catalog not empty: ${snap.catalogEntries}" }
        require(snap.admittedCount == 0) { "admitted not empty: ${snap.admittedCount}" }
        require(snap.activeJitterSources == 0) { "jitter alloc not empty: ${snap.activeJitterSources}" }
        require(snap.jitterBufferCount == 0) { "jitter buffers not empty: ${snap.jitterBufferCount}" }
        require(snap.liveDecoders == 0) { "live decoders not empty: ${snap.liveDecoders}" }
    }

    fun assertStopped(wiring: ConferenceSessionMediaWiring, sessionId: String) {
        require(!wiring.hasSession(sessionId)) { "session still registered" }
    }

    fun assertRejected(result: WireIngressResult) {
        require(result is WireIngressResult.Rejected) { "expected reject got $result" }
    }

    fun assertAccepted(result: WireIngressResult) {
        require(result is WireIngressResult.Accepted) { "expected accept got $result" }
    }
}
