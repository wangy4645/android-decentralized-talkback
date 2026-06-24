package com.talkback.core.audio

import com.talkback.core.model.EndpointAddress

enum class AudioRoute {
    SPEAKER,
    EARPIECE,
    WIRED_HEADSET,
    BLUETOOTH
}

/**
 * Skeleton audio routing layer for handset / speaker / BT selection.
 */
interface AudioRouter {
    fun selectInput(activeEndpoint: EndpointAddress?): AudioRoute
    fun selectOutput(targetEndpoints: List<EndpointAddress>): AudioRoute
    fun onRouteChanged(listener: (AudioRoute) -> Unit)
}

class DefaultAudioRouter : AudioRouter {
    private var listener: ((AudioRoute) -> Unit)? = null
    private var current: AudioRoute = AudioRoute.SPEAKER

    override fun selectInput(activeEndpoint: EndpointAddress?): AudioRoute {
        current = AudioRoute.SPEAKER
        listener?.invoke(current)
        return current
    }

    override fun selectOutput(targetEndpoints: List<EndpointAddress>): AudioRoute {
        current = AudioRoute.SPEAKER
        listener?.invoke(current)
        return current
    }

    override fun onRouteChanged(listener: (AudioRoute) -> Unit) {
        this.listener = listener
    }
}
