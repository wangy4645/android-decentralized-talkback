package com.talkback.core.conference.session.profile01

/**
 * Directed field / harness Profile 01 wire fixtures (Q4 golden bytes).
 *
 * PUBLIC TEST DATA ONLY — not production Meeting origin.
 */
object Profile01DirectedWireFixtures {
    const val CONFERENCE_ID = "77031862f9c613735bbd4f3212f9fea7"
    const val CHANNEL_ID = ProductChainFieldSoakConstants.CHANNEL_ID

    private const val CREATION_SIGNED_FACT_HEX =
        "a20058bca40001010102aa005077031862f9c613735bbd4f3212f9fea7010702634d303103a600040144ef0102030219a0280319a029040805a6000101195dc0021403183204187805182e048382634d303150245f786ea577678b814c720da4a1b14682634d303250e9cb6aa21fd5af73fd423f79df62d37b82634d3033503adaf2372978e9a01aa0392940f8c2d305000601075820324bb1f757c4438cb65736f240858636a00369904bbbbe3ef521ced4d627cea3080109f603a2000101f60158402d6f240bcc1e547a6c0c30708f655cd9ebca2c8309ab4ee7e3ec833ec4e25e1d036fc5b26dcd0c267dc3cde11653385900f12f527e39e907a092528fbaf8ff43"

    private const val SOURCE_DECLARATION_SIGNED_FACT_HEX =
        "a2005869a40001010902ab005077031862f9c613735bbd4f3212f9fea701070201030204634d303205010650461241e14ea6008d4b548313deb83f25071a1020304008f6095820fcccf01f522f9df2bc56a054676b0bdc8597830ad2d5d8e8f2e9220e76d9a40f0a0103a10001015840b46049d915bc8bd8941c7384126d6b7bfe7952d3961d11da7d849a1fa2c195846aa342922819097355c6699e23d15f6db982f9820166c6906aa17cc024380473"

    private const val STALE_SOURCE_DECLARATION_SIGNED_FACT_HEX =
        "a2005869a40001010902ab005077031862f9c613735bbd4f3212f9fea701070201030204634d303205010650461241e14ea6008d4b548313deb83f25071a1020304008f6095820fcccf01f522f9df2bc56a054676b0bdc8597830ad2d5d8e8f2e9220e76d9a40f0a0103a10001015840b46049d915bc8bd8941c7384126d6b7bfe7952d3961d11da7d849a1fa2c195846aa342922819097355c6699e23d15f6db982f9820166c6906aa17cc024380474"

    private const val MEMBERSHIP_SIGNED_FACT_HEX =
        "a20058bba40001010202a8005077031862f9c613735bbd4f3212f9fea7010702634d30310301045820f4ca1337b4d2906f473f627ba48e981fe50d93311c4a2471bfd7301bf2fa9577058382634d303150245f786ea577678b814c720da4a1b14682634d303250e9cb6aa21fd5af73fd423f79df62d37b82634d3033503adaf2372978e9a01aa0392940f8c2d3060207582085ae01e9a04f33440123aa28d6c5378460c8842e6237279208a8d598520e577103a2000101674144445f4d3033015840921624e984189e1814198709d4716ca472f1dd88e38bdfd351d7b96c8058196d56dfe718256d5b8a43c975805438916c1a3d57816dccf9f0cddb15e5621d590f"

    private const val M01_PUBLIC_X963_HEX =
        "04984225585d2285c138033d6140e3cef8b91859704e53c313f8b636ba4f9676499734144f46fd19a767a545287c4396b97b69dd38faaea8981adc1a4fed9b401e"
    private const val M02_PUBLIC_X963_HEX =
        "04cbbcaf7e67f163b45f766bed945666932e4629e1e228afbfc8ece2a8a8cb9eb2ae522dd528a32b21a611569f914b5a79d9afe1c8f438ba987da3037e0ec8b3e7"

    val creationSignedFactBytes: ByteArray = hex(CREATION_SIGNED_FACT_HEX)
    val creationFactDigest: ByteArray = hex("f4ca1337b4d2906f473f627ba48e981fe50d93311c4a2471bfd7301bf2fa9577")
    val sourceDeclarationSignedFactBytes: ByteArray = hex(SOURCE_DECLARATION_SIGNED_FACT_HEX)
    val sourceDeclarationFactDigest: ByteArray = hex("a539468fd524c0ea373405f25a6aeae41fb95e2988600c02f5e0d5939faa6979")
    val membershipSignedFactBytes: ByteArray = hex(MEMBERSHIP_SIGNED_FACT_HEX)
    val membershipFactDigest: ByteArray = hex("672a066d517b28515fbe9c787628d19074a6b514de5cdfbbd70c1b912a75d69b")
    val staleSourceDeclarationSignedFactBytes: ByteArray = hex(STALE_SOURCE_DECLARATION_SIGNED_FACT_HEX)

    fun goldenVectorTrustBoundary(): Profile01WireTrustBoundary =
        Profile01GoldenVectorSignedFactTrustBoundary(
            mapOf(
                "M01" to 1L to hex(M01_PUBLIC_X963_HEX),
                "M02" to 1L to hex(M02_PUBLIC_X963_HEX),
            ),
        )

    fun decodeSessionWireFact(supplement: Profile01SessionMediaSupplement): Profile01WireSessionFact {
        val decoded =
            com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder.decodeCreationSession(
                creationSignedFactBytes,
                supplement,
            )
        return (decoded as com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder.DecodeResult.Ready).value
    }

    fun decodeMembershipWireFact(): Profile01WireMembershipFact {
        val decoded =
            com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder.decodeMembership(
                membershipSignedFactBytes,
            )
        return (decoded as com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder.DecodeResult.Ready).value
    }

    fun decodeMemberSourceWireFact(): Profile01WireMemberSourceFact {
        val decoded =
            com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder.decodeSourceDeclarationMember(
                sourceDeclarationSignedFactBytes,
            )
        return (decoded as com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder.DecodeResult.Ready).value
    }

    fun hex(value: String): ByteArray {
        val clean = value.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }
}
