package com.talkback.core.conference.session.profile01.wire

internal fun ByteArray.toHexLower(): String =
    joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

internal fun String.hexToId128Bytes(): ByteArray {
    val clean = trim()
    require(clean.length == 32) { "id128 hex must be 32 chars" }
    return ByteArray(16) { index ->
        val offset = index * 2
        clean.substring(offset, offset + 2).toInt(16).toByte()
    }
}
