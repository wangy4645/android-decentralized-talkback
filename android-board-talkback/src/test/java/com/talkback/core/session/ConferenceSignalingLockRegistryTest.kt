package com.talkback.core.session

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceSignalingLockRegistryTest {

    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var registry: ConferenceSignalingLockRegistry
    private val key = ConferenceSignalKey(sessionId = "sess-1", peerId = "M01")

    @Before
    fun setUp() {
        logs.clear()
        registry = ConferenceSignalingLockRegistry(logSink = { logs.add(it) })
    }

    @Test
    fun initialAnswerBlocksConcurrentIceRestart() {
        val initialEntered = CountDownLatch(1)
        val releaseInitial = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val initial = thread {
            runBlocking {
                registry.withConferenceSignalLock(key, ConferenceSignalOwner.INITIAL_ANSWER.name) {
                    order.add("initial_start")
                    initialEntered.countDown()
                    releaseInitial.await(5, TimeUnit.SECONDS)
                    order.add("initial_end")
                }
            }
        }

        assertTrue(initialEntered.await(2, TimeUnit.SECONDS))

        val ice = thread {
            runBlocking {
                registry.withConferenceSignalLock(key, ConferenceSignalOwner.ICE_RESTART.name) {
                    order.add("ice_start")
                }
            }
        }

        Thread.sleep(100)
        assertTrue(logs.any { it.contains("CONFERENCE_SIGNAL_LOCK_WAIT") && it.contains("owner=ICE_RESTART") })
        assertTrue(logs.any { it.contains("holder=INITIAL_ANSWER") })

        releaseInitial.countDown()
        initial.join(5_000)
        ice.join(5_000)

        assertEquals(listOf("initial_start", "initial_end", "ice_start"), order)
    }

    @Test
    fun readyRecoveryAcquiresWithoutWaiting() {
        runBlocking {
            registry.withConferenceSignalLock(key, ConferenceSignalOwner.ICE_RESTART.name) {
                Unit
            }
        }
        assertTrue(logs.none { it.contains("CONFERENCE_SIGNAL_LOCK_WAIT") })
        assertTrue(logs.any { it.contains("CONFERENCE_SIGNAL_LOCK_ACQUIRE") && it.contains("owner=ICE_RESTART") })
        assertTrue(logs.any { it.contains("CONFERENCE_SIGNAL_LOCK_RELEASE") && it.contains("owner=ICE_RESTART") })
    }

    @Test
    fun differentPeerKeysDoNotBlockEachOther() {
        val otherKey = ConferenceSignalKey(sessionId = "sess-1", peerId = "M02")
        val bothStarted = CountDownLatch(2)
        val release = CountDownLatch(1)

        val t1 = thread {
            runBlocking {
                registry.withConferenceSignalLock(key, ConferenceSignalOwner.MESH_JOIN.name) {
                    bothStarted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val t2 = thread {
            runBlocking {
                registry.withConferenceSignalLock(otherKey, ConferenceSignalOwner.MESH_JOIN.name) {
                    bothStarted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }

        assertTrue(bothStarted.await(2, TimeUnit.SECONDS))
        assertTrue(logs.none { it.contains("CONFERENCE_SIGNAL_LOCK_WAIT") })
        release.countDown()
        t1.join(5_000)
        t2.join(5_000)
    }

    @Test
    fun lockReleasedWhenBlockThrows() {
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                registry.withConferenceSignalLock(key, ConferenceSignalOwner.ICE_RESTART.name) {
                    throw RuntimeException("createOffer failed")
                }
            }
        }
        assertTrue(logs.any { it.contains("CONFERENCE_SIGNAL_LOCK_RELEASE") && it.contains("owner=ICE_RESTART") })

        runBlocking {
            registry.withConferenceSignalLock(key, ConferenceSignalOwner.NORMAL_NEGOTIATION.name) {
                Unit
            }
        }
        assertTrue(logs.any { it.contains("owner=NORMAL_NEGOTIATION") })
    }
}
