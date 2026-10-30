package org.webrtc;

import com.talkback.core.webrtc.PostApmPcmDispatcher;

/**
 * Production POST_SOFTWARE_APM_PRE_ENCODER PCM delivery (native APM_OUT tap).
 * JNI may be absent until the production WebRTC AAR is rebuilt with delivery hooks.
 */
public final class TalkbackApmPcmProduction {
    private TalkbackApmPcmProduction() {}

    public static void setDeliveryEnabled(boolean enabled) {
        try {
            nativeSetDeliveryEnabled(enabled);
        } catch (UnsatisfiedLinkError ignored) {
            // Production AAR without delivery JNI: wiring remains; PCM arrives via harness only.
        }
    }

    @CalledByNative
    public static void onPcm16Frame(byte[] audioData, int sampleRateHz, int channels, int frames) {
        PostApmPcmDispatcher.onNativePcm16Frame(audioData, sampleRateHz, channels, frames);
    }

    private static native void nativeSetDeliveryEnabled(boolean enabled);
}
