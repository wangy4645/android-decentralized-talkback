package com.talkback.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StaticPeerConfigTest {
    @Test
    fun parsePeersJson() {
        val json = """{"peers":[{"moduleId":"M02","host":"192.168.1.2","port":50001}]}"""
        val peers = StaticPeerConfig.parse(json)
        assertEquals(1, peers.size)
        assertEquals("M02", peers[0].moduleId.value)
        assertEquals("192.168.1.2", peers[0].host)
        assertEquals(50001, peers[0].port)
    }
}
