package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfferDeliveryObservationTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        OfferDeliveryObservation.resetForTest { lines.add(it) }
    }

    @After
    fun tearDown() {
        OfferDeliveryObservation.resetForTest(null)
    }

    @Test
    fun emit_includesStageAndCorrelation() {
        OfferDeliveryObservation.emit(
            stage = OfferDeliveryObservation.Stage.LOCAL_ACCEPT,
            remoteModuleId = "M03",
            offerLineageId = "L55",
            sessionId = "sess-1",
            restartAttemptId = 17L,
            transportGeneration = 2L,
            pathKind = OfferDeliveryObservation.PathKind.RECOVERY_REATTACH,
            detail = "udp_write_ok"
        )
        val line = lines.single()
        assertTrue(line.startsWith("OFFER_DELIVERY stage=LOCAL_ACCEPT"))
        assertTrue(line.contains("remote=M03"))
        assertTrue(line.contains("offerLineageId=L55"))
        assertTrue(line.contains("pathKind=RECOVERY_REATTACH"))
        assertTrue(line.contains("session=sess-1"))
        assertTrue(line.contains("restartAttemptId=17"))
        assertTrue(line.contains("gen=2"))
    }

    @Test
    fun emit_missingLineageUsesNone() {
        OfferDeliveryObservation.emit(
            stage = OfferDeliveryObservation.Stage.REMOTE_RECEIVE,
            remoteModuleId = "M02"
        )
        assertTrue(lines.single().contains("offerLineageId=NONE"))
    }

    @Test
    fun emit_messageTypeClassifiedFields() {
        OfferDeliveryObservation.emit(
            stage = OfferDeliveryObservation.Stage.MESSAGE_TYPE_CLASSIFIED,
            remoteModuleId = "M02",
            offerLineageId = "L2",
            pathKind = OfferDeliveryObservation.PathKind.RECOVERY_REATTACH,
            signalType = "GROUP_JOIN",
            joinIntent = "RECOVERY_REATTACH",
            sessionId = "conf-1"
        )
        val line = lines.single()
        assertTrue(line.contains("stage=MESSAGE_TYPE_CLASSIFIED"))
        assertTrue(line.contains("signalType=GROUP_JOIN"))
        assertTrue(line.contains("joinIntent=RECOVERY_REATTACH"))
        assertTrue(line.contains("pathKind=RECOVERY_REATTACH"))
        assertTrue(line.contains("signalDomain=RECOVERY_REATTACH"))
    }

    @Test
    fun udpAndDecodeStages_emitIngressMarkers() {
        OfferDeliveryObservation.udpDatagramReceived("192.168.31.214", 50000, 5L, 400)
        assertTrue(lines[0].contains("stage=UDP_DATAGRAM_RECEIVED"))
        assertTrue(lines[0].contains("bytes=400"))
        OfferDeliveryObservation.emit(
            stage = OfferDeliveryObservation.Stage.SIGNAL_ENVELOPE_DECODED,
            remoteModuleId = "M02",
            offerLineageId = "L1",
            pathKind = OfferDeliveryObservation.PathKind.RECOVERY_REATTACH,
            signalType = "GROUP_JOIN",
            joinIntent = "RECOVERY_REATTACH"
        )
        assertTrue(lines[1].contains("stage=SIGNAL_ENVELOPE_DECODED"))
        OfferDeliveryObservation.emit(
            stage = OfferDeliveryObservation.Stage.RECOVERY_REATTACH_CLASSIFIED,
            remoteModuleId = "M02",
            offerLineageId = "L1",
            pathKind = OfferDeliveryObservation.PathKind.RECOVERY_REATTACH,
            joinIntent = "RECOVERY_REATTACH"
        )
        assertTrue(lines[2].contains("stage=RECOVERY_REATTACH_CLASSIFIED"))
    }
}