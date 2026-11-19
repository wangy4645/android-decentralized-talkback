package com.talkback.appprod.ui

import com.talkback.core.channeltext.ChannelTextController
import com.talkback.core.endpointattachment.ChannelAttachmentController
import com.talkback.core.endpointattachment.EndpointAttachmentController
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
                message.contains(ChannelTextController.REASON_SEND_FAILED, ignoreCase = true) ||
                message.contains(EndpointAttachmentController.REASON_SEND_FAILED, ignoreCase = true) ||
                message.contains(ChannelAttachmentController.REASON_SEND_FAILED, ignoreCase = true) ->
                "SEND_FAILED"
            message.contains(EndpointAttachmentController.REASON_TOO_LARGE, ignoreCase = true) ||
                message.contains(ChannelAttachmentController.REASON_TOO_LARGE, ignoreCase = true) ->
                "ATTACHMENT_TOO_LARGE"
            message.contains(EndpointAttachmentController.REASON_EMPTY, ignoreCase = true) ||
                message.contains(ChannelAttachmentController.REASON_EMPTY, ignoreCase = true) ->
                "ATTACHMENT_EMPTY"
            message.contains("BULK_TOO_LARGE", ignoreCase = true) -> "ATTACHMENT_TOO_LARGE"
            message.contains("BULK_ICE_NOT_READY", ignoreCase = true) -> "UNREACHABLE"
            message.contains("BULK_PTT_PAUSED", ignoreCase = true) -> "BULK_PTT_PAUSED"
            message.contains("BULK_DC_OPEN_FAILED", ignoreCase = true) ||
                message.contains("BULK_READY_TIMEOUT", ignoreCase = true) ||
                message.contains("BULK_SEND_FAILED", ignoreCase = true) -> "SEND_FAILED"
            else -> "FAILED"
        }
    }
}
