# ADR-0050 Phase-1 — Implementation Candidate

**Status:** AUTHORIZED (product auth 2026-08-11)  
**ADR:** [0050-negotiation-admission-handoff.md](../adr/0050-negotiation-admission-handoff.md)  
**Finding:** [rca-004-media-edge-recovery-convergence-finding.md](./rca-004-media-edge-recovery-convergence-finding.md)

## Question (only)

> When may ICE restart admission bypass `NEGOTIATION_NON_OWNER_BLOCKED`?

**Answer (Option A):** When the local edge holds a **valid negotiation lease** bound to media-action HOST_RESTART / PENDING intent for this edge + episode — without changing `canonicalNegotiationOwnerModuleId`.

## Gate change

```text
before:
  negotiationOwner != local → NON_OWNER_BLOCKED → return

after:
  negotiationOwner != local
    → valid lease? → yes: NEGOTIATION_LEASE_ADMITTED → continue dispatch
                  → no:  NON_OWNER_BLOCKED → return
```

## Lease rules

| Rule | Behavior |
|------|----------|
| Grant | ICE_RESTART_ONLY path; mediaActionOwner ∈ {PENDING, HOST_RESTART}; obligation OPEN; owner ≠ local |
| Scope | `ConferenceEdgeKey` + `recoveryAttemptId` + `obligationGeneration` (INV-2) |
| Expire | Soft deny lease only; **no** `enterFailedMediaResidency` (INV-3) |
| Ownership | Never rewrite negotiation owner (INV-1) |

## Touch / no-touch

**Touch:** `issueBoundedIceRestart` admission branch + `EdgeRecoveryRecord` lease fields + logs.

**No-touch:** `assignMediaActionOwner` supersede rules · Phase-2 · CompletionPolicy · UVCP · membership · new recovery phases.

## Tests

1. Remote negotiation owner + PENDING/HOST_RESTART → lease admit → `RECOVERY_ICE_RESTART_DISPATCHED`  
2. No media-action intent → still `NON_OWNER_BLOCKED`  
3. Lease expiry → deny restart, **no** FAILED_MEDIA from expiry alone  
4. Negotiation owner unchanged after lease admit
