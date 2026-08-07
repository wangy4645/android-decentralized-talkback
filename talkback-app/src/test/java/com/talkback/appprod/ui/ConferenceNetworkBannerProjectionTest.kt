package com.talkback.appprod.ui

import com.talkback.appprod.ui.ConferenceNetworkBannerProjection.BannerScope
import com.talkback.appprod.ui.UserVisibleConnectivityProjection.UserVisibleConnectivityState
import org.junit.Assert.assertEquals
import org.junit.Test

/** P1A-PRES-001 — single peer impairment isolation + local vs conference banners. */
class ConferenceNetworkBannerProjectionTest {

    @Test
    fun caseA_singlePeerFlap_noConferenceBanner() {
        val scope = ConferenceNetworkBannerProjection.resolve(
            conferenceLive = true,
            localLanOnline = true,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.RECONNECTING,
                "M03" to UserVisibleConnectivityState.CONNECTED
            )
        )
        assertEquals(BannerScope.NONE, scope)
    }

    @Test
    fun caseB_localWifiOff_localBanner() {
        val scope = ConferenceNetworkBannerProjection.resolve(
            conferenceLive = true,
            localLanOnline = false,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.RECONNECTING,
                "M03" to UserVisibleConnectivityState.RECONNECTING
            )
        )
        assertEquals(BannerScope.LOCAL, scope)
    }

    @Test
    fun caseC_majorityPeersImpaired_conferenceBanner() {
        val scope = ConferenceNetworkBannerProjection.resolve(
            conferenceLive = true,
            localLanOnline = true,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.RECONNECTING,
                "M03" to UserVisibleConnectivityState.DEGRADED
            )
        )
        assertEquals(BannerScope.CONFERENCE, scope)
    }

    @Test
    fun twoParty_singleRemoteFlap_noConferenceBanner() {
        val scope = ConferenceNetworkBannerProjection.resolve(
            conferenceLive = true,
            localLanOnline = true,
            joinedParticipantCount = 2,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.RECONNECTING
            )
        )
        assertEquals(BannerScope.NONE, scope)
    }

    @Test
    fun notLive_noBanner() {
        val scope = ConferenceNetworkBannerProjection.resolve(
            conferenceLive = false,
            localLanOnline = true,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.RECONNECTING
            )
        )
        assertEquals(BannerScope.NONE, scope)
    }
}