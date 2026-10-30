#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "common_audio/vad/include/webrtc_vad.h"

JNIEXPORT jlong JNICALL
Java_com_talkback_core_conference_transport_TalkbackWebRtcVad_nativeCreate(JNIEnv* env,
                                                                           jclass clazz,
                                                                           jint mode) {
  VadInst* vad = WebRtcVad_Create();
  if (vad == NULL) {
    return 0;
  }
  if (WebRtcVad_Init(vad) != 0) {
    WebRtcVad_Free(vad);
    return 0;
  }
  if (WebRtcVad_set_mode(vad, mode) != 0) {
    WebRtcVad_Free(vad);
    return 0;
  }
  return (jlong)(intptr_t)vad;
}

JNIEXPORT void JNICALL
Java_com_talkback_core_conference_transport_TalkbackWebRtcVad_nativeFree(JNIEnv* env,
                                                                         jclass clazz,
                                                                         jlong handle) {
  if (handle == 0) {
    return;
  }
  WebRtcVad_Free((VadInst*)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_talkback_core_conference_transport_TalkbackWebRtcVad_nativeProcess(JNIEnv* env,
                                                                            jclass clazz,
                                                                            jlong handle,
                                                                            jshortArray pcm,
                                                                            jint sample_rate_hz) {
  if (handle == 0 || pcm == NULL) {
    return -1;
  }
  const jsize length = (*env)->GetArrayLength(env, pcm);
  if (length <= 0) {
    return -1;
  }
  jshort* samples = (*env)->GetShortArrayElements(env, pcm, NULL);
  if (samples == NULL) {
    return -1;
  }
  const int result =
      WebRtcVad_Process((VadInst*)(intptr_t)handle,
                        sample_rate_hz,
                        (const int16_t*)samples,
                        (size_t)length);
  (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
  return result;
}
