cmake_minimum_required(VERSION 3.22.1)

if(BUILD_HOST_JNI)
  project(talkback_webrtc_vad_host C)
else()
  project(talkback_board_talkback_native C CXX)
endif()

set(WEBRTC_PIN "${CMAKE_CURRENT_SOURCE_DIR}/../../../../webrtc-instrumentation/webrtc-src-pin")

include_directories(
  ${WEBRTC_PIN}
  ${CMAKE_CURRENT_SOURCE_DIR}/stubs
)

file(GLOB VAD_C_SOURCES
  ${WEBRTC_PIN}/common_audio/vad/webrtc_vad.c
  ${WEBRTC_PIN}/common_audio/vad/vad_core.c
  ${WEBRTC_PIN}/common_audio/vad/vad_filterbank.c
  ${WEBRTC_PIN}/common_audio/vad/vad_gmm.c
  ${WEBRTC_PIN}/common_audio/vad/vad_sp.c
)

file(GLOB SPL_C_SOURCES ${WEBRTC_PIN}/common_audio/signal_processing/*.c)
list(FILTER SPL_C_SOURCES EXCLUDE REGEX ".*_mips\\.c$")
list(FILTER SPL_C_SOURCES EXCLUDE REGEX ".*_neon\\.c$")

add_library(
  talkback_webrtc_vad
  SHARED
  talkback_webrtc_vad_jni.c
  stubs/rtc_base/checks_stub.c
  ${VAD_C_SOURCES}
  ${SPL_C_SOURCES}
)

if(BUILD_HOST_JNI)
  find_package(JNI REQUIRED)
  target_include_directories(talkback_webrtc_vad PRIVATE ${JNI_INCLUDE_DIRS})
  if(WIN32)
    set_target_properties(talkback_webrtc_vad PROPERTIES OUTPUT_NAME "talkback_webrtc_vad")
  endif()
else()
  find_library(log-lib log)
  target_link_libraries(talkback_webrtc_vad ${log-lib})

  add_library(pnsrd_dliterate_probe SHARED ../../androidTest/cpp/pnsrd_dliterate_probe.cpp)
  add_library(gres3_sendmmsg_probe SHARED ../../androidTest/cpp/gres3_sendmmsg_probe.cpp)
  target_link_libraries(pnsrd_dliterate_probe ${log-lib} dl)
  target_link_libraries(gres3_sendmmsg_probe ${log-lib})
  target_compile_features(pnsrd_dliterate_probe PRIVATE cxx_std_17)
  target_compile_features(gres3_sendmmsg_probe PRIVATE cxx_std_17)
endif()
