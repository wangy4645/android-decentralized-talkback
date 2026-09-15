package com.talkback.core.conference.authority

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.wire.WireKeyContext
import com.talkback.core.conference.wire.WireSourceBinding

/**
 * Sole producer of derived ADR-0058 execution capabilities (C-E2B07-01).
 * Does NOT originate or self-authorize P01 facts — consumes [VerifiedFactSeam] only.
 */
class AuthorityFactStore(
    private val verifiedFacts: InjectableVerifiedFactSeam = InjectableVerifiedFactSeam(),
) {
    private val wireByIdentity = linkedMapOf<String, DerivedWireCapability>()
    private val admittedByIdentity = linkedMapOf<String, AdmittedMediaSource>()

    fun verifiedFactSeam(): InjectableVerifiedFactSeam = verifiedFacts

    /**
     * Install already-verified facts, then derive current capabilities.
     * Bridge/harness MUST supply facts only via verified seam — never from packets.
     */
    fun acceptVerifiedSource(fact: SourceAuthorizationFact) {
        verifiedFacts.installSource(fact)
        if (fact.current) {
            rederive(fact.sourceIdentity)
        }
    }

    fun acceptVerifiedKey(fact: MediaKeyContextFact) {
        verifiedFacts.installKey(fact)
        // Re-derive all sources sharing this epoch
        for (s in verifiedFacts.currentSources().filter { it.mediaKeyEpoch == fact.mediaKeyEpoch }) {
            rederive(s.sourceIdentity)
        }
    }

    fun derivedWire(sourceIdentity: String): DerivedWireCapability? =
        wireByIdentity[sourceIdentity]

    fun derivedAdmitted(sourceIdentity: String): AdmittedMediaSource? =
        admittedByIdentity[sourceIdentity]

    fun currentWireCapabilities(): Map<String, DerivedWireCapability> = wireByIdentity.toMap()

    fun currentAdmitted(): Map<String, AdmittedMediaSource> = admittedByIdentity.toMap()

    /**
     * C-E2B07-02 step 1: mark old capability non-current; no new derivation for old incarnation.
     */
    fun revokeSource(sourceIdentity: String, incarnationId: Long): RevocationCommit? {
        val cur = verifiedFacts.allSources()[sourceIdentity] ?: return null
        if (cur.incarnationId != incarnationId) return null
        verifiedFacts.markSourceNonCurrent(sourceIdentity, incarnationId)
        wireByIdentity.remove(sourceIdentity)
        val admitted = admittedByIdentity.remove(sourceIdentity)
        return RevocationCommit(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            mediaKeyEpoch = cur.mediaKeyEpoch,
            removedAdmitted = admitted,
        )
    }

    fun retireMediaKeyEpoch(mediaKeyEpoch: Long): List<RevocationCommit> {
        verifiedFacts.markKeyNonCurrent(mediaKeyEpoch)
        val commits = mutableListOf<RevocationCommit>()
        val affected =
            verifiedFacts.allSources().values.filter {
                it.mediaKeyEpoch == mediaKeyEpoch && it.current
            }
        // Also fence sources already non-current that still hold derived caps for this epoch
        val identities =
            (
                affected.map { it.sourceIdentity } +
                    wireByIdentity.filter { it.value.mediaKeyEpoch == mediaKeyEpoch }.keys
            ).toSet()
        for (id in identities) {
            val src = verifiedFacts.allSources()[id] ?: continue
            if (src.mediaKeyEpoch != mediaKeyEpoch) continue
            verifiedFacts.markSourceNonCurrent(id, src.incarnationId)
            wireByIdentity.remove(id)
            val admitted = admittedByIdentity.remove(id)
            commits +=
                RevocationCommit(
                    sourceIdentity = id,
                    incarnationId = src.incarnationId,
                    mediaKeyEpoch = mediaKeyEpoch,
                    removedAdmitted = admitted,
                )
        }
        // Remove any remaining wire caps for this epoch
        wireByIdentity.entries.removeAll { it.value.mediaKeyEpoch == mediaKeyEpoch }
        return commits
    }

    private fun rederive(sourceIdentity: String) {
        val src = verifiedFacts.allSources()[sourceIdentity] ?: return
        if (!src.current) {
            wireByIdentity.remove(sourceIdentity)
            admittedByIdentity.remove(sourceIdentity)
            return
        }
        val key = verifiedFacts.allKeys()[src.mediaKeyEpoch] ?: return
        if (!key.current) {
            wireByIdentity.remove(sourceIdentity)
            admittedByIdentity.remove(sourceIdentity)
            return
        }
        wireByIdentity[sourceIdentity] =
            DerivedWireCapability(
                mediaKeyEpoch = src.mediaKeyEpoch,
                key =
                    WireKeyContext(
                        masterKey = key.masterKey.copyOf(),
                        masterSalt = key.masterSalt.copyOf(),
                        keyContextHint64 = key.keyContextHint64.copyOf(),
                    ),
                binding =
                    WireSourceBinding(
                        ssrc = src.ssrc,
                        sourceAdmissionKey48 = src.sourceAdmissionKey48.copyOf(),
                    ),
                sourceIdentity = src.sourceIdentity,
                incarnationId = src.incarnationId,
            )
        admittedByIdentity[sourceIdentity] =
            AdmittedMediaSource(
                sourceIdentity = src.sourceIdentity,
                incarnationId = src.incarnationId,
                ssrc = src.ssrc,
            )
    }
}

data class RevocationCommit(
    val sourceIdentity: String,
    val incarnationId: Long,
    val mediaKeyEpoch: Long,
    val removedAdmitted: AdmittedMediaSource?,
)
