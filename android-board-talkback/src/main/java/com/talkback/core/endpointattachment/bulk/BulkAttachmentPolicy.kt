package com.talkback.core.endpointattachment.bulk

object BulkAttachmentPolicy {
    const val DC_LABEL = "tb-bulk-v1"
    /** Pre-negotiated SCTP stream id; both peers must use [DC_NEGOTIATED_ID] + negotiated=true. */
    const val DC_NEGOTIATED_ID = 1
    const val PROTOCOL_VERSION = 1

    /** Binary frame payload cap (excluding 5-byte header). */
    const val DATA_FRAME_MAX_BYTES = 16 * 1024

    const val FRAME_TYPE_DATA: Byte = 0x02
    const val FRAME_TYPE_END: Byte = 0x03
}