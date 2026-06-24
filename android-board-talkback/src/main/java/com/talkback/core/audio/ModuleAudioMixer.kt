package com.talkback.core.audio

import com.talkback.core.model.EndpointAddress

/**
 * Module-internal audio distribution: one RTP stream to/from all local endpoints.
 */
class ModuleAudioMixer {
    private var activeCaptureEndpoint: EndpointAddress? = null

    fun setActiveCapture(endpoint: EndpointAddress?) {
        activeCaptureEndpoint = endpoint
    }

    fun activeCapture(): EndpointAddress? = activeCaptureEndpoint

    fun playbackTargets(onlineLocalEndpoints: List<EndpointAddress>): List<EndpointAddress> =
        onlineLocalEndpoints
}
