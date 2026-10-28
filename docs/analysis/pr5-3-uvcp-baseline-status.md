# PR5-3 / UVCP Baseline Status

**Status:** `CLOSED / MIGRATION VERIFIED`  
**Date:** 2026-08-04  
**Freeze:** [pr5-3-uvcp-baseline-contract-frozen.md](./pr5-3-uvcp-baseline-contract-frozen.md)  
**Closure stamp:** AUTHORIZE M2 CLASS C DEMOTION completed; line closed by operator confirmation

```text
PR5-3 Contract                    FROZEN ✅
M0 Shadow                         SATISFIED ✅
M1 Projection Adapter             VERIFIED ✅
M2 Class C Demotion               IMPLEMENTED / VERIFIED ✅

CompletionPolicy                  unchanged
D1                                unchanged
E16 Activation                    unchanged
Media Axis                        unchanged
Coordinator Recovery Gates        unchanged

L.1.5 / PRR                       NEXT LINE (separate) — see l15-prr-baseline-status.md
```

## Architecture judgment (accepted)

M2 fixed **UVCP semantic leakage**, not recovery lifecycle:

```text
BEFORE (Class C leakage):
  EdgeRecoveryPhase.RECOVERING → UVCP SYNCING/RECONNECTING
  ≡ phase fact == completion/reachability decision

AFTER (axis isolation):
  Recovery phase          → Coordinator / recovery control only
  EpisodeCompletionProjection → UVCP completion axis
  Presence / Media        → independent projections
```

### Invariants retained

| # | Invariant | Status |
|---|-----------|--------|
| 1 | `recovering ≠ failed`; `isEdgeRecovering` remains internal recovery fact; **not** ⇒ SYNCING ⇒ completion pending | HOLD |
| 2 | `mediaUnavailable` may still force RECONNECTING; recovering-only does not | HOLD |
| 3 | `demoteClassCRecoveringInUvcp=false` = compatibility escape hatch (no contract reopen) | HOLD |

**Rollback flag:** `UserVisibleConnectivityProjection.demoteClassCRecoveringInUvcp` (default `true`)

## Final route (PR5-3 tree)

```text
E16 Joint Convergence             CLOSED / FIELD_VERIFIED ✅
PR5-3 Contract Boundary           FROZEN ✅
M0 Shadow                         VERIFIED ✅
M1 Completion Projection Adapter  VERIFIED ✅
M2 Class C Demotion               VERIFIED ✅
--------------------------------
L.1.5 / PRR Transport Self-Heal   SEPARATE FRONTIER
```

### Framing for the next line (normative wording)

Do **not** write: “UVCP 已修，所以 SYNCING 问题解决。”

Write: “PR5-3 已移除 completion/presence projection 污染；下一阶段验证 transport path resurrection 是否独立成立。”

**Next doc tree:** [l15-prr-baseline-status.md](./l15-prr-baseline-status.md) · [l15-prr-admission-contract.md](./l15-prr-admission-contract.md)

**Companions (archived under this line):**
- [pr5-3-uvcp-m2-class-c-demotion-implementation.md](./pr5-3-uvcp-m2-class-c-demotion-implementation.md)
- [pr5-3-uvcp-m2-class-c-demotion-review.md](./pr5-3-uvcp-m2-class-c-demotion-review.md)
- [pr5-3-uvcp-m1-adapter-implementation.md](./pr5-3-uvcp-m1-adapter-implementation.md)
- [pr5-3-uvcp-migration-rfc.md](./pr5-3-uvcp-migration-rfc.md)

## Boundary with L.1.5 (archived reminder)

```text
Projection correctness  ≠  Transport resurrection
```

PR5-3 closed Mode A (phase no longer pollutes UVCP). It does **not** prove WiFi flap → repair → remote path → completion. That proof belongs to L.1.5/PRR only.