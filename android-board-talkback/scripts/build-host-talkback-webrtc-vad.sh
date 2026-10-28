#!/usr/bin/env bash
set -eu

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODULE="$(cd "$(dirname "$0")/.." && pwd)"
WEBRTC_PIN="$ROOT/webrtc-instrumentation/webrtc-src-pin"
CPP="$MODULE/src/main/cpp"
OUT="$MODULE/build/talkback-webrtc-vad-host"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

mkdir -p "$OUT"

mapfile -t SPL_SOURCES < <(
  find "$WEBRTC_PIN/common_audio/signal_processing" -maxdepth 1 -name '*.c' \
    ! -name '*_mips.c' ! -name '*_neon.c'
)

COMMON_FLAGS=(
  -O2
  -I"$CPP/stubs"
  -I"$WEBRTC_PIN"
)

VAD_SOURCES=(
  "$WEBRTC_PIN/common_audio/vad/webrtc_vad.c"
  "$WEBRTC_PIN/common_audio/vad/vad_core.c"
  "$WEBRTC_PIN/common_audio/vad/vad_filterbank.c"
  "$WEBRTC_PIN/common_audio/vad/vad_gmm.c"
  "$WEBRTC_PIN/common_audio/vad/vad_sp.c"
)

gcc -shared -fPIC "${COMMON_FLAGS[@]}" \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  "$CPP/talkback_webrtc_vad_jni.c" \
  "${VAD_SOURCES[@]}" \
  "${SPL_SOURCES[@]}" \
  -o "$OUT/libtalkback_webrtc_vad.so"

gcc "${COMMON_FLAGS[@]}" \
  "$CPP/talkback_vad_cli.c" \
  "${VAD_SOURCES[@]}" \
  "${SPL_SOURCES[@]}" \
  -o "$OUT/talkback_vad_cli"

echo "Built $OUT/libtalkback_webrtc_vad.so"
echo "Built $OUT/talkback_vad_cli"
