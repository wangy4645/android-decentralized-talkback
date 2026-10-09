package com.talkback.core.conference.runtime

/**
 * A3-Q1 — one remote source PCM block actually entering [EqualWeightMixer] for a cycle.
 *
 * [kind] separates decoded REAL from future PLC/concealment PCM so amplitude stats do not
 * cross-contaminate.
 */
enum class SourceMixInputKind {
    REAL,
    PLC,
}

class SourceMixInputSnapshot(
    val kind: SourceMixInputKind,
    val samples: ShortArray,
)
