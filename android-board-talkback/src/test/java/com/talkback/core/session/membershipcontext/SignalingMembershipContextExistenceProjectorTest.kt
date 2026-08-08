package com.talkback.core.session.membershipcontext

import com.talkback.core.model.MembershipContextExistenceQueryPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignalingMembershipContextExistenceProjectorTest {

    private val sentProbes = mutableListOf<MembershipContextExistenceQueryPayload>()
    private lateinit var projector: SignalingMembershipContextExistenceProjector

    @Before
    fun setUp() {
        sentProbes.clear()
        projector = SignalingMembershipContextExistenceProjector { _, payload ->
            sentProbes.add(payload)
            true
        }
    }

    private fun query(epoch: Long = 5L) = MembershipContextExistenceQuery(
        channelId = "CH-01",
        decisionEpoch = epoch,
        correlationId = "CH-01:5:conf-1",
        conferenceSessionId = "conf-1"
    )

    @Test
    fun obtainEvidence_withoutResponse_isUnknown() {
        val evidence = projector.obtainEvidence(query())
        assertEquals(MembershipContextExistenceAnswer.UNKNOWN, evidence.answer)
        assertFalse(evidence.authorityOriginated)
    }

    @Test
    fun requestAuthorityProbe_dispatchesOnceUntilResponse() {
        val q = query()
        assertTrue(projector.requestAuthorityProbe(q, "M01"))
        assertFalse(projector.requestAuthorityProbe(q, "M01"))
        assertEquals(1, sentProbes.size)
    }

    @Test
    fun requestAuthorityProbe_failedSendAllowsRetry() {
        var sendCount = 0
        val projector = SignalingMembershipContextExistenceProjector { _, _ ->
            sendCount++
            false
        }
        val q = query()
        assertFalse(projector.requestAuthorityProbe(q, "M01"))
        assertFalse(projector.requestAuthorityProbe(q, "M01"))
        assertEquals(2, sendCount)
    }

    @Test
    fun recordAuthorityResponse_enablesPresentEvidence() {
        val q = query()
        projector.recordAuthorityResponse(
            MembershipContextExistenceEvidence(
                answer = MembershipContextExistenceAnswer.PRESENT,
                channelId = q.channelId,
                decisionEpoch = q.decisionEpoch,
                correlationId = q.correlationId,
                authorityId = "M01",
                authorityOriginated = true
            )
        )
        val evidence = projector.obtainEvidence(q)
        assertEquals(MembershipContextExistenceAnswer.PRESENT, evidence.answer)
        assertTrue(evidence.authorityOriginated)
    }
}
