package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.OperationalProfileTestSigning
import com.talkback.core.session.gbc.trust.profile.ProductionProfileRevisionAuthenticator
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptor

object OperationalEstablishmentFixtureSupport {
    const val TRUST_DOMAIN = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN
    const val RECORD_ID = "oe-establishment-record-v1"

    fun establishmentRecord(): EstablishmentRecord =
        EstablishmentRecord(
            deploymentTrustDomainId = TRUST_DOMAIN,
            operationalAuthorityPublicKeySpki = OperationalProfileTestSigning.publicKeySpki.copyOf(),
            establishmentRecordIdentity = RECORD_ID,
            provenanceClass = EstablishmentProvenanceClass.OPERATOR_OUT_OF_BAND,
        )

    fun authorize(record: EstablishmentRecord = establishmentRecord()): EstablishmentAuthorization? =
        PapT2EstablishmentAuthorizationIssuer().authorize(record)

    fun productHarness(file: java.nio.file.Path): ProductHarness {
        val persistence = FileOperationalTrustAnchorPersistence(file)
        val issuer = PapT2EstablishmentAuthorizationIssuer()
        val establisher = OperationalAnchorEstablisher(persistence)
        val anchorSource = OperationalTrustAnchorBootstrap.loadSource(persistence)
        val authenticator =
            ProductionProfileRevisionAuthenticator(anchorSource, TRUST_DOMAIN)
        val trustStore = AcceptedLocalTrustStateStore()
        val acceptor = ProfileRevisionAcceptor(authenticator, trustStore)
        return ProductHarness(persistence, issuer, establisher, anchorSource, authenticator, acceptor, trustStore)
    }

    fun establishProductAnchor(harness: ProductHarness): EstablishmentResult {
        val auth = issuerAuthorize(harness) ?: return EstablishmentResult.Rejected("authorization failed")
        return harness.establisher.establish(auth)
    }

    private fun issuerAuthorize(harness: ProductHarness): EstablishmentAuthorization? =
        harness.issuer.authorize(establishmentRecord())

    data class ProductHarness(
        val persistence: FileOperationalTrustAnchorPersistence,
        val issuer: PapT2EstablishmentAuthorizationIssuer,
        val establisher: OperationalAnchorEstablisher,
        val anchorSource: PersistedOperationalTrustAnchorSource,
        val authenticator: ProductionProfileRevisionAuthenticator,
        val acceptor: ProfileRevisionAcceptor,
        val trustStore: AcceptedLocalTrustStateStore,
    )
}
