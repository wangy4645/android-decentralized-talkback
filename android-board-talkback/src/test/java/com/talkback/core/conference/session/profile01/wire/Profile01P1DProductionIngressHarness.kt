package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.Profile01WireSessionFact
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreAlias
import com.talkback.core.session.gbc.trust.profile.establishment.forModule
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreLoadResult
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot
import com.talkback.core.session.gbc.wiring.EstablishmentProductionWiringHarness
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PR-P1D-3 harness — Q5 vectors + production establishment unwrap composition.
 */
internal object Profile01P1DProductionIngressHarness {
    val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()

    data class Fixture(
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val validator: Profile01ConferenceMediaFactValidator,
        val ingress: Profile01ConferenceMediaFactIngress,
        val authority: Profile01EstablishmentAuthoritySurface,
        val signer: Profile01PersistedSignedFactSigner,
        val builtPackage: Profile01MediaKeyPackageBuildResult.Ready,
        val conferenceIdHex: String,
        val localModuleId: String,
        val establishmentKeyVersion: Long,
        val keyOperationSpy: SpyEstablishmentPekUnwrapKeyOperation?,
        val observabilityLogs: MutableList<String>,
    )

    fun create(
        establishmentKeyVersion: Long = corpus.inputs.recipientKeyVersion,
        signerKeyVersion: Long = 1L,
        includeProductionDecrypt: Boolean = true,
        spyKeyOperation: Boolean = false,
    ): Fixture {
        val observabilityLogs = mutableListOf<String>()
        val localModuleId = corpus.inputs.recipientModuleId
        val signerBundle = testSigner(moduleId = "M01", keyVersion = signerKeyVersion)
        val built = buildQ5Package(signerBundle.signer)
        val decoded =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val conferenceIdHex = decoded.conferenceId.toHex()

        val keystoreStore = Q5EstablishmentKeystoreIdentityStore(localModuleId, establishmentKeyVersion)
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        establishmentStore.atomicReplace(
            establishmentSnapshot(
                localModuleId = localModuleId,
                establishmentKeyVersion = establishmentKeyVersion,
                localSpki = Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki(),
            ),
        )
        val deliveryAcceptor =
            EstablishmentProductionWiringHarness.deliveryAcceptorOnly(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = GenerationFactPtiLayerBFixtureSupport.anchorStore(),
                store = establishmentStore,
            )
        val authority =
            Profile01EstablishmentAuthorityWiring.create(
                establishmentTrustStore = establishmentStore,
                localModuleId = localModuleId,
                keystoreIdentityStore = keystoreStore,
                establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            )

        val spy =
            if (spyKeyOperation) {
                SpyEstablishmentPekUnwrapKeyOperation(
                    Profile01Q5TestRecipientKeyEstablishmentSeam.q5TestOnlyEstablishmentPrivateKey(),
                )
            } else {
                null
            }
        val keyOperation =
            spy
                ?: Q5EstablishmentPekUnwrapKeyOperation(
                    Profile01Q5TestRecipientKeyEstablishmentSeam.q5TestOnlyEstablishmentPrivateKey(),
                )
        val mediaKeyDecrypt =
            if (includeProductionDecrypt) {
                Profile01ProductionMediaKeyDecryptComposition.compose(authority, keyOperation)
            } else {
                null
            }

        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val validator =
            Profile01ConferenceMediaFactValidator(signerBundle.trustBoundary).also { v ->
                seedMembershipConvergence(v, decoded)
            }
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge =
                    com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge(
                        com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry(),
                    ),
                mediaKeyDecrypt = mediaKeyDecrypt,
                supplementRegistry = supplementRegistry,
                onLog = observabilityLogs::add,
            )

        return Fixture(
            supplementRegistry = supplementRegistry,
            validator = validator,
            ingress = ingress,
            authority = authority,
            signer = signerBundle.signer,
            builtPackage = built,
            conferenceIdHex = conferenceIdHex,
            localModuleId = localModuleId,
            establishmentKeyVersion = establishmentKeyVersion,
            keyOperationSpy = spy,
            observabilityLogs = observabilityLogs,
        )
    }

    fun resignTamperedPackage(
        signer: Profile01PersistedSignedFactSigner,
        signedFactBytes: ByteArray,
        tamper: (Profile01WireMediaKeyPackage) -> Profile01WireMediaKeyPackage,
    ): ByteArray {
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val tampered = tamper(wire)
        val snapshot =
            Profile01MediaKeyPackageAuthoritySnapshot(
                conferenceId = tampered.conferenceId.copyOf(),
                conferenceEpoch = tampered.conferenceEpoch,
                ownerModuleId = tampered.ownerModuleId,
                membershipVersion = tampered.membershipVersion,
                mediaKeyEpoch = tampered.mediaKeyEpoch,
                membershipFactDigest = tampered.membershipFactDigest.copyOf(),
                mediaKeyCommitment = tampered.mediaKeyCommitment.copyOf(),
                recipientModuleId = tampered.recipientModuleId,
                packageIdentity = tampered.packageIdentity.copyOf(),
                wrappedPek = tampered.wrappedPek.copyOf(),
                gcmNonce = tampered.gcmNonce.copyOf(),
                ciphertext = tampered.ciphertext.copyOf(),
                gcmTag = tampered.gcmTag.copyOf(),
                recipientKeyVersion = tampered.recipientKeyVersion,
                signerKeyVersion = signer.signerKeyVersion,
            )
        val fullCanonicalBytes = Profile01WireCborEncoder.encodeMediaKeyPackageFullFact(snapshot)
        val signature = signer.signFullFact(fullCanonicalBytes)
        return Profile01SignedFactEnvelopeBuilder.build(fullCanonicalBytes, signature)
    }

    fun placeholderSupplement(channelId: String = "p1d-test-channel"): Profile01SessionMediaSupplement =
        Profile01SessionMediaSupplement(
            channelId = channelId,
            masterKey = ByteArray(32),
            masterSalt = ByteArray(12),
            keyContextHint64 = ByteArray(8),
        )

    private fun buildQ5Package(signer: Profile01PersistedSignedFactSigner): Profile01MediaKeyPackageBuildResult.Ready {
        val q5ReferenceWire = Profile01Q5CryptoVectorFixtures.wirePackage(corpus)
        val secret = hex(corpus.inputs.conferenceMediaSecretHex)
        val contextDigest = hex(corpus.expected.membershipKeyContextDigestHex)
        val commitment = Profile01PackageCrypto.computeMediaKeyCommitment(secret, contextDigest)
        val recipientBinding =
            Profile01PackageRecipientBinding.fromModuleEstablishmentBinding(
                ModuleEstablishmentBinding(
                    moduleId = corpus.inputs.recipientModuleId,
                    establishmentKeyVersion = corpus.inputs.recipientKeyVersion,
                    keyState = GenerationFactKeyState.ACTIVE,
                    establishmentPublicKeySpki =
                        Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki(),
                    activatedAtRevision = 10L,
                ),
            )
        val request =
            Profile01MediaKeyPackageBuildRequest(
                material =
                    Profile01ConferenceKeyMaterial(
                        conferenceId = q5ReferenceWire.conferenceId.copyOf(),
                        conferenceEpoch = q5ReferenceWire.conferenceEpoch,
                        ownerModuleId = q5ReferenceWire.ownerModuleId,
                        membershipVersion = q5ReferenceWire.membershipVersion,
                        mediaKeyEpoch = q5ReferenceWire.mediaKeyEpoch,
                        conferenceMediaSecret = secret,
                        membershipKeyContextDigest = contextDigest,
                        mediaKeyCommitment = commitment,
                    ),
                creationFactDigest = hex(corpus.inputs.membershipFactDigestHex),
                recipient = recipientBinding,
            )
        val result =
            Profile01MediaKeyPackageBuilder(
                signer = signer,
                randomSource =
                    Profile01PackageRandomSource.fixed(
                        pek = hex(corpus.inputs.pekHex),
                        packageIdentity = hex(corpus.inputs.packageIdentityHex),
                        gcmNonce = hex(corpus.inputs.gcmNonceHex),
                    ),
            ).build(request)
        return result as Profile01MediaKeyPackageBuildResult.Ready
    }

    private fun seedMembershipConvergence(
        validator: Profile01ConferenceMediaFactValidator,
        wire: Profile01WireMediaKeyPackage,
    ) {
        val creationSeed =
            Profile01WireSessionFact(
                signedFactBytes = byteArrayOf(),
                factDigest = wire.membershipFactDigest.copyOf(),
                conferenceId = wire.conferenceId.toHex(),
                channelId = "p1d-test-channel",
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                membershipView =
                    listOf(
                        Profile01WireMembershipMember("M01", byteArrayOf(1)),
                        Profile01WireMembershipMember(localModuleId(wire), byteArrayOf(2)),
                    ),
                mediaKeyCommitment = wire.mediaKeyCommitment.copyOf(),
                endpoint = MediaGroupEndpointBinding("239.0.0.1", 5004),
                masterKey = ByteArray(32),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
        validator.membershipRegistry().seedFromCreation(creationSeed)
    }

    private fun localModuleId(wire: Profile01WireMediaKeyPackage): String = wire.recipientModuleId

    private fun establishmentSnapshot(
        localModuleId: String,
        establishmentKeyVersion: Long,
        localSpki: ByteArray,
    ): AcceptedLocalEstablishmentTrustState {
        val peerBinding =
            ModuleEstablishmentBinding(
                moduleId = "M01",
                establishmentKeyVersion = establishmentKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = localSpki.copyOf(),
                activatedAtRevision = 10L,
            )
        val localBinding =
            ModuleEstablishmentBinding(
                moduleId = localModuleId,
                establishmentKeyVersion = establishmentKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = localSpki.copyOf(),
                activatedAtRevision = 10L,
            )
        return AcceptedLocalEstablishmentTrustState(
            taskProfileRevision = 10L,
            revisionIdentity = ByteArray(32),
            localModuleId = localModuleId,
            bindingsByKey =
                listOf(peerBinding, localBinding).associateBy {
                    AcceptedLocalEstablishmentTrustState.BindingKey(
                        it.moduleId,
                        it.establishmentKeyVersion,
                    )
                },
        )
    }

    private data class TestSignerBundle(
        val signer: Profile01PersistedSignedFactSigner,
        val trustBoundary: Profile01GoldenVectorSignedFactTrustBoundary,
    )

    private fun testSigner(
        moduleId: String,
        keyVersion: Long,
    ): TestSignerBundle {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = moduleId,
                signerKeyVersion = keyVersion,
            )!!
        val x963 = ecPublicKeyX963(keyPair.public)
        return TestSignerBundle(
            signer = signer,
            trustBoundary =
                Profile01GoldenVectorSignedFactTrustBoundary(
                    mapOf(moduleId to keyVersion to x963),
                ),
        )
    }

    private fun ecPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as java.security.interfaces.ECPublicKey
        val x = ec.w.affineX.toByteArray()
        val y = ec.w.affineY.toByteArray()
        val x32 = x.copyOfRange(maxOf(0, x.size - 32), x.size)
        val y32 = y.copyOfRange(maxOf(0, y.size - 32), y.size)
        return byteArrayOf(0x04) + ByteArray(32 - x32.size) + x32 + ByteArray(32 - y32.size) + y32
    }

    private fun hex(value: String): ByteArray = Profile01Q5CryptoVectorFixtures.hex(value)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
}

private class Q5EstablishmentKeystoreIdentityStore(
    private val moduleId: String,
    private val establishmentKeyVersion: Long,
) : LocalEstablishmentKeystoreIdentityStore {
    private val snapshot =
        Profile01LocalEstablishmentIdentitySnapshot(
            moduleId = moduleId,
            establishmentKeyVersion = establishmentKeyVersion,
            publicKeySpki = Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki().copyOf(),
            keyAlias = LocalEstablishmentKeystoreAlias.forModule(moduleId, establishmentKeyVersion),
        )

    override fun provisionFirstIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreProvisionResult =
        LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned(snapshot)

    override fun loadIdentity(
        moduleId: String,
        establishmentKeyVersion: Long,
    ): LocalEstablishmentKeystoreLoadResult =
        if (moduleId == this.moduleId && establishmentKeyVersion == this.establishmentKeyVersion) {
            LocalEstablishmentKeystoreLoadResult.Ready(snapshot)
        } else {
            LocalEstablishmentKeystoreLoadResult.Unavailable("MISSING_KEYSTORE_IDENTITY")
        }
}

private class Q5EstablishmentPekUnwrapKeyOperation(
    private val privateKey: PrivateKey,
) : EstablishmentPekUnwrapKeyOperation {
    override fun unwrap(
        wrappedPek: ByteArray,
        authorizedKeyAlias: String,
    ): Profile01PekUnwrapResult = EstablishmentRsaOaepPekCrypto.unwrap(wrappedPek, privateKey)
}

internal class SpyEstablishmentPekUnwrapKeyOperation(
    private val privateKey: PrivateKey,
) : EstablishmentPekUnwrapKeyOperation {
    val requestedAliases = mutableListOf<String>()

    override fun unwrap(
        wrappedPek: ByteArray,
        authorizedKeyAlias: String,
    ): Profile01PekUnwrapResult {
        requestedAliases += authorizedKeyAlias
        return EstablishmentRsaOaepPekCrypto.unwrap(wrappedPek, privateKey)
    }
}
