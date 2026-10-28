# ADR-0057 F8 — Post-Observer Native Consumption Audit

**Status:** AUTHORIZED · TRACE-ONLY · NO BEHAVIOR FIX  
**Parent:** `logs/adr0057-track-c-case-a-20260902-115542/F8_PROPAGATION_AUDIT.txt`  
**Mechanism:** pnsrd-inst-1 fork + `f8_native_trace` (Talkback log tag)

---

## 1. F8 posture (frozen — F8b root seam CONFIRMED)

```text
F8:
  CONTRACT RETAINED
  A2 Java propagation: FIELD PROVEN / CLOSED
  Native NetworkMonitor / map / enum / bind: FIELD PROVEN / CLOSED
  Candidate filter: BEHAVIOR CORRECT
  FIELD OBJECTIVE: FAIL / OPEN

F8b:
  ROOT SEAM CONFIRMED

Residual:
  UDPPort local-address materialization
  (bound ANY → MaybeSetDefaultLocalAddress → concrete wlan0 IP)

F9:
  NOT OPEN
```

**Authoritative F8b emission run:** `logs/adr0057-track-c-case-a-20260902-140751/`  
**Next:** [ANY-address materialization audit](./adr0057-f8-any-address-materialization-audit.md)

**F8a Java wiring gap:** REJECTED  
**Candidate filter audit:** STOPPED (`any_ip` reject = consequence)

**STOP (unchanged):** F8 contract rewrite · A1 fallback · synthetic candidate ·
candidate rewrite · retry/timeout · STUN/TURN · GPLB/GIDR changes ·
registry/bridge/detector/observer edits.

---

## 2. Audit questions (four)

| # | Question | Primary events |
|---|----------|----------------|
| 1 | `nativeNotifyOfNetworkConnect` reached? | `F8_NATIVE_NETWORK_CONNECT stage=jni_notify` |
| 2 | `AndroidNetworkMonitor` received correct `NetworkInformation`? | `stage=jni_notify` + `stage=native_connected` field parity |
| 3 | `network_info_by_handle_` / `network_handle_by_address_` built? | `F8_NATIVE_NETWORK_MAP handleFound=true` |
| 4 | Why does `BasicNetworkManager` still expose loopback only? | `F8_NATIVE_NETWORK_ENUM` + `F8_NATIVE_NETWORKS_UPDATE` + `F8_NATIVE_BIND_SOCKET` |

---

## 3. Native chain (pinned WebRTC @ b831eb81)

```text
NetworkMonitor.onNetworkConnect (Java)
  → nativeNotifyOfNetworkConnect (JNI)
  → AndroidNetworkMonitor::NotifyOfNetworkConnect
  → OnNetworkConnected_n (network thread)
  → network_info_by_handle_ / network_handle_by_address_
  → InvokeNetworksChangedCallback
  → BasicNetworkManager::OnNetworksChanged
  → UpdateNetworksOnce → CreateNetworks / ConvertIfAddrs
  → PortAllocator / UDPPort → host candidate
```

**Instrumentation seams (trace-only):**

| Event | Source file | Stage |
|-------|-------------|-------|
| `F8_NATIVE_NETWORK_CONNECT` | `android_network_monitor.cc` | `jni_notify`, `native_connected` |
| `F8_NATIVE_NETWORK_MAP` | `android_network_monitor.cc` | after map insert |
| `F8_NATIVE_BIND_SOCKET` | `android_network_monitor.cc` | `BindSocketToNetwork` |
| `F8_NATIVE_NETWORK_ENUM` | `network.cc` | `ConvertIfAddrs` per interface |
| `F8_NATIVE_NETWORKS_UPDATE` | `network.cc` | `UpdateNetworksOnce` post-merge |

Log tag: **`Talkback`** (same collector as Java F8 diagnostics).  
Grep: `F8_NATIVE_` | correlate with `F8_OBSERVER_NETWORK_CONNECT` | `ICE_TRANSPORT_` | `GENERATED`.

---

## 4. Decision tree

```text
Java F8_OBSERVER_NETWORK_CONNECT correct
→ no F8_NATIVE_NETWORK_CONNECT stage=jni_notify
    = JNI / observer bridge defect (unlikely after propagation audit)

jni_notify present
→ no stage=native_connected OR field mismatch
    = JNI marshaling / network-thread post defect

native_connected + map handleFound=true
→ F8_NATIVE_NETWORK_ENUM wlan0 ignored=true
    = native enumeration / filter defect (GetInterfaceInfo / IsIgnoredNetwork)

enum includes wlan0 (ignored=false)
→ F8_NATIVE_NETWORKS_UPDATE summary lacks wlan0:active
    = BasicNetworkManager merge / availability defect

networks update includes wlan0:active
→ F8_NATIVE_BIND_SOCKET ADDRESS_NOT_FOUND or setsocknetworkResult≠0
    = android_setsocknetwork / handle binding defect

enum + bind correct
→ still GENERATED loopback only
    = port allocator / candidate gathering residual (downstream of network manager)
```

---

## 5. Build / field run

```text
1. Rebuild instrumentation AAR (WSL):
     webrtc-instrumentation/pnsrd-inst-1/scripts/run-phase-a-wsl.sh
   (apply_pnsrd_inst_1.py now applies F8 native trace after pnsrd-inst-1 core)

2. Verify AAR strings:
     grep F8_NATIVE f8_native_trace.cc in .so optional;
     runtime: F8_NATIVE_NETWORK_CONNECT in logcat

3. Install debug APK on M01/M02/M03

4. Case A collectors (F8_NATIVE_ in observe=):
     scripts/adr0057-track-c-case-a-start-run.ps1
     → M01→M02→M03 Talk page idle 5min
     scripts/adr0057-track-c-case-a-stop-run.ps1

5. Per device grep:
     Select-String F8_NATIVE_ M0x-talkback.log
     Select-String F8_OBSERVER_ M0x-talkback.log
     Select-String GENERATED M0x-talkback.log
```

---

## 6. Artifacts

| Path | Role |
|------|------|
| `webrtc-instrumentation/pnsrd-inst-1/rtc_base/f8_native_trace.{h,cc}` | trace sink |
| `webrtc-instrumentation/pnsrd-inst-1/scripts/apply_pnsrd_inst_1.py` | apply + `f8-native-trace-applied.patch` |
| `webrtc-instrumentation/webrtc-src-pin/sdk/android/src/jni/android_network_monitor.cc` | pinned reference |
| `webrtc-instrumentation/webrtc-src-pin/rtc_base/network.cc` | pinned reference |
