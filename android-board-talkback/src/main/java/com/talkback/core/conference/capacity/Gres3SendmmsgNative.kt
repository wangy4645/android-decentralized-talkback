package com.talkback.core.conference.capacity

/**
 * E3 architecture probe: blocking native sendmmsg batch (harness only).
 */
object Gres3SendmmsgNative {
    data class OpenResult(
        val socketFd: Int,
        val localPort: Int,
        val sendBufferSizeDefault: Int,
        val sendBufferSizeEffective: Int,
    )

    data class BatchResult(
        val returnedMessages: Int,
        val syscallWallNs: Long,
        val errno: Int,
    ) {
        val requestedMessages: Int
            get() = Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT

        val complete: Boolean
            get() = errno == 0 && returnedMessages == requestedMessages

        val isEagain: Boolean
            get() = errno == Gres3SenderExecutionModel.ERRNO_EAGAIN
    }

    init {
        System.loadLibrary("gres3_sendmmsg_probe")
    }

    fun open(
        bindPort: Int = 0,
        destIps: Array<String>,
        destPorts: IntArray,
    ): OpenResult {
        val values =
            nativeOpen(bindPort, destIps, destPorts)
                ?: error("gres3 sendmmsg nativeOpen failed")
        require(values.size == 4) { "nativeOpen returned ${values.size} values" }
        return OpenResult(
            socketFd = values[0],
            localPort = values[1],
            sendBufferSizeDefault = values[2],
            sendBufferSizeEffective = values[3],
        )
    }

    fun close(socketFd: Int) {
        if (socketFd >= 0) {
            nativeClose(socketFd)
        }
    }

    fun sendmmsgBatch(
        socketFd: Int,
        payload: ByteArray,
        payloadLen: Int,
    ): BatchResult = invokeBatch(nativeSendmmsgBatch(socketFd, payload, payloadLen))

    fun sendmmsgBatchNonblocking(
        socketFd: Int,
        payload: ByteArray,
        payloadLen: Int,
    ): BatchResult = invokeBatch(nativeSendmmsgBatchNonblocking(socketFd, payload, payloadLen))

    private fun invokeBatch(values: IntArray?): BatchResult {
        if (values == null) {
            return BatchResult(returnedMessages = 0, syscallWallNs = 0L, errno = -1)
        }
        require(values.size == 3) { "nativeSendmmsgBatch returned ${values.size} values" }
        return BatchResult(
            returnedMessages = values[0],
            syscallWallNs = values[1].toLong(),
            errno = values[2],
        )
    }

    private external fun nativeOpen(
        bindPort: Int,
        destIps: Array<String>,
        destPorts: IntArray,
    ): IntArray?

    private external fun nativeClose(socketFd: Int)

    private external fun nativeSendmmsgBatch(
        socketFd: Int,
        payload: ByteArray,
        payloadLen: Int,
    ): IntArray?

    private external fun nativeSendmmsgBatchNonblocking(
        socketFd: Int,
        payload: ByteArray,
        payloadLen: Int,
    ): IntArray?
}
