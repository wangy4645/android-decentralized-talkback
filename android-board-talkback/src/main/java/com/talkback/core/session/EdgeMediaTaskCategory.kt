package com.talkback.core.session

/** IA-002-1: contract category for edge executor workloads. */
enum class EdgeMediaTaskCategory {
    MEDIA_CRITICAL,
    MEDIA_CONTROL,
    OBSERVATION
}

fun EdgeMediaTaskType.contractCategory(): EdgeMediaTaskCategory = when (this) {
    EdgeMediaTaskType.SRD_APPLY -> EdgeMediaTaskCategory.MEDIA_CRITICAL
    EdgeMediaTaskType.PLAYBACK_CONTROL,
    EdgeMediaTaskType.ICE_CONTROL -> EdgeMediaTaskCategory.MEDIA_CONTROL
    EdgeMediaTaskType.AUDIO_LEVEL_REFRESH -> EdgeMediaTaskCategory.OBSERVATION
    EdgeMediaTaskType.OTHER -> EdgeMediaTaskCategory.MEDIA_CONTROL
}
