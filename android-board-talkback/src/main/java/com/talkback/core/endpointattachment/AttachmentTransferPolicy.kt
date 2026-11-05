package com.talkback.core.endpointattachment

import com.talkback.core.endpointattachment.AttachmentChunker.MAX_ATTACHMENT_BYTES

object AttachmentTransferPolicy {
    /** Incomplete inbound transfer eviction (memory only; no partial files in core). */
    const val TRANSFER_TTL_MS: Long = 120_000L

    /** Max simultaneous inbound transfers per controller instance. */
    const val MAX_INBOUND_TRANSFERS: Int = 16

    /** Product default cap (512 KiB). Hard ceiling remains [MAX_ATTACHMENT_BYTES]. */
    const val DEFAULT_MAX_ATTACHMENT_BYTES: Int = 512 * 1024

    const val CHUNK_PACING_MS: Long = 2L
}
