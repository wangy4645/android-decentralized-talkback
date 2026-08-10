# RCA-003 Round-3 — adjudication (D-like / transitional)

**Date:** 2026-08-10  
**Log:** `logs/rca003-pres-conv-r3-20260810-220243/`  
**Status:** ADJUDICATED — observe-only; **no product code**  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)

## Frozen result (precise — not “Case D fully”)

```text
RCA-003 Round-3

Case D-like:
  Transient Presentation Degradation

Finding:
  degraded is not always terminal;
  degraded may self-converge.
```

Round-2 **Case E** remains valid (persistence >120s observed).  
Round-3 does **not** revoke E; it proves a **second mode**.

```text
DEGRADED_TRANSITIONAL   ← Round-3
DEGRADED_STUCK          ← Round-2
```

## Status bands

```text
Recovery          PASS
Media lifecycle   PASS（至少存在 CONNECTED）
Presentation      有延迟收敛 / 偶发持久 degraded
```

## Timeline (M02 view of M01)

| Rel flap | Time | Fact |
|----------|------|------|
| +0s | 22:03:08 | NETWORK_LOST |
| +11s | 22:03:19 | M01 `EDGE_RECOVERED`(remote=M02) |
| +17s **T1** | 22:03:25 | `MEDIA_LIFECYCLE M01=CONNECTED`; pill=`syncing`; rprobe: `mediaUnavailable=false receivePathLive=true recovering=true` → SYNCING |
| +30s **T2** | 22:03:38 | pill=`M01 degraded... recovering=[]`; rprobe: **`mediaUnavailable=true`** ice=CONNECTED receivePathLive=true recovering=false → **DEGRADED** |
| +60s **T3** | 22:04:08 | pill clear; rprobe: `mediaUnavailable=false` → ONLINE |

Operator wall-clock (~40s sync / ~50s degrade / ~1min+ clear from collector start) matches.

## Model update (freeze)

Too coarse:

```text
Media lifecycle → Presentation
```

Accurate:

```text
Media lifecycle
      |
      v
Peer health aggregation   (axes: receivePathLive, mediaUnavailable, recovering, …)
      |
      v
Presentation projection   (UserVisibleConnectivityProjection → Meeting pill)
```

Evidence: at T1, lifecycle CONNECTED while pill still SYNCING — presentation is **not** bound 1:1 to `MEDIA_LIFECYCLE`.

## Degraded produce / clear (Round-3 evidence)

**Produce (T2):** `mediaUnavailable=true` with `receivePathLive=true` + recovering=false  
→ ADR-0044 path: `MEDIA_UNAVAILABLE` + control `STABLE` → **DEGRADED**  
→ `formatMeetingHint` → `M01 degraded...`

**Clear (T3):** `mediaUnavailable` returns **false** → CONNECTED / ONLINE.

Owner chain (desk):

```text
conferenceMediaUnavailable / edgeMediaUnavailablePeer
  → ParticipantPresentationFacts.mediaUnavailablePeer
  → UserVisibleConnectivityProjection.deriveAxes/project
  → MeetingPresenceDisplay → Meeting pill
```

Not: WiFi / Phase-2 / ownership / ICE restart main chain / recovering flag sticky.

## Next (no more flap soak)

[Round-4 desk](./rca-003-presentation-convergence-desk-round4.md):  
**After recovery, which state decides “peer is healthy”?**  
Focus: who sets/clears `mediaUnavailable` (residency / MediaUsabilityFact) while ICE/receivePath already live.
