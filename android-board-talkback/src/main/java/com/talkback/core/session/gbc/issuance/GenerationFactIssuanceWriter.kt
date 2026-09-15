package com.talkback.core.session.gbc.issuance

import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.SignedGenerationFactEnvelope
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

fun interface GenerationFactIssuanceSigner {
    fun sign(
        authorityCanonicalBytes: ByteArray,
        verificationContextBytes: ByteArray,
    ): ByteArray
}

/**
 * Origin-side issuance writer (PV2-A).
 * PREPARE → SIGN → FINALIZE → publish gate admission.
 */
class GenerationFactIssuanceWriter(
    private val store: GenerationFactIssuanceStore,
    private val signer: GenerationFactIssuanceSigner,
) {
    private val durableStore = store as? DurableGenerationFactIssuanceStore
    private val inFlightTransactions = AtomicInteger(0)

    fun inFlightCount(): Int = inFlightTransactions.get()

    fun prepareIssuance(
        key: GenerationFactIssuanceKey,
        authority: GenerationFactCanonicalCodec.AuthoritySemantics,
        verificationContext: GenerationFactCanonicalCodec.VerificationContext,
    ): PrepareIssuanceResult {
        val authorityBytes = GenerationFactCanonicalCodec.encodeAuthority(authority)
        val contextBytes = GenerationFactCanonicalCodec.encodeVerificationContext(verificationContext)
        val commitment = GenerationFactCanonicalCodec.computeSemanticDigest(authorityBytes)
        val prepareId = UUID.randomUUID().toString()
        return store.prepare(
            key,
            PreparedIssuanceRecord(
                prepareId = prepareId,
                factCommitment = commitment,
                authorityCanonicalBytes = authorityBytes,
                verificationContextBytes = contextBytes,
                admittedBeforeFence = true,
            ),
        )
    }

    fun signAndFinalize(
        key: GenerationFactIssuanceKey,
        prepareId: String,
    ): FinalizeIssuanceResult {
        inFlightTransactions.incrementAndGet()
        return try {
            val prepared =
                durableStore?.preparedRecords(key)?.get(prepareId)
                    ?: return FinalizeIssuanceResult.PrepareNotFound
            val signatureRs =
                signer.sign(
                    prepared.authorityCanonicalBytes,
                    prepared.verificationContextBytes,
                )
            val envelope =
                SignedGenerationFactEnvelope(
                    authorityCanonicalBytes = prepared.authorityCanonicalBytes,
                    verificationContextBytes = prepared.verificationContextBytes,
                    signatureRs = signatureRs,
                )
            store.finalize(key, prepareId, envelope.toSignedFactBytes())
        } finally {
            inFlightTransactions.decrementAndGet()
        }
    }

    fun abortPrepare(
        key: GenerationFactIssuanceKey,
        prepareId: String,
    ): Boolean = store.abortPrepare(key, prepareId)

    fun abortAllPrepared(key: GenerationFactIssuanceKey): Int {
        val preparedIds = durableStore?.preparedRecords(key)?.keys?.toList().orEmpty()
        var count = 0
        preparedIds.forEach { id ->
            if (store.abortPrepare(key, id)) count++
        }
        return count
    }
}

/**
 * FINALIZE-before-first-publish gate (PV2-IA-T3).
 */
class GenerationFactIssuancePublishGate(
    private val store: GenerationFactIssuanceStore,
) {
    fun admitFirstPublish(
        key: GenerationFactIssuanceKey,
        factCommitment: ByteArray,
    ): PublishAdmissionResult {
        val finalized = store.findFinalized(key, factCommitment)
            ?: return PublishAdmissionResult.NotFinalized
        return PublishAdmissionResult.Admitted(
            signedFactBytes = finalized.signedFactBytes.copyOf(),
            factCommitment = finalized.factCommitment.copyOf(),
        )
    }
}

/**
 * Retirement fence coordinator (PV2-E / PV2-IA-T4).
 */
class GenerationFactRetirementCoordinator(
    private val store: GenerationFactIssuanceStore,
    private val writer: GenerationFactIssuanceWriter,
) {
    fun beginRetirementPrepare(key: GenerationFactIssuanceKey): RetirementPrepareResult =
        store.beginRetirementPrepare(key)

    fun proposeTerminalHead(key: GenerationFactIssuanceKey): TerminalHeadResult {
        if (writer.inFlightCount() > 0) {
            return TerminalHeadResult.UnresolvedPrepared
        }
        writer.abortAllPrepared(key)
        return store.proposeTerminalHead(key)
    }

    fun abortRetirement(key: GenerationFactIssuanceKey): Boolean = store.abortRetirement(key)
}
