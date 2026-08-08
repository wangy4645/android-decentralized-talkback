package com.talkback.core.session.membershipcontext

import com.talkback.core.model.MembershipContextExistenceQueryPayload
import com.talkback.core.model.MembershipContextExistenceResponsePayload
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0043 P1 v0: cache + signaling probe dispatch for authority-grounded existence evidence.
 */
class SignalingMembershipContextExistenceProjector(
    private val sendProbe: (authorityId: String, payload: MembershipContextExistenceQueryPayload) -> Boolean
) : MembershipContextExistenceProjector {

    private val evidenceByCorrelation = ConcurrentHashMap<String, MembershipContextExistenceEvidence>()
    private val pendingProbes = ConcurrentHashMap.newKeySet<String>()

    override fun obtainEvidence(query: MembershipContextExistenceQuery): MembershipContextExistenceEvidence =
        evidenceByCorrelation[query.correlationId]
            ?: unknownEvidence(query)

    override fun requestAuthorityProbe(
        query: MembershipContextExistenceQuery,
        authorityId: String
    ): Boolean {
        if (!pendingProbes.add(query.correlationId)) return false
        val payload = MembershipContextExistenceQueryPayload(
            channelId = query.channelId,
            decisionEpoch = query.decisionEpoch,
            correlationId = query.correlationId
        )
        val sent = sendProbe(authorityId, payload)
        if (!sent) {
            pendingProbes.remove(query.correlationId)
        }
        return sent
    }

    override fun recordAuthorityResponse(evidence: MembershipContextExistenceEvidence) {
        evidenceByCorrelation[evidence.correlationId] = evidence
        pendingProbes.remove(evidence.correlationId)
    }

    fun clear() {
        evidenceByCorrelation.clear()
        pendingProbes.clear()
    }

    companion object {
        fun answerFromWire(raw: String): MembershipContextExistenceAnswer? =
            when (raw.uppercase()) {
                "PRESENT" -> MembershipContextExistenceAnswer.PRESENT
                "ABSENT" -> MembershipContextExistenceAnswer.ABSENT
                else -> null
            }

        fun evidenceFromResponse(
            authorityId: String,
            payload: MembershipContextExistenceResponsePayload
        ): MembershipContextExistenceEvidence? {
            val answer = answerFromWire(payload.answer) ?: return null
            return MembershipContextExistenceEvidence(
                answer = answer,
                channelId = payload.channelId,
                decisionEpoch = payload.decisionEpoch,
                correlationId = payload.correlationId,
                authorityId = authorityId,
                authorityOriginated = true
            )
        }

        private fun unknownEvidence(query: MembershipContextExistenceQuery): MembershipContextExistenceEvidence =
            MembershipContextExistenceEvidence(
                answer = MembershipContextExistenceAnswer.UNKNOWN,
                channelId = query.channelId,
                decisionEpoch = query.decisionEpoch,
                correlationId = query.correlationId,
                authorityId = "",
                authorityOriginated = false
            )
    }
}
