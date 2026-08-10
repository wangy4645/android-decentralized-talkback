# RCA-003 — Presentation Convergence after Recovery

**Status:** **CLOSED**  
**Date:** 2026-08-11  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**IC:** [rca-003-ic-uvcp-residency-decoupling.md](./rca-003-ic-uvcp-residency-decoupling.md) · PR [#157](https://github.com/wangy4645/android-decentralized-talkback/pull/157)  
**Case-3 field:** [rca-003-ic-uvcp-gray-adjudication-20260811-063722.md](./rca-003-ic-uvcp-gray-adjudication-20260811-063722.md)

## Freeze card

```text
RCA-003 Presentation Convergence after Recovery

Root:
  FAILED_MEDIA residency leaked into realtime availability projection

Fix:
  UVCP consumes current availability only (#157)

Verification:
  Case-3 FIELD VERIFIED (20260811-063722)
  Case-3 does NOT require EDGE_RECOVERED

Status:
  CLOSED

Do not reopen: recovery · Phase-2 · UVCP · residency clear for this track
```

## Portfolio (closed vs parked)

```text
WiFi Recovery Protocol              CLOSED / VERIFIED
Media Ownership (reattach handoff)  CLOSED / VERIFIED
Conference Rejoin                   CLOSED / VERIFIED
RCA-003 Presentation Convergence    CLOSED

  Implementation      PASS
  Case-3 validation   PASS
  Field verification  PASS

Mesh / M03↔M02 unrecovered edge     PARKED → Session Churn / Mesh Edge Recovery Stability
Session Churn                       OPEN (independent)
Roster Projection                   OPEN (independent)
```

## Classification (do not conflate)

| Observation | Route |
|-------------|-------|
| Recovery succeeded + UI sticky degraded | Was RCA-003 — **CLOSED** |
| Recovery failed + UI degraded | Expected — not a UVCP bug |
| Mesh edge never returns CONNECTED | Independent stability track |

```text
degraded ≠ bug
Ask: why did this mesh edge not recover?
Not: why does UI show degraded?
```

## Case 3 gate (corrected)

**In scope:**

```text
FAILED_MEDIA residency / edgePhase=FAILED_MEDIA_RECOVERY
+ current media healthy (CONNECTED)
+ receivePathLive=true
→ UVCP must not inherit residency → mediaUnavailable=false → ONLINE / pill clear
```

**Not required:** `EDGE_RECOVERED=true` (over-strict; corrected after gray-2).

## Field evidence (Case 3 PASS)

`logs/rca003-ic-uvcp-gray-20260811-063722/` — M01→M02 ×29:

```text
media=CONNECTED · ice=CONNECTED · receivePathLive=true
edgeRecoveryPhase=FAILED_MEDIA_RECOVERY · obligationOpen=true
mediaUnavailable=false · finalPresence=ONLINE · connectingHint=null
```

Anti-signal: `CONNECTED + mediaUnavailable=true` = 0.

## Sealed answers

```text
R5.1  FAILED_MEDIA = incident residency (not live unavailable)
R5.2  CLEAR = obligationClosed ∧ iceConnected ∧ receivePathLive
R5.3  FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
```

## Next (independent — not RCA-003)

```text
RCA-004 Media Edge Recovery Convergence Audit (AUDIT ONLY)
  docs/analysis/rca-004-media-edge-recovery-convergence-audit-entry.md
Not: modify recovery · Phase-2 · UVCP for RCA-003
```
