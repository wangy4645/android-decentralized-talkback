package com.talkback.core.util

import com.talkback.core.session.GroupOfferBinding
import com.talkback.core.session.GroupPcLineageBindingSupport

object GroupPcLineageBindingLog {
    fun bindingRecorded(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        pcLineage: Long,
        role: GroupOfferBinding.BindingRole,
    ): String =
        "GPLB_BINDING_RECORDED sid=$sessionId peer=$peer offerLineageId=$offerLineageId " +
            "pcLineage=$pcLineage role=$role"

    fun bindingLive(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        pcLineage: Long,
        role: GroupOfferBinding.BindingRole,
    ): String =
        "GPLB_BINDING_LIVE sid=$sessionId peer=$peer offerLineageId=$offerLineageId " +
            "pcLineage=$pcLineage role=$role"

    fun iceAppliedToLiveBinding(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        pcLineage: Long,
    ): String =
        "GPLB_ICE_APPLIED_TO_LIVE_BINDING sid=$sessionId peer=$peer offerLineageId=$offerLineageId " +
            "pcLineage=$pcLineage"

    fun bindingFenced(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        pcLineage: Long,
        reason: String,
    ): String =
        "GPLB_BINDING_FENCED sid=$sessionId peer=$peer offerLineageId=$offerLineageId " +
            "pcLineage=$pcLineage reason=$reason"

    fun acceptCorrelated(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        pcLineage: Long,
        result: GroupPcLineageBindingSupport.CorrelationResult,
    ): String =
        "GPLB_ACCEPT_CORRELATE sid=$sessionId peer=$peer wireLineage=$offerLineageId " +
            "targetPcLineage=$pcLineage result=$result"

    fun acceptFenced(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        reason: String,
    ): String =
        "GPLB_ACCEPT_FENCED sid=$sessionId peer=$peer offerLineageId=$offerLineageId reason=$reason"

    fun iceCorrelated(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        pcLineage: Long,
        queued: Boolean,
        result: GroupPcLineageBindingSupport.CorrelationResult,
    ): String =
        "GPLB_ICE_CORRELATE sid=$sessionId peer=$peer wireLineage=$offerLineageId " +
            "targetPcLineage=$pcLineage queued=$queued result=$result"

    fun iceFenced(
        sessionId: String,
        peer: String,
        offerLineageId: String,
        reason: String,
    ): String =
        "GPLB_ICE_FENCED sid=$sessionId peer=$peer offerLineageId=$offerLineageId reason=$reason"
}
