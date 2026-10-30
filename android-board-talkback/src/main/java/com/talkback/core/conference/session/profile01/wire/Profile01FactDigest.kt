package com.talkback.core.conference.session.profile01.wire

import java.security.MessageDigest

object Profile01FactDigest {
    fun authorityDigestObjectBytes(fullFactBytes: ByteArray): ByteArray {
        val fact = Profile01CborCodec.decodeStrict(fullFactBytes)
        val map = fact.intKeyMap() ?: throw Profile01CborCodec.CborException("not a fact map")
        val schema = map[0]?.asUnsigned() ?: throw Profile01CborCodec.CborException("missing schema")
        val factType = map[1]?.asUnsigned() ?: throw Profile01CborCodec.CborException("missing factType")
        val authority = map[2] ?: throw Profile01CborCodec.CborException("missing authority")
        return Profile01CborCodec.encode(
            Profile01CborCodec.CborValue.CborArray(
                listOf(
                    Profile01CborCodec.CborValue.Unsigned(schema),
                    Profile01CborCodec.CborValue.Unsigned(factType),
                    authority,
                ),
            ),
        )
    }

    fun computeFactDigest(fullFactBytes: ByteArray): ByteArray {
        val authorityObject = authorityDigestObjectBytes(fullFactBytes)
        return MessageDigest.getInstance("SHA-256").run {
            update(Profile01WireConstants.FACT_DIGEST_DOMAIN)
            update(authorityObject)
            digest()
        }
    }

    fun descriptorDigest(descriptor: Profile01CborCodec.CborValue): ByteArray {
        val canonical = Profile01CborCodec.encode(descriptor)
        return MessageDigest.getInstance("SHA-256").run {
            update(Profile01WireConstants.DESCRIPTOR_DIGEST_DOMAIN)
            update(canonical)
            digest()
        }
    }
}
