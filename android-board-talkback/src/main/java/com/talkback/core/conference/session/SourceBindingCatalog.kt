package com.talkback.core.conference.session

/**
 * Session-scoped authoritative binding catalog: moduleId ↔ wire identity ↔ SSRC.
 */
class SourceBindingCatalog {
    data class Entry(
        val moduleId: String,
        val sourceIdentity: String,
        val ssrc: Int,
        val incarnationId: Long,
        val mediaKeyEpoch: Long,
        val sourceAdmissionKey48: ByteArray,
    ) {
        companion object {
            fun from(binding: MemberBindingFact): Entry =
                Entry(
                    moduleId = binding.moduleId,
                    sourceIdentity = binding.sourceIdentity,
                    ssrc = binding.ssrc,
                    incarnationId = binding.incarnationId,
                    mediaKeyEpoch = binding.mediaKeyEpoch,
                    sourceAdmissionKey48 = binding.sourceAdmissionKey48.copyOf(),
                )
        }
    }

    private val byModuleId = linkedMapOf<String, Entry>()
    private val bySsrc = hashMapOf<Int, Entry>()

    fun install(binding: MemberBindingFact): Entry {
        val entry = Entry.from(binding)
        byModuleId[entry.moduleId]?.let { remove(it.moduleId) }
        byModuleId[entry.moduleId] = entry
        bySsrc[entry.ssrc] = entry
        return entry
    }

    fun get(moduleId: String): Entry? = byModuleId[moduleId]

    fun resolveBySsrc(ssrc: Int): Entry? = bySsrc[ssrc]

    fun remove(moduleId: String): Entry? {
        val removed = byModuleId.remove(moduleId) ?: return null
        bySsrc.remove(removed.ssrc)
        return removed
    }

    fun all(): List<Entry> = byModuleId.values.toList()

    fun size(): Int = byModuleId.size

    fun clear() {
        byModuleId.clear()
        bySsrc.clear()
    }
}
