package com.talkback.governance.transition

@JvmInline
value class TransitionId(val raw: Long) {
    companion object {
        val NONE = TransitionId(0L)

        fun isNone(id: TransitionId): Boolean = id.raw == 0L
    }

    override fun toString(): String = if (isNone(this)) "NONE" else "T$raw"
}
