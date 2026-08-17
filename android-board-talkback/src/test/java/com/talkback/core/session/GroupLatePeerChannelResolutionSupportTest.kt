package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLatePeerChannelResolutionSupportTest {

    private fun session(
        id: String,
        channel: String,
        associated: Boolean = false
    ) = GroupLatePeerChannelResolutionSupport.SessionInput(
        sessionId = id,
        channelId = channel,
        peerHasAssociation = associated
    )

    @Test
    fun helloChannelId_matchesSession_usesHelloSource() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = "CH-A",
            sessions = listOf(
                session("s1", "CH-A"),
                session("s2", "CH-B")
            )
        )
        assertTrue(result is GroupLatePeerChannelResolutionSupport.Result.Resolved)
        val resolved = result as GroupLatePeerChannelResolutionSupport.Result.Resolved
        assertEquals("CH-A", resolved.channelId)
        assertEquals("s1", resolved.sessionId)
        assertEquals(
            GroupLatePeerChannelResolutionSupport.ChannelSource.HELLO,
            resolved.channelSource
        )
    }

    @Test
    fun helloChannelId_noMatchingSession_absent() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = "CH-X",
            sessions = listOf(session("s1", "CH-A"))
        )
        assertEquals(GroupLatePeerChannelResolutionSupport.Result.Absent, result)
    }

    @Test
    fun channelLess_singleAcceptedSession_usesLocalSession() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = null,
            sessions = listOf(session("s1", "CH-01"))
        )
        assertTrue(result is GroupLatePeerChannelResolutionSupport.Result.Resolved)
        val resolved = result as GroupLatePeerChannelResolutionSupport.Result.Resolved
        assertEquals("CH-01", resolved.channelId)
        assertEquals(
            GroupLatePeerChannelResolutionSupport.ChannelSource.LOCAL_ACCEPTED_SESSION,
            resolved.channelSource
        )
    }

    @Test
    fun channelLess_multipleSessions_noAssociation_absent() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = null,
            sessions = listOf(
                session("s1", "CH-A"),
                session("s2", "CH-B")
            )
        )
        assertEquals(GroupLatePeerChannelResolutionSupport.Result.Absent, result)
    }

    @Test
    fun channelLess_multipleSessions_oneAssociation_usesAssociatedSession() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = null,
            sessions = listOf(
                session("s1", "CH-A"),
                session("s2", "CH-B", associated = true)
            )
        )
        assertTrue(result is GroupLatePeerChannelResolutionSupport.Result.Resolved)
        val resolved = result as GroupLatePeerChannelResolutionSupport.Result.Resolved
        assertEquals("CH-B", resolved.channelId)
        assertEquals("s2", resolved.sessionId)
        assertEquals(
            GroupLatePeerChannelResolutionSupport.ChannelSource.LOCAL_ACCEPTED_SESSION,
            resolved.channelSource
        )
    }

    @Test
    fun channelLess_multipleAssociations_absent() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = null,
            sessions = listOf(
                session("s1", "CH-A", associated = true),
                session("s2", "CH-B", associated = true)
            )
        )
        assertEquals(GroupLatePeerChannelResolutionSupport.Result.Absent, result)
    }

    @Test
    fun channelLess_noSessions_absent() {
        val result = GroupLatePeerChannelResolutionSupport.resolve(
            helloChannelId = null,
            sessions = emptyList()
        )
        assertEquals(GroupLatePeerChannelResolutionSupport.Result.Absent, result)
    }
}
