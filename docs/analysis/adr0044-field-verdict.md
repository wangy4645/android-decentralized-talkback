# ADR-0044 — Thin Field Verdict

**Status:** **PASS** · Case A  
**Date:** 2026-08-09  
**Parent:** [adr0044-field-run-card.md](./adr0044-field-run-card.md) · ADR-0044 · PR #130 (`62951da` / `25c86c8`)  
**LogDir:** `logs/adr0044-field-20260809-080841/`  
**T0:** `2026-08-09 08:09:52` · Stimulus: M03 WiFi OFF ~15s → ON (W2) · SSID `happy`

---

## Status board

```text
WiFi Recovery Architecture       CLOSED ✅
ADR-0043/RNA                     FROZEN ✅
P1a                              CLOSED ✅
FAILED_MEDIA desk Q1-Q3          CLOSED OBSERVATION ✅

ADR-0044
  Decision                       ACCEPTED ✅
  Implementation                 MERGED ✅
  Field                          PASS (thin validation) ✅
  Scope                          Presentation only
```

---

## Verdict

```text
VERDICT = PASS
CASE    = A
```

Qualifying window (both observers):

```text
edgeRecoveryPhase=FAILED_MEDIA_RECOVERY
mediaUnavailable=true
controllerEdgeRecovering=false
finalPresence=DEGRADED   ≠ RECONNECTING
```

| Observer | Peer | Qualifying probes | DEGRADED | RECONNECTING |
| -------- | ---- | ----------------- | -------- | ------------ |
| M02 | M03 | 62 | 62 | 0 |
| M03 | M02 | 61 | 61 | 0 |

Adjudicator: `scripts/adr0044-field-adjudicate.ps1`

---

## Fact triplet (authoritative sample)

End-state rprobe (soak):

```text
M02→M03 / M03→M02:
  phase=FAILED_MEDIA_RECOVERY
  recovering=false
  finalPresence=DEGRADED
  ice=CONNECTED
  receivePathLive=true
```

### Presentation follows recovery fact

```text
Sync (active repair window)
  → DEGRADED (terminal residency, repair stopped)
```

**Not** sticky RECONNECTING after `recovering=false`.

---

## User observation (expected, not fail)

Field note: UI showed **Degraded** while media plane felt healthy.

This is **legal and in-scope for ADR-0044**:

```text
ICE CONNECTED + receivePathLive=true
+ FAILED_MEDIA_RECOVERY residency (mediaUnavailable=true)
+ recovering=false
→ EndpointStatus=DEGRADED
```

```text
DEGRADED ≠ media broken
DEGRADED ≠ recovery bug
DEGRADED ≠ retry trigger
```

Clearing residency remains recovery ownership (`markRecovered` / supersede) — **out of scope** for this Field.

---

## Non-goals (not adjudicated)

```text
ICE timing · membership · GROUP_RESYNC · obligation · completion · retry
```

Prior ENV wedge (M02↔M03 mesh CHECKING on earlier attempt) deferred to independent mesh join desk — **not** this verdict.

---

## Close

```text
ADR-0044 Field follow-up CLOSED (PASS)
Presentation semantic loop complete
No reflux into recovery core
```
