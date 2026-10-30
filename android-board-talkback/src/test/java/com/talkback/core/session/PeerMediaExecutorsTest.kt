package com.talkback.core.session

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PeerMediaExecutorsTest {

    @Test
    fun blockedPeerDoesNotSerializeOtherPeer() {
        val executors = PeerMediaExecutors(threadNamePrefix = "test-edge")
        try {
            val aEntered = CountDownLatch(1)
            val aHold = CountDownLatch(1)
            val bDone = CountDownLatch(1)
            executors.execute("session|M03") {
                aEntered.countDown()
                aHold.await(5, TimeUnit.SECONDS)
            }
            assertTrue(aEntered.await(500, TimeUnit.MILLISECONDS))
            executors.execute("session|M04") {
                bDone.countDown()
            }
            assertTrue(
                "M04 must run while M03 is held",
                bDone.await(500, TimeUnit.MILLISECONDS)
            )
            aHold.countDown()
        } finally {
            executors.shutdownAll()
        }
    }

    @Test
    fun samePeerRemainsSerial() {
        val executors = PeerMediaExecutors(threadNamePrefix = "test-edge")
        try {
            val order = mutableListOf<Int>()
            val firstHold = CountDownLatch(1)
            val secondDone = CountDownLatch(1)
            executors.execute("session|M03") {
                firstHold.await(5, TimeUnit.SECONDS)
                synchronized(order) { order.add(1) }
            }
            executors.execute("session|M03") {
                synchronized(order) { order.add(2) }
                secondDone.countDown()
            }
            Thread.sleep(80)
            synchronized(order) {
                assertTrue("second task must wait on same peer", order.isEmpty())
            }
            firstHold.countDown()
            assertTrue(secondDone.await(500, TimeUnit.MILLISECONDS))
            synchronized(order) {
                assertTrue(order == listOf(1, 2))
            }
        } finally {
            executors.shutdownAll()
        }
    }

    @Test
    fun edgeTaskObservabilitySubmitStartEndPairing() {
        val lines = mutableListOf<String>()
        val executors = PeerMediaExecutors(threadNamePrefix = "test-edge", logSink = lines::add)
        try {
            val hold = CountDownLatch(1)
            val secondDone = CountDownLatch(1)
            executors.execute("session-1|M04", EdgeMediaTaskType.PLAYBACK_CONTROL) {
                hold.await(5, TimeUnit.SECONDS)
            }
            executors.execute("session-1|M04", EdgeMediaTaskType.SRD_APPLY) {
                secondDone.countDown()
            }
            Thread.sleep(50)
            assertTrue(
                lines.any {
                    it.contains("EDGE_TASK_SUBMIT") &&
                        it.contains("taskType=PLAYBACK_CONTROL") &&
                        it.contains("category=MEDIA_CONTROL")
                }
            )
            assertTrue(
                lines.any {
                    it.contains("EDGE_TASK_SUBMIT") &&
                        it.contains("taskType=SRD_APPLY") &&
                        it.contains("category=MEDIA_CRITICAL")
                }
            )
            hold.countDown()
            assertTrue(secondDone.await(500, TimeUnit.MILLISECONDS))
            val starts = lines.filter { it.startsWith("EDGE_TASK_START") }
            val ends = lines.filter { it.startsWith("EDGE_TASK_END") }
            assertTrue(starts.size == 2)
            assertTrue(ends.size == 2)
            assertTrue(ends.all { it.contains("success=true") })
            val srdStart = starts.first { it.contains("taskType=SRD_APPLY") }
            assertTrue(srdStart.contains("queueWaitMs="))
            assertTrue(srdStart.contains("startTimestamp="))
        } finally {
            executors.shutdownAll()
        }
    }

    @Test
    fun observationBlockDoesNotStarveMediaCriticalAdmission() {
        val lines = mutableListOf<String>()
        val executors = PeerMediaExecutors(threadNamePrefix = "test-edge", logSink = lines::add)
        try {
            val observationHold = CountDownLatch(1)
            val observationStarted = CountDownLatch(1)
            val srdStarted = CountDownLatch(1)
            executors.execute(
                "session-1|M04",
                EdgeMediaTaskType.AUDIO_LEVEL_REFRESH,
                origin = "refreshAudioLevel"
            ) {
                observationStarted.countDown()
                observationHold.await(5, TimeUnit.SECONDS)
            }
            assertTrue(observationStarted.await(500, TimeUnit.MILLISECONDS))
            executors.execute(
                "session-1|M04",
                EdgeMediaTaskType.SRD_APPLY,
                origin = "conferenceApplyRemoteAnswer"
            ) {
                srdStarted.countDown()
            }
            assertTrue(
                "MEDIA_CRITICAL must START while OBSERVATION lane is blocked",
                srdStarted.await(500, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                lines.any {
                    it.contains("EDGE_TASK_START") &&
                        it.contains("taskType=SRD_APPLY") &&
                        it.contains("category=MEDIA_CRITICAL")
                }
            )
            observationHold.countDown()
        } finally {
            executors.shutdownAll()
        }
    }

    @Test
    fun mediaCriticalBlockedByPrimaryLaneHolder() {
        val lines = mutableListOf<String>()
        val executors = PeerMediaExecutors(threadNamePrefix = "test-edge", logSink = lines::add)
        try {
            val hold = CountDownLatch(1)
            val srdStarted = CountDownLatch(1)
            executors.execute("session-1|M04", EdgeMediaTaskType.ICE_CONTROL) {
                hold.await(5, TimeUnit.SECONDS)
            }
            Thread.sleep(30)
            executors.execute("session-1|M04", EdgeMediaTaskType.SRD_APPLY) {
                srdStarted.countDown()
            }
            assertTrue(
                lines.any {
                    it.startsWith("EDGE_TASK_BLOCKED") &&
                        it.contains("blockedByCategory=MEDIA_CONTROL")
                }
            )
            hold.countDown()
            assertTrue(srdStarted.await(500, TimeUnit.MILLISECONDS))
        } finally {
            executors.shutdownAll()
        }
    }
}
