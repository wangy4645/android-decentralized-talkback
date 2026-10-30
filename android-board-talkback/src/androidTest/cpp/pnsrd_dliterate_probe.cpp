#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace {

#if defined(__aarch64__)

constexpr uint64_t kRelaVaddr[4] = {0xAE3048, 0xAE3050, 0xAE3058, 0xAE3060};
constexpr uint64_t kRelaAddend[4] = {0xAC21F4, 0xAC27E8, 0x41960C, 0x4543C4};
constexpr uint64_t kDtInitArrayFileVaddr = 0xAE3048;
constexpr size_t kDtInitArraySz = 32;
constexpr int kSlotCount = 4;
constexpr const char* kAbiLabel = "arm64-v8a_pnsrd_inst";

#else

// M01 armeabi-v7a path loads stock WebRTC (INIT_ARRAYSZ=4), not pnsrd-inst-1.
constexpr uint64_t kRelaVaddr[1] = {0x6425F8};
constexpr uint64_t kRelaAddend[1] = {0x0};
constexpr uint64_t kDtInitArrayFileVaddr = 0x6425F8;
constexpr size_t kDtInitArraySz = 4;
constexpr int kSlotCount = 1;
constexpr const char* kAbiLabel = "armeabi-v7a_stock_webrtc";

#endif

bool ModuleNameHintsJingle(const char* name) {
  if (name == nullptr || name[0] == '\0') {
    return false;
  }
  return strstr(name, "jingle_peerconnection") != nullptr ||
         strstr(name, "peerconnection_so") != nullptr ||
         strstr(name, "libjingle") != nullptr;
}

bool ModuleNameHintsCandidate(const char* name) {
  if (name == nullptr || name[0] == '\0') {
    return false;
  }
  return ModuleNameHintsJingle(name) || strstr(name, "base.apk") != nullptr ||
         strstr(name, "talkback.test") != nullptr;
}

uintptr_t InitArrayFileOffset(uintptr_t init_vaddr, uintptr_t dlpi_addr) {
  if (init_vaddr >= dlpi_addr && dlpi_addr != 0) {
    return init_vaddr - dlpi_addr;
  }
  return init_vaddr;
}

uintptr_t ResolveInitArrayRuntime(uintptr_t dlpi_addr, uintptr_t init_vaddr) {
  if (init_vaddr >= dlpi_addr && dlpi_addr != 0) {
    return init_vaddr;
  }
  return dlpi_addr + init_vaddr;
}

bool InitArrayFingerprint(uintptr_t init_vaddr, size_t init_sz, uintptr_t dlpi_addr) {
  if (init_sz != kDtInitArraySz) {
    return false;
  }
  return InitArrayFileOffset(init_vaddr, dlpi_addr) == kDtInitArrayFileVaddr;
}

struct ProbeState {
  bool found = false;
  char name[512] = {};
  uintptr_t dlpi_addr = 0;
  uintptr_t dt_init_array_vaddr = 0;
  size_t dt_init_arraysz = 0;
  uintptr_t init_array_runtime = 0;
  uint64_t slot[4] = {0, 0, 0, 0};
  uint64_t expected[4] = {0, 0, 0, 0};
  bool match[4] = {false, false, false, false};
  int modules_seen = 0;
  int iterate_rc = -1;
  char scan_log[4096] = {};
};

const ElfW(Dyn)* FindDynamicFromPhdr(const dl_phdr_info* info) {
  if (info == nullptr || info->dlpi_phdr == nullptr) {
    return nullptr;
  }
  for (int i = 0; i < info->dlpi_phnum; ++i) {
    const ElfW(Phdr)& ph = info->dlpi_phdr[i];
    if (ph.p_type == PT_DYNAMIC) {
      return reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr + ph.p_vaddr);
    }
  }
  return nullptr;
}

bool ReadDynamicTags(const ElfW(Dyn)* dyn, uintptr_t* init_vaddr, size_t* init_sz) {
  if (dyn == nullptr || init_vaddr == nullptr || init_sz == nullptr) {
    return false;
  }
  *init_vaddr = 0;
  *init_sz = 0;
  for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; ++d) {
    if (d->d_tag == DT_INIT_ARRAY) {
      *init_vaddr = static_cast<uintptr_t>(d->d_un.d_ptr);
    } else if (d->d_tag == DT_INIT_ARRAYSZ) {
      *init_sz = static_cast<size_t>(d->d_un.d_val);
    }
  }
  return *init_vaddr != 0 && *init_sz >= kDtInitArraySz;
}

void TryAppendScan(ProbeState* out, const char* line) {
  const size_t used = strlen(out->scan_log);
  const size_t line_len = strlen(line);
  if (used + line_len + 2 >= sizeof(out->scan_log)) {
    return;
  }
  strncat(out->scan_log, line, sizeof(out->scan_log) - used - 1);
  strncat(out->scan_log, "\n", sizeof(out->scan_log) - used - line_len - 1);
}

bool FillProbeResult(uintptr_t dlpi_addr, uintptr_t init_vaddr, size_t init_sz,
                     const char* label, ProbeState* out) {
  out->found = true;
  snprintf(out->name, sizeof(out->name), "%s", label ? label : "(null)");
  out->dlpi_addr = dlpi_addr;
  out->dt_init_array_vaddr = init_vaddr;
  out->dt_init_arraysz = init_sz;
  out->init_array_runtime = ResolveInitArrayRuntime(dlpi_addr, init_vaddr);

  auto* slots = reinterpret_cast<volatile uint64_t*>(out->init_array_runtime);
  for (int i = 0; i < kSlotCount; ++i) {
    out->slot[i] = slots[i];
    out->expected[i] = static_cast<uint64_t>(dlpi_addr) + kRelaAddend[i];
    out->match[i] = (out->slot[i] == out->expected[i]);
  }
  return true;
}

bool FillFromPhdrInfo(const dl_phdr_info* info, ProbeState* out, const char* label) {
  uintptr_t init_vaddr = 0;
  size_t init_sz = 0;
  if (!ReadDynamicTags(FindDynamicFromPhdr(info), &init_vaddr, &init_sz)) {
    return false;
  }
  return FillProbeResult(static_cast<uintptr_t>(info->dlpi_addr), init_vaddr, init_sz,
                         label, out);
}

int ProbeCallback(struct dl_phdr_info* info, size_t size, void* data) {
  (void)size;
  if (info == nullptr) {
    return 0;
  }
  auto* out = reinterpret_cast<ProbeState*>(data);
  ++out->modules_seen;

  const char* name = info->dlpi_name;
  const ElfW(Dyn)* dyn = FindDynamicFromPhdr(info);
  uintptr_t init_vaddr = 0;
  size_t init_sz = 0;
  if (dyn != nullptr) {
    ReadDynamicTags(dyn, &init_vaddr, &init_sz);
  }

  const uintptr_t dlpi_addr = static_cast<uintptr_t>(info->dlpi_addr);
  const bool name_hit = ModuleNameHintsCandidate(name);
  const bool jingle_name = ModuleNameHintsJingle(name);
  const bool fingerprint_hit = InitArrayFingerprint(init_vaddr, init_sz, dlpi_addr);

  if (name_hit || fingerprint_hit) {
    char brief[384];
    snprintf(brief, sizeof(brief),
             "mod[%d] name=%s init=0x%llx file_off=0x%llx sz=%zu addr=0x%llx fp=%s",
             out->modules_seen, name ? name : "(empty)",
             static_cast<unsigned long long>(init_vaddr),
             static_cast<unsigned long long>(InitArrayFileOffset(init_vaddr, dlpi_addr)),
             init_sz, static_cast<unsigned long long>(dlpi_addr),
             fingerprint_hit ? "yes" : "no");
    TryAppendScan(out, brief);
  }

  if (jingle_name || fingerprint_hit) {
    const char* label = (name != nullptr && name[0] != '\0') ? name : "(empty_dlpi_name)";
    if (FillFromPhdrInfo(info, out, label)) {
      return 1;
    }
  }
  return 0;
}

void AppendLine(char* buf, size_t cap, size_t* used, const char* line) {
  if (*used >= cap) {
    return;
  }
  const int n = snprintf(buf + *used, cap - *used, "%s\n", line);
  if (n > 0) {
    *used += static_cast<size_t>(n);
  }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_talkback_lab_pnsrd_PnsrdDlIterateProbe_nativeRunInitArrayProbe(JNIEnv* env,
                                                                        jclass /*clazz*/) {
  ProbeState st{};
  st.iterate_rc = dl_iterate_phdr(ProbeCallback, &st);

  char report[8192];
  char line[512];
  size_t used = 0;
  report[0] = '\0';

  snprintf(line, sizeof(line), "probe_abi=%s", kAbiLabel);
  AppendLine(report, sizeof(report), &used, line);
  snprintf(line, sizeof(line), "dl_iterate_phdr_rc=%d", st.iterate_rc);
  AppendLine(report, sizeof(report), &used, line);

  if (!st.found) {
    AppendLine(report, sizeof(report), &used, "found=false");
    AppendLine(report, sizeof(report), &used,
               "error=libjingle not matched by dl_iterate_phdr name or init_array fingerprint");
    snprintf(line, sizeof(line), "modules_seen=%d", st.modules_seen);
    AppendLine(report, sizeof(report), &used, line);
#if !defined(__aarch64__)
    AppendLine(report, sizeof(report), &used,
               "gate=SKIP_pnsrd_inst_slot3_requires_arm64_inst_jingle");
#endif
    if (st.scan_log[0] != '\0') {
      AppendLine(report, sizeof(report), &used, "scan_log:");
      AppendLine(report, sizeof(report), &used, st.scan_log);
    }
    return env->NewStringUTF(report);
  }

  snprintf(line, sizeof(line), "found=true method=dl_iterate_phdr dlpi_name=%s", st.name);
  AppendLine(report, sizeof(report), &used, line);
  snprintf(line, sizeof(line), "dlpi_addr=0x%llx", static_cast<unsigned long long>(st.dlpi_addr));
  AppendLine(report, sizeof(report), &used, line);
  snprintf(line, sizeof(line), "DT_INIT_ARRAY=0x%llx DT_INIT_ARRAYSZ=%zu",
           static_cast<unsigned long long>(st.dt_init_array_vaddr), st.dt_init_arraysz);
  AppendLine(report, sizeof(report), &used, line);
  snprintf(line, sizeof(line), "DT_INIT_ARRAY_file_off=0x%llx",
           static_cast<unsigned long long>(
               InitArrayFileOffset(st.dt_init_array_vaddr, st.dlpi_addr)));
  AppendLine(report, sizeof(report), &used, line);
  snprintf(line, sizeof(line), "init_array_runtime=0x%llx",
           static_cast<unsigned long long>(st.init_array_runtime));
  AppendLine(report, sizeof(report), &used, line);

  int match_count = 0;
  for (int i = 0; i < kSlotCount; ++i) {
    snprintf(
        line, sizeof(line),
        "slot[%d] vaddr=0x%llx actual=0x%llx expected=dlpi_addr+0x%llx=0x%llx match=%s",
        i, static_cast<unsigned long long>(kRelaVaddr[i]),
        static_cast<unsigned long long>(st.slot[i]),
        static_cast<unsigned long long>(kRelaAddend[i]),
        static_cast<unsigned long long>(st.expected[i]), st.match[i] ? "true" : "false");
    AppendLine(report, sizeof(report), &used, line);
    if (st.match[i]) {
      ++match_count;
    }
  }

  snprintf(line, sizeof(line), "match_count=%d/%d", match_count, kSlotCount);
  AppendLine(report, sizeof(report), &used, line);

#if defined(__aarch64__)
  snprintf(line, sizeof(line), "slot3_instrumentation_ctor match=%s",
           st.match[3] ? "PASS" : "FAIL");
  AppendLine(report, sizeof(report), &used, line);

  if (st.match[3]) {
    AppendLine(report, sizeof(report), &used,
               "verdict=INIT_ARRAY_RELOC_OK_slot3 linker_relocation_branch=CLOSE");
    AppendLine(report, sizeof(report), &used,
               "next=constructor_execution_vs_PNSRD_INST_logging");
  } else if (st.slot[3] == 0 && st.slot[0] == 0) {
    AppendLine(report, sizeof(report), &used,
               "verdict=INIT_ARRAY_SLOTS_ZERO_at_loader_view");
    AppendLine(report, sizeof(report), &used, "next=loader_relocation_investigation");
  } else {
    AppendLine(report, sizeof(report), &used, "verdict=INIT_ARRAY_RELOC_MISMATCH");
    AppendLine(report, sizeof(report), &used, "next=loader_relocation_investigation");
  }
#else
  AppendLine(report, sizeof(report), &used,
             "verdict=NON_INST_ARM32_JINGLE gate=NOT_AUTHORITATIVE_for_pnsrd_inst_slot3");
#endif

  return env->NewStringUTF(report);
}

extern "C" JNIEXPORT void JNICALL
Java_com_talkback_lab_pnsrd_PnsrdDlIterateProbe_nativeTestLog(JNIEnv* /*env*/, jclass /*clazz*/) {
  __android_log_print(ANDROID_LOG_INFO, "PNSRD_INST", "NATIVE_SINK_SMOKE");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_talkback_lab_pnsrd_PnsrdDlIterateProbe_nativeQueryInstCtorRan(JNIEnv* env,
                                                                     jclass /*clazz*/) {
  char report[1024];
  char line[256];
  size_t used = 0;
  report[0] = '\0';

  dlerror();
  void* handle = dlopen("libjingle_peerconnection_so.so", RTLD_NOW | RTLD_NOLOAD);
  if (handle == nullptr) {
    handle = dlopen("libjingle_peerconnection_so.so", RTLD_NOW);
  }
  if (handle == nullptr) {
    const char* err = dlerror();
    AppendLine(report, sizeof(report), &used, "jingle_loaded=false");
    snprintf(line, sizeof(line), "dlopen_error=%s", err ? err : "unknown");
    AppendLine(report, sizeof(report), &used, line);
    return env->NewStringUTF(report);
  }
  AppendLine(report, sizeof(report), &used, "jingle_loaded=true");

  dlerror();
  using CtorRanFn = int (*)();
  auto* fn = reinterpret_cast<CtorRanFn>(dlsym(handle, "PnsrdInstIsCtorRan"));
  const char* sym_err = dlerror();
  if (fn == nullptr) {
    AppendLine(report, sizeof(report), &used, "PnsrdInstIsCtorRan=dlsym_FAIL");
    snprintf(line, sizeof(line), "dlsym_error=%s", sym_err ? sym_err : "unknown");
    AppendLine(report, sizeof(report), &used, line);
    AppendLine(report, sizeof(report), &used, "ctor_ran_flag=UNAVAILABLE");
    return env->NewStringUTF(report);
  }

  const int ctor_ran = fn();
  snprintf(line, sizeof(line), "PnsrdInstIsCtorRan=%d", ctor_ran);
  AppendLine(report, sizeof(report), &used, line);
  snprintf(line, sizeof(line), "ctor_ran_flag=%s", ctor_ran != 0 ? "1" : "0");
  AppendLine(report, sizeof(report), &used, line);
  AppendLine(report, sizeof(report), &used,
             ctor_ran != 0 ? "ctor_execution=PASS" : "ctor_execution=FAIL");
  AppendLine(report, sizeof(report), &used,
             "log_marker=PNSRD_CTOR_RAN (check logcat independently)");
  return env->NewStringUTF(report);
}
