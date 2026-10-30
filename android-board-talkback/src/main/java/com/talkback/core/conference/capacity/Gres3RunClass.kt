package com.talkback.core.conference.capacity

/**
 * G-RES-3 evidence run classification (mechanical qualifying-set guard).
 */
enum class Gres3RunClass {
    QUALIFICATION,
    QUALIFYING,
    ;

    companion object {
        fun parse(raw: String?): Gres3RunClass =
            when (raw?.uppercase()) {
                QUALIFYING.name -> QUALIFYING
                else -> QUALIFICATION
            }
    }
}
