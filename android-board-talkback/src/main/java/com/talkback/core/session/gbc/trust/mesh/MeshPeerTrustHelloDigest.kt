package com.talkback.core.session.gbc.trust.mesh

import android.util.Base64
import org.json.JSONObject

/**
 * Optional HELLO-carried public keys so late mesh peers can materialize Profile01 trust.
 */
data class MeshPeerTrustHelloDigest(
    val signingPublicKeySpki: ByteArray? = null,
    val signingKeyVersion: Long = 0L,
    val establishmentPublicKeySpki: ByteArray? = null,
    val establishmentKeyVersion: Long = 0L,
) {
    fun hasSigningMaterial(): Boolean =
        signingPublicKeySpki != null && signingPublicKeySpki.isNotEmpty() && signingKeyVersion > 0L

    fun hasEstablishmentMaterial(): Boolean =
        establishmentPublicKeySpki != null &&
            establishmentPublicKeySpki.isNotEmpty() &&
            establishmentKeyVersion > 0L

    fun encodeJson(): JSONObject {
        val json = JSONObject()
        signingPublicKeySpki?.let {
            json.put("signingSpkiB64", Base64.encodeToString(it, Base64.NO_WRAP))
            json.put("signingKeyVersion", signingKeyVersion)
        }
        establishmentPublicKeySpki?.let {
            json.put("establishmentSpkiB64", Base64.encodeToString(it, Base64.NO_WRAP))
            json.put("establishmentKeyVersion", establishmentKeyVersion)
        }
        return json
    }

    companion object {
        fun decodeJson(json: JSONObject?): MeshPeerTrustHelloDigest? {
            if (json == null) return null
            val signingSpki =
                json.optString("signingSpkiB64").takeIf { it.isNotBlank() }?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }
            val establishmentSpki =
                json.optString("establishmentSpkiB64").takeIf { it.isNotBlank() }?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }
            if (signingSpki == null && establishmentSpki == null) return null
            return MeshPeerTrustHelloDigest(
                signingPublicKeySpki = signingSpki,
                signingKeyVersion = json.optLong("signingKeyVersion", 0L),
                establishmentPublicKeySpki = establishmentSpki,
                establishmentKeyVersion = json.optLong("establishmentKeyVersion", 0L),
            )
        }
    }
}
