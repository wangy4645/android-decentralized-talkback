package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ConferenceSessionMediaFact
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeetingProductMediaShadowDelegateTest {
    private val explodingPort =
        object : ConferenceSessionMediaFactPort {
            override fun sessionFact(
                sessionId: String,
                channelId: String,
                rosterEpoch: Long,
            ): ConferenceSessionMediaFact? = error("boom")

            override fun memberBinding(
                sessionId: String,
                moduleId: String,
            ): MemberBindingFact? = error("boom")

            override fun memberReplaceBinding(
                sessionId: String,
                moduleId: String,
            ): Pair<MemberBindingFact, MemberBindingFact>? = error("boom")

            override fun memberReplacePending(
                sessionId: String,
                moduleId: String,
            ): Boolean = false
        }

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        MeetingProductMediaShadow.observability.snapshot() // reset via new hooks only
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaCoordinatorDelegate.factPort = explodingPort
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun shadowFailure_doesNotPropagateToCaller() {
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted("s1", "ch1", 1L)
        val snap = MeetingProductMediaShadow.observability.snapshot()
        val failures = snap["failureCounts"] as Map<*, *>
        assertTrue((failures[ShadowHook.SESSION_STARTED.name] as Long) >= 1L)
    }

    @Test
    fun deferredNoFact_recordedWithoutThrowing() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted("s2", "ch1", 1L)
        val snap = MeetingProductMediaShadow.observability.snapshot()
        val outcomes = snap["outcomeCounts"] as Map<*, *>
        assertEquals(1L, outcomes[ShadowOutcome.DEFERRED_NO_FACT.name])
    }
}
