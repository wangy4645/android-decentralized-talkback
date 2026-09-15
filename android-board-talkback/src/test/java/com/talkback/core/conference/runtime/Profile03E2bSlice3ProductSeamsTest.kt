package com.talkback.core.conference.runtime

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-0058 E2b Slice 3 — R13/R14 on product C-IG-01 seams
 * ([AndroidWifiMulticastLockSeam] + [MulticastSocketTransportRebindSeam]).
 *
 * Does not wire TalkbackSession / GROUP PTT / ADR-0056 Meeting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Profile03E2bSlice3ProductSeamsTest {
    @Test
    fun slice3_productFactory_doesNotInstallFakeSeams() {
        val rt = AndroidRuntimeResources.forAndroidProduct(appContext())
        assertTrue(rt.multicastLock is AndroidWifiMulticastLockSeam)
        assertTrue(rt.rebindSeam is MulticastSocketTransportRebindSeam)
        assertFalse(rt.multicastLock is FakeMulticastLockSeam)
        assertFalse(rt.rebindSeam is FakeTransportRebindSeam)
    }

    @Test
    fun r13a_androidMulticastLock_silenceEmptyTopK_doesNotRelease() {
        val rt = AndroidRuntimeResources.forAndroidProduct(appContext())
        assertTrue(rt.beginTransportScope(mediaHandle("sock-1"), 0L))
        assertTrue(rt.multicastLock.isHeld())
        assertTrue(rt.rebindSeam.isSocketOpen())
        val releasesBefore = rt.multicastLock.releaseCount()

        rt.onSilenceOrEmptyTopKOrSoftReclaim()
        rt.decoderPool.release("any")
        rt.selectTopK(1L)

        assertTrue(rt.multicastLock.isHeld())
        assertEquals(releasesBefore, rt.multicastLock.releaseCount())
        assertTrue(rt.rebindSeam.isSocketOpen())
        assertTrue(rt.transportState().active)
    }

    @Test
    fun r13b_androidMulticastLock_transportScopeEnd_releasesLockAndClosesSocket() {
        val rt = AndroidRuntimeResources.forAndroidProduct(appContext())
        assertTrue(rt.beginTransportScope(mediaHandle("sock-1"), 0L))
        assertTrue(rt.multicastLock.isHeld())
        assertTrue(rt.rebindSeam.isSocketOpen())

        rt.endTransportScope()

        assertFalse(rt.multicastLock.isHeld())
        assertEquals(1, rt.multicastLock.releaseCount())
        assertFalse(rt.rebindSeam.isSocketOpen())
        assertFalse(rt.transportState().active)
    }

    @Test
    fun r14_realSocketRebind_preservesAuthorityAndFence_lockStaysHeld() {
        val rt = AndroidRuntimeResources.forAndroidProduct(appContext())
        rt.install(AdmittedMediaSource("S1", 7L))
        rt.install(AdmittedMediaSource("S2", 7L))
        assertTrue(rt.hardFence("S1", 7L))
        assertTrue(rt.beginTransportScope(mediaHandle("sock-old"), 0L))

        val seam = rt.rebindSeam as MulticastSocketTransportRebindSeam
        val firstSocket = seam.currentSocket
        assertNotNull(firstSocket)
        assertFalse(firstSocket!!.isClosed)
        val firstId = rt.transportState().handle!!.id

        val before = rt.authoritySnapshot()
        assertEquals(ExecutionFenceState.HARD_FENCED, before.fenceByIdentity["S1"])
        assertEquals(ExecutionFenceState.OPEN, before.fenceByIdentity["S2"])

        val newHandle = rt.rebindTransport(mediaHandle("sock-new"), nowMs = 10L)
        val secondSocket = seam.currentSocket
        assertNotNull(secondSocket)
        assertTrue(firstSocket.isClosed)
        assertFalse(secondSocket === firstSocket)
        assertFalse(secondSocket!!.isClosed)
        assertEquals(1, seam.rebindCount)
        assertTrue(seam.lastJoinAttempted)
        assertNotEquals(firstId, newHandle.id)
        assertNotEquals("sock-old", rt.transportState().handle?.id)

        // Rebind is transport-only: lock remains held (not a transport-scope end).
        assertTrue(rt.multicastLock.isHeld())
        assertEquals(0, rt.multicastLock.releaseCount())

        val after = rt.authoritySnapshot()
        assertEquals(before, after)
        assertEquals(before.admittedIdentities, after.admittedIdentities)
        assertEquals(before.incarnationByIdentity, after.incarnationByIdentity)
        assertEquals(before.fenceByIdentity, after.fenceByIdentity)
        assertEquals(before.membershipIdentity, after.membershipIdentity)
        assertEquals(before.conferenceGeneration, after.conferenceGeneration)
        assertEquals(before.anchorEpoch, after.anchorEpoch)
        assertEquals(ExecutionFenceState.HARD_FENCED, after.fenceByIdentity["S1"])
        assertTrue(
            rt.evidenceSnapshot().any { it.kind == RuntimeDegradationKind.REBIND_DISRUPTION },
        )
    }

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    private fun mediaHandle(id: String): TransportHandle =
        TransportHandle(
            id = id,
            endpoint =
                MediaGroupEndpointBinding(
                    multicastAddress = "239.255.42.99",
                    mediaPort = 46999,
                    underlayScopeId = "slice3-test",
                ),
        )
}
