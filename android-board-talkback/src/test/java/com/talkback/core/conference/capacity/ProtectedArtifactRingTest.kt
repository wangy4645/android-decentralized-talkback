package com.talkback.core.conference.capacity

import com.talkback.core.conference.wire.ConferenceWireConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedArtifactRingTest {
    @Test
    fun buildProduces120ByteArtifacts() {
        val ring = ProtectedArtifactRing.build(ringSize = 4)
        assertEquals(4, ring.capacity)
        repeat(4) { index ->
            assertEquals(
                ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES,
                ring.artifactAt(index).size,
            )
        }
    }
}
