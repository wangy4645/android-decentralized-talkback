package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.crypto.GenerationFactPv1FixtureSupport
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.trust.GenerationFactKeyState

object GenerationFactPtiFixtureSupport {
    const val MODULE = GenerationFactPv1FixtureSupport.MODULE_M01
    const val KEY_VERSION = GenerationFactPv1FixtureSupport.KEY_VERSION
    const val ACTIVE_REVISION = GenerationFactPv1FixtureSupport.ACTIVE_REVISION
    const val RETIRED_REVISION = GenerationFactPv1FixtureSupport.RETIRED_REVISION

    fun activeBindingPayload(revision: Long = ACTIVE_REVISION): GenerationFactProfileTrustPayload =
        GenerationFactProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = MODULE,
            moduleBindings =
                listOf(
                    ModuleSigningBinding(
                        moduleId = MODULE,
                        signerKeyVersion = KEY_VERSION,
                        keyState = GenerationFactKeyState.ACTIVE,
                        publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                        activatedAtRevision = ACTIVE_REVISION,
                    ),
                ),
        )

    fun retiredBindingPayload(
        issuanceRoot: ByteArray,
        revision: Long = RETIRED_REVISION,
    ): GenerationFactProfileTrustPayload =
        GenerationFactProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = MODULE,
            moduleBindings =
                listOf(
                    ModuleSigningBinding(
                        moduleId = MODULE,
                        signerKeyVersion = KEY_VERSION,
                        keyState = GenerationFactKeyState.RETIRED_VERIFY,
                        publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                        activatedAtRevision = ACTIVE_REVISION,
                        retiredVerifyAtRevision = RETIRED_REVISION,
                        retirementCheckpoint =
                            ProfileRetirementCheckpointBinding(
                                trustBindingRevision = RETIRED_REVISION,
                                issuanceRoot = issuanceRoot,
                            ),
                    ),
                ),
        )

    fun preparedBindingPayload(revision: Long = ACTIVE_REVISION): GenerationFactProfileTrustPayload =
        GenerationFactProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = MODULE,
            moduleBindings =
                listOf(
                    ModuleSigningBinding(
                        moduleId = MODULE,
                        signerKeyVersion = KEY_VERSION,
                        keyState = GenerationFactKeyState.PREPARED,
                        publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                        activatedAtRevision = ACTIVE_REVISION,
                    ),
                ),
        )

    fun harness(): PtiHarness {
        val authenticator = FixtureProfileRevisionAuthenticator()
        val store = AcceptedLocalTrustStateStore()
        val acceptor = ProfileRevisionAcceptor(authenticator, store)
        val lookup = ProfileBackedGenerationFactTrustLookup(store)
        return PtiHarness(authenticator, store, acceptor, lookup)
    }

    fun accept(
        harness: PtiHarness,
        payload: GenerationFactProfileTrustPayload,
    ): ProfileRevisionAcceptResult {
        harness.authenticator.allowPayload(payload)
        return harness.acceptor.acceptPayload(payload)
    }

    data class PtiHarness(
        val authenticator: FixtureProfileRevisionAuthenticator,
        val store: AcceptedLocalTrustStateStore,
        val acceptor: ProfileRevisionAcceptor,
        val lookup: ProfileBackedGenerationFactTrustLookup,
    )
}
