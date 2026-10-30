package com.talkback.core.conference.runtime

/**
 * Latest V/level per installed incarnation. Observations without a matching
 * installed incarnation are ignored — MUST NOT create AdmittedMediaSource.
 */
class VoiceLevelStore(
    private val registry: AdmittedMediaSourceRegistry,
) {
    private val latest = linkedMapOf<String, VoiceLevelObservation>()

    fun observe(observation: VoiceLevelObservation): Boolean {
        val inst = registry.get(observation.sourceIdentity) ?: return false
        if (inst.source.incarnationId != observation.incarnationId) return false
        latest[observation.sourceIdentity] = observation
        return true
    }

    fun get(sourceIdentity: String): VoiceLevelObservation? = latest[sourceIdentity]

    fun snapshot(): Map<String, VoiceLevelObservation> = latest.toMap()

    fun clear() {
        latest.clear()
    }
}
