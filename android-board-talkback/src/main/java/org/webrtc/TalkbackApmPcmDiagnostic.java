package org.webrtc;

/** Knife 2 JNI stub; native implementation lives in libjingle_peerconnection_so.so. */
public final class TalkbackApmPcmDiagnostic {
    private TalkbackApmPcmDiagnostic() {}

    public static void setEnabled(boolean enabled) {
        nativeSetEnabled(enabled);
    }

    private static native void nativeSetEnabled(boolean enabled);
}
