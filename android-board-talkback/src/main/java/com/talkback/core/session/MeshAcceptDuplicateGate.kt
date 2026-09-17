package com.talkback.core.session

import com.talkback.core.media.MediaSessionState
import com.talkback.core.qos.IceConnectivity

/**
 * OPS-03: [MESH_ALREADY_CONNECTED] may only short-circuit duplicate GROUP_ACCEPT when the
 * current peer-media generation is still live and mesh-complete — not from historical ICE.
 */
object MeshAcceptDuplicateGate {
    fun qualifiesForMeshAlreadyConnected(
        qosIceState: String?,
        moduleId: String,
        meshCompletedModules: Set<String>,
        liveMediaState: MediaSessionState?,
    ): Boolean =
        IceConnectivity.isConnected(qosIceState) &&
            moduleId in meshCompletedModules &&
            liveMediaState != null
}
