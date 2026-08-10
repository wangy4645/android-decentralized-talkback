# Post-close soak — M02 media unrecovered (`20260811-064426`)

**Date:** 2026-08-11  
**LogDir:** `logs/rca003-ic-uvcp-gray-20260811-064426/`  
**Session:** `de2411c0-888c-425c-8150-8704ff489897`  
**RCA-003:** remains **CLOSED** (this is mesh / media-plane recovery, not UVCP)

## Verdict

```text
M02 media plane did NOT recover toward observers
UVCP / #157: NOT implicated (current path truly down)
Track: Session Churn / Mesh Edge Recovery Stability (PARK → now REPRODUCED)
```

## Asymmetry

| View | After flap (~06:45:33+) | Terminal before leave (~06:47:09) |
|------|-------------------------|-----------------------------------|
| **M01 → M02** | DISCONNECTED → **ICE FAILED** @ 06:45:43 | never CONNECTED again |
| **M02 → M01** | DISCONNECTED → FAILED → **CONNECTED** @ 06:46:13 | recovered |
| **M02 → M03** | DISCONNECTED → **CONNECTED** @ 06:45:56 | recovered |
| **M03 → M02** | DISCONNECTED → CHECKING stuck | never CONNECTED; `NO_MEDIA_ACTION_OWNER` |

`RECOVERY_EDGE_RECOVERED` = **0** on all three devices this run.

## M01→M02 death chain (host)

```text
06:45:33  ICE_DISCONNECTED · negotiation owner=M02 (existing_owner)
06:45:36  HOST_RESTART assigned · NEGOTIATION_NON_OWNER_BLOCKED (local=M01 owner=M02)
06:45:43  ICE FAILED · RECOVERY_REEVALUATE ROUTE_LOST
          MEDIA_ACTION_DEFERRED MEDIA_NOT_READY
06:45:49  WATCHDOG_DEFERRED CAPABILITY_UNAVAILABLE_AT_FIRE
06:46:32  REATTACH_ACCEPTED → FAILED_MEDIA_RECOVERY (attempt_timeout)
06:47:02  CLEAR_HELD iceConnected=false receivePathLive=true
```

Dominant rprobe (M01→M02): `media=RECONNECTING|FAILED` · `ice=FAILED` · `mediaUnavailable=true` — **correct current unavailability**.

## M03→M02 (same park signature as gray-1)

```text
06:45:52  EXPLICIT_ABORT:NO_MEDIA_ACTION_OWNER → FAILED_MEDIA_RECOVERY
rprobe: media=RECONNECTING · ice=CHECKING · DEGRADED (×75)
```

## UVCP note

Pill `M02 reconnecting...` while ICE down is expected.  
Later M01 `connectingHint=null` with recovering=[] while rprobe still `ice=FAILED` / `NOT_PROJECTED` is aggregation chrome — **not** Case-3 false healthy (media was not CONNECTED). Do not reopen RCA-003 from that alone.

## Next

```text
Promoted to: RCA-004 Media Edge Recovery Convergence Audit (AUDIT ONLY)
  docs/analysis/rca-004-media-edge-recovery-convergence-audit-entry.md
Not: UVCP · Phase-2 · residency clear · RCA-003 · ownership redesign
```