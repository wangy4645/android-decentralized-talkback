package com.talkback.core.webrtc

/**
 * A11-0: semantic placement of [org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback]
 * relative to the Android/WebRTC capture chain.
 *
 * Verified against `webrtc-instrumentation/webrtc-src-pin`:
 * - `WebRtcAudioRecord.AudioRecordThread`: `AudioRecord.read` → HW session effects already enabled
 * - same buffer → `nativeDataIsRecorded` → `AudioTransportImpl::ProcessCaptureFrame` (software APM)
 * - callback receives a copy of that same pre-APM buffer (after optional microphone mute zeroing)
 *
 * Do not label this POST_APM; software AGC/high-pass may still run only on the native path.
 */
enum class CapturePcmMeasurementPoint {
    /**
     * B-13: parallel AudioRecord on VOICE_COMMUNICATION with **no** Java AcousticEchoCanceler/NS.
     * Not the production TX path. HAL may still apply source-level processing.
     */
    PRE_JAVA_HW_EFFECT,

    /** Java ADM SamplesReadyCallback; alias APM_IN in Knife 2 logs. */
    POST_HW_PRE_WEBRTC_APM,

    /** Native `ProcessCaptureFrame` after `ProcessStream`; alias APM_OUT in Knife 2 logs. */
    POST_SOFTWARE_APM_PRE_ENCODER,
}
