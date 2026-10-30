package com.talkback.core.network



import android.net.ConnectivityManager

import android.net.Network

import com.talkback.core.webrtc.F8BridgeDiagnostic

import com.talkback.core.webrtc.WebRtcNetworkInformationProjection

import org.webrtc.NetworkChangeDetector

import java.util.concurrent.CopyOnWriteArraySet

import java.util.concurrent.atomic.AtomicLong



/**

 * Fact source for the Talkback-selected operational Android [Network].

 * Publishes selection lifecycle events; does not implement network policy.

 */

class SelectedOperationalNetworkRegistry(

    private val connectivityManager: ConnectivityManager,

    private val projectNetwork: (ConnectivityManager, Network) -> NetworkChangeDetector.NetworkInformation? =

        WebRtcNetworkInformationProjection::project,

) {

    data class Snapshot(

        val network: Network,

        val networkId: String,

    )



    sealed class Event {

        abstract val snapshot: Snapshot

        abstract val information: NetworkChangeDetector.NetworkInformation



        data class Selected(

            override val snapshot: Snapshot,

            override val information: NetworkChangeDetector.NetworkInformation,

        ) : Event()



        data class Updated(

            override val snapshot: Snapshot,

            override val information: NetworkChangeDetector.NetworkInformation,

        ) : Event()



        data class Lost(

            override val snapshot: Snapshot,

            val handle: Long,

        ) : Event() {

            override val information: NetworkChangeDetector.NetworkInformation

                get() = error("Lost events do not carry NetworkInformation")

        }

    }



    private val revision = AtomicLong(0)



    @Volatile

    private var current: Snapshot? = null



    private val listeners = CopyOnWriteArraySet<(Event) -> Unit>()



    fun currentRevision(): Long = revision.get()



    fun addListener(listener: (Event) -> Unit): () -> Unit {

        listeners.add(listener)

        replayCurrent(listener)

        return { listeners.remove(listener) }

    }



    fun currentSnapshot(): Snapshot? = current



    fun currentNetworkInformation(): NetworkChangeDetector.NetworkInformation? {

        val network = current?.network ?: return null

        return projectNetwork(connectivityManager, network)

    }



    fun seedFromActiveNetwork() {

        connectivityManager.activeNetwork?.let(::onNetworkAvailable)

    }



    fun onNetworkAvailable(network: Network) {

        val snapshot = Snapshot(network, network.toString())

        current = snapshot

        val information = projectNetwork(connectivityManager, network)

        if (information == null) {

            F8BridgeDiagnostic.registryProjectionMiss(

                event = "selected",

                networkId = snapshot.networkId,

                handle = network.networkHandle,

            )

            return

        }

        val nextRevision = revision.incrementAndGet()

        F8BridgeDiagnostic.registryPublish(

            event = "selected",

            networkId = snapshot.networkId,

            information = information,

            revision = nextRevision,

        )

        listeners.forEach { it(Event.Selected(snapshot, information)) }

    }



    fun onNetworkPropertiesChanged(network: Network) {

        val snapshot = current ?: return

        if (snapshot.network != network) return

        val information = projectNetwork(connectivityManager, network)

        if (information == null) {

            F8BridgeDiagnostic.registryProjectionMiss(

                event = "updated",

                networkId = snapshot.networkId,

                handle = network.networkHandle,

            )

            return

        }

        val nextRevision = revision.incrementAndGet()

        F8BridgeDiagnostic.registryPublish(

            event = "updated",

            networkId = snapshot.networkId,

            information = information,

            revision = nextRevision,

        )

        listeners.forEach { it(Event.Updated(snapshot, information)) }

    }



    fun onNetworkLost(network: Network) {

        val snapshot = current ?: return

        if (snapshot.network != network) return

        val handle = network.networkHandle

        current = null

        val nextRevision = revision.incrementAndGet()

        F8BridgeDiagnostic.registryPublishLost(
            networkId = snapshot.networkId,
            handle = handle,
            revision = nextRevision,
        )

        listeners.forEach { it(Event.Lost(snapshot, handle)) }

    }



    private fun replayCurrent(listener: (Event) -> Unit) {

        val snapshot = current ?: return

        val information = projectNetwork(connectivityManager, snapshot.network)

        if (information == null) {

            F8BridgeDiagnostic.registryProjectionMiss(

                event = "replay",

                networkId = snapshot.networkId,

                handle = snapshot.network.networkHandle,

            )

            return

        }

        listener(Event.Selected(snapshot, information))

    }

}

