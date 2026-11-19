package com.talkback.core.endpointattachment.bulk

import com.talkback.core.endpointattachment.AttachmentTransferPolicy

object AttachmentSizePolicy {
    /** Existing V2 UDP path. */
    const val V2_SMALL_MAX_BYTES: Int = AttachmentTransferPolicy.DEFAULT_MAX_ATTACHMENT_BYTES

    const val BULK_MAX_BYTES: Long = 50L * 1024L * 1024L

    enum class Route {
        V2_UDP,
        DC_BULK,
        REJECT_TOO_LARGE,
        REJECT_EMPTY,
    }

    fun route(sizeBytes: Long): Route = when {
        sizeBytes <= 0L -> Route.REJECT_EMPTY
        sizeBytes <= V2_SMALL_MAX_BYTES -> Route.V2_UDP
        sizeBytes <= BULK_MAX_BYTES -> Route.DC_BULK
        else -> Route.REJECT_TOO_LARGE
    }
}