package com.talkback.core.ptt

class FloorOperationTokenRegistry {

    private val tokensByIdentity = linkedMapOf<FloorOperationIdentity, FloorOperationToken>()
    private val withdrawnVersionByIdentity = linkedMapOf<FloorOperationIdentity, Long>()

    @Synchronized
    fun create(identity: FloorOperationIdentity, version: Long): FloorOperationToken {
        tokensByIdentity[identity]?.let { existing ->
            if (existing.validity == OperationTokenValidity.VALID) {
                tokensByIdentity[identity] = existing.withValidity(OperationTokenValidity.INVALIDATED)
            }
        }
        val token = FloorOperationToken(identity, version, OperationTokenValidity.VALID)
        tokensByIdentity[identity] = token
        return token
    }

    @Synchronized
    fun lookup(identity: FloorOperationIdentity): FloorOperationToken? = tokensByIdentity[identity]

    @Synchronized
    fun validTokenFor(identity: FloorOperationIdentity): FloorOperationToken? {
        val token = tokensByIdentity[identity] ?: return null
        return if (token.validity == OperationTokenValidity.VALID) token else null
    }

    @Synchronized
    fun invalidate(token: FloorOperationToken): Boolean {
        val current = tokensByIdentity[token.identity] ?: return false
        if (current !== token || current.isTerminal()) return false
        tokensByIdentity[token.identity] = current.withValidity(OperationTokenValidity.INVALIDATED)
        return true
    }

    @Synchronized
    fun invalidate(identity: FloorOperationIdentity): Boolean {
        val current = tokensByIdentity[identity] ?: return false
        return invalidate(current)
    }

    @Synchronized
    fun complete(token: FloorOperationToken): Boolean {
        val current = tokensByIdentity[token.identity] ?: return false
        if (current !== token || current.isTerminal()) return false
        tokensByIdentity[token.identity] = current.withValidity(OperationTokenValidity.COMPLETED)
        return true
    }

    @Synchronized
    fun recordWithdrawal(identity: FloorOperationIdentity, version: Long) {
        withdrawnVersionByIdentity[identity] =
            maxOf(withdrawnVersionByIdentity[identity] ?: 0L, version)
        invalidate(identity)
    }

    @Synchronized
    fun isWithdrawn(identity: FloorOperationIdentity, version: Long): Boolean {
        val withdrawn = withdrawnVersionByIdentity[identity] ?: return false
        return version <= withdrawn
    }
}
