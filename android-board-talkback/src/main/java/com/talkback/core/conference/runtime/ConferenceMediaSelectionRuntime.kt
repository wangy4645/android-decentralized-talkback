package com.talkback.core.conference.runtime

/**
 * E2b-02 facade: admission boundary + Top-K + decode-eligibility only.
 * No jitter / PLC / Opus / AudioTrack.
 */
class ConferenceMediaSelectionRuntime(
    val registry: AdmittedMediaSourceRegistry = AdmittedMediaSourceRegistry(),
    val voiceStore: VoiceLevelStore = VoiceLevelStore(registry),
    private val selector: TopKSelector = TopKSelector(),
) {
    private var selection: TopKSelector.SelectionState = TopKSelector.SelectionState()

    fun install(source: AdmittedMediaSource) {
        registry.install(source)
    }

    fun hardFence(sourceIdentity: String, incarnationId: Long): Boolean =
        registry.hardFence(sourceIdentity, incarnationId)

    /**
     * Post-P02 voice/level fact. Does not create AdmittedMediaSource.
     * @return false if no matching installed incarnation (observation dropped)
     */
    fun observeVoice(observation: VoiceLevelObservation): Boolean =
        voiceStore.observe(observation)

    fun selectTopK(nowMs: Long): TopKSelector.SelectionState {
        selection = selector.select(nowMs, registry, voiceStore.snapshot(), selection)
        return selection
    }

    fun currentTopK(): TopKSelector.SelectionState = selection

    fun isDecodeEligible(sourceIdentity: String, incarnationId: Long): Boolean =
        DecodeEligibilityGate.isDecodeEligible(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            registry = registry,
            topK = selection,
        )

    /** Harness helper: identities currently decode-eligible. */
    fun decodeEligibleIdentities(): Set<String> =
        selection.members
            .filter { isDecodeEligible(it.sourceIdentity, it.incarnationId) }
            .map { it.sourceIdentity }
            .toSet()

    /** Conference session wiring teardown — after authority facts removed. */
    fun clearRegistryForSessionWiring() {
        registry.clearForSessionWiring()
    }
}
