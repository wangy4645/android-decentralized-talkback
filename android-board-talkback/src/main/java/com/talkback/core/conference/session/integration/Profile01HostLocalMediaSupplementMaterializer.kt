package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01PackageCrypto
import com.talkback.core.conference.session.profile01.wire.hexToId128Bytes

/**
 * PR-PA-SR4-TX-A1 — host authoritative media material → executable supplement registry entry.
 *
 * Does not decrypt host-emitted MEDIA_KEY_PACKAGE; does not bypass [Profile01SessionMediaSupplementRegistry].
 * Failures are logged only — they do not block CREATION origin or Meeting state.
 */
class Profile01HostLocalMediaSupplementMaterializer(
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority,
    private val supplementRegistry: Profile01SessionMediaSupplementRegistry,
    private val onSupplementReady: (conferenceId: String, mediaKeyEpoch: Long) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    fun materializeFromAuthority(
        request: HostLocalSupplementMaterializationRequest,
    ): HostLocalSupplementMaterializationOutcome {
        val boundConferenceId = sessionIndex.conferenceIdForSession(request.sessionId)
        if (boundConferenceId != null && boundConferenceId != request.conferenceIdHex) {
            logOutcome(request, "STALE_IDENTITY", "reason=CONFERENCE_BINDING_MISMATCH")
            return HostLocalSupplementMaterializationOutcome.STALE_IDENTITY
        }

        val authorityMaterial =
            mediaKeyAuthority.materialForSession(request.sessionId)
                ?: run {
                    logOutcome(request, "DERIVE_FAILED", "reason=NO_AUTHORITY_MATERIAL")
                    return HostLocalSupplementMaterializationOutcome.NO_AUTHORITY_MATERIAL
                }

        if (!authorityMaterial.mediaKeyCommitment.contentEquals(request.expectedMediaKeyCommitment)) {
            logOutcome(request, "STALE_IDENTITY", "reason=COMMITMENT_MISMATCH")
            return HostLocalSupplementMaterializationOutcome.STALE_IDENTITY
        }

        if (
            !Profile01PackageCrypto.verifyMediaKeyCommitment(
                authorityMaterial.conferenceMediaSecret,
                authorityMaterial.membershipKeyContextDigest,
                authorityMaterial.mediaKeyCommitment,
            )
        ) {
            logOutcome(request, "DERIVE_FAILED", "reason=COMMITMENT_VERIFY_FAIL")
            return HostLocalSupplementMaterializationOutcome.DERIVE_FAILED
        }

        val derived =
            deriveMaterial(request, authorityMaterial)
                ?: run {
                    logOutcome(request, "DERIVE_FAILED", "reason=DERIVE_REJECTED")
                    return HostLocalSupplementMaterializationOutcome.DERIVE_FAILED
                }

        val existing = supplementRegistry.lookup(request.conferenceIdHex, request.mediaKeyEpoch)
        if (existing != null) {
            if (!derivedSemanticallyEqual(existing, derived)) {
                logOutcome(request, "STALE_IDENTITY", "reason=REGISTRY_IDENTITY_MISMATCH")
                return HostLocalSupplementMaterializationOutcome.STALE_IDENTITY
            }
            logOutcome(request, "ALREADY_PRESENT")
            notifyReady(request)
            return HostLocalSupplementMaterializationOutcome.ALREADY_PRESENT
        }

        supplementRegistry.put(derived)
        logOutcome(request, "READY")
        notifyReady(request)
        return HostLocalSupplementMaterializationOutcome.READY
    }

    private fun deriveMaterial(
        request: HostLocalSupplementMaterializationRequest,
        authorityMaterial: Profile01ConferenceMediaKeyMaterialAuthority.Material,
    ): Profile01DerivedMediaKeyMaterial? =
        runCatching {
            val conferenceIdBytes = request.conferenceIdHex.hexToId128Bytes()
            val srtp =
                Profile01PackageCrypto.deriveSrtpMaterial(
                    authorityMaterial.conferenceMediaSecret,
                    authorityMaterial.membershipKeyContextDigest,
                )
            val hint =
                Profile01PackageCrypto.deriveKeyContextHint64(
                    conferenceIdBytes,
                    request.conferenceEpoch,
                    request.mediaKeyEpoch,
                    authorityMaterial.membershipKeyContextDigest,
                )
            Profile01DerivedMediaKeyMaterial(
                conferenceId = request.conferenceIdHex,
                conferenceEpoch = request.conferenceEpoch,
                membershipVersion = request.membershipVersion,
                mediaKeyEpoch = request.mediaKeyEpoch,
                masterKey = srtp.masterKey,
                masterSalt = srtp.masterSalt,
                keyContextHint64 = hint,
                membershipKeyContextDigest = authorityMaterial.membershipKeyContextDigest.copyOf(),
            )
        }.getOrNull()

    private fun notifyReady(request: HostLocalSupplementMaterializationRequest) {
        onSupplementReady(request.conferenceIdHex, request.mediaKeyEpoch)
    }

    private fun logOutcome(
        request: HostLocalSupplementMaterializationRequest,
        outcome: String,
        extra: String = "",
    ) {
        val suffix = if (extra.isEmpty()) "" else " $extra"
        onLog(
            "PROFILE01_HOST_LOCAL_SUPPLEMENT " +
                "session=${request.sessionId} " +
                "conferenceId=${request.conferenceIdHex} " +
                "mediaKeyEpoch=${request.mediaKeyEpoch} " +
                "outcome=$outcome$suffix",
        )
    }

    private fun derivedSemanticallyEqual(
        left: Profile01DerivedMediaKeyMaterial,
        right: Profile01DerivedMediaKeyMaterial,
    ): Boolean =
        left.conferenceId == right.conferenceId &&
            left.conferenceEpoch == right.conferenceEpoch &&
            left.membershipVersion == right.membershipVersion &&
            left.mediaKeyEpoch == right.mediaKeyEpoch &&
            left.masterKey.contentEquals(right.masterKey) &&
            left.masterSalt.contentEquals(right.masterSalt) &&
            left.keyContextHint64.contentEquals(right.keyContextHint64) &&
            left.membershipKeyContextDigest.contentEquals(right.membershipKeyContextDigest)
}

data class HostLocalSupplementMaterializationRequest(
    val sessionId: String,
    val conferenceIdHex: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val expectedMediaKeyCommitment: ByteArray,
)

enum class HostLocalSupplementMaterializationOutcome {
    READY,
    ALREADY_PRESENT,
    STALE_IDENTITY,
    DERIVE_FAILED,
    NO_AUTHORITY_MATERIAL,
}
