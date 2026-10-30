# ADR-0058 E3a.3f-0 — MTK Multicast Filter Product-Control Audit (Read-Only)

## Status

```text
CLOSED — READ-ONLY AUDIT
Purpose: implementation feasibility evidence for optimization-path coverage
Does NOT gate: ADR-0060 architecture decision (already frozen)
Media engine: NOT AUTHORIZED
```

## Question

On M02-class MediaTek Android devices, does there exist a **non-root, reversible, semantically explicit, product-shippable** control that can reliably restore multicast ingress?

## Method

Pure read audit of:

- E3a.3d vendor observability artifacts
- E3a.3e A/B intervention results
- Android platform multicast-filter architecture (AOSP `WifiNative`, `WifiMulticastLockManager`)
- Probe product seam (`AndroidWifiMulticastLockSeam`)

No new device interventions in this step.

---

## Control surface inventory

| Control | Layer | App/product API? | Reversible? | M02 evidence | Product value |
|---------|-------|------------------|-------------|--------------|---------------|
| `WifiManager.MulticastLock` | Framework → supplicant RXFILTER | **YES** (documented public API) | YES (scope-bound) | **Held by probe**; M02 still 0% / state-loss | **Already required** — insufficient alone on M02 |
| `IpClient` multicast filtering disable | Connectivity / `WifiClientModeImpl` | NO (system internal) | YES (with lock lifecycle) | **Absent on M02** vs Huawei M01/M03 log `set Multicast Filtering:false` | **0** — not exposed to apps; vendor path divergence |
| `wpa_supplicant` `RXFILTER-*` via `DRIVER` cmd | Supplicant → nl80211/driver | NO (system / root shell) | YES | Observed `RXFILTER-ADD 2` on M02 lock acquire; ingress still fails | **0** for product — equivalent to MulticastLock path already exercised |
| `CMD_SET_FALLBACK_PACKET_FILTERING` | `WifiClientModeImpl` state machine | NO (framework internal) | Tied to WiFi FSM | Toggles with **probe lifecycle** (~75s), not user/app API | **0** — not a controllable product knob |
| `ip link set wlan0 allmulticast on` | Kernel netdev flag | NO (root shell) | YES | E3a.3e A2 **NO_CHANGE** (0/2999) | **0** — root-only; ineffective on M02 fault |
| `iw dev wlan0 set power_save off` | mac80211 / driver PS | NO (root shell) | YES | E3a.3e A1 **NO_CHANGE** (0/2999) | **0** — root-only; ineffective on M02 fault |
| `wlanoidSetCurrentPacketFilter` (MTK OID) | MTK driver/firmware | NO (driver private) | Unknown | dmesg `some flags we don't support` — repeated | **0** — no stable public API; flags partially unsupported |
| WifiHAL APF program | Vendor HAL offload filter | NO (system) | YES | Seen on M03 (Huawei), not product path on M02 audit | **0** for third-party apps |
| `su` / `tcpdump` on wlan0 | Diagnostic | NO | N/A | E3a.3d.2 pcap unusable (24B) | Diagnostic only |
| `wpa_cli` interactive `DRIVER RXFILTER-*` | Shell → supplicant | NO (shell / debug) | YES | Same semantics as framework path; requires privileged access | **0** |
| `cmd wifi` shell multicast helpers | System shell | NO (system UID) | Varies | Not evidenced as distinct from MulticastLock chain on M02 | **0** for shipped app |

---

## Platform architecture (why MulticastLock is the product ceiling)

Android documents `WifiManager.MulticastLock` as the **only supported application mechanism** to receive WiFi multicast. Internally, `WifiMulticastLockManager` calls `stopFilteringMulticastPackets()` → `WifiNative.stopFilteringMulticastV4Packets()` → supplicant `RXFILTER-STOP` / `RXFILTER-ADD` (multicast) / `RXFILTER-START`.

M02 logs show exactly this RXFILTER sequence on lock acquire — yet ingress remains 0% in fault state and exhibits E3a.3b state-loss. Therefore:

```text
The product API path is already exercised.
Further RXFILTER manipulation is not a new product surface — it is the same path.
```

Huawei peers additionally log `IpClient: set Multicast Filtering:false`. M02 does **not**. This is a **vendor integration divergence below the public API**, not an missing app call.

---

## MTK-specific findings (from E3a.3d artifacts)

```text
1. Kernel IGMP membership present (632AFFEF) while app RX = 0
2. wlan0 kernel mcast RX counter = 0 during active sender
3. wlanoidSetCurrentPacketFilter: unsupported flags (driver WARN)
4. No IpClient multicast-filter-disable log line (unlike working Huawei devices)
5. Wlan wake reasons: ipv4RxMulticast=0, rxMulticast=0 during fault probe
```

Classification: failure at or below MTK driver/firmware RX delivery, **after** the standard Android MulticastLock → RXFILTER chain.

---

## Verdict

```text
PRODUCT-LEVEL CONTROLLABLE WORKAROUND ON M02: NOT FOUND

Known public/product surface:
  WifiManager.MulticastLock  →  already used; insufficient on M02 fault class

Known privileged surfaces:
  ALLMULTI, power_save, RXFILTER shell, driver OID, debugfs
  →  root/system only and/or already NO_CHANGE in E3a.3e

Implication for ADR-0060:
  Do NOT block correctness on MTK vendor filter debugging.
  Treat M02 as optimization-path incompatible until/unless a shippable
  control is discovered (low probability from this audit).
```

## Recommended follow-up (non-blocking)

| Track | Action | Owner |
|-------|--------|-------|
| **3f-1** | L2 domain replacement — capability granularity | Field |
| **Optimization** | If OEM partnership later exposes HAL filter API, re-audit under ADR | Product/OEM |
| **Correctness** | Implement ADR-0060 unicast fan-out | Implementation |

Do **not** open stacked vendor A/B (PS + ALLMULTI + RXFILTER shell) without new ADR.

---

## Artifacts

- `logs/adr0058-e3a3d-wifi-vendor-obs-20260903-110932/STEP_SUMMARY.txt`
- `logs/adr0058-e3a3e-a1-ps-off-20260903-111859/`
- `logs/adr0058-e3a3e-a2-allmulti-20260903-113408/`
- `android-board-talkback/.../AndroidWifiMulticastLockSeam.kt`
- `android-board-talkback/.../UnderlayMulticastProbeRunner.kt`
