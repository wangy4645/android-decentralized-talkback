package com.talkback.lab.dalpha

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * D-α lab spike — device instrumented only.
 * Authorization: conference-srd-d-alpha-lab-spike-authorization-001.md
 *
 * V4 gate: hold Factory A's signaling-thread callback, prove Factory B SRD still completes.
 */
class DAlphaLabSpikeInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun v1_factoriesAreDistinctObjects() {
        val pair = DAlphaLabFactories.create(context)
        try {
            assertTrue(pair.factoriesDistinct)
            assertNotSame(pair.factoryA, pair.factoryB)
        } finally {
            DAlphaLabFactories.dispose(pair, disposeAdm = true)
        }
    }

    @Test
    fun v2_sharedAdmConstructsTwoFactories() {
        val pair = DAlphaLabFactories.create(context)
        try {
            assertTrue(pair.factoriesDistinct)
            createMinimalPeerConnection(pair.factoryA).close()
            createMinimalPeerConnection(pair.factoryB).close()
        } finally {
            DAlphaLabFactories.dispose(pair, disposeAdm = true)
        }
    }

    /**
     * V4 architecture gate.
     *
     * Hold Factory A on its SdpObserver callback (signaling-thread domain occupancy).
     * While A is held, Factory B must complete setRemoteDescription independently.
     * If B stalls (shared native worker), this fails → D-α FAIL.
     */
    @Test
    fun v4_factoryBCompletesSrdWhileFactoryASignalingHeld() {
        val pair = DAlphaLabFactories.create(context)
        val pcA = createMinimalPeerConnection(pair.factoryA)
        val pcB = createMinimalPeerConnection(pair.factoryB)

        val aHeld = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val aCallbackDone = CountDownLatch(1)
        val aThreadName = AtomicReference<String>()

        // Occupy Factory A signaling domain: block inside SdpObserver (typical signaling thread).
        Thread({
            val offer = createOfferBlocking(pcA)
            setLocalBlocking(pcA, SessionDescription(SessionDescription.Type.OFFER, offer))
            pcA.setRemoteDescription(
                object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        aThreadName.set(Thread.currentThread().name)
                        aHeld.countDown()
                        releaseA.await(30, TimeUnit.SECONDS)
                        aCallbackDone.countDown()
                    }
                    override fun onCreateFailure(error: String?) {
                        aHeld.countDown()
                        aCallbackDone.countDown()
                    }
                    override fun onSetFailure(error: String?) {
                        aHeld.countDown()
                        aCallbackDone.countDown()
                    }
                },
                SessionDescription(SessionDescription.Type.ANSWER, minimalAnswerSdp()),
            )
        }, "dalpha-a-hold").start()

        assertTrue("Factory A must enter signaling hold", aHeld.await(15, TimeUnit.SECONDS))

        // While A held: B signalingState + full SRD path must progress.
        val bStateOk = AtomicBoolean(false)
        val bCompleted = AtomicBoolean(false)
        val bFailed = AtomicBoolean(false)
        val bLatch = CountDownLatch(1)
        val bThreadName = AtomicReference<String>()

        Thread({
            try {
                // Must not hang if domains are independent.
                val state = pcB.signalingState()
                bStateOk.set(state != PeerConnection.SignalingState.CLOSED)
                val offerB = createOfferBlocking(pcB)
                setLocalBlocking(pcB, SessionDescription(SessionDescription.Type.OFFER, offerB))
                pcB.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) = Unit
                        override fun onSetSuccess() {
                            bThreadName.set(Thread.currentThread().name)
                            bCompleted.set(true)
                            bLatch.countDown()
                        }
                        override fun onCreateFailure(error: String?) {
                            bFailed.set(true)
                            bLatch.countDown()
                        }
                        override fun onSetFailure(error: String?) {
                            bFailed.set(true)
                            bLatch.countDown()
                        }
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, minimalAnswerSdp()),
                )
            } catch (_: Exception) {
                bFailed.set(true)
                bLatch.countDown()
            }
        }, "dalpha-b-srd").start()

        val bFinished = bLatch.await(15, TimeUnit.SECONDS)
        assertFalse("Factory A must still be held during B attempt", aCallbackDone.await(0, TimeUnit.MILLISECONDS))

        releaseA.countDown()
        aCallbackDone.await(10, TimeUnit.SECONDS)

        assertTrue("Factory B signalingState readable while A held", bStateOk.get())
        assertTrue(
            "D-α FAIL V4: Factory B SRD did not complete while Factory A signaling held " +
                "(aThread=${aThreadName.get()} bThread=${bThreadName.get()})",
            bFinished && bCompleted.get() && !bFailed.get(),
        )

        pcA.close()
        pcB.close()
        DAlphaLabFactories.dispose(pair, disposeAdm = true)
    }

    @Test
    fun v5_disposeFactoryADoesNotBreakFactoryBSignalingRead() {
        val pair = DAlphaLabFactories.create(context)
        val pcA = createMinimalPeerConnection(pair.factoryA)
        val pcB = createMinimalPeerConnection(pair.factoryB)
        runCatching { pcA.close() }
        runCatching { pair.factoryA.dispose() }
        val state = pcB.signalingState()
        assertNotEquals(PeerConnection.SignalingState.CLOSED, state)
        pcB.close()
        // Dispose B + ADM only after B still worked.
        runCatching { pair.factoryB.dispose() }
        runCatching { pair.audioDeviceModule.release() }
    }

    private fun createMinimalPeerConnection(factory: org.webrtc.PeerConnectionFactory): PeerConnection {
        val config = PeerConnection.RTCConfiguration(emptyList())
        return requireNotNull(
            factory.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(
                    receiver: org.webrtc.RtpReceiver?,
                    streams: Array<out org.webrtc.MediaStream>?,
                ) = Unit
            }),
        )
    }

    private fun createOfferBlocking(pc: PeerConnection): String {
        val latch = CountDownLatch(1)
        val sdp = AtomicReference<String>()
        val err = AtomicReference<String>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                sdp.set(desc?.description)
                latch.countDown()
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                err.set(error)
                latch.countDown()
            }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
        assertTrue("createOffer timeout err=${err.get()}", latch.await(10, TimeUnit.SECONDS))
        return requireNotNull(sdp.get()) { "createOffer failed: ${err.get()}" }
    }

    private fun setLocalBlocking(pc: PeerConnection, desc: SessionDescription) {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) = Unit
            override fun onSetSuccess() {
                ok.set(true)
                latch.countDown()
            }
            override fun onCreateFailure(error: String?) {
                latch.countDown()
            }
            override fun onSetFailure(error: String?) {
                latch.countDown()
            }
        }, desc)
        assertTrue("setLocalDescription timeout", latch.await(10, TimeUnit.SECONDS))
        assertTrue("setLocalDescription failed", ok.get())
    }

    private fun minimalAnswerSdp(): String =
        "v=0\r\n" +
            "o=- 0 0 IN IP4 127.0.0.1\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=group:BUNDLE 0\r\n" +
            "a=msid-semantic: WMS\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=rtcp:9 IN IP4 0.0.0.0\r\n" +
            "a=ice-ufrag:ufrag\r\n" +
            "a=ice-pwd:pwd\r\n" +
            "a=fingerprint:sha-256 00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00\r\n" +
            "a=setup:active\r\n" +
            "a=mid:0\r\n" +
            "a=sendrecv\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n"
}
