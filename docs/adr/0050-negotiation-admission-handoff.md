# ADR-0050: Negotiation Admission Handoff

## Status

**ACCEPTED** (semantics) — **2026-08-11** · **Option A + INV-1..3 FROZEN**  
**Implementation:** **MERGED** #165 (`de58d6c`) · Phase-1 IC + Phase-2 lease gate  
**Field (admission):** **GATE VERIFIED** — `logs/adr0050-admission-20260811-154011/`  
  · Admission PASS · Execution OPEN · Completion NOT REACHED  
**Follow-up:** [adr0050-r1-ice-restart-execution-attribution-audit.md](../analysis/adr0050-r1-ice-restart-execution-attribution-audit.md) ·  
  [finding](../analysis/adr0050-r1-ice-restart-execution-attribution-finding.md) (**FINDING COMPLETE**)
**Parent finding:** [rca-004-media-edge-recovery-convergence-finding.md](../analysis/rca-004-media-edge-recovery-convergence-finding.md) (**FINDING COMPLETE**)  
**IC:** [adr0050-phase1-implementation-candidate.md](../analysis/adr0050-phase1-implementation-candidate.md)  
**Admission run card:** [adr0050-directed-admission-validation-run-card.md](../analysis/adr0050-directed-admission-validation-run-card.md)

```text
Note: ADR-0046 is already taken
  (Successor Admission Terminal Convergence Contract).
This handoff ADR is numbered 0050.

Product auth (2026-08-11): Phase-1 IC + Phase-2 ICE-restart lease admission authorized.
Scope remains Option A only; no ownership redesign / completion / UVCP / membership.
```

## Portfolio cut (authorized freeze)

```text
Recovery Last-mile                 = CLOSED / PASS
Presentation Convergence           = CLOSED / PASS (RCA-003)
Media Edge Recovery Finding        = COMPLETE (RCA-004)
Negotiation Admission Handoff      = GATE FIELD-VERIFIED (#165 / 154011)
ICE Restart Execution              = ATTRIBUTED (ADR-0050-R1 FINDING COMPLETE)
  Primary: remote ingress absent / answer missing
  Contributing: bilateral restart on M02↔M03
Completion                         = NOT REACHED

WiFi Recovery Incident Chain v1
  Closed: Delivery · Ownership Handoff · Same-session Rejoin · Presentation · Admission gate
  Open:   R2 candidate (ingress readiness vs lease arbitration) — separate auth
```

**Problem rename (do not reverse):**

> Not: “WiFi recovery failed.”  
> Yes: **Recovery intent exists, but negotiation execution admission was not granted to the recovery actor.**

## Architecture cut

```text
WiFi flap
   → Delivery Observation          CLOSED
   → Media Action Ownership        CLOSED
   → Negotiation Admission         CLOSED (ADR-0050 gate VERIFIED)
   → ICE Restart Execution         <---- OPEN (R1)
   → EDGE_RECOVERED                NOT REACHED
   → UVCP Projection               CLOSED
```

## Decision (ACCEPTED)

**Option A:** Media-action owner may hold a temporary **negotiation lease** that admits ICE restart without transferring long-term negotiation ownership.

```text
MediaActionOwner
      → NegotiationLease
      → ICE restart allowed
```

| ID | Option | Status |
|----|--------|--------|
| **A** | Negotiation lease for media-action owner | **ACCEPTED direction** |
| B | Always remote negotiation owner executes | Rejected for first knife (bilateral wait) |
| C | Merge into RecoveryCoordinator | Deferred (large blast radius) |

## Frozen invariants

### INV-1 — Lease ≠ ownership transfer

```text
lease ≠ ownership transfer
```

Lease = permission to execute negotiation.  
Not = who owns the edge / recovery intent / obligation.

Forbidden: `HOST_RESTART → permanently change negotiationOwner` (RCA-001-class churn).

### INV-2 — Lease scope

```text
lease = single edge + single recovery episode
```

Forbidden: session / conference / channel / global lease (healthy vs failed edges must not couple — e.g. M02→M01 CONNECTED vs M01→M02 FAILED).

### INV-3 — Lease expiry ≠ recovery failure

```text
lease expiry ≠ recovery failed
```

Lease is admission only. Terminals remain `EDGE_RECOVERED` or `FAILED_MEDIA`.  
Forbidden: `lease timeout ⇒ recovery fail` as a new failure class.

## Phased work

| Phase | Work | Auth |
|-------|------|------|
| **0** | ACCEPTED semantics + INV freeze | **DONE** (#164) |
| **1** | Implementation Candidate: when may ICE restart bypass non-owner block? | **AUTHORIZED + WRITTEN** |
| **2** | Single-gate patch at ICE-restart admission | **AUTHORIZED** (this change) |

### Phase 1 IC

Answer only:

> When is ICE restart admission allowed to bypass `NEGOTIATION_NON_OWNER_BLOCKED`?

**Answer:** When local media-action owner ∈ {PENDING, HOST_RESTART}, obligation OPEN, and a valid negotiation lease is granted/held for this edge + episode — without mutating `canonicalNegotiationOwnerModuleId`.

See [adr0050-phase1-implementation-candidate.md](../analysis/adr0050-phase1-implementation-candidate.md).

### Phase 2 patch shape

```text
before: negotiationOwner != local → NON_OWNER_BLOCKED

after:  negotiationOwner != local
          → valid negotiation lease? → yes: NEGOTIATION_LEASE_ADMITTED → continue
                                    → no:  NON_OWNER_BLOCKED
```

**Touch:** ICE-restart admission gate + lease fields/logs only.  
**Do not touch:** `assignMediaActionOwner` · Phase-2 / Delivery · CompletionPolicy · UVCP · membership · supersede · new recovery states.

## Out of scope (frozen)

```text
Field soak / WiFi flap matrices
Retry · ICE timeout · ICE strategy
Edge ownership redesign
New recovery states (e.g. NEGOTIATION_RECOVERY_PENDING_V2)
Split M01 vs M03 into two RCAs
New coordinator actor
```

## Acceptance (semantics — now)

- [x] Option A selected  
- [x] INV-1..3 frozen  
- [x] Scope = admission handoff only  
- [x] Phase-1 IC written  
- [x] Phase-2 gate patch (lease admit)  

## Implementation gate

```text
Phase 0 ACCEPTED (#164)
  → Phase 1 IC + Phase 2 patch MERGED (#165)
  → Directed admission field: GATE VERIFIED (154011)
  → ADR-0050-R1 ICE Restart Execution Attribution DRAFTED
  → do NOT enlarge timeout / UVCP / residency / owner from 154011 alone
```
