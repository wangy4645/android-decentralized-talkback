package com.talkback.core.webrtc



import android.content.Context

import com.talkback.core.network.SelectedOperationalNetworkRegistry

import org.webrtc.NetworkChangeDetector

import org.webrtc.NetworkChangeDetectorFactory



internal class TalkbackWebRtcNetworkBridge(

    private val registry: SelectedOperationalNetworkRegistry,

    private val observer: NetworkChangeDetector.Observer,

) : NetworkChangeDetector {



    private var unsubscribe: (() -> Unit)? = null



    init {

        unsubscribe =

            registry.addListener { event ->

                when (event) {

                    is SelectedOperationalNetworkRegistry.Event.Selected,

                    is SelectedOperationalNetworkRegistry.Event.Updated -> {

                        observer.onNetworkConnect(event.information)

                        observer.onConnectionTypeChanged(event.information.type)

                    }

                    is SelectedOperationalNetworkRegistry.Event.Lost -> {

                        observer.onNetworkDisconnect(event.handle)

                    }

                }

            }

        registry.currentNetworkInformation()?.let { information ->

            observer.onConnectionTypeChanged(information.type)

        }

        val activeList = getActiveNetworkList()

        F8BridgeDiagnostic.detectorCreated(

            initialActiveNetworkCount = activeList?.size ?: 0,

            registryRevision = registry.currentRevision(),

            registryHasSnapshot = registry.currentSnapshot() != null,

        )

        logActiveList()

    }



    override fun destroy() {

        unsubscribe?.invoke()

        unsubscribe = null

    }



    override fun getCurrentConnectionType(): NetworkChangeDetector.ConnectionType {

        return registry.currentNetworkInformation()?.type

            ?: NetworkChangeDetector.ConnectionType.CONNECTION_NONE

    }



    override fun supportNetworkCallback(): Boolean = true



    override fun getActiveNetworkList(): List<NetworkChangeDetector.NetworkInformation>? {

        val information = registry.currentNetworkInformation()

        val list =

            if (information == null) {

                emptyList()

            } else {

                listOf(information)

            }

        logActiveList()

        return list

    }



    private fun logActiveList() {

        val information = registry.currentNetworkInformation()

        F8BridgeDiagnostic.detectorActiveList(

            count = if (information == null) 0 else 1,

            revision = registry.currentRevision(),

            information = information,

        )

    }

}



internal class TalkbackWebRtcNetworkBridgeFactory(

    private val registry: SelectedOperationalNetworkRegistry,

) : NetworkChangeDetectorFactory {

    override fun create(

        observer: NetworkChangeDetector.Observer,

        context: Context,

    ): NetworkChangeDetector {

        return TalkbackWebRtcNetworkBridge(

            registry,

            F8BridgeLoggingObserver(observer),

        )

    }

}


