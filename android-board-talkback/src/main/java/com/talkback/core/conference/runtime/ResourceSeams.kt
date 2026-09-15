package com.talkback.core.conference.runtime

/**
 * C-IG-01 MulticastLock seam (no new authority).
 * C-E2B05-01: lifetime follows transport scope.
 *
 * Product implementation: [AndroidWifiMulticastLockSeam] wrapping
 * `WifiManager.MulticastLock`. [FakeMulticastLockSeam] is harness-only.
 */
interface MulticastLockSeam {
    fun acquire(): Boolean
    fun release()
    fun isHeld(): Boolean
    fun acquireCount(): Int
    fun releaseCount(): Int
}

class FakeMulticastLockSeam : MulticastLockSeam {
    private var held = false
    private var acquires = 0
    private var releases = 0

    override fun acquire(): Boolean {
        held = true
        acquires += 1
        return true
    }

    override fun release() {
        if (held) {
            held = false
            releases += 1
        }
    }

    override fun isHeld(): Boolean = held

    override fun acquireCount(): Int = acquires

    override fun releaseCount(): Int = releases
}

/**
 * C-IG-01 AudioTrack playout seam.
 */
interface AudioTrackPlayoutSeam {
    /** @return false on write failure / busy. */
    fun write(block: MixedBlock, nowMs: Long): Boolean
}

class RecordingAudioTrackSeam(
    var failKind: RuntimeDegradationKind? = null,
) : AudioTrackPlayoutSeam {
    var writeAttempts: Int = 0
        private set

    override fun write(block: MixedBlock, nowMs: Long): Boolean {
        writeAttempts += 1
        return failKind == null
    }
}

/**
 * C-IG-01 interface/socket rebind seam.
 *
 * Product implementation: [MulticastSocketTransportRebindSeam].
 * [FakeTransportRebindSeam] is harness-only and MUST NOT be the product assembly.
 */
interface TransportRebindSeam {
    val rebindCount: Int

    /** Initial bind/join when Conference media transport scope begins. */
    fun open(handle: TransportHandle): TransportHandle

    /** Rebuild socket / re-join endpoint. MUST NOT mint authority. */
    fun rebind(newHandle: TransportHandle): TransportHandle

    /** Tear down socket when transport scope ends. */
    fun close()

    fun isSocketOpen(): Boolean
}

class FakeTransportRebindSeam : TransportRebindSeam {
    override var rebindCount: Int = 0
        private set

    private var open = false

    override fun open(handle: TransportHandle): TransportHandle {
        open = true
        return handle
    }

    override fun rebind(newHandle: TransportHandle): TransportHandle {
        rebindCount += 1
        open = true
        return newHandle
    }

    override fun close() {
        open = false
    }

    override fun isSocketOpen(): Boolean = open
}
