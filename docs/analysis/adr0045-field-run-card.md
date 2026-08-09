# ADR-0045 — Thin Field Run Card

**Status:** **PAUSED** · Phase 2.1 thin Field — no qualifying FAILED_MEDIA case on M02  
**Date:** 2026-08-09  
**Parent:** [0045-post-obligation-failed-media-residency-clear-admission.md](../adr/0045-post-obligation-failed-media-residency-clear-admission.md)  
**Impl:** Phase 1 #131 · Phase 2 #133 · Phase 2.1 #136 · merge `094082b` or later  
**Field #1:** `logs/adr0045-field-20260809-093047` — M03 PASS / M02 trigger gap (ADR-0045)  
**Field #2:** `logs/adr0045-field-20260809-094259` — **reclassified** → [mobile-validation-successor-recovery-pending-observation.md](./mobile-validation-successor-recovery-pending-observation.md)  
**Adjudicate:** `scripts/adr0045-field-adjudicate.ps1` (only when GATE holds)

```text
Field Authorization: ADR-0045 post-obligation residency clear only
```

---

## Status board

```text
WiFi Recovery Architecture       CLOSED ✅
ADR-0043/RNA                     FROZEN ✅
ADR-0044 Presentation            CLOSED ✅

ADR-0045
  Decision                       ACCEPTED ✅
  Phase 1 Policy                 MERGED ✅ (#131) · PASS
  Phase 2 Trigger                MERGED ✅ (#133) · PARTIAL
  Phase 2.1 Entry trigger        MERGED ✅ (#136) · Field PAUSED
  Field #1 (20260809-093047)     NOT PASS (M03 PASS / M02 trigger gap)
  Field #2 (20260809-094259)     NOT ADR-0045 — successor SYNCING observation
  Scope                          Residency clear admission only
  Do not                         force FAILED_MEDIA to mix with successor track
```

---

## Goal (single)

Validate that after obligation deadline, when snapshot E4 holds, Recovery clears failed-media residency and presentation **leaves DEGRADED**:

```text
FAILED_MEDIA_RECOVERY
+ obligationClosed (OBLIGATION_DEADLINE)
+ recovering=false
+ ice=CONNECTED
+ receivePathLive=true
        ↓
FAILED_MEDIA_RESIDENCY_CLEARED
        ↓
mediaUnavailable=false
presentation leaves DEGRADED
```

```text
closeReason remains OBLIGATION_DEADLINE
≠ recovery completion success
≠ RECOVERY_EDGE_RECOVERED
```

This Field validates **residency clear admission**, not WiFi Recovery / completion / UVCP vocabulary redesign.

---

## Topology / Setup

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Host | M02 | `2d73067a` |
| DUT  | M03 | `MDX0220416001963` |

SSID: **`happy`** only

**APK:** `main` @ merge containing PR #136 (`094082b` or later)

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
$LogDir = "logs\adr0045-field-$stamp"
.\scripts\wifi-recovery-start-run.ps1 -Scenario W2 -LogDir $LogDir
# 3-party conference up → annotate T0
# M03 WiFi OFF ~15s → ON
# Soak until AFTER obligation deadline (typically allow 90–150s after restore)
# Expect: brief DEGRADED window may appear, then FAILED_MEDIA_RESIDENCY_CLEARED
# Stop collectors (kill PIDs in COLLECTOR_PIDS.txt) then:
.\scripts\adr0045-field-adjudicate.ps1 -LogDir $LogDir
```

Stimulus class matches ADR-0044 / FAILED_MEDIA Case A (M03 isolated flap).

---

## Observe (only)

| Item | Expect |
| ---- | ------ |
| Clear fact | `FAILED_MEDIA_RESIDENCY_CLEARED` on M02↔M03 observers |
| closeReason | still `OBLIGATION_DEADLINE` (not rewritten to RECOVERED) |
| Completion | **no** `RECOVERY_EDGE_RECOVERED` for this clear |
| Presentation | after clear: `mediaUnavailable=false` and `finalPresence` **leaves DEGRADED** |

**Log authority:** `[DEBUG-rprobe] REACHABILITY_PROBE` + residency clear lines  
Primary observers: **M02** (peer M03) and **M03** (peer M02).

### Qualifying pre-clear window (optional but expected)

```text
edgeRecoveryPhase=FAILED_MEDIA_RECOVERY
mediaUnavailable=true
controllerEdgeRecovering=false
finalPresence=DEGRADED
```

May be short if E4 is already true at deadline.

### Qualifying post-clear window (required)

```text
FAILED_MEDIA_RESIDENCY_CLEARED present
AND later probes:
  mediaUnavailable=false
  finalPresence ≠ DEGRADED
```

Do **not** require `finalPresence=ONLINE` / “recovery success”.

---

## Pass criteria

```text
PASS:
  FAILED_MEDIA_RESIDENCY_CLEARED observed
  + closeReason remains OBLIGATION_DEADLINE
  + no RECOVERY_EDGE_RECOVERED for clear
  + presentation leaves DEGRADED (mediaUnavailable=false)
```

---

## Non-goals (frozen)

```text
✗ ICE restart success rate / latency
✗ membership / control reconciliation
✗ ADR-0038 completion success
✗ UVCP vocabulary / ADR-0044 mapping redesign
✗ Directed #5 / WiFi matrix expansion
✗ Mesh M02↔M03 ICE CHECKING wedge
✗ retry / SUPERSEDE-as-clear
```

---

## Failure classification (frozen)

### Case A — PASS

```text
clear event + leave DEGRADED + closeReason=OBLIGATION_DEADLINE + no completion event
→ PASS
```

### Case B — clear missing (trigger / E4 / APK)

```text
post-deadline E4 evidence present (ice CONNECTED + receivePathLive)
but no FAILED_MEDIA_RESIDENCY_CLEARED
→ Phase 2 / wiring follow-up (not ADR-0044)
```

### Case C — completion pollution

```text
clear path emits RECOVERY_EDGE_RECOVERED
or closeReason rewritten to RECOVERED
→ ADR-0045 boundary violation
```

### ENV_INVALID

```text
no FAILED_MEDIA_RECOVERY residency window
or conference never stable 3-party
→ ENV_INVALID (retry thin field; do not expand scope)
```

---

## Discipline

```text
PASS  → ADR-0045 Field thin validation CLOSED
FAIL  → residency-clear track only; no WiFi Recovery / ADR-0038 reopen
```
