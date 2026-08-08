# ADR-0044 — Thin Field Run Card

**Status:** **AUTHORIZED** · presentation validation only  
**Date:** 2026-08-09  
**Parent:** [0044-user-visible-connectivity-semantics-media-residency.md](../adr/0044-user-visible-connectivity-semantics-media-residency.md)  
**Impl:** PR #130 · merge `62951da` (incl. KDoc `25c86c8`)  
**Adjudicate:** `scripts/adr0044-field-adjudicate.ps1`

```text
Field Authorization: ADR-0044 presentation validation only
```

---

## Status board

```text
WiFi Recovery Architecture       CLOSED ✅
ADR-0043/RNA                     FROZEN ✅

P1a Presentation Boundary        CLOSED ✅
FAILED_MEDIA desk Q1-Q3          CLOSED OBSERVATION ✅

ADR-0044
  Decision                       ACCEPTED ✅
  Implementation                 MERGED ✅
  Field                          AUTHORIZED (thin validation)
  Scope                          Presentation only
```

---

## Goal (single)

Validate terminal media residency presentation after repair stops:

```text
FAILED_MEDIA_RECOVERY
  + mediaUnavailable=true
  + recovering=false   (controllerEdgeRecovering=false)
        ↓
EndpointStatus / finalPresence = DEGRADED
        ≠
RECONNECTING
```

This Field validates **user-visible connectivity semantics**, not WiFi Recovery.

---

## Topology / Setup

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Host | M02 | `2d73067a` |
| DUT  | M03 | `MDX0220416001963` |

SSID: **`happy`** only

**Allow:** existing mobile-validation environment · same class of M03 WiFi flap episode  
**APK:** `main` @ merge containing PR #130 (`62951da` or later)

```powershell
cd talkback
.\gradlew.bat :talkback-app:assembleDebug
adb -s HTUBB21B09220661 install -r talkback-app\build\outputs\apk\debug\talkback-app-debug.apk
adb -s MDX0220416001963 install -r talkback-app\build\outputs\apk\debug\talkback-app-debug.apk
adb -s 2d73067a push talkback-app\build\outputs\apk\debug\talkback-app-debug.apk /sdcard/Download/talkback/talkback-app-debug.apk
```

---

## Execution

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "logs\adr0044-field-$stamp"
.\scripts\wifi-recovery-start-run.ps1 -Scenario W2 -LogDir $LogDir
# 3-party conference up → annotate T0
# M03 WiFi OFF ~15s → ON
# Soak until obligation closed + residency visible (typically 60–120s after restore)
# Stop collectors (kill PIDs in COLLECTOR_PIDS.txt) then:
.\scripts\adr0044-field-adjudicate.ps1 -LogDir $LogDir
```

Stimulus class matches prior Case A / FAILED_MEDIA desk episodes (M03 isolated flap).

---

## Observe (only)

| Item | Expect |
| ---- | ------ |
| M02 → M03 endpoint presentation | `finalPresence=DEGRADED` |
| M03 → M02 endpoint presentation | `finalPresence=DEGRADED` |
| Reconnect copy | must not persist after `recovering=false` |
| Recovery authority | unchanged (background only; not verdict input) |

**Log authority for presentation:** `[DEBUG-rprobe] REACHABILITY_PROBE`  
Primary observers: **M02** (peer row for M03) and **M03** (peer row for M02).

### Qualifying terminal window (must hold together)

```text
edgeRecoveryPhase=FAILED_MEDIA_RECOVERY
mediaUnavailable=true
controllerEdgeRecovering=false
```

Then require:

```text
finalPresence=DEGRADED
```

and **not**:

```text
finalPresence=RECONNECTING
```

---

## Pass criteria

```text
PASS:
  terminal media residency → DEGRADED
  and no active recovery → no RECONNECTING
```

Human UI check (optional corroboration): peer shows Degraded / degraded hint — **not** “正在重新连接…”.

---

## Non-goals (frozen)

Do **not** adjudicate:

```text
✗ ICE CONNECTED timing / ICE restart
✗ membership convergence
✗ GROUP_RESYNC
✗ obligation lifecycle
✗ completion admission
✗ recovery success rate
✗ retry behavior
✗ conference Poor Network banner (P1a closed)
```

If those logs appear, treat as **background fact only** — exclude from verdict.

---

## Failure classification (frozen)

### Case A — PASS

```text
FAILED_MEDIA_RECOVERY + recovering=false + UI/finalPresence=DEGRADED
→ PASS
```

### Case B — ADR-0044 projection regression

```text
FAILED_MEDIA_RECOVERY + recovering=false + UI/finalPresence=RECONNECTING
→ ADR-0044 projection regression
```

**Not** ADR-0043 regression · **Not** recovery bug · route back to **presentation only**.

### Case C — presentation implementation issue

```text
recovering=false + UI=RECONNECTING + authority source wrong
→ presentation implementation issue
```

Still does **not** reopen recovery core.

### ENV_INVALID

```text
no FAILED_MEDIA_RECOVERY residency window
or mediaUnavailable never true after flap
or conference never stable 3-party
→ ENV_INVALID (retry thin field; do not expand scope)
```

---

## Discipline

```text
PASS  → ADR-0044 Field CLOSED (presentation loop complete)
FAIL  → presentation-only follow-up; no recovery reopen
```

```text
Observation → Ownership proof → Semantic ADR → Presentation impl → Thin validation
```

No reflux into recovery core.
