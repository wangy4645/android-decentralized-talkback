package com.talkback.core.conference.session.profile01.wire

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Host-side Q5 corpus loader for unit tests (docs/analysis JSON).
 * On-device soak uses [Profile01Q5EmbeddedVectors].
 */
object Profile01Q5CryptoVectorFixtures {
    private val gson = Gson()

    data class Q5Corpus(
        val inputs: Q5Inputs,
        val expected: Q5Expected,
        @SerializedName("negativeVectors")
        val negativeVectors: List<Q5NegativeVector>,
    )

    data class Q5Inputs(
        @SerializedName("conferenceMediaSecretHex")
        val conferenceMediaSecretHex: String,
        @SerializedName("membershipFactDigestHex")
        val membershipFactDigestHex: String,
        @SerializedName("recipientModuleId")
        val recipientModuleId: String,
        @SerializedName("recipientKeyVersion")
        val recipientKeyVersion: Long,
        @SerializedName("packageIdentityHex")
        val packageIdentityHex: String,
        @SerializedName("pekHex")
        val pekHex: String,
        @SerializedName("gcmNonceHex")
        val gcmNonceHex: String,
    )

    data class Q5Expected(
        @SerializedName("mediaKeyCommitmentHex")
        val mediaKeyCommitmentHex: String,
        @SerializedName("wrappedPekHex")
        val wrappedPekHex: String,
        @SerializedName("packageAadBytesHex")
        val packageAadBytesHex: String,
        @SerializedName("ciphertextHex")
        val ciphertextHex: String,
        @SerializedName("gcmTagHex")
        val gcmTagHex: String,
        @SerializedName("srtpMasterKeyHex")
        val srtpMasterKeyHex: String,
        @SerializedName("srtpMasterSaltHex")
        val srtpMasterSaltHex: String,
        @SerializedName("membershipKeyContextDigestHex")
        val membershipKeyContextDigestHex: String,
    )

    data class Q5NegativeVector(
        val name: String,
        val expected: String,
    )

    fun loadCorpus(): Q5Corpus {
        val file =
            listOf(
                File("talkback/docs/analysis/0058-profile-01-q5-crypto-vectors.json"),
                File("../docs/analysis/0058-profile-01-q5-crypto-vectors.json"),
                File("../../docs/analysis/0058-profile-01-q5-crypto-vectors.json"),
                File("docs/analysis/0058-profile-01-q5-crypto-vectors.json"),
            ).firstOrNull { it.isFile }
                ?: error("Q5 corpus not found")
        return gson.fromJson(file.readText(), Q5Corpus::class.java)
    }

    fun wirePackage(corpus: Q5Corpus = loadCorpus()): Profile01WireMediaKeyPackage {
        val e = corpus.expected
        val i = corpus.inputs
        return Profile01WireMediaKeyPackage(
            signedFactBytes = byteArrayOf(),
            factDigest = byteArrayOf(),
            fullCanonicalBytes = byteArrayOf(),
            conferenceId = hex("00112233445566778899aabbccddeeff"),
            conferenceEpoch = 7L,
            ownerModuleId = "M01",
            membershipVersion = 2L,
            mediaKeyEpoch = 3L,
            membershipFactDigest = hex(i.membershipFactDigestHex),
            mediaKeyCommitment = hex(e.mediaKeyCommitmentHex),
            recipientModuleId = i.recipientModuleId,
            packageIdentity = hex(i.packageIdentityHex),
            wrappedPek = hex(e.wrappedPekHex),
            gcmNonce = hex(i.gcmNonceHex),
            ciphertext = hex(e.ciphertextHex),
            gcmTag = hex(e.gcmTagHex),
            recipientKeyVersion = i.recipientKeyVersion,
        )
    }

    fun hex(value: String): ByteArray = Profile01Q5EmbeddedVectors.hex(value)
}
