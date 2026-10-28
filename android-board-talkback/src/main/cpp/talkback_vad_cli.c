#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "common_audio/vad/include/webrtc_vad.h"

int main(int argc, char** argv) {
  int mode = 2;
  int sample_rate_hz = 48000;
  size_t frame_samples = 960;
  if (argc >= 2) {
    mode = atoi(argv[1]);
  }
  if (argc >= 3) {
    sample_rate_hz = atoi(argv[2]);
  }
  if (argc >= 4) {
    frame_samples = (size_t)atoi(argv[3]);
  }

  int16_t* pcm = (int16_t*)malloc(sizeof(int16_t) * frame_samples);
  if (pcm == NULL) {
    return 2;
  }
  const size_t read_count =
      fread(pcm, sizeof(int16_t), frame_samples, stdin);
  if (read_count != frame_samples) {
    free(pcm);
    return 3;
  }

  VadInst* vad = WebRtcVad_Create();
  if (vad == NULL) {
    free(pcm);
    return 4;
  }
  if (WebRtcVad_Init(vad) != 0 || WebRtcVad_set_mode(vad, mode) != 0) {
    WebRtcVad_Free(vad);
    free(pcm);
    return 5;
  }

  const int decision =
      WebRtcVad_Process(vad, sample_rate_hz, pcm, frame_samples);
  WebRtcVad_Free(vad);
  free(pcm);

  if (decision < 0) {
    return 6;
  }
  printf("%d\n", decision > 0 ? 1 : 0);
  return 0;
}
