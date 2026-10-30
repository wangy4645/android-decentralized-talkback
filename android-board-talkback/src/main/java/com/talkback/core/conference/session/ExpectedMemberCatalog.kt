package com.talkback.core.conference.session

/**
 * L1b expectation set: still-present members tracked independently of [SourceBindingCatalog].
 * Does not admit media. PENDING_SOURCE is not mix-ready.
 */
class ExpectedMemberCatalog {
    enum class State {
        PENDING_SOURCE,
        BOUND,
    }

    data class Row(
        val moduleId: String,
        val state: State,
        val reason: String,
    )

    private val rows = linkedMapOf<String, Row>()

    fun size(): Int = rows.size

    fun boundCount(): Int = rows.values.count { it.state == State.BOUND }

    fun pendingCount(): Int = rows.values.count { it.state == State.PENDING_SOURCE }

    fun pendingModuleIds(): List<String> =
        rows.values.filter { it.state == State.PENDING_SOURCE }.map { it.moduleId }

    fun get(moduleId: String): Row? = rows[moduleId]

    fun expectPresent(
        moduleIds: Collection<String>,
        pendingReason: String,
    ) {
        for (id in moduleIds) {
            if (id.isBlank()) continue
            val current = rows[id]
            if (current == null) {
                rows[id] = Row(id, State.PENDING_SOURCE, pendingReason)
            }
        }
        val keep = moduleIds.filter { it.isNotBlank() }.toSet()
        rows.keys.filter { it !in keep }.toList().forEach { rows.remove(it) }
    }

    fun markBound(moduleId: String) {
        if (moduleId.isBlank()) return
        rows[moduleId] = Row(moduleId, State.BOUND, "INSTALL_MEMBER")
    }

    fun markAllPending(reason: String) {
        for (id in rows.keys.toList()) {
            rows[id] = Row(id, State.PENDING_SOURCE, reason)
        }
    }

    fun drop(moduleId: String) {
        rows.remove(moduleId)
    }

    fun clear() {
        rows.clear()
    }

    fun snapshotFields(): Map<String, String> =
        mapOf(
            "expectedMembers" to size().toString(),
            "pendingSourceMembers" to pendingCount().toString(),
            "boundExpectedMembers" to boundCount().toString(),
            "pendingModuleIds" to pendingModuleIds().joinToString(",").ifEmpty { "none" },
        )
}
