package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.session.integration.MeetingProfile01AwareConferenceSessionMediaFactPort
import com.talkback.core.conference.session.integration.MeetingProfile01ConferenceSessionIndex
import com.talkback.core.conference.session.integration.MeetingProfile01FactWireIngress
import com.talkback.core.conference.session.integration.MeetingProfile01PreBindFactRetention
import com.talkback.core.conference.session.integration.WireIngressOutcome
import com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-P1D-3 exit tests D3-1..D3-7 — full production MEDIA_KEY_PACKAGE decrypt ingress chain.
 */
class Profile01ProductionMediaKeyPackageIngressTest {
    private val sessionId = "p1d-session-1"
    private val channelId = "p1d-test-channel"

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun d3_1_validMediaKeyPackage_ingestApplied() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Ready)

        val wireOutcome =
            wireIngress(fixture).onConferenceSignedFact(
                signedFactEnvelope(fixture.builtPackage.signedFactBytes),
            )
        assertEquals(WireIngressOutcome.APPLIED, wireOutcome)
    }

    @Test
    fun p1c_field_obs_applied_emitsIngestAndSupplementReady_noSecrets() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertTrue(
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            ) is Profile01MediaKeyPackageIngressResult.Ready,
        )

        val ready =
            fixture.observabilityLogs.single {
                it.startsWith("PROFILE01_MEDIA_SUPPLEMENT ") && it.contains("state=READY")
            }
        assertTrue(ready.contains("conferenceId=${fixture.conferenceIdHex}"))
        assertTrue(ready.contains("mediaKeyEpoch=${wire.mediaKeyEpoch}"))

        val ingest =
            fixture.observabilityLogs.single {
                it.startsWith("PROFILE01_MEDIA_KEY_INGEST ") && it.contains("outcome=APPLIED")
            }
        assertTrue(ingest.contains("conferenceId=${fixture.conferenceIdHex}"))
        assertTrue(ingest.contains("mediaKeyEpoch=${wire.mediaKeyEpoch}"))
        assertTrue(ingest.contains("recipientModuleId=${wire.recipientModuleId}"))
        assertTrue(ingest.contains("establishmentKeyVersion=${fixture.establishmentKeyVersion}"))

        val joined = fixture.observabilityLogs.joinToString("\n")
        assertFalse(joined.contains("masterKey="))
        assertFalse(joined.contains("masterSalt="))
        assertFalse(joined.contains("wrappedPek="))
        assertFalse(joined.contains("plaintext"))
        assertTrue(
            fixture.observabilityLogs.indexOf(ready) < fixture.observabilityLogs.indexOf(ingest),
        )
    }

    @Test
    fun p1c_field_obs_reject_emitsRejectedIngest_withoutSupplementReady() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertRejected(
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                localRecipientModuleId = "M03",
                localEstablishmentKeyVersion = fixture.establishmentKeyVersion,
            ),
            "RECIPIENT_MODULE_MISMATCH",
        )

        val ingest =
            fixture.observabilityLogs.single {
                it.startsWith("PROFILE01_MEDIA_KEY_INGEST ")
            }
        assertTrue(ingest.contains("outcome=REJECTED_RECIPIENT_MODULE_MISMATCH"))
        assertTrue(ingest.contains("conferenceId=${fixture.conferenceIdHex}"))
        assertTrue(ingest.contains("mediaKeyEpoch=${wire.mediaKeyEpoch}"))
        assertTrue(ingest.contains("recipientModuleId=${wire.recipientModuleId}"))
        assertFalse(fixture.observabilityLogs.any { it.startsWith("PROFILE01_MEDIA_SUPPLEMENT ") })
    }

    @Test
    fun d3_2_appliedRegistryResolveSupplement_returnsRealKeyMaterialNotPlaceholder() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val placeholder = Profile01P1DProductionIngressHarness.placeholderSupplement(channelId)

        val before =
            fixture.supplementRegistry.resolveSupplement(
                fixture.conferenceIdHex,
                wire.mediaKeyEpoch,
                channelId,
                placeholder,
            )
        assertArrayEquals(placeholder.masterKey, before.masterKey)

        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Ready)
        val material = (result as Profile01MediaKeyPackageIngressResult.Ready).material

        val resolved =
            fixture.supplementRegistry.resolveSupplement(
                fixture.conferenceIdHex,
                wire.mediaKeyEpoch,
                channelId,
                placeholder,
            )
        assertEquals(16, resolved.masterKey.size)
        assertEquals(12, resolved.masterSalt.size)
        assertEquals(8, resolved.keyContextHint64.size)
        assertArrayEquals(material.masterKey, resolved.masterKey)
        assertArrayEquals(material.masterSalt, resolved.masterSalt)
        assertArrayEquals(material.keyContextHint64, resolved.keyContextHint64)
        assertFalse(resolved.masterKey.all { it.toInt() == 0 })
        assertFalse(resolved.masterSalt.all { it.toInt() == 0 })
        assertFalse(resolved.keyContextHint64.all { it.toInt() == 0 })
        assertEquals(Profile01P1DProductionIngressHarness.corpus.expected.srtpMasterKeyHex, material.masterKey.toHex())
        assertEquals(Profile01P1DProductionIngressHarness.corpus.expected.srtpMasterSaltHex, material.masterSalt.toHex())
    }

    @Test
    fun d3_3_recipientModuleIdMismatch_terminalRejectRegistryUnchanged() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)

        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                localRecipientModuleId = "M03",
                localEstablishmentKeyVersion = fixture.establishmentKeyVersion,
            )
        assertRejected(result, "RECIPIENT_MODULE_MISMATCH")
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)
    }

    @Test
    fun d3_4_recipientKeyVersionMismatch_terminalRejectRegistryUnchanged() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)

        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                fixture.localModuleId,
                localEstablishmentKeyVersion = fixture.establishmentKeyVersion + 1,
            )
        assertRejected(result, "RECIPIENT_KEY_VERSION_MISMATCH")
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)
    }

    @Test
    fun d3_5_corruptCryptoFields_terminalRejectRegistryUnchanged() {
        val fixture = Profile01P1DProductionIngressHarness.create()
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)

        val corruptWrapped =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                fixture.signer,
                fixture.builtPackage.signedFactBytes,
            ) { decoded ->
                decoded.copy(
                    wrappedPek =
                        decoded.wrappedPek.copyOf().also {
                            it[0] = (it[0].toInt() xor 0x01).toByte()
                        },
                )
            }
        assertRejected(
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                corruptWrapped,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            ),
            "OAEP_DECRYPT_FAIL",
        )
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)

        val corruptCipher =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                fixture.signer,
                fixture.builtPackage.signedFactBytes,
            ) { decoded ->
                decoded.copy(
                    ciphertext =
                        decoded.ciphertext.copyOf().also {
                            it[0] = (it[0].toInt() xor 0x01).toByte()
                        },
                )
            }
        assertRejected(
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                corruptCipher,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            ),
            "GCM_AUTH_FAIL",
        )
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)

        val corruptCommitment =
            Profile01P1DProductionIngressHarness.resignTamperedPackage(
                fixture.signer,
                fixture.builtPackage.signedFactBytes,
            ) { decoded ->
                decoded.copy(
                    mediaKeyCommitment =
                        decoded.mediaKeyCommitment.copyOf().also {
                            it[0] = (it[0].toInt() xor 0x01).toByte()
                        },
                )
            }
        assertRejected(
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                corruptCommitment,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            ),
            "GCM_AUTH_FAIL",
        )
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)
    }

    @Test
    fun d3_6_establishmentIdentityUnavailable_decryptSeamUnavailableRegistryUnchanged() {
        val fixture = Profile01P1DProductionIngressHarness.create(includeProductionDecrypt = false)
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(fixture.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertNull(fixture.ingress.mediaKeyDecryptSeam())
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)

        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            )
        assertRejected(result, "DECRYPT_SEAM_UNAVAILABLE")
        assertRegistryEmpty(fixture, wire.mediaKeyEpoch)
    }

    @Test
    fun d3_7_signerVersionEqualsEstablishmentVersion_stillAppliedUsingEstablishmentIdentityOnly() {
        val sharedVersion = Profile01P1DProductionIngressHarness.corpus.inputs.recipientKeyVersion
        val signingStore = AcceptedLocalTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateSigningStore(
            signingStore,
            snapshot =
                Profile01EstablishmentAuthorityTestFixtures.signingOnlySnapshot(
                    signerKeyVersion = sharedVersion,
                ),
        )
        val signerVersion =
            signingStore.currentSnapshot()
                ?.bindingsByKey
                ?.values
                ?.first()
                ?.signerKeyVersion
        assertEquals(sharedVersion, signerVersion)

        val fixture =
            Profile01P1DProductionIngressHarness.create(
                establishmentKeyVersion = sharedVersion,
                signerKeyVersion = sharedVersion,
                spyKeyOperation = true,
            )
        assertEquals(sharedVersion, fixture.authority.localEstablishmentKeyVersion())
        assertEquals(sharedVersion, fixture.signer.signerKeyVersion)

        val availability = fixture.authority.verifiedLocalEstablishmentIdentity()
        assertTrue(availability is Profile01LocalEstablishmentIdentityAvailability.Verified)
        val verified = availability as Profile01LocalEstablishmentIdentityAvailability.Verified

        val result =
            fixture.ingress.ingestMediaKeyPackageSignedFact(
                fixture.builtPackage.signedFactBytes,
                fixture.localModuleId,
                fixture.establishmentKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Ready)
        assertEquals(listOf(verified.identity.keyAlias), fixture.keyOperationSpy!!.requestedAliases)
    }

    private fun wireIngress(
        fixture: Profile01P1DProductionIngressHarness.Fixture,
    ): MeetingProfile01FactWireIngress {
        val registry = ConferenceSessionMediaControlFactRegistry()
        val sessionIndex = MeetingProfile01ConferenceSessionIndex()
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, fixture.conferenceIdHex)
        return MeetingProfile01FactWireIngress(
            ingress = fixture.ingress,
            supplementRegistry = fixture.supplementRegistry,
            sessionIndex = sessionIndex,
            preBindRetention = MeetingProfile01PreBindFactRetention(),
            networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
            localModuleId = { fixture.localModuleId },
            localEstablishmentKeyVersion = { fixture.authority.localEstablishmentKeyVersion() },
        )
    }

    private fun signedFactEnvelope(bytes: ByteArray): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.CONFERENCE_SIGNED_FACT,
            from = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            to = null,
            sessionId = sessionId,
            timestampMs = 1L,
            payload = Base64.getEncoder().encodeToString(bytes),
            nonce = "p1d-wire-nonce",
            signature = "p1d-wire-signature",
        )

    private fun assertRegistryEmpty(
        fixture: Profile01P1DProductionIngressHarness.Fixture,
        mediaKeyEpoch: Long,
    ) {
        assertNull(fixture.supplementRegistry.lookup(fixture.conferenceIdHex, mediaKeyEpoch))
    }

    private fun assertRejected(
        result: Profile01MediaKeyPackageIngressResult,
        reason: String,
    ) {
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Rejected)
        assertEquals(reason, (result as Profile01MediaKeyPackageIngressResult.Rejected).reason)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
}
