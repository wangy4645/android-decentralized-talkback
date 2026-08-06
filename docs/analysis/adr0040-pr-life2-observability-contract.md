# ADR-0040 PR-LIFE-2 — Attempt Lineage & Ownership Diagnostics

**Status:** AUTHORIZED (contract freeze)  
**Parent:** [ADR-0040](../adr/0040-obligation-convergence.md) · [recovery-convergence-audit](./recovery-convergence-audit.md)  
**Depends on:** PR-LIFE-1 (#121)

## Scope

PR-LIFE-2 does **not** alter:
- recovery state transition
- completion predicate
- timeout budget
- admission rules
- UI projection

It only exposes lifecycle ownership invariants.

## Telemetry

### RECOVERY_ATTEMPT_LINEAGE

Fields: attemptId · parentAttemptId · resumeFromDeferred · deferTrigger · deferredReason · transitionSeq

Aggregation key: `(edgeId, attemptId, transitionSeq)` (dedupe duplicate sinks)

### RECOVERY_ATTEMPT_OWNERSHIP_LOST

Detection only (no auto-heal):
- obligationOpen
- L2 recovered
- watchdog inactive
- no terminal state
- above observation window threshold

## Regression matrix

| Case | Expected |
|------|----------|
| Normal flap | watchdog → recover |
| Capability defer | defer → clear → resume watchdog → recover |
| Membership defer | prior path unchanged |
| Control reconciliation defer | prior path unchanged |
| Late recovery | not stranded |
| Hangup during defer | cleanup only |
| Duplicate log sink | dedupe via transitionSeq |

## Negative signature (must not regress)

`deferredReason remains + no watchdog + hangup only clears`