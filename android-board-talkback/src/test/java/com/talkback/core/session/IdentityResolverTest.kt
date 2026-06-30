package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityResolverTest {

    @After
    fun tearDown() {
        IdentityResolver.compatDriftLogger = null
    }

    private fun groupSession(
        localModuleId: String = "M03",
        bootstrapEndpointId: String = "E01",
        vararg members: Pair<String, String>
    ): TalkbackSession {
        val local = EndpointAddress(ModuleId(localModuleId), EndpointId(bootstrapEndpointId))
        val session = TalkbackSession(
            id = "s1",
            type = SessionType.GROUP,
            local = local,
            channelId = "CH-01"
        )
        session.groupMembers = members.map { (moduleId, endpointId) ->
            EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        }
        return session
    }

    @Test
    fun local_returnsCanonicalFromGroupMembers() {
        val session = groupSession(
            members = arrayOf("M01" to "E01", "M02" to "E01", "M03" to "E03")
        )
        val resolved = IdentityResolver.local(session, "M03")
        assertEquals("M03-E03", resolved.key)
    }

    @Test
    fun local_fallsBackToBootstrapWhenMissingFromRoster() {
        val session = groupSession(
            members = arrayOf("M01" to "E01", "M02" to "E01")
        )
        val resolved = IdentityResolver.local(session, "M03")
        assertEquals("M03-E01", resolved.key)
    }

    @Test
    fun localKey_matchesLocalKey() {
        val session = groupSession(
            members = arrayOf("M03" to "E03")
        )
        assertEquals("M03-E03", IdentityResolver.localKey(session, "M03"))
    }

    @Test
    fun isLocal_trueForCanonicalKey() {
        val session = groupSession(
            members = arrayOf("M03" to "E03")
        )
        val canonical = EndpointAddress(ModuleId("M03"), EndpointId("E03"))
        assertTrue(IdentityResolver.isLocal(session, canonical, "M03"))
    }

    @Test
    fun isLocal_compatModuleIdMatch_logsDrift() {
        val session = groupSession(
            members = arrayOf("M03" to "E03")
        )
        val bootstrap = EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        val logs = mutableListOf<String>()
        IdentityResolver.compatDriftLogger = { logs.add(it) }
        assertTrue(IdentityResolver.isLocal(session, bootstrap, "M03"))
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("LOCAL_IDENTITY_DRIFT_COMPAT"))
        assertTrue(logs.single().contains("expected=M03-E03"))
        assertTrue(logs.single().contains("actual=M03-E01"))
    }

    @Test
    fun isLocal_falseForDifferentModule() {
        val session = groupSession(
            members = arrayOf("M03" to "E03")
        )
        val remote = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        assertFalse(IdentityResolver.isLocal(session, remote, "M03"))
    }

    @Test
    fun local_nonGroup_returnsBootstrapLocal() {
        val bootstrap = EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        val session = TalkbackSession(
            id = "u1",
            type = SessionType.UNICAST,
            local = bootstrap,
            channelId = null
        )
        session.remote = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        assertEquals(bootstrap, IdentityResolver.local(session, "M03"))
    }
}
