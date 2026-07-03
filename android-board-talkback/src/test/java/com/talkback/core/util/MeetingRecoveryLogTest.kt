package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MeetingRecoveryLogTest {
    @Before
    fun setUp() {
        MeetingRecoveryLog.resetForTest()
    }

    @After
    fun tearDown() {
        MeetingRecoveryLog.resetForTest()
    }

    @Test
    fun meshOperational_thenFirstFloor_clearsPending() {
        val channelId = "CH-01"
        MeetingRecoveryLog.onConferenceReleased(channelId, "local_hangup")
        assertTrue(MeetingRecoveryLog.hasPendingForTest(channelId))

        MeetingRecoveryLog.onMeshOperational(channelId)
        assertTrue(MeetingRecoveryLog.hasPendingForTest(channelId))

        MeetingRecoveryLog.onFirstFloorGrant(channelId)
        assertFalse(MeetingRecoveryLog.hasPendingForTest(channelId))
    }

    @Test
    fun firstFloorWithoutMeshOperational_clearsPending() {
        val channelId = "CH-02"
        MeetingRecoveryLog.onConferenceReleased(channelId, "remote_hangup")
        MeetingRecoveryLog.onFirstFloorGrant(channelId)
        assertFalse(MeetingRecoveryLog.hasPendingForTest(channelId))
    }

    @Test
    fun grantIgnoredWithoutPriorRelease() {
        MeetingRecoveryLog.onFirstFloorGrant("CH-99")
        assertFalse(MeetingRecoveryLog.hasPendingForTest("CH-99"))
    }
}
