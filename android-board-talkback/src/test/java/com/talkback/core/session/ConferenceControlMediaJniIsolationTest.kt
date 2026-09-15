package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fault injection for P0.1b / P0.1c: one peer's WebRTC JNI must not stall
 * conference control facts on the coordinator thread.
 */
class ConferenceControlMediaJniIsolationTest {

    @Test
    fun blockedSrdOnM03_doesNotBlockM04MemberAccepted() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val facts = CopyOnWriteArrayList<String>()
        val srdEntered = CountDownLatch(1)
        val srdHold = CountDownLatch(1)
        val m04Accepted = CountDownLatch(1)
        try {
            coordinator.execute {
                facts += "MEMBER_ACCEPTED M03"
                ConferenceMediaJniAffinity.dispatch(media, SESSION, "M03") {
                    srdEntered.countDown()
                    srdHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("M03 SRD must start", srdEntered.await(1, TimeUnit.SECONDS))

            coordinator.execute {
                facts += "MEMBER_ACCEPTED M04"
                m04Accepted.countDown()
            }
            assertTrue(
                "M04 MEMBER_ACCEPTED must proceed while M03 SRD is blocked",
                m04Accepted.await(500, TimeUnit.MILLISECONDS)
            )
            assertTrue(facts.contains("MEMBER_ACCEPTED M03"))
            assertTrue(facts.contains("MEMBER_ACCEPTED M04"))
        } finally {
            srdHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedAddIceOnM02_doesNotBlockM04MemberAccepted() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val facts = CopyOnWriteArrayList<String>()
        val iceEntered = CountDownLatch(1)
        val iceHold = CountDownLatch(1)
        val iceApplied = AtomicBoolean(false)
        val m04Accepted = CountDownLatch(1)
        try {
            coordinator.execute {
                facts += "ICE_RECV M02"
                ConferenceMediaJniAffinity.dispatch(media, SESSION, "M02") {
                    iceEntered.countDown()
                    iceHold.await(5, TimeUnit.SECONDS)
                    iceApplied.set(true)
                    facts += "ICE_APPLIED M02"
                }
            }
            assertTrue("M02 addIceCandidate must start on edge executor", iceEntered.await(1, TimeUnit.SECONDS))

            coordinator.execute {
                facts += "MEMBER_ACCEPTED M04"
                m04Accepted.countDown()
            }
            assertTrue(
                "M04 MEMBER_ACCEPTED must proceed while M02 addIceCandidate is blocked",
                m04Accepted.await(500, TimeUnit.MILLISECONDS)
            )
            assertFalse("M02 ICE JNI must still be blocked", iceApplied.get())
            assertTrue(facts.contains("ICE_RECV M02"))
            assertTrue(facts.contains("MEMBER_ACCEPTED M04"))
            assertFalse(facts.contains("ICE_APPLIED M02"))
        } finally {
            iceHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedPlaybackJniOnM03_doesNotBlockM04MemberAccepted() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val playbackHold = CountDownLatch(1)
        val playbackEntered = CountDownLatch(1)
        val m04Accepted = CountDownLatch(1)
        try {
            coordinator.execute {
                ConferenceMediaJniAffinity.dispatch(media, SESSION, "M03") {
                    playbackEntered.countDown()
                    playbackHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("M03 playback JNI must start", playbackEntered.await(1, TimeUnit.SECONDS))
            coordinator.execute { m04Accepted.countDown() }
            assertTrue(
                "M04 MEMBER_ACCEPTED must proceed while M03 playback JNI is blocked",
                m04Accepted.await(500, TimeUnit.MILLISECONDS)
            )
        } finally {
            playbackHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedRelayJni_doesNotBlockM02MemberAccepted() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val relayHold = CountDownLatch(1)
        val relayEntered = CountDownLatch(1)
        val m02Accepted = CountDownLatch(1)
        try {
            coordinator.execute {
                ConferenceMediaJniAffinity.dispatch(
                    media,
                    SESSION,
                    ConferenceMediaJniAffinity.RELAY_FANOUT_PEER
                ) {
                    relayEntered.countDown()
                    relayHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("relay JNI must start off coordinator", relayEntered.await(1, TimeUnit.SECONDS))
            coordinator.execute { m02Accepted.countDown() }
            assertTrue(
                "M02 MEMBER_ACCEPTED must proceed while session relay JNI is blocked",
                m02Accepted.await(500, TimeUnit.MILLISECONDS)
            )
        } finally {
            relayHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedIceRestartCreateOfferOnM03_doesNotBlockM02MemberAccepted() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val offerHold = CountDownLatch(1)
        val offerEntered = CountDownLatch(1)
        val m02Accepted = CountDownLatch(1)
        val dispatched = AtomicBoolean(false)
        try {
            coordinator.execute {
                dispatched.set(true)
                ConferenceMediaJniAffinity.dispatch(media, SESSION, "M03") {
                    offerEntered.countDown()
                    offerHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("ICE restart dispatch must enqueue", dispatched.get() || offerEntered.await(1, TimeUnit.SECONDS))
            assertTrue("M03 createOffer must start on edge", offerEntered.await(1, TimeUnit.SECONDS))
            coordinator.execute { m02Accepted.countDown() }
            assertTrue(
                "M02 MEMBER_ACCEPTED must proceed while M03 ICE-restart createOffer is blocked",
                m02Accepted.await(500, TimeUnit.MILLISECONDS)
            )
        } finally {
            offerHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedRefreshAudioLevelOnM03_doesNotBlockM04MemberAccepted() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val statsHold = CountDownLatch(1)
        val statsEntered = CountDownLatch(1)
        val m04Accepted = CountDownLatch(1)
        try {
            coordinator.execute {
                ConferenceMediaJniAffinity.dispatch(
                    media,
                    SESSION,
                    "M03",
                    EdgeMediaTaskType.AUDIO_LEVEL_REFRESH,
                    origin = "refreshAudioLevel"
                ) {
                    statsEntered.countDown()
                    statsHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("M03 getStats must start off coordinator", statsEntered.await(1, TimeUnit.SECONDS))
            coordinator.execute { m04Accepted.countDown() }
            assertTrue(
                "M04 MEMBER_ACCEPTED must proceed while M03 refreshAudioLevel is blocked",
                m04Accepted.await(500, TimeUnit.MILLISECONDS)
            )
        } finally {
            statsHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedStopCaptureOnM03_doesNotBlockMeetingEndTeardown() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val captureHold = CountDownLatch(1)
        val captureEntered = CountDownLatch(1)
        val teardownContinued = CountDownLatch(1)
        try {
            coordinator.execute {
                ConferenceMediaJniAffinity.dispatch(media, SESSION, "M03") {
                    captureEntered.countDown()
                    captureHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("M03 stopCapture must start off coordinator", captureEntered.await(1, TimeUnit.SECONDS))
            coordinator.execute { teardownContinued.countDown() }
            assertTrue(
                "MEETING_END teardown must proceed while M03 stopCapture is blocked",
                teardownContinued.await(500, TimeUnit.MILLISECONDS)
            )
        } finally {
            captureHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun blockedReleaseOnM03_doesNotBlockMeetingEndTeardown() {
        val coordinator = Executors.newSingleThreadExecutor { r ->
            Thread(r, "talkback-coordinator")
        }
        val media = PeerMediaExecutors(threadNamePrefix = "test-edge")
        val releaseHold = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val teardownContinued = CountDownLatch(1)
        try {
            coordinator.execute {
                ConferenceMediaJniAffinity.dispatch(
                    media,
                    SESSION,
                    "M03",
                    origin = "releaseSessionMedia"
                ) {
                    releaseEntered.countDown()
                    releaseHold.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("M03 release must start off coordinator", releaseEntered.await(1, TimeUnit.SECONDS))
            coordinator.execute { teardownContinued.countDown() }
            assertTrue(
                "MEETING_END teardown must proceed while M03 release is blocked",
                teardownContinued.await(500, TimeUnit.MILLISECONDS)
            )
        } finally {
            releaseHold.countDown()
            media.shutdownAll()
            coordinator.shutdownNow()
        }
    }

    companion object {
        private const val SESSION = "conf-1"
    }
}
