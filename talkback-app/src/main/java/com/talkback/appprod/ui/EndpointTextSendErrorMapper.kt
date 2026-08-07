package com.talkback.appprod.ui

import com.talkback.core.channeltext.ChannelTextController
import com.talkback.core.endpointtext.EndpointTextController

/**
 * Maps EndpointText / ChannelText send failures to UI error codes.
 */
object EndpointTextSendErrorMapper {
    fun map(result: Result<Unit>): String? {
        if (result.isSuccess) return null
        val message = result.exceptionOrNull()?.message.orEmpty()
        return when {
            message.contains("SERVICE_STOPPED", ignoreCase = true) -> "SERVICE_STOPPED"
            message.contains(EndpointTextController.REASON_UNREACHABLE, ignoreCase = true) ||
                message.contains(ChannelTextController.REASON_UNREACHABLE, ignoreCase = true) ||
                message.contains("not discovered", ignoreCase = true) -> "UNREACHABLE"
            message.contains(ChannelTextController.REASON_NO_MEMBERS, ignoreCase = true) ->
                "NO_MEMBERS"
            message.contains(EndpointTextController.REASON_TEXT_TOO_LONG, ignoreCase = true) ||
                message.contains(ChannelTextController.REASON_TEXT_TOO_LONG, ignoreCase = true) ->
                "TEXT_TOO_LONG"
            message.contains(EndpointTextController.REASON_SEND_FAILED, ignoreCase = true) ||
                message.contains(ChannelTextController.REASON_SEND_FAILED, ignoreCase = true) ->
                "SEND_FAILED"
            else -> "FAILED"
        }
    }
}
