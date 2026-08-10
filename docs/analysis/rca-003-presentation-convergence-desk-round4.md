# RCA-003 Round-4 — desk: who owns peer-healthy after recovery?

**Status:** SUPERSEDED by [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md) (COMPLETE)  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
**Round-3:** [rca-003-round3-adjudication-20260810-220243.md](./rca-003-round3-adjudication-20260810-220243.md)

## One question

> After recovery completes, which state source decides “this peer is healthy”?

Specifically: **who sets `mediaUnavailable`, and who clears it?**

## Why not more soak

Rounds 2–3 already established transitional vs stuck. Further flaps will not name the owner.

## Known produce/clear (from Round-3 rprobe)

| Event | Condition |
|-------|-----------|
| DEGRADED appear | `mediaUnavailable=true` ∧ `receivePathLive=true` ∧ `recovering=false` |
| DEGRADED clear | `mediaUnavailable=false` (same axes otherwise healthy) |

Pill string is a **projection**, not the SoT. SoT candidate: **`conferenceMediaUnavailable` / failed-media residency ∪ MediaState usability**.

## Desk targets (read-only)

| Symbol | File / area |
|--------|-------------|
| `TalkViewModel.edgeMediaUnavailablePeer` | app UI adapter |
| `TalkbackCoordinator.conferenceMediaUnavailable` | composes `MediaUsabilityFact` + `isMediaUnavailable` residency |
| `MediaUsabilityFact.isUnavailable` | media-axis predicate |
| `ConferenceEdgeRecoveryController.isMediaUnavailable` | ADR-0030 failed-media residency |
| `UserVisibleConnectivityProjection.deriveAxes` | maps axes → DEGRADED (ADR-0044) |

Existing field instrument: `[DEBUG-rprobe] REACHABILITY_PROBE` already logs `mediaUnavailable` / `receivePathLive` / `finalPresence` (enabled in current builds).

## Distinguish modes (documentation only)

```text
DEGRADED_TRANSITIONAL  — mediaUnavailable asserts then clears (R3)
DEGRADED_STUCK         — mediaUnavailable stays true >120s (R2)
```

Round-4 exit: name set/clear sites + whether stuck mode is residency sticky, MediaState lag, or aggregation bug.  
**Still do not** modify recovery / ICE / delivery.
