package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants

/**
 * Canonical MediaGroupDescriptor for Profile01 production CREATION origin.
 */
object Profile01MediaGroupDescriptor {
    private const val ADDRESS_FAMILY_IPV4: Long = 4L
    private const val UNDERLAY_SCOPE: Long = 8L
    private const val KEY_DISTRIBUTION_PROFILE_RSA3072: Long = 1L
    private const val CODEC_OPUS_MONO: Long = 1L
    private const val BITRATE_BPS: Long = 24_000L
    private const val PACKETIZATION_MS: Long = 20L
    private const val CONTROL_PORT_OFFSET: Int = 1

    fun productionDescriptor(): Profile01CborCodec.CborValue {
        val addressOctets =
            Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS
                .split('.')
                .map { it.toInt().toByte() }
                .toByteArray()
        val mediaPort = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT.toLong()
        val controlPort = (Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT + CONTROL_PORT_OFFSET).toLong()
        val trafficProfile =
            Profile01CborCodec.CborValue.CborMap(
                listOf(
                    u(0) to u(CODEC_OPUS_MONO),
                    u(1) to u(BITRATE_BPS),
                    u(2) to u(PACKETIZATION_MS),
                    u(3) to u(Slice4MulticastNetworkConstants.NOMINAL_PPS.toLong()),
                    u(4) to u(120L),
                    u(5) to u(46L),
                ),
            )
        return Profile01CborCodec.CborValue.CborMap(
            listOf(
                u(0) to u(ADDRESS_FAMILY_IPV4),
                u(1) to Profile01CborCodec.CborValue.ByteString(addressOctets),
                u(2) to u(mediaPort),
                u(3) to u(controlPort),
                u(4) to u(UNDERLAY_SCOPE),
                u(5) to trafficProfile,
            ),
        )
    }

    fun keyDistributionProfile(): Long = KEY_DISTRIBUTION_PROFILE_RSA3072

    private fun u(value: Int): Profile01CborCodec.CborValue = Profile01CborCodec.CborValue.Unsigned(value.toLong())

    private fun u(value: Long): Profile01CborCodec.CborValue = Profile01CborCodec.CborValue.Unsigned(value)
}
