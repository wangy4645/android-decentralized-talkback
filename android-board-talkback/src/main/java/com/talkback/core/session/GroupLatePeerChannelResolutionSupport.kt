package com.talkback.core.session

/**
 * Resolves which GROUP channel a late-peer HELLO targets.
 * HELLO.channelId wins when present; channel-less HELLO never guesses across sessions.
 */
object GroupLatePeerChannelResolutionSupport {

    data class SessionInput(
        val sessionId: String,
        val channelId: String,
        val peerHasAssociation: Boolean
    )

    enum class ChannelSource {
        HELLO,
        LOCAL_ACCEPTED_SESSION
    }

    sealed interface Result {
        data class Resolved(
            val channelId: String,
            val sessionId: String,
            val channelSource: ChannelSource
        ) : Result

        data object Absent : Result
    }

    fun resolve(helloChannelId: String?, sessions: List<SessionInput>): Result {
        val eligible = sessions.filter { it.channelId.isNotBlank() }
        if (!helloChannelId.isNullOrBlank()) {
            val matched = eligible.filter { it.channelId == helloChannelId }
            return if (matched.size == 1) {
                Result.Resolved(
                    channelId = helloChannelId,
                    sessionId = matched.first().sessionId,
                    channelSource = ChannelSource.HELLO
                )
            } else {
                Result.Absent
            }
        }
        val associated = eligible.filter { it.peerHasAssociation }
        return when {
            associated.size == 1 -> Result.Resolved(
                channelId = associated.first().channelId,
                sessionId = associated.first().sessionId,
                channelSource = ChannelSource.LOCAL_ACCEPTED_SESSION
            )
            associated.size > 1 -> Result.Absent
            eligible.size == 1 -> Result.Resolved(
                channelId = eligible.first().channelId,
                sessionId = eligible.first().sessionId,
                channelSource = ChannelSource.LOCAL_ACCEPTED_SESSION
            )
            else -> Result.Absent
        }
    }
}
