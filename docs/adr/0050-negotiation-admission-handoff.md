# ADR-0050: Negotiation Admission Handoff

## Status

**NOT STARTED** — **PENDING PRODUCT AUTHORIZATION**  
**Direction frozen:** Option **A** (negotiation lease) preferred  
**Date:** 2026-08-11  
**Parent finding:** [rca-004-media-edge-recovery-convergence-finding.md](../analysis/rca-004-media-edge-recovery-convergence-finding.md) (**FINDING COMPLETE**)

```text
Note: ADR-0046 is already taken
  (Successor Admission Terminal Convergence Contract).
This handoff ADR is numbered 0050.

RCA-003 CLOSED · RCA-004 FINDING COMPLETE
Remaining seam: Negotiation Admission Handoff (this ADR)
```

## Architecture cut (healthy boundary)

```text
WiFi flap
   → Delivery Observation          CLOSED
   → Media Action Ownership        CLOSED
   → Negotiation Admission         <---- OPEN (this ADR)
   → ICE Restart
   → EDGE_RECOVERED
   → UVCP Projection               CLOSED
```

| Layer | Status | Note |
|-------|--------|------|
| Delivery | CLOSED | Evidence plane known |
| Media Action Owner | CLOSED | No permanent reject (RCA-001 class) |
| Negotiation Admission | **OPEN** | Dual-role mismatch / no lease |
| Recovery Completion (RCA-004) | FINDING COMPLETE | Root class identified |
| Presentation (RCA-003) | CLOSED | No false degraded |

**Not a WiFi recovery bug.** Distributed state-machine seam:

> Intent exists, media-action responsibility exists, but the second authority needed to execute (negotiation admission) is not granted to the recovery actor.

## Context

Inbound `ICE_RESTART_ONLY`: local may claim `HOST_RESTART` / stay `PENDING` while `negotiationOwner = remote`. Dispatch requires `negotiationOwner == local` → `NEGOTIATION_NON_OWNER_BLOCKED`. M01/M03 surfaces differ; **common failure** = no effective execution path for the recovery actor.

Edge store already per-`ConferenceEdgeKey`. **Do not** redesign Edge-scoped ownership.

## Decision question (only)

> When media-action owner ≠ negotiation owner, who obtains **temporary negotiation admission** so ICE restart can execute?

## Options

| ID | Option | Sketch | Risk |
|----|--------|--------|------|
| **A** | **Preferred:** Media-action owner gets temporary **negotiation lease** | Long-term ownership unchanged; lease enables ICE restart | Smallest |
| B | Always defer execution to remote negotiation owner | Bilateral wait under mutual recovery | Higher |
| C | Merge into RecoveryCoordinator | Clean long-term; large blast radius | Not first knife |

### Option A shape

```text
MediaActionOwner
      → requests / holds
NegotiationLease
      → ICE restart allowed
```

Adds **temporary execution right** only — does **not** redefine who owns the edge, recovery intent, or obligation.

## Frozen invariants (Option A)

### INV-1 — Lease ≠ ownership transfer

```text
Negotiation lease ≠ ownership transfer
```

Forbidden:

```text
HOST_RESTART → change negotiationOwner permanently
```

Avoids reopening RCA-001-class supersede churn.

### INV-2 — Lease scope

```text
Lease scope = single edge + single recovery episode (attempt/gen)
```

Forbidden: conference / session / global recovery lease (cross-peer pollution).

### INV-3 — Lease expiry ≠ recovery failure

```text
Lease expiration cannot mark recovery failed
```

Lease is **admission only**. Terminal remains:

```text
EDGE_RECOVERED  or  FAILED_MEDIA (existing paths)
```

Forbidden: `lease timeout ⇒ recovery fail` as a new failure class.

## Implementation Candidate (when authorized)

**Touch one gate only** (ICE restart dispatch admission), conceptually:

```text
if (negotiationOwner != local) {
    if (hasValidNegotiationLease(edge, episode)) allow ICE restart
    else request / deny  // still NON_OWNER without lease
}
```

**Do not touch:**

```text
assignMediaActionOwner
RecoveryDeliveryProgress / Phase-2
CompletionPolicy / ADR-0038
UVCP / RCA-003
membership / epoch
Ownership supersede path
```

## Out of scope (frozen)

```text
More WiFi flap soaks to “find root”
Retry / ICE timeout / ICE strategy patches
Split M01 vs M03 into two RCAs
New coordinator actor
Phase-2 Delivery · UVCP · Edge-scoped redesign
```

## Acceptance (when ACCEPTED + IC)

1. Inbound ICE_RESTART_ONLY with local media action no longer deadlocks solely on remote negotiationOwner.  
2. Lease grant/deny/expire observable in logs.  
3. INV-1–INV-3 hold; no UVCP / completion / Phase-2 delta.

## Implementation gate

```text
Product auth → ACCEPTED ADR (Option A + INV-1..3) → IC → single-gate patch
Not: field trial-and-error
```

## Milestone wording (portfolio)

```text
Recovery Last-mile:           PASS
Post-recovery convergence:    PASS except admission handoff
Open successor:               ADR-0050 Negotiation Admission Handoff
```
