# ADR-0057 F8 — Talkback-Selected Network Bridge (Option A2)

**Status:** CONTRACT RETAINED · Option A2 IMPLEMENTED · Java propagation FIELD PROVEN CLOSED · Native consumption audit AUTHORIZED  
**Mechanism:** Option A2 — Talkback-pinned `NetworkChangeDetector` (Talkback-Selected Network Bridge)  
**Scope:** GROUP WebRTC transport execution plane only  
**Related RCA:** `logs/adr0057-track-c-case-a-20260902-104114/TRANSPORT_RCA_GM6.txt`

---

## 1. Problem statement (frozen)

```text
Plane A/B (Talkback operational stack)  sees MANET wlan + 192.168.10.x
Plane C (WebRTC NetworkMonitor)         host gather loopback-only on M02/M03

Root integration seam (localized):
  Talkback-selected operational Android Network is NOT represented to WebRTC
  as complete NetworkChangeDetector.NetworkInformation before ICE execution.
```

**Not in scope:** GPLB, GIE, IGIE, GIDR, generation, ICE retry/timeout, STUN/TURN,
application routing, transmitReady semantics, synthetic IceCandidate injection.

---

## 2. Mechanism selection (frozen)

| Option | Verdict |
|--------|---------|
| **A2 Talkback-Selected Network Bridge** | **SELECTED** — authoritative transport network context from Talkback |
| A1 default detector + merge | HOLD — fallback only if A2 field validation fails |
| A3 full delegated wrapper | NOT NEEDED for v1 |
| B native/AAR fork | REJECT |
| C fieldTrials / delay PC / CHANGE_NETWORK_STATE alone | NOT PRIMARY |
| Synthetic IceCandidate | **FORBIDDEN** |

**Semantic framing (not “补 LAN candidate”):**

```text
Talkback-selected operational Network N
  → authoritative network context for GROUP WebRTC transport execution
  → WebRTC-native host candidate gathering on N
```

`authoritative` applies only to **transport network selection**, not Membership/Generation authority.

---

## 3. Wiring model

### 3.1 Target architecture

```text
ConnectivityManager.NetworkCallback
        │
        ▼
SelectedOperationalNetworkRegistry          ← NEW shared fact (selection authority)
  • current Network? + stable networkId
  • connect / disconnect / replace / link-properties-changed
        │
        ├──────────────────────────┬────────────────────────────
        ▼                          ▼
AndroidSignalingSocketBinder   TalkbackWebRtcNetworkBridge
  bindSocket(DatagramSocket)     NetworkChangeDetector (A2)
        │                          │
   Plane B: signaling UDP         │  getActiveNetworkList()
                                 │  observer.onNetworkConnect(info)
                                 │  observer.onNetworkDisconnect(handle)
                                 ▼
                          NetworkMonitor (org.webrtc)
                                 │
                                 ▼ JNI
                          AndroidNetworkMonitor (native)
                                 │
                    ├─ network_info_by_handle_
                    ├─ network_handle_by_address_
                    ├─ BasicNetworkManager → host gather
                    └─ BindSocketToNetwork(handle, fd)
                                 │
                                 ▼
                          WebRTC-native ICE host candidates
```

### 3.2 Dependency rule (frozen)

```text
ALLOWED:  SelectedOperationalNetworkRegistry → { SignalingBinder, WebRtcBridge }
FORBIDDEN: WebRtcBridge → AndroidSignalingSocketBinder (binder is executor, not authority)
```

`NetworkCapabilityObserver` remains the **selection observer**; it publishes into
`SelectedOperationalNetworkRegistry` instead of pushing only into `socketBinder`.

### 3.3 Injection point (frozen)

| Step | Location | Requirement |
|------|----------|-------------|
| 1 | `WebRtcSharedFactory.acquire()` | `NetworkMonitor.getInstance().setNetworkChangeDetectorFactory(...)` |
| 2 | same | **Before** `PeerConnectionFactory.initialize()` |
| 3 | first `createPeerConnection()` | triggers native `startMonitoring` — factory must already be set |

Reference: `NetworkMonitor.setNetworkChangeDetectorFactory` asserts `numObservers == 0`.

### 3.4 Lifecycle events (F8-T4 minimum)

Bridge MUST propagate:

| Event | Source (Android) | Bridge action |
|-------|------------------|---------------|
| N available | `onAvailable(N)` | `onNetworkConnect(info(N))` |
| N link/IP change | `onLinkPropertiesChanged(N, …)` | refresh `onNetworkConnect(info(N))` (same handle) |
| N capabilities change | `onCapabilitiesChanged(N, …)` | refresh if type/VPN semantics change |
| N lost | `onLost(N)` | `onNetworkDisconnect(handle(N))` |
| N1 → N2 replace | `onLost(N1)` then `onAvailable(N2)` | disconnect(N1) then connect(N2) |

**Not sufficient:** one-shot `getActiveNetworkList() = [N]` without ongoing updates.

### 3.5 Current code gaps (pre-implementation)

`NetworkCapabilityObserver` today (`NetworkCapabilityObserver.kt`):

- Pushes directly to `AndroidSignalingSocketBinder` — no shared registry
- Implements `onAvailable` / `onLost` only — **no** `onLinkPropertiesChanged`
- `onLost` clears binder unconditionally — no per-network handle tracking for N1→N2

F8 implementation MUST extend selection publishing to satisfy §3.4 before bridge can be correct.

---

## 4. `NetworkInformation` field mapping

Built from Talkback-selected `android.net.Network N` using semantics **compatible with**
shipped WebRTC `NetworkMonitorAutoDetect.ConnectivityManagerDelegate.networkToInfo()`.

Reference: `webrtc-src-pin/sdk/android/api/org/webrtc/NetworkMonitorAutoDetect.java` L391–432.

### 4.1 Field table

| `NetworkInformation` field | Java constructor arg | Source | Native use |
|--------------------------|------------------------|--------|--------------|
| `name` | `String name` | `LinkProperties.getInterfaceName()` | `interface_name` → `network_handle_by_if_name_`, host gather ifname |
| `type` | `ConnectionType type` | `getConnectionType(getNetworkState(N))` | `adapter_type` in BasicNetworkManager |
| `underlyingTypeForVpn` | `ConnectionType underlyingTypeForVpn` | `getUnderlyingConnectionTypeForVpn(networkState)` | VPN underlying adapter |
| `handle` | `long handle` | `network.getNetworkHandle()` (API 23+) or `Integer.parseInt(network.toString())` | `network_info_by_handle_`, `android_setsocknetwork(handle, fd)` |
| `ipAddresses` | `IPAddress[]` | all `LinkAddress` in `LinkProperties.getLinkAddresses()` → `new IPAddress(addr.getAddress())` | `network_handle_by_address_`, bind lookup |

### 4.2 Construction algorithm (normative)

```text
INPUT:  selected Network N, ConnectivityManager cm

1. link = cm.getLinkProperties(N)
   IF link == null → return null (do not fabricate)

2. ifName = link.getInterfaceName()
   IF ifName == null → return null

3. networkState = getNetworkState(N)    // same as NetworkMonitorAutoDetect
   connectionType = getConnectionType(networkState)
   IF connectionType == CONNECTION_NONE → return null

4. underlying = getUnderlyingConnectionTypeForVpn(networkState)

5. handle = networkToNetId(N):
     API >= M: N.getNetworkHandle()
     else:     Integer.parseInt(N.toString())

6. ips = [ IPAddress(byte[]) for each LinkAddress in link.getLinkAddresses() ]

7. RETURN new NetworkInformation(ifName, connectionType, underlying, handle, ips)
```

### 4.3 Fields that MUST NOT be invented

| Field | Failure mode if wrong |
|-------|----------------------|
| `handle` | `BindSocketToNetwork` → `NOT_IMPLEMENTED` / wrong network |
| `name` (interface) | `FindNetworkHandleFromIfname` miss → `ADDRESS_NOT_FOUND` |
| `ipAddresses` | `network_handle_by_address_` miss → host candidate / bind mismatch |

### 4.4 MANET field evidence (Case A 20260902-104114)

Expected when bridge is correct on M02 (`192.168.10.23`):

```text
name            wlan0 (or device-specific; MUST match LinkProperties)
handle          stable per Android Network object
type            CONNECTION_WIFI
ipAddresses     includes 192.168.10.23/… (IPv4 from LinkAddresses)
```

### 4.5 `getActiveNetworkList()` semantics (A2)

```text
IF selected Network N is current AND info(N) != null:
  return listOf(info(N))
ELSE:
  return emptyList()    // NOT a fabricated LAN entry (F8-EG4)
```

Optional: also call `observer.onConnectionTypeChanged` when aggregate type changes
(same as default detector); not required for v1 if single selected network.

---

## 5. F8 contract terms (frozen)

```text
F8-T0
WebRTC NetworkChangeDetectorFactory injection MUST occur before the first
WebRTC NetworkMonitor observer starts monitoring
(numObservers == 0).

F8-T1
For GROUP media execution, the Talkback-selected operational Android Network
MUST be represented to WebRTC as complete NetworkInformation
(§4 field mapping).

F8-T2
The representation MUST preserve the selected Network's native handle,
interface identity, eligible IP addresses, and network type with semantics
compatible with the shipped WebRTC NetworkMonitor networkToInfo() path.

F8-T3
ICE candidates remain WebRTC-native gathered candidates.
Talkback MUST NOT synthesize, rewrite, or inject host IceCandidate objects
or SDP candidate lines.

F8-T4
Selected-Network connect / disconnect / replacement / link-properties change
MUST be propagated to the WebRTC NetworkMonitor observer;
a stale NetworkInformation entry MUST NOT remain current after N is lost
or replaced.

F8-T5
No change to GPLB, GIE, IGIE, GIDR, generation, ICE retry/timeout,
STUN/TURN, application routing, or transmitReady semantics.
```

**Note:** Non-loopback host candidate (`192.168.10.x`) is a **field acceptance observation**,
not a contract authority input. Correct `NetworkInformation` is the obligation; candidates are the outcome.

---

## 6. Acceptance gates

### 6.1 Engineering gates (F8-EG)

| ID | Gate |
|----|------|
| **F8-EG1** | Factory installed before monitoring starts (`setNetworkChangeDetectorFactory` with `numObservers==0`; no `AssertionError` on cold start + first PC). |
| **F8-EG2** | Selected Network with `192.168.10.x` on LinkProperties → `NetworkInformation` contains matching `handle`, `name`, and `ipAddresses` entry. |
| **F8-EG3** | Network replacement N1→N2 → `onNetworkDisconnect(handle(N1))` then `onNetworkConnect(info(N2))`; `getActiveNetworkList()` reflects N2 only. |
| **F8-EG4** | No selected Network → `getActiveNetworkList()` empty; **no** fabricated LAN `NetworkInformation`. |
| **F8-EG5** | No code path constructs synthetic `IceCandidate` / injected host SDP for GROUP mesh. |
| **F8-EG6** | GPLB / GIDR / GIE / IGIE fixture regressions PASS (unchanged). |

### 6.2 Field acceptance (Case A rerun)

```text
Plane B: selected N bound (existing TransportCapabilityTrace localIp=192.168.10.x)
Plane C: ICE_TRANSPORT bridge log shows same handle/interface/IP as §4
GENERATED: 192.168.10.x host candidate on M02/M03 (observation)
M02↔M03:   viable candidate pair + ICE_CONNECTED
Then:      re-score transmitReady (out of F8 scope; downstream)
```

---

## 7. Minimum implementation surface (authorization preview)

| Component | Role | Est. LOC |
|-----------|------|----------|
| `SelectedOperationalNetworkRegistry` | Shared selected-network fact + listeners | ~60–80 |
| `NetworkCapabilityObserver` | Publish §3.4 events to registry | ~40–60 delta |
| `TalkbackWebRtcNetworkBridge` | `NetworkChangeDetector` + `NetworkChangeDetectorFactory` | ~120–180 |
| `WebRtcSharedFactory` | Install factory before `initialize()` | ~15–25 |
| `NetworkInformationBuilder` | `networkToInfo`-compatible builder (§4) | ~80–120 |
| Tests F8-EG1–EG5 | Unit/Robolectric | ~150–200 |

**Forbidden files/patterns:** TalkbackCoordinator ICE send/receive changes, GPLB, synthetic candidate helpers.

---

## 8. Status block

```text
F8:
  ROOT SEAM CONFIRMED
  IMPLEMENTATION MECHANISM SELECTED: Option A2 (Talkback-Selected Network Bridge)
  CONTRACT: FROZEN (this document)
  IMPLEMENTATION: NOT YET AUTHORIZED

Fallback:
  Option A1 if F8-EG2/field GENERATED host fails with correct NetworkInformation
  → audit before merge; do not preempt with A1.
```

---

## 9. References

| Artifact | Path |
|----------|------|
| Shipped AAR | `libs/pnsrd-inst-1-stream-webrtc-android.aar` (`stream-webrtc-android:1.3.10-pnsrd-inst-1`) |
| `networkToInfo` | `webrtc-instrumentation/webrtc-src-pin/.../NetworkMonitorAutoDetect.java` |
| Native bind | `webrtc-instrumentation/webrtc-src-pin/.../android_network_monitor.cc` |
| Current binder | `android-board-talkback/.../AndroidSignalingSocketBinder.kt` |
| Current observer | `android-board-talkback/.../NetworkCapabilityObserver.kt` |
| Factory seam | `android-board-talkback/.../WebRtcSharedFactory.kt` |
| Field RCA | `logs/adr0057-track-c-case-a-20260902-104114/` |
