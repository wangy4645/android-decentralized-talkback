# ADR-0057 F8b — Host Candidate Emission Gap (trace-only audit)

**Status:** FIELD COMPLETE · ROOT SEAM CONFIRMED · FILTER AUDIT STOPPED  
**Parent:** `logs/adr0057-track-c-case-a-20260902-125547/F8_NATIVE_CONSUMPTION_ADJUDICATION.txt`  
**Authoritative F8b run:** `logs/adr0057-track-c-case-a-20260902-140751/`  
**Next:** [ANY-address materialization](./adr0057-f8-any-address-materialization-audit.md)

> Informal label: **F8b candidate — Host Candidate Emission Gap** → narrowed to  
> **UDPPort ANY-address materialization**.

---

## 1. Frozen F8 posture (post native + emission audits)

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
  specifically:
    bound ANY address
    → MaybeSetDefaultLocalAddress / equivalent
    → concrete wlan0 local address

Observed failure:
  0.0.0.0:<port> remains ANY
  → candidate created as ANY
  → correctly rejected by candidate filter

F9:
  NOT OPEN
```

**STOP (unchanged):** F8 contract rewrite · A1 · registry/bridge/detector/observer · socket binding changes · synthetic candidate · candidate rewrite · retry/timeout · STUN/TURN · behavior fix (not yet authorized).

**Rejected hypothesis (field-closed):** WebRTC does not recognize MANET adapter / NetworkMonitor bridge / `android_setsocknetwork` bind path / candidate filter defect.

**Proven chain (M02/M03 arm64 @ 140751):**

```text
wlan0 visible → address map correct → socket bind 192.168.10.23/.24 SUCCESS
→ PORT_CREATED wlan0 (networkIP=192.168.10.x)
→ PORT_PREPARE / CANDIDATE_CREATED address=0.0.0.0:<port>
→ CANDIDATE_FILTER rejected reason=any_ip  (CORRECT)
→ GENERATED loopback only
```

**Key inequality:**

```text
android_setsocknetwork SUCCESS != socket local address becomes wlan0 IP
```

**Strong control variable (not root-cause named):** M01 = armeabi-v7a (LAN host OK) · M02/M03 = arm64-v8a (bind OK, gather FAIL).

---

## 2. Audit question (single)

Where does `192.168.10.x` disappear between a **successfully bound UDP socket on wlan0** and a **host candidate surfaced toward PeerConnection observer / Java**?

```text
successful bound UDP socket
  → Port / UDPPort
  → PrepareAddress / OnLocalAddressReady
  → Port::FinishAddingAddress / SignalCandidateReady
  → BasicPortAllocatorSession::OnCandidateReady
  → SanitizeCandidate / CheckCandidateFilter
  → SignalCandidatesReady
  → (JNI / Java GENERATED)
```

---

## 3. Instrumentation events

Log tag: **`Talkback`**. Grep: `F8_PORT_` | `F8_CANDIDATE_` | correlate with `F8_NATIVE_BIND_SOCKET` | `GENERATED`.

| Event | Source | Fields |
|-------|--------|--------|
| `F8_PORT_CREATED` | `UDPPort::Init`, `AddAllocatedPort` | networkName, networkIP, localAddress, port, portKind |
| `F8_PORT_PREPARE` | `UDPPort::PrepareAddress`, `OnLocalAddressReady` | boundAddress, advertisedAddress, socketBound |
| `F8_CANDIDATE_CREATED` | `Port::FinishAddingAddress` | address, type, protocol, relatedAddress, networkName |
| `F8_CANDIDATE_FILTER` | `IsAllowedByCandidateFilter`, `OnCandidateReady`, `SanitizeCandidate` | address, verdict, reason, filterFlags |
| `F8_CANDIDATE_PRUNE` | `OnCandidateReady` (done gathering), turn prune | candidate, reason |
| `F8_CANDIDATE_SURFACED` | `OnCandidateReady` → `SignalCandidatesReady` | address, type, protocol, networkName, stage |

**Filter / prune reasons to watch:**

- `any_ip` · `host_filtered` · `port_not_ready` · `filter_mismatch`
- `mdns_hostname` · `related_address_stripped` · `sanitize_transform`
- `port_done_gathering` · `turn_keep_first_ready`

**WebRTC mechanisms this audit is designed to expose (read-only):**

- adapter/network cost pruning
- host candidate filtering / sanitization
- mDNS host candidate handling
- `IPIsPrivate` / local-network privacy paths
- port allocator flags (`PORTALLOCATOR_*`)
- candidate dedup
- network preference / cost
- IPv4/IPv6 family filtering

---

## 4. Decision tree

```text
F8_NATIVE_BIND_SOCKET wlan0 SUCCESS
→ no F8_PORT_CREATED networkName=wlan0
    = allocation sequence never opened UDPPort on wlan0 network object

F8_PORT_CREATED wlan0
→ F8_PORT_PREPARE boundAddress=192.168.10.x but no F8_CANDIDATE_CREATED same address
    = PrepareAddress / OnLocalAddressReady / AddAddress gap

F8_CANDIDATE_CREATED 192.168.10.x
→ F8_CANDIDATE_FILTER rejected (host_filtered | any_ip | …)
    = candidate filter / privacy / allocator flags

F8_CANDIDATE_CREATED 192.168.10.x
→ F8_CANDIDATE_FILTER transformed (mdns_hostname | …)
    = sanitization / mDNS obfuscation

F8_CANDIDATE_CREATED 192.168.10.x
→ no F8_CANDIDATE_SURFACED (port_not_ready | filter_mismatch)
    = BasicPortAllocatorSession readiness / filter gate

F8_CANDIDATE_SURFACED 192.168.10.x
→ GENERATED still loopback only
    = downstream of SignalCandidatesReady (JNI / Java observer) — out of F8b native scope
```

---

## 5. Parallel static diff (v7a vs arm64)

**Goal:** determine whether shipped **armeabi-v7a** vs **arm64-v8a** native builds differ in field trials, mDNS, candidate privacy, or allocator defaults — without naming ABI as root cause.

```powershell
scripts/adr0057-f8-v7a-arm64-static-diff.ps1
```

Compares strings/symbols from:

- Maven stock AAR `armeabi-v7a/libjingle_peerconnection_so.so` (M01 path)
- Local instrumentation AAR `arm64-v8a/libjingle_peerconnection_so.so` (M02/M03 path)

---

## 6. Build / field run

```text
1. Rebuild instrumentation AAR (WSL):
     webrtc-instrumentation/pnsrd-inst-1/scripts/run-phase-a-wsl.sh
   (apply_pnsrd_inst_1.py applies F8 native + F8 candidate trace)

2. Rebuild APK (extractPnsrdInstJni overlays arm64 .so)

3. Case A collectors (observe includes F8_PORT_ / F8_CANDIDATE_):
     scripts/adr0057-track-c-case-a-start-run.ps1
     → idle 5min on Talk page
     scripts/adr0057-track-c-case-a-stop-run.ps1

4. Adjudicate:
     scripts/adr0057-f8-candidate-adjudicate.ps1 -LogDir <dir>
```

---

## 7. Artifacts

| Path | Role |
|------|------|
| `webrtc-instrumentation/pnsrd-inst-1/rtc_base/f8_native_trace.{h,cc}` | trace sink (native + port/candidate) |
| `webrtc-instrumentation/pnsrd-inst-1/scripts/apply_pnsrd_inst_1.py` | apply patches |
| `scripts/adr0057-f8-candidate-adjudicate.ps1` | per-device loss-point matrix |
| `scripts/adr0057-f8-v7a-arm64-static-diff.ps1` | ABI static string diff |
