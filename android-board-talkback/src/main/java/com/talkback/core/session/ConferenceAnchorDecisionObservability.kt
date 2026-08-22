package com.talkback.core.session

/**
 * IA-2: behavior-neutral [ANCHOR_DECISION] observability for Conference anchor admission.
 */
object ConferenceAnchorDecisionObservability {

    const val TOKEN = "ANCHOR_DECISION"

    enum class LogReason {
        INITIAL_ANCHOR_POLICY,
        FAILOVER_RANKING,
        PRESERVED_ANCHOR,
        RANKING_CANDIDATE
    }

    data class Event(
        val phase: ConferenceInitialAnchorPolicy.Phase,
        val initiatorModuleId: String,
        val candidateAnchorId: String?,
        val currentAnchorId: String?,
        val admittedAnchorId: String?,
        val reason: LogReason,
        val scores: Map<String, Long>? = null
    )

    fun formatLine(event: Event): String {
        val scoresPart = event.scores
            ?.takeIf { it.isNotEmpty() }
            ?.entries
            ?.sortedBy { it.key }
            ?.joinToString(",") { "${it.key}=${it.value}" }
            ?.let { " scores=$it" }
            .orEmpty()
        return buildString {
            append(TOKEN)
            append(" phase=").append(event.phase.name)
            append(" initiator=").append(event.initiatorModuleId)
            append(" candidate=").append(event.candidateAnchorId ?: "none")
            append(" currentAnchor=").append(event.currentAnchorId ?: "none")
            append(" admitted=").append(event.admittedAnchorId ?: "none")
            append(" reason=").append(event.reason.name)
            append(scoresPart)
        }
    }

    fun reasonForAdmission(
        initialAnchor: ConferenceInitialAnchorPolicy.Output,
        input: AnchorAdmissionDecisionInput,
        decision: AnchorAdmissionDecision
    ): LogReason? {
        if (decision !is AnchorAdmissionDecision.Anchor) return null
        if (initialAnchor.phase == ConferenceInitialAnchorPolicy.Phase.CREATE) {
            return LogReason.INITIAL_ANCHOR_POLICY
        }
        val preserved = input.currentTopologyMode == ConferenceTopologyMode.ANCHOR &&
            input.currentAnchorId != null &&
            input.currentAnchorId in input.members.distinct() &&
            decision.anchorId == input.currentAnchorId
        if (preserved) return LogReason.PRESERVED_ANCHOR
        return LogReason.RANKING_CANDIDATE
    }

    fun eventForAdmission(
        input: AnchorAdmissionDecisionInput,
        initialAnchor: ConferenceInitialAnchorPolicy.Output,
        decision: AnchorAdmissionDecision
    ): Event? {
        val reason = reasonForAdmission(initialAnchor, input, decision) ?: return null
        val admitted = (decision as? AnchorAdmissionDecision.Anchor)?.anchorId
        return Event(
            phase = initialAnchor.phase,
            initiatorModuleId = input.hostModuleId,
            candidateAnchorId = initialAnchor.candidateAnchorId,
            currentAnchorId = input.currentAnchorId,
            admittedAnchorId = admitted,
            reason = reason
        )
    }

    fun eventForFailover(
        initiatorModuleId: String,
        failedAnchorId: String,
        nextAnchorId: String,
        scores: Map<String, Long>?
    ): Event = Event(
        phase = ConferenceInitialAnchorPolicy.Phase.RUN,
        initiatorModuleId = initiatorModuleId,
        candidateAnchorId = nextAnchorId,
        currentAnchorId = failedAnchorId,
        admittedAnchorId = nextAnchorId,
        reason = LogReason.FAILOVER_RANKING,
        scores = scores
    )

    data class Desk4pBaselineVerdict(
        val pass: Boolean,
        val failureReason: String? = null
    )

    /**
     * IA-3 desk 4p baseline run card adjudication (log-only; no media/ICE/SRD).
     * See: docs/analysis/conference-initial-anchor-desk-4p-baseline-run-card.md
     */
    fun evaluateDesk4pBaselineRunCard(
        lines: Collection<String>,
        expectedInitiator: String = "M01",
        snapshotAnchorId: String? = null
    ): Desk4pBaselineVerdict {
        val events = lines.mapNotNull(::parseLine)
        val createRankingAdmission = events.any {
            it.phase == ConferenceInitialAnchorPolicy.Phase.CREATE &&
                it.reason == LogReason.RANKING_CANDIDATE
        }
        if (createRankingAdmission) {
            return Desk4pBaselineVerdict(
                pass = false,
                failureReason = "CREATE admission used RANKING_CANDIDATE"
            )
        }
        val createInitial = events.firstOrNull {
            it.phase == ConferenceInitialAnchorPolicy.Phase.CREATE &&
                it.reason == LogReason.INITIAL_ANCHOR_POLICY
        }
            ?: return Desk4pBaselineVerdict(
                pass = false,
                failureReason = "missing ANCHOR_DECISION phase=CREATE reason=INITIAL_ANCHOR_POLICY"
            )
        if (createInitial.initiatorModuleId != expectedInitiator ||
            createInitial.candidateAnchorId != expectedInitiator ||
            createInitial.admittedAnchorId != expectedInitiator
        ) {
            return Desk4pBaselineVerdict(
                pass = false,
                failureReason = "CREATE anchor != $expectedInitiator"
            )
        }
        val resolvedSnapshot = snapshotAnchorId ?: extractSnapshotAnchorId(lines)
        if (resolvedSnapshot == null) {
            return Desk4pBaselineVerdict(
                pass = false,
                failureReason = "missing snapshot.anchorId evidence"
            )
        }
        if (resolvedSnapshot != expectedInitiator) {
            return Desk4pBaselineVerdict(
                pass = false,
                failureReason = "snapshot.anchorId=$resolvedSnapshot expected=$expectedInitiator"
            )
        }
        return Desk4pBaselineVerdict(pass = true)
    }

    /**
     * IA-3 desk gate: CREATE anchor must match initiator before P0.1g SRD isolation.
     */
    fun passesP01gBaselineGate(
        lines: Collection<String>,
        expectedInitiator: String = "M01",
        snapshotAnchorId: String? = null
    ): Boolean = evaluateDesk4pBaselineRunCard(lines, expectedInitiator, snapshotAnchorId).pass

    /** Parses `anchor=M01` from CONFERENCE_TOPOLOGY_PUBLISHED lines. */
    fun extractSnapshotAnchorId(lines: Collection<String>): String? =
        lines.asSequence()
            .filter { "CONFERENCE_TOPOLOGY_PUBLISHED" in it }
            .mapNotNull { line ->
                Regex("""\banchor=([^\s]+)""").find(line)?.groupValues?.getOrNull(1)
            }
            .firstOrNull()

    fun parseLine(line: String): Event? {
        if (TOKEN !in line) return null
        val phase = extractToken(line, "phase=")?.let {
            runCatching { ConferenceInitialAnchorPolicy.Phase.valueOf(it) }.getOrNull()
        } ?: return null
        val reason = extractToken(line, "reason=")?.let {
            runCatching { LogReason.valueOf(it) }.getOrNull()
        } ?: return null
        val initiator = extractToken(line, "initiator=") ?: return null
        val candidate = extractToken(line, "candidate=")?.takeUnless { it == "none" }
        val currentAnchor = extractToken(line, "currentAnchor=")?.takeUnless { it == "none" }
        val admitted = extractToken(line, "admitted=")?.takeUnless { it == "none" }
        val scores = extractScores(line)
        return Event(
            phase = phase,
            initiatorModuleId = initiator,
            candidateAnchorId = candidate,
            currentAnchorId = currentAnchor,
            admittedAnchorId = admitted,
            reason = reason,
            scores = scores
        )
    }

    private fun extractToken(line: String, prefix: String): String? {
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val from = start + prefix.length
        val end = line.indexOf(' ', from).let { if (it < 0) line.length else it }
        return line.substring(from, end)
    }

    private fun extractScores(line: String): Map<String, Long>? {
        val prefix = " scores="
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val body = line.substring(start + prefix.length).trim()
        if (body.isEmpty()) return null
        return body.split(',').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = part.substring(0, idx)
            val value = part.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            key to value
        }.toMap().takeIf { it.isNotEmpty() }
    }
}
