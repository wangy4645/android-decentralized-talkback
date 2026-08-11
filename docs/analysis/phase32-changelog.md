# Phase 3.2 Behavior Change -- Changelog

**Status:** Gate 3C CLOSED; RNA-6 DEFERRED; RNA Directed #3 PASS; Recovery Completion OPEN

---

## Structural fixes

### Owner bootstrap ordering fix

Inbound reattach provenance must be established **before** canonical negotiation owner lock.

``text
recoveryViaInboundReattach
        |
write recovery provenance on EdgeRecoveryRecord
        |
bootstrap canonical owner (C)
        |
wire owner token validation / adoption (A)
``

Previously `upsertEdge` bootstrapped owner before `recoveryViaInboundReattach` was set.

**Code:** `ConferenceEdgeRecoveryController.upsertEdge(recoveryViaInboundReattach)`, `supersedeAttempt` clears `canonicalNegotiationOwnerModuleId` for new attempt episodes.

---

## Phase 3.2 Product Surface Freeze (PTT UI V1)

**Status:** FROZEN (feat/ptt-action-placeholder-v1)

Product boundary for the Talk page action row — not capability implementation.

| Control | V1 | V2 |
|---------|----|----|
| PTT | Core (complete) | Maintain |
| All Call (was Broadcast) | Placeholder entry | Authorized one-to-all notify |
| Emergency | Placeholder entry | Priority floor + event |
| Monitor | Placeholder entry | Listen-only session |
| Record | Placeholder entry | Local voice note |

**In scope (V1):** rename to All Call; bottom-sheet placeholders; no Meeting redirect from All Call.

**Out of scope (V1):** All Call authorization; Emergency workflow; Monitor; Recording.

**Next track:** Group Stability (Membership soak, Anchor, Join churn).

---


| Gate | Scope | Status |
|------|-------|--------|
| Authority unit | `RecoveryNegotiationAuthorityTest` | VERIFIED |
| Negotiation stabilization | `NegotiationStabilizationGateTest` | VERIFIED |
| Controller lifecycle | `RecoveryNegotiationControllerGateTest` | VERIFIED |
| Gate 3C | NEGOTIATION_INTENT_TERMINAL_CLOSURE | FIELD VERIFIED (Directed #3 PASS) |
| Field W1/W2 | Device | NOT AUTHORIZED until prep sequence |

---

## Gate 3C (FIELD VERIFIED)

RNA-5 v2 frozen + implemented + Directed #3 PASS.

- PR-3C-A..D VERIFIED on `main`
- Directed #2 FAIL (historical dangling) ARCHIVED
- Directed #3 PASS — `logs/wifi-recovery-m03-rna0037-directed3-20260805-193602`
- `DEFERRED_DANGLING` CLOSED BY RNA-5 v2 + Directed #3 PASS

Plan: `docs/analysis/gate3c-negotiation-intent-terminal-closure-plan.md`.
Run card: `docs/analysis/rna-directed-3-run-card.md`.

Recovery Completion remains OPEN. Next: offline RNA-0037 evidence completion audit.

---

## Field prep sequence

1. Install Phase 3.2 APK
2. Clear collector
3. W1
4. `wifi-recovery-adjudicate.ps1` (RNA-0037 field verdict)
5. W2
6. Recovery Completion Authority review
---

## ADR-0040 Phase-1 (2026-08-06)

**PR-LIFE-1:** PASS (field `m03-flap-recovery-convergence-20260806-161533`)

**Fixed:** Capability deferral lifecycle ownership loss (restore attempt ownership after capability deferral)

**Not changed:** Completion admission · Media lifecycle · UI projection · Timeout budget

**PR-LIFE-2:** AUTHORIZED — attempt lineage telemetry + `RECOVERY_ATTEMPT_OWNERSHIP_LOST` diagnostic (observability only)
---

## ADR-0040 Phase-2 observability (2026-08-06)

**PR-LIFE-2:** attempt lineage + ownership-lost diagnostics (behavior-neutral contract freeze)

**Telemetry:** RECOVERY_ATTEMPT_LINEAGE · RECOVERY_ATTEMPT_OWNERSHIP_LOST (diagnostic only)

**Aggregation key:** (edgeId, attemptId, transitionSeq)

**Not changed:** recovery FSM · completion · timeout budget · admission · UI