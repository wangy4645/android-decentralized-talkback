# ADR-0050: Negotiation Admission Handoff

## Status

**NOT STARTED** — **PENDING PRODUCT AUTHORIZATION**  
**Date opened (stub):** 2026-08-11  
**Parent finding:** [rca-004-media-edge-recovery-convergence-finding.md](../analysis/rca-004-media-edge-recovery-convergence-finding.md) (**FINDING COMPLETE**)

```text
Note: ADR-0046 is already taken
  (Successor Admission Terminal Convergence Contract).
This handoff ADR is numbered 0050.
```

## Context

RCA-004 showed inbound `ICE_RESTART_ONLY` recovery can claim local media action (`HOST_RESTART` / `PENDING`) while **negotiation owner = remote (flapped peer)**. ICE restart dispatch requires `negotiationOwner == localModuleId` → `NEGOTIATION_NON_OWNER_BLOCKED`. Surface labels differ (`NON_OWNER_BLOCKED` vs `NO_MEDIA_ACTION_OWNER`), mechanism class is one: **dual-role ownership without admission handoff**.

Edge store is already per-`ConferenceEdgeKey`. Problem is **not** missing edge scope.

## Decision question (only)

> When media-action owner ≠ negotiation owner, who obtains **temporary negotiation admission** so ICE restart / recovery media action can execute?

## Options

| ID | Option | Sketch | Risk |
|----|--------|--------|------|
| **A** | **Preferred:** Media-action owner receives a temporary **negotiation lease** | Long-term negotiation owner unchanged; lease scoped to attempt / edge / ICE-restart intent | Smallest |
| B | Negotiation owner always executes; local requests remote | Bilateral wait / deadlock under mutual recovery | Higher |
| C | Merge roles into RecoveryCoordinator | Clean long-term; large blast radius | Out of band for first knife |

## Out of scope (frozen)

```text
Phase-2 Delivery
Ownership supersede (RCA-001) reopen
UVCP / RCA-003
Retry budget / ICE timeout patches
Edge-scoped ownership redesign
WiFi recovery protocol reopen
```

## Acceptance (when authorized)

1. Dual-role mismatch no longer deadlocks inbound ICE_RESTART_ONLY when media action is local HOST_RESTART.  
2. Lease (or chosen option) is observable in logs.  
3. No UVCP / completion-predicate / Phase-2 change.

## Implementation gate

```text
Product auth → ACCEPTED ADR text → Implementation Candidate → single-edge patch
Not: field soak to “discover” root (already found)
```
