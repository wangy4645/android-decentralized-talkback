package com.talkback.core.session.gbc

/**
 * Retains verified Authoritative Generation Facts and supports re-provision semantics
 * within the post-verification seam (no production wire transport).
 */
class FactStore {
    private val byIdentity = linkedMapOf<String, AuthoritativeGenerationFact>()
    private var currentFactIdentity: String? = null

    fun retain(fact: AuthoritativeGenerationFact) {
        byIdentity[fact.factIdentity] = fact
        if (fact.attestsCurrent) {
            currentFactIdentity = fact.factIdentity
        }
    }

    fun get(factIdentity: String): AuthoritativeGenerationFact? = byIdentity[factIdentity]

    fun current(): AuthoritativeGenerationFact? =
        currentFactIdentity?.let { byIdentity[it] }

    /** D1 re-provision: return retained Current Fact unmodified, or null. */
    fun reProvideCurrent(): AuthoritativeGenerationFact? = current()?.copy()

    fun all(): Map<String, AuthoritativeGenerationFact> = byIdentity.toMap()

    fun clear() {
        byIdentity.clear()
        currentFactIdentity = null
    }
}
