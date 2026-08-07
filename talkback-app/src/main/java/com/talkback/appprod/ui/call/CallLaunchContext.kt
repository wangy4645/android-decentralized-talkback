package com.talkback.appprod.ui.call

/**
 * Unified outbound call launch contract. EndpointKey is the sole business identity.
 */
data class CallLaunchContext(
    val targetKey: String,
    val targetLabel: String,
    val teamName: String,
    val source: CallSource,
    val returnTarget: CallReturnTarget
)

enum class CallSource {
    CONTACT,
    CONVERSATION,
    FAVORITE
}

sealed class CallReturnTarget {
    data class Conversation(
        val endpointKey: String,
        val endpointLabel: String
    ) : CallReturnTarget()

    data object Contacts : CallReturnTarget()

    data object Talk : CallReturnTarget()
}
