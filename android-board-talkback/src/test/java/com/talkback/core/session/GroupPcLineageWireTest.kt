package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupPcLineageWireTest {

    @Test
    fun encodeDecode_groupAccept_roundTripsOfferLineageEcho() {
        val wire = GroupPcLineageWire.encodeGroupAcceptAnswer("v=0", "GM42")
        val parsed = GroupPcLineageWire.parseGroupAccept(wire)
        assertEquals("v=0", parsed.sdp)
        assertEquals("GM42", parsed.offerLineageId)
    }

    @Test
    fun parseGroupAccept_rawSdp_isUnknown() {
        val parsed = GroupPcLineageWire.parseGroupAccept("v=0")
        assertEquals("v=0", parsed.sdp)
        assertEquals(GroupPcLineageWire.UNKNOWN_LINEAGE, parsed.offerLineageId)
    }

    @Test
    fun encodeDecode_ice_roundTripsOfferLineage() {
        val wire = GroupPcLineageWire.encodeIceCandidate("candidate:1", "GM7")
        val parsed = GroupPcLineageWire.parseIce(wire)
        assertEquals("candidate:1", parsed.candidate)
        assertEquals("GM7", parsed.offerLineageId)
    }

    @Test
    fun parseIce_plainCandidate_isUnknown() {
        val parsed = GroupPcLineageWire.parseIce("candidate:1")
        assertEquals("candidate:1", parsed.candidate)
        assertEquals(GroupPcLineageWire.UNKNOWN_LINEAGE, parsed.offerLineageId)
    }
}
