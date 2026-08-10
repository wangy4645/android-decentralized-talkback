# RCA-003 Round-3 — degraded source-of-truth observation

**Status:** AUTHORIZED — **observe + desk read / no product code**  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
**Round-2:** [rca-003-round2-adjudication-20260810-211253.md](./rca-003-round2-adjudication-20260810-211253.md)

## One question

> What is the source of truth for `degraded`? Who last wrote it?

Hypotheses (do not pick yet):

| | Hypothesis |
|---|------------|
| **A** | Presentation cache / reducer missed CONNECTED cover of DEGRADED |
| **B** | MEDIA_LIFECYCLE CONNECTED is local/partial; presentation uses wider axes (receive path, heartbeat, membership, quality, stream) |

## Three timestamps (every episode)

```text
T1  MEDIA_LIFECYCLE … CONNECTED   (for the peer shown as degraded)
T2  Meeting pill … degraded...      (first durable emit)
T3  HEALTHY clear / DEGRADED_CLEAR / CONNECTED hint null  (or absent for full window)
```

Also: **who last wrote degraded** (log + call site).

## Desk inventory (read-only seed — not a fix)

Presentation path (app layer), not recovery:

| Artifact | Role |
|----------|------|
| `UserVisibleConnectivityProjection.deriveAxes` / `project` | Dual-axis → `DEGRADED` (ADR-0044: terminal residency ≠ RECONNECTING) |
| `UserVisibleConnectivityProjection.formatMeetingHint` | Emits `"$peer degraded..."` |
| `MeetingPresenceDisplay` | Feeds axes → endpoint / meeting hint |
| `TalkViewModel` | Logs `Meeting pill: … connectingHint=` |
| `MediaLifecycle.DEGRADED` / `MediaSessionManager` | Media lifecycle enum (ICE DISCONNECTED → DEGRADED) |
| `ConferenceRuntimeProjector.conferenceDegraded` | Runtime degraded from edge recovering/failed — **orthogonal naming**; do not conflate with pill `degraded...` without proof |

There is **no** `setPeerDegraded` symbol; ownership is likely **axes → project → formatMeetingHint**.

Round-3 field goal: prove whether after T1 CONNECTED, axes still yield `MEDIA_UNAVAILABLE` / `control DEGRADED`, or hint is stale.

## Field procedure (optional corroboration)

```text
1. Same as Round-2: flap M02, no hangup, ≥120s
2. Prefer watch DUT (M02) pill for peer M01 (Round-2 locus)
3. Correlate T1/T2/T3 on M02 log
4. Grep: MEDIA_LIFECYCLE, Meeting pill, receivePath, mediaUnavailable, recovering
```

## Exit

```text
Hypothesis A or B with T1/T2/T3 + owning function name
Impl only after that admission + narrow design review
Do NOT modify recovery / ICE / delivery
```
