package com.talkback.core.session

/** #181 — admission domain selected before downstream lifecycle (#179 / #180). */
enum class GroupAdmissionDomain {
    BOOTSTRAP,
    PAIRWISE_MESH,
    NONE
}
