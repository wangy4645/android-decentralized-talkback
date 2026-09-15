package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthoritySurface
import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01LocalEstablishmentIdentity
import com.talkback.core.conference.session.profile01.wire.Profile01LocalEstablishmentIdentityAvailability
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore

/**
 * Read-only establishment field probe (EP1–EP5). Emits single-line `EP_PROBE` records only.
 *
 * Does not provision, rotate, accept profiles, or mutate trust state.
 */
object EstablishmentFieldProbe {
    const val LOG_PREFIX = "EP_PROBE"

    data class Session(
        val deviceModuleId: String,
        val establishmentAuthority: Profile01EstablishmentAuthoritySurface,
        val signingTrustStore: AcceptedLocalTrustStateStore,
    )

    fun logEp1RemoteLookup(
        session: Session,
        peerModuleId: String,
        establishmentKeyVersion: Long,
        log: (String) -> Unit = ::defaultLog,
    ): Ep1RemoteLookupOutcome {
        val snapshot = session.establishmentAuthority.establishmentTrustStore.currentSnapshot()
        val lookup =
            session.establishmentAuthority.recipientEstablishmentKeyLookup.lookupEstablishmentKey(
                peerModuleId,
                establishmentKeyVersion,
            )
        val outcome =
            when (lookup) {
                is Profile01EstablishmentKeyLookupResult.Found ->
                    Ep1RemoteLookupOutcome.Found(
                        peerModuleId = peerModuleId,
                        establishmentKeyVersion = lookup.ref.establishmentKeyVersion,
                        fingerprintSha256Hex = lookup.ref.establishmentPublicKeySpki.sha256Hex(),
                        algorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1.name,
                    )
                Profile01EstablishmentKeyLookupResult.UnknownModule ->
                    Ep1RemoteLookupOutcome.UnknownModule(peerModuleId)
                Profile01EstablishmentKeyLookupResult.UnknownKeyVersion ->
                    Ep1RemoteLookupOutcome.UnknownKeyVersion(peerModuleId, establishmentKeyVersion)
                Profile01EstablishmentKeyLookupResult.KeyNotUsable ->
                    Ep1RemoteLookupOutcome.KeyNotUsable(peerModuleId, establishmentKeyVersion)
            }
        log(
            formatLine(
                case = "EP1",
                session = session,
                extra =
                    linkedMapOf(
                        "peerModuleId" to peerModuleId,
                        "requestedEstablishmentKeyVersion" to establishmentKeyVersion.toString(),
                        "lookupOutcome" to outcome.wireName,
                        "establishmentKeyVersion" to outcome.resolvedVersion?.toString(),
                        "establishmentAlgorithm" to outcome.establishmentAlgorithm,
                        "publicKeyFingerprint" to outcome.publicKeyFingerprintSha256Hex,
                        "profileRevisionIdentity" to snapshot?.revisionIdentity?.toHex(),
                        "deploymentTrustDomainId" to snapshot?.deploymentTrustDomainId,
                        "signerKeyVersion" to session.activeSignerKeyVersion().toString(),
                        "localEstablishmentKeyVersion" to
                            session.establishmentAuthority.localEstablishmentKeyVersion().toString(),
                    ),
            ),
        )
        return outcome
    }

    fun logEp2LocalPossession(
        session: Session,
        log: (String) -> Unit = ::defaultLog,
    ): Ep2LocalPossessionOutcome {
        val snapshot = session.establishmentAuthority.establishmentTrustStore.currentSnapshot()
        val availability = session.establishmentAuthority.verifiedLocalEstablishmentIdentity()
        val outcome =
            when (availability) {
                is Profile01LocalEstablishmentIdentityAvailability.Verified ->
                    Ep2LocalPossessionOutcome.Verified(
                        version = availability.identity.establishmentKeyVersion,
                        fingerprintSha256Hex = availability.identity.publicKeyFingerprintSha256Hex(),
                        keyAlias = availability.identity.keyAlias,
                    )
                is Profile01LocalEstablishmentIdentityAvailability.Unavailable ->
                    Ep2LocalPossessionOutcome.Unavailable(availability.diagnostic)
            }
        log(
            formatLine(
                case = "EP2",
                session = session,
                extra =
                    linkedMapOf(
                        "localIdentityAvailability" to outcome.wireName,
                        "localKeyAlias" to outcome.localKeyAlias,
                        "establishmentKeyVersion" to outcome.establishmentKeyVersion?.toString(),
                        "publicKeyFingerprint" to outcome.publicKeyFingerprintSha256Hex,
                        "profileRevisionIdentity" to snapshot?.revisionIdentity?.toHex(),
                        "deploymentTrustDomainId" to snapshot?.deploymentTrustDomainId,
                        "signerKeyVersion" to session.activeSignerKeyVersion().toString(),
                        "localEstablishmentKeyVersion" to
                            Profile01LocalEstablishmentIdentity.activeEstablishmentKeyVersion(
                                session.establishmentAuthority.establishmentTrustStore,
                                session.deviceModuleId,
                            ).toString(),
                    ),
            ),
        )
        return outcome
    }

    fun logEp4IdentityDomainSeparation(
        session: Session,
        log: (String) -> Unit = ::defaultLog,
    ): Ep4IdentityDomainOutcome {
        val signerVersion = session.activeSignerKeyVersion()
        val establishmentVersion = session.establishmentAuthority.localEstablishmentKeyVersion()
        val outcome =
            when {
                signerVersion <= 0L && establishmentVersion <= 0L ->
                    Ep4IdentityDomainOutcome.InsufficientState("missing signer and establishment versions")
                signerVersion <= 0L ->
                    Ep4IdentityDomainOutcome.InsufficientState("missing signerKeyVersion")
                establishmentVersion <= 0L ->
                    Ep4IdentityDomainOutcome.InsufficientState("missing establishmentKeyVersion")
                signerVersion == establishmentVersion ->
                    Ep4IdentityDomainOutcome.CollapsedDomain(signerVersion, establishmentVersion)
                else ->
                    Ep4IdentityDomainOutcome.Separated(signerVersion, establishmentVersion)
            }
        val snapshot = session.establishmentAuthority.establishmentTrustStore.currentSnapshot()
        log(
            formatLine(
                case = "EP4",
                session = session,
                extra =
                    linkedMapOf(
                        "signerKeyVersion" to signerVersion.toString(),
                        "establishmentKeyVersion" to establishmentVersion.toString(),
                        "domainSeparationOutcome" to outcome.wireName,
                        "profileRevisionIdentity" to snapshot?.revisionIdentity?.toHex(),
                        "deploymentTrustDomainId" to snapshot?.deploymentTrustDomainId,
                    ),
            ),
        )
        return outcome
    }

    fun logEp5NegativeFence(
        session: Session,
        peerModuleId: String,
        wrongEstablishmentKeyVersion: Long,
        log: (String) -> Unit = ::defaultLog,
    ): Ep5NegativeFenceOutcome {
        val beforeCount =
            session.establishmentAuthority.establishmentTrustStore.currentSnapshot()
                ?.bindingsByKey
                ?.size
        val unknownModule =
            session.establishmentAuthority.recipientEstablishmentKeyLookup.lookupEstablishmentKey(
                "M99",
                wrongEstablishmentKeyVersion,
            )
        val wrongVersion =
            session.establishmentAuthority.recipientEstablishmentKeyLookup.lookupEstablishmentKey(
                peerModuleId,
                wrongEstablishmentKeyVersion,
            )
        val afterCount =
            session.establishmentAuthority.establishmentTrustStore.currentSnapshot()
                ?.bindingsByKey
                ?.size
        val outcome =
            Ep5NegativeFenceOutcome(
                unknownModuleOutcome = unknownModule.toWireName(),
                wrongVersionOutcome = wrongVersion.toWireName(),
                storeBindingCountUnchanged = beforeCount == afterCount,
            )
        log(
            formatLine(
                case = "EP5",
                session = session,
                extra =
                    linkedMapOf(
                        "peerModuleId" to peerModuleId,
                        "wrongEstablishmentKeyVersion" to wrongEstablishmentKeyVersion.toString(),
                        "unknownModuleLookupOutcome" to outcome.unknownModuleOutcome,
                        "wrongVersionLookupOutcome" to outcome.wrongVersionOutcome,
                        "storeBindingCountUnchanged" to outcome.storeBindingCountUnchanged.toString(),
                        "signerKeyVersion" to session.activeSignerKeyVersion().toString(),
                        "localEstablishmentKeyVersion" to
                            session.establishmentAuthority.localEstablishmentKeyVersion().toString(),
                    ),
            ),
        )
        return outcome
    }

    private fun Session.activeSignerKeyVersion(): Long {
        val snapshot = signingTrustStore.currentSnapshot() ?: return 0L
        return snapshot.bindingsByKey.values
            .asSequence()
            .filter { binding ->
                binding.moduleId == deviceModuleId &&
                    binding.keyState == GenerationFactKeyState.ACTIVE
            }
            .maxOfOrNull { it.signerKeyVersion }
            ?: 0L
    }

    private fun formatLine(
        case: String,
        session: Session,
        extra: Map<String, String?>,
    ): String {
        val parts = mutableListOf("$LOG_PREFIX case=$case", "device=${session.deviceModuleId}")
        extra.forEach { (key, value) ->
            if (!value.isNullOrBlank()) {
                parts += "$key=$value"
            }
        }
        return parts.joinToString(separator = " ")
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

    private fun Profile01EstablishmentKeyLookupResult.toWireName(): String =
        when (this) {
            is Profile01EstablishmentKeyLookupResult.Found -> "Found"
            Profile01EstablishmentKeyLookupResult.UnknownModule -> "UnknownModule"
            Profile01EstablishmentKeyLookupResult.UnknownKeyVersion -> "UnknownKeyVersion"
            Profile01EstablishmentKeyLookupResult.KeyNotUsable -> "KeyNotUsable"
        }

    private fun defaultLog(line: String) {
        // Field runners attach log sink (TalkbackLog / logcat) at instrumentation boundary.
        println(line)
    }
}

sealed class Ep1RemoteLookupOutcome {
    abstract val wireName: String
    abstract val resolvedVersion: Long?
    abstract val establishmentAlgorithm: String?
    abstract val publicKeyFingerprintSha256Hex: String?

    data class Found(
        val peerModuleId: String,
        val establishmentKeyVersion: Long,
        val fingerprintSha256Hex: String,
        val algorithm: String,
    ) : Ep1RemoteLookupOutcome() {
        override val wireName: String = "Found"
        override val resolvedVersion: Long = establishmentKeyVersion
        override val establishmentAlgorithm: String? = algorithm
        override val publicKeyFingerprintSha256Hex: String? = fingerprintSha256Hex
    }

    data class UnknownModule(
        val peerModuleId: String,
    ) : Ep1RemoteLookupOutcome() {
        override val wireName: String = "UnknownModule"
        override val resolvedVersion: Long? = null
        override val establishmentAlgorithm: String? = null
        override val publicKeyFingerprintSha256Hex: String? = null
    }

    data class UnknownKeyVersion(
        val peerModuleId: String,
        val requestedVersion: Long,
    ) : Ep1RemoteLookupOutcome() {
        override val wireName: String = "UnknownKeyVersion"
        override val resolvedVersion: Long? = null
        override val establishmentAlgorithm: String? = null
        override val publicKeyFingerprintSha256Hex: String? = null
    }

    data class KeyNotUsable(
        val peerModuleId: String,
        val requestedVersion: Long,
    ) : Ep1RemoteLookupOutcome() {
        override val wireName: String = "KeyNotUsable"
        override val resolvedVersion: Long? = null
        override val establishmentAlgorithm: String? = null
        override val publicKeyFingerprintSha256Hex: String? = null
    }
}

sealed class Ep2LocalPossessionOutcome {
    abstract val wireName: String
    abstract val establishmentKeyVersion: Long?
    abstract val publicKeyFingerprintSha256Hex: String?
    abstract val localKeyAlias: String?

    data class Verified(
        val version: Long,
        val fingerprintSha256Hex: String,
        val keyAlias: String,
    ) : Ep2LocalPossessionOutcome() {
        override val wireName: String = "Verified"
        override val establishmentKeyVersion: Long = version
        override val publicKeyFingerprintSha256Hex: String? = fingerprintSha256Hex
        override val localKeyAlias: String? = keyAlias
    }

    data class Unavailable(
        val diagnostic: String,
    ) : Ep2LocalPossessionOutcome() {
        override val wireName: String = "Unavailable"
        override val establishmentKeyVersion: Long? = null
        override val publicKeyFingerprintSha256Hex: String? = null
        override val localKeyAlias: String? = null
    }
}

sealed class Ep4IdentityDomainOutcome {
    abstract val wireName: String

    data class Separated(
        val signerKeyVersion: Long,
        val establishmentKeyVersion: Long,
    ) : Ep4IdentityDomainOutcome() {
        override val wireName: String = "Separated"
    }

    data class CollapsedDomain(
        val signerKeyVersion: Long,
        val establishmentKeyVersion: Long,
    ) : Ep4IdentityDomainOutcome() {
        override val wireName: String = "CollapsedDomain"
    }

    data class InsufficientState(
        val reason: String,
    ) : Ep4IdentityDomainOutcome() {
        override val wireName: String = "InsufficientState"
    }
}

data class Ep5NegativeFenceOutcome(
    val unknownModuleOutcome: String,
    val wrongVersionOutcome: String,
    val storeBindingCountUnchanged: Boolean,
)
