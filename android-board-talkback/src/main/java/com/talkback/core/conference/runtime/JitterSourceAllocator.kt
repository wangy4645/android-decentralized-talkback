package com.talkback.core.conference.runtime

/**
 * MaxJitterSources=10 allocator (C-E2B05-04).
 * Policy is explicit — harness must not randomize branches.
 */
class JitterSourceAllocator(
    private val registry: AdmittedMediaSourceRegistry,
    var policy: JitterAllocPolicy = JitterAllocPolicy.REJECT_NEW,
) {
    private val active = linkedMapOf<String, Long>() // identity -> incarnation

    fun activeCount(): Int = active.size

    fun activeIdentities(): Set<String> = active.keys.toSet()

    fun allocate(sourceIdentity: String, incarnationId: Long): JitterAllocResult {
        val inst = registry.get(sourceIdentity)
        if (inst == null || inst.source.incarnationId != incarnationId) {
            return JitterAllocResult(JitterAllocOutcome.NOT_ADMITTED)
        }
        if (sourceIdentity in active) {
            active[sourceIdentity] = incarnationId
            return JitterAllocResult(JitterAllocOutcome.ALLOCATED)
        }
        if (active.size < MediaResourceConstants.MAX_JITTER_SOURCES) {
            active[sourceIdentity] = incarnationId
            return JitterAllocResult(JitterAllocOutcome.ALLOCATED)
        }
        return when (policy) {
            JitterAllocPolicy.REJECT_NEW ->
                JitterAllocResult(JitterAllocOutcome.REJECT_NEW)
            JitterAllocPolicy.RECLAIM_EXISTING_THEN_ALLOCATE -> {
                val victim = active.keys.first()
                active.remove(victim)
                active[sourceIdentity] = incarnationId
                JitterAllocResult(
                    JitterAllocOutcome.RECLAIM_EXISTING_THEN_ALLOCATE,
                    reclaimedIdentity = victim,
                )
            }
        }
    }

    fun release(sourceIdentity: String) {
        active.remove(sourceIdentity)
    }
}
