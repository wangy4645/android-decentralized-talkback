package org.webrtc;

/**
 * Multicast playout PCM → shared WebRTC {@code AudioProcessing} reverse stream (APM seam ⑥).
 * Native lives in libjingle_peerconnection_so.so (talkback WebRTC AAR).
 */
public final class TalkbackApmReverseFeed {
    private TalkbackApmReverseFeed() {}

    public static void setEnabled(boolean enabled) {
        try {
            nativeSetEnabled(enabled);
        } catch (UnsatisfiedLinkError ignored) {
            // Production AAR without reverse JNI: no-op; capture AEC unchanged.
        }
    }

    /**
     * @return true when native accepted the buffer (false if disabled, no APM, or JNI absent).
     */
    public static boolean injectPcm16(short[] pcm, int sampleRateHz, int channels) {
        try {
            return nativeInjectPcm16(pcm, sampleRateHz, channels);
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    private static native void nativeSetEnabled(boolean enabled);

    private static native boolean nativeInjectPcm16(short[] pcm, int sampleRateHz, int channels);
}
