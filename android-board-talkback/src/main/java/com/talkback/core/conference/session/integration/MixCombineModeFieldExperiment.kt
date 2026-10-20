package com.talkback.core.conference.session.integration

import android.util.Log
import java.io.File

/**
 * Field A/B — runtime mix combine mode without rebuild.
 *
 * Push [FIELD_FLAG_FILE_NAME] under app `filesDir` (one line: `SUM` or `AVG`), cold start or call
 * [applyFromPrivateFlagFile].
 */
object MixCombineModeFieldExperiment {
    const val LOG_TAG = "TalkbackMixCombineAB"
    const val FIELD_FLAG_FILE_NAME = "mix_combine_field.flag"

    fun applyFromPrivateFlagFile(flagFile: File) {
        if (!flagFile.isFile) {
            Log.i(LOG_TAG, "flag missing path=${flagFile.absolutePath} keep=${BrightScreenMeetingMixPolicy.productionMixCombineMode}")
            return
        }
        val line =
            flagFile
                .readText()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: run {
                    Log.w(LOG_TAG, "empty flag ${flagFile.absolutePath}")
                    return
                }
        val mode =
            when (line.uppercase()) {
                "SUM", "LINEAR_SUM_SOFT_LIMIT", "LINEAR_SUM" ->
                    BrightScreenMeetingMixPolicy.MixCombineMode.LINEAR_SUM_SOFT_LIMIT
                "AVG", "AVERAGE", "EQUAL_WEIGHT_AVERAGE" ->
                    BrightScreenMeetingMixPolicy.MixCombineMode.EQUAL_WEIGHT_AVERAGE
                "ACTIVE", "SPEECH", "SPEECH_AVG", "ACTIVE_SPEECH_EQUAL_WEIGHT" ->
                    BrightScreenMeetingMixPolicy.MixCombineMode.ACTIVE_SPEECH_EQUAL_WEIGHT
                else -> {
                    Log.w(LOG_TAG, "unknown mode '$line' in ${flagFile.absolutePath}")
                    return
                }
            }
        BrightScreenMeetingMixPolicy.productionMixCombineMode = mode
        Log.i(LOG_TAG, "applied mode=$mode from ${flagFile.absolutePath}")
    }

    fun configureForFieldTest(mode: BrightScreenMeetingMixPolicy.MixCombineMode) {
        BrightScreenMeetingMixPolicy.productionMixCombineMode = mode
    }
}
