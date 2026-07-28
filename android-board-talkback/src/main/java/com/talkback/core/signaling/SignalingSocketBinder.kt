package com.talkback.core.signaling

import java.net.DatagramSocket

interface SignalingSocketBinder {
    fun bindSocket(socket: DatagramSocket): String
}